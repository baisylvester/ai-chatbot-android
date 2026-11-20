# Architecture

## Modules and dependency direction

```
:app ──► :feature:auth ─┐
     ──► :feature:chat ─┼──► :core:designsystem ──► :core:domain (pure Kotlin/JVM)
     ──► :feature:mail ─┘                              ▲
     ──► :core:data ───────────────────────────────────┘
```

- `:core:domain` is pure Kotlin (no Android plugin) and depends only on coroutines and `javax.inject`. It holds models, the `Outcome`/`AppError` types, repository interfaces, and the business rules: `SendCoordinator`, `MessageRules`, `ConnectMailboxUseCase` and `Pkce`, `ReviewMailActionUseCase`, `AccountLifecycle`. Shared test fakes live in its `testFixtures`.
- `:core:data` implements the domain interfaces with OkHttp, kotlinx.serialization, Room, DataStore and Tink, and binds them with Hilt. DTOs, Room entities and domain models are mapped explicitly.
- `:feature:*` modules depend only on `:core:domain` and `:core:designsystem`, never on `:core:data`, and Gradle enforces this. They contain ViewModels (`StateFlow` UI state, one-shot effects through `Channel`) and Composables that only render state and forward events.
- `:app` contains the Hilt application, startup ordering, navigation (type-safe routes), deep-link and share handling, and the reminder worker.

`:core:designsystem` also depends on the domain so it can map `AppError` to localized text in one place.

## Key decisions

- **Server is authoritative.** Room is a per-account cache keyed by `userId|platformUrl|chatbotId`. Every write first checks that the account epoch from the request is still current (`AccountKeys` / `SessionManager.epoch`), so a late response from a previous account cannot repopulate the cache. `AccountLifecycle` wipes the cache, pending sends, connect attempts and in-flight work on sign-out or an account change.
- **Send reliability.** One request UUID and the exact trimmed text are persisted before submission. If the server definitely refused (409 busy, mailbox not connected, 429, 4xx), the same ID is kept for a safe retry. An uncertain outcome (timeout after sending, 5xx/502, SSE EOF without `done`, an `error` event) is resolved by reading `lastTurn` and the transcript. The app never generates a new ID on its own, and only one unresolved send is allowed per conversation. Sends run in an application scope, so leaving the screen does not cancel them. After process death, a `Submitting` send is reconciled, not resubmitted.
- **Transport.** Plain OkHttp is used, without Retrofit, for SSE control: `ClientApi` sends the bearer token, never an Origin header or cookies. It refreshes once on a 401 (safe because a 401 means nothing ran) and rejects responses whose `X-Authenticated-User` doesn't match the token's user. Timeouts: 30 s by default, 270 s for the JSON send, 250 s idle for streams. `retryOnConnectionFailure(false)` prevents silent POST replays. `SseDecoder` handles CR/LF/CRLF and UTF-8 split across arbitrary chunks.
- **Decimal strings.** `sequence`, `revision` and message cursors are validated as decimal strings and stored as text. They are ordered by `(length, text)`, both in SQL and in `DecimalStrings.compare`, and never parsed as floating point. Conversation-list cursors are opaque.
- **Auth.** Supabase password grant and refresh run over REST with the publishable key. A single mutex serializes refreshes, which matters because Supabase rotates refresh tokens. The session is AES-256-GCM sealed by a Tink keyset wrapped with an Android Keystore key, and stored in DataStore. A refresh rejection, or a refresh that returns a different user, signs the user out as "session expired". A network failure during refresh keeps the session.
- **Gmail connect.** The app sends an S256 challenge of a device-held verifier. The server creates a single-use, 10-minute transaction and returns Google's URL (server-side PKCE), which the app opens in a Custom Tab. The existing callback route parks the encrypted token and redirects to `chatbotmail://oauth/gmail?tx&code`. The app then completes with `{tx, code, verifier}` under its bearer token. The link is never trusted as proof: it is ignored unless it matches the pending attempt for the same account, and the UI shows the server's status. An attacker who phishes the consent URL lacks the completion code, and the victim's device lacks the verifier.
- **Approvals.** The detail endpoint returns the live Gmail draft (full body, To/Cc/Bcc, attachments) or the messages to trash, plus a content `version`. Approve sends that version; the server recomputes it, refuses stale approvals, and claims atomically (`pending → processing`). Gmail 4xx results become `failed`; network errors and 5xx become `uncertain`, which is never retried. Reject only moves `pending → rejected`. The app adds a confirmation dialog and an in-flight lock. Nothing approves from chat text, notifications or workers.
- **Untrusted content.** Email-derived text is rendered as plain `Text` only: no HTML, no WebView, no remote resources. Logs never contain tokens or content; the app has no HTTP logging interceptor.
- **Privacy.** `allowBackup=false` plus exclusion rules. Transcripts are cached only for the 20 most recently synced conversations. Notifications are generic and use `VISIBILITY_PRIVATE`.
- **Background.** There is no always-on agent. WorkManager checks pending approvals about every 30 minutes with network, only when the user opts in. Screens refresh on foreground (lifecycle-scoped collection), every 5 s in a chat, every 30 s on the list, and on network reconnect.

## Known limitations

- Gmail has no conditional send, so a draft edited in the milliseconds between the server's version check and `drafts.send` could still be sent. This is documented server-side.
- A custom URL scheme can be claimed by another installed app. Switching `MAIL_NATIVE_RETURN_URI` to a verified HTTPS App Link needs an `assetlinks.json` with the release signing certificate, plus an `autoVerify` intent filter. That has not been configured.
- Native email/password registration, confirmation/resend, sign-in, and private assistant provisioning are implemented; see [new-user onboarding](NEW_USER_ONBOARDING.md). Platform Google sign-in and password recovery are not implemented. Gmail OAuth connects a mailbox after platform authentication.
- The Room cache is protected by Android file-based encryption, not by app-level encryption.
