# Integration-gap assessment and native mail contract

Assessed against `embeddable_chatbot` working tree on 2026-11-18 (branch with uncommitted client-API work; nothing there was overwritten).

## Verified observations

| Claim | Verified in source | Notes |
|---|---|---|
| Supabase Auth + signed-in `/api/v1/client` API | `apps/dashboard/src/lib/client-api.ts` `authenticateClient` | Bearer Supabase access JWT verified via `getClaims` (issuer + `authenticated` audience). Cookies only for same-origin first-party web. |
| Private, cross-device history | `packages/core/src/client-conversations.ts`, migration `20261117090000_client_conversations.sql` | Owner = JWT `sub`; `source='client'` sessions. |
| Stable conversation IDs + request IDs | `createClientConversation` (`on conflict (id) do nothing`), `claimClientTurn` (sha256 of `{message, emailManager}`) | Same requestId + same payload replays stored turn; different payload → 409. |
| `emailManager:true` | `messages/route.ts` `sendSchema` | Owner/admin of the chatbot's org **and** an active connection, else 403 / 409. |
| Gmail agent: search, read, summarize, organize, draft | `packages/mail-agent/src/agent.ts` `mailTools` | Organize = archive, move_to_inbox, mark_read/unread, star/unstar (immediate). |
| Send / Trash need approval | `draft_email` / `request_trash` only queue `mail_actions`; `approveAction` server action executes | `claimAction` is an atomic `pending→processing` update. |
| Mailbox management is browser-only | `(platform)/agent/actions.ts` (Next server actions, `requireUser()` cookie session), `/api/agent/gmail/connect|callback` (session cookie + state cookie) | **No bearer-authenticated mailbox endpoints existed.** |
| Web Push ≠ Android push | `packages/mail-agent/src/push.ts` (VAPID Web Push to browser endpoints) | No FCM sender, no device-token table. |

## Gaps and resolution

| Gap | Resolution |
|---|---|
| Mail status / eligibility not available to native | **New** `GET /api/v1/client/mail/status` |
| Connect flow bound to browser cookies | **New** native transaction: `POST /mail/connect` → system browser → existing Google redirect URI `/api/agent/gmail/callback` (extended) → app deep link → `POST /mail/connect/complete` |
| Disconnect only as server action | **New** `DELETE /mail/connection` |
| Pending approvals only rendered server-side; preview truncated to 2,000 chars | **New** `GET /mail/actions`, `GET /mail/actions/{id}` (live Gmail draft: full body, To/Cc/Bcc, attachments; Trash targets' metadata) |
| Approval not bound to reviewed content (Gmail draft can be edited after queuing) | **New** `POST /mail/actions/{id}/approve {version}`; server recomputes version from Gmail and refuses stale approvals |
| Reject could flip a `processing` action | Native reject only transitions `pending` |
| Network failure during Gmail send recorded as plain `failed` | New `uncertain` status (migration) + reconciliation hint |
| 409s indistinguishable except by message text | Additive `error.reason` field (`conversation_busy`, `request_running`, `request_mismatch`, `lease_lost`, `mailbox_not_connected`, `mail_role_required`, `stale_approval`, `already_handled`, …) |
| Native push | **Not implemented** (needs FCM project + server sender). App uses foreground refresh + best-effort WorkManager check of pending approvals. |

## Native mail API (prefix `/api/v1/client`, bearer Supabase access JWT, no Origin)

All responses: `Cache-Control: private, no-store`, `X-Authenticated-User`. Errors: `{"error":{"code","message","reason?"}}`.

### `GET /mail/status?chatbotId=cb_…`
```json
{ "provider": "gmail", "serverReady": true, "eligible": true, "role": "owner",
  "connection": { "email": "me@example.com", "status": "active" } | null,
  "pendingApprovals": 2 }
```
`eligible` = caller is owner/admin of the chatbot's organization. `role` is `owner|admin|member|null` (null = not a member).

### `POST /mail/connect` `{ "chatbotId": "cb_…", "verifierChallenge": "<base64url sha256(verifier), 43 chars>" }`
Requires `eligible` (else 403 `mail_role_required`) and `serverReady` (else 503 `mail_not_configured`).
```json
{ "transactionId": "uuid", "authorizationUrl": "https://accounts.google.com/…", "expiresAt": "ISO" }
```
Server stores `sha256(state)`, the app's `verifierChallenge`, and a server-side PKCE verifier (S256 challenge sent to Google). 10-minute expiry.

### Callback (existing route `/api/agent/gmail/callback`, extended)
If the browser state cookie does not match, the state is looked up as a native transaction (single use, unexpired). The code is exchanged with PKCE, the refresh token is encrypted and parked on the transaction, and the browser is redirected to
`MAIL_NATIVE_RETURN_URI?tx=<transactionId>&code=<one-time completion code>` or `…?tx=<id>&error=denied|permission|failed|expired`.
Default `MAIL_NATIVE_RETURN_URI` = `chatbotmail://oauth/gmail`. No access/refresh token appears in any URL.

### `POST /mail/connect/complete` `{ "transactionId", "code", "verifier" }`
Succeeds only for the transaction's own user, unexpired, not completed, matching completion code hash and `sha256(verifier) == verifierChallenge`. Then saves the connection. Returns the status object. Errors: 404 `transaction_not_found`, 410 `transaction_expired`, 409 `already_completed`, 400 `verification_failed`.
The attacker who starts a transaction and phishes the URL lacks the completion code (delivered to the victim's device); the victim's device lacks the verifier.

### `DELETE /mail/connection`
Revokes the Google token (best effort) and deletes the connection and its queued actions (same as web). 204.

### `GET /mail/actions?filter=pending|recent`
```json
{ "actions": [{ "id": "uuid", "kind": "send|trash", "summary": "…", "status": "pending|processing|done|rejected|failed|uncertain",
  "error": null, "createdAt": "ISO", "resolvedAt": null, "to": "a@b.c", "subject": "…", "messageCount": null }] }
```

### `GET /mail/actions/{id}`
```json
{ "action": { …list item… }, "account": "me@example.com", "version": "<hex>",
  "send": { "draftId": "…", "to": ["a@b.c"], "cc": [], "bcc": [], "subject": "…", "body": "full plain text", "attachments": [{"filename","mimeType","size"}], "draftExists": true } | null,
  "trash": { "messages": [{ "id","from","subject","date","snippet" }], "missing": ["id"] } | null }
```
For a `pending` send whose draft no longer exists, `version` is null and approval is impossible. For `uncertain` sends, `draftExists:false` means Gmail most likely sent it.

### `POST /mail/actions/{id}/approve` `{ "version": "<hex from detail>" }`
Recomputes the version from Gmail; mismatch → 409 `stale_approval` (nothing executed). Then atomic claim `pending→processing`; lost race → 409 `already_handled`. Gmail 4xx → `failed`; network error / 5xx → `uncertain`. Returns `{ "action": {…} }` with final status.

### `POST /mail/actions/{id}/reject`
`pending → rejected` only (draft stays in Gmail Drafts). Otherwise 409 `already_handled`. Returns `{ "action": {…} }`.

## As implemented (differences from the plan above)

- `mail_oauth_transactions` also stores `chatbot_public_id`, so `connect/complete` can return the full status object.
- Additional error reasons: `callback_pending` (409), `oauth_denied`, `oauth_permission`, `oauth_failed` (400 on complete), `draft_missing` (409 on approve), `action_not_found` (404), `mailbox_revoked` (403), `mail_not_configured` (also 409 when opening the mailbox).
- `GET /mail/actions?filter=recent` returns the newest 50 actions of the last 7 days.
- Implementation: `apps/dashboard/src/lib/native-mail.ts`, `apps/dashboard/src/app/api/v1/client/mail/**`, `packages/mail-agent/src/native.ts`, migration `20261118090000_native_mail.sql`. The authoritative description is `docs/CLIENT_API.md` → "Native mail API".
