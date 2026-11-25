# Mail Assistant for Android

The native mail home includes Gmail folders, search, message reading, a draft composer, AI writing/reply/summary/grammar/translation, and a searchable prompt library. See [reference coverage and remaining gaps](docs/GENIE_MAIL_PARITY.md). The native API was deployed on 20 November 2026. The physical-device Gmail Inbox and AI-writing checks passed directly against the live HTTPS service; the delivered APK does not require the PC backend. Fresh-account confirmation-email delivery and release signing remain unverified or incomplete.

Native Kotlin/Jetpack Compose client for the Embeddable Chatbot platform (`../../embeddable_chatbot`). It signs in with the platform's Supabase account, uses the private, cross-device `/api/v1/client` conversations, and lets owners/admins connect Gmail, ask the assistant about their mail, and explicitly approve or reject sends and Trash requests.

General chat, connected-Gmail assistance and email writing now share one AI Assistant entry point. It resumes the latest conversation, exposes history and writing tools, and returns generated writing to the same chat as editable text. See [unified assistant behavior](docs/UNIFIED_ASSISTANT.md).

The assistant also uses the platform's live published knowledge base, shows its source/status, and displays citations. Processed knowledge changes apply to later requests without rebuilding the APK. See [knowledge sync](docs/KNOWLEDGE_SYNC.md).

- Integration gaps and the native mail API contract: [docs/INTEGRATION.md](docs/INTEGRATION.md)
- Module boundaries and key decisions: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Native registration, email confirmation, automatic private assistant setup, and Gmail onboarding: [docs/NEW_USER_ONBOARDING.md](docs/NEW_USER_ONBOARDING.md)
- Backend API reference: `embeddable_chatbot/docs/CLIENT_API.md` (sections "Endpoints" and "Native mail API")

## Requirements

| Tool | Version used |
|---|---|
| JDK | 17 (`JAVA_HOME`) |
| Gradle | 9.6.0 (wrapper) |
| Android Gradle Plugin | 9.5.1 |
| Kotlin | 2.3.21 (KSP 2.3.14) |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 |

minSdk 26 gives native `java.time` and Keystore-backed AES-GCM. targetSdk 36 meets the current Google Play requirement; compileSdk 37 is required by the AndroidX versions in use.

## Configuration

Public client values come from Gradle properties (`-P…`), environment variables (`CHATBOT_SUPABASEURL`, …) or the gitignored `local.properties`:

```properties
sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk
chatbot.platformUrl.debug=https://ai-chatbot-9wg.pages.dev
chatbot.platformUrl.release=https://ai-chatbot-9wg.pages.dev
chatbot.supabaseUrl=https://<project>.supabase.co
chatbot.supabasePublishableKey=sb_publishable_…
# chatbot.chatbotId is assigned by authenticated native onboarding.
```

The delivered debug APK also enables `chatbot.migrateLocalPlatform=true` for a one-time upgrade from known PC loopback URLs to the live default, only when the Supabase project and public key match. The upgrade preserves the signed-in account and assistant selection. Leave this opt-in disabled for unrelated environments.

These values are public. Never put a Supabase service-role key, `cbk_…` organization key, LLM key or Google OAuth client secret in the app. Ordinary users start at Welcome and register or sign in; the server assigns their private assistant automatically. Debug builds retain a signed-out developer setup screen for changing platform and Supabase configuration.

**Emulator networking:** inside the emulator `localhost` is the emulator itself. The host machine's `localhost:3100` is `http://10.0.2.2:3100`. Cleartext HTTP is allowed only in **debug** builds and only for `10.0.2.2`, `localhost` and `127.0.0.1` (`app/src/debug/res/xml/network_security_config.xml`). Release builds require HTTPS in both the network security config and the code (`PlatformConfig.validate`, `ClientApi.url`). On a physical device, use `adb reverse tcp:3100 tcp:3100` with `http://localhost:3100`, or an HTTPS tunnel.

**Release signing:** create a gitignored `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Without it, `assembleRelease` produces an unsigned APK.

## Build, test, run

```bash
./gradlew assembleDebug
```

```bash
./gradlew test
```

```bash
./gradlew lintDebug
```

```bash
./gradlew :feature:mail:connectedDebugAndroidTest
```

```bash
./gradlew assembleRelease
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

```bash
adb shell am start -n com.embeddablechatbot.android.debug/com.embeddablechatbot.android.MainActivity
```

On Windows PowerShell use `.\gradlew.bat`. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Backend prerequisites (not done automatically)

1. Apply the client migrations through the platform's migration runner: `20261117090000_client_conversations.sql`, `20261118090000_native_mail.sql`, `20261119075549_native_onboarding.sql`, and `20261119090000_mail_client_drafts.sql`. They were applied to this project's configured database on 19 November 2026; a separate environment still needs its own migrations.
2. Deploy the dashboard with `/api/v1/client/*`, including `/onboarding` and `/mail/*`, and `/auth/email-confirmed`. These routes are live at `https://ai-chatbot-9wg.pages.dev` as of 20 November 2026. Supabase Auth must allow `https://ai-chatbot-9wg.pages.dev/auth/email-confirmed`; its exact allowlist and SMTP configuration remain owner-verification checks.
3. Gmail server configuration: `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET`, `MAIL_TOKEN_SECRET`, model key. The Google redirect URI is unchanged (`<platform>/api/agent/gmail/callback`).
4. Optional: `MAIL_NATIVE_RETURN_URI` (default `chatbotmail://oauth/gmail`, which matches the app's intent filter).
5. Native onboarding creates an owned personal organization and assistant for the authenticated user; mailbox authorization still verifies ownership or admin membership. Google consent testing restrictions also apply.

## Manual verification checklist

Use a disposable test account and a test Gmail mailbox. Never use production data.

1. Sign in on the web at `/chat/<cb_id>` and in the app with the **same** account and chatbot. Create a conversation on one, and confirm it appears on the other after a refresh. Delete it, and confirm it disappears on both.
2. Mailbox → Connect Gmail opens the system browser or a Custom Tab. After consent, the app returns and shows "Connected to Gmail as …". Then disconnect and reconnect.
3. In a chat with Mailbox tools on: "find unread mail from this week", "archive the newsletter", "draft a reply to …". Archive and other organize actions happen immediately. A draft appears under Mailbox → Waiting for your approval.
4. Open the request and check the account, To/Cc/Bcc, subject and full body. Then edit the draft in Gmail and press Send: the app must report "changed since you opened this" and show the new text. Approve once, then verify a second device or a double tap reports "already handled".
5. Reject a send: it is marked Declined, and the draft stays in Gmail Drafts.
6. Turn on airplane mode while a reply is being written. The message shows "checking whether the server received this", and after reconnecting it resolves from server state without being resent. Kill the app mid-send and reopen it: you see the same behaviour.
7. Sign out, then sign in as another account: no conversations, drafts or pending sends from the first account are visible.
8. Turn on approval reminders. The notification shows no email content, and tapping it only opens the Mailbox screen.

## Status

| Area | State |
|---|---|
| Native registration, confirmation/resend, automatic assistant setup, Gmail onboarding/skip | Implemented; unit and physical-device UI tests passed. Fresh-account confirmation-email delivery remains unverified |
| Sign-in, session restore, refresh coordination, sign-out cleanup | Implemented, unit-tested |
| Conversations list/create/open/delete, sync, pagination, offline cache | Implemented, unit-tested |
| JSON send and SSE streaming, stable request IDs, uncertain-send recovery, process-death recovery | Implemented, unit-tested |
| Gmail connect (system browser, single-use transaction, PKCE, device verifier), status, disconnect | Implemented, unit-tested (Android + backend). Real Google consent and Gmail connection verified with the requested account |
| Approvals: full review, version-bound approve, reject, stale, already-handled, uncertain | Implemented, unit + instrumented tested. Real Gmail draft review and decline verified; no real send or Trash executed |
| Approval reminders (WorkManager, ~30 min, best effort) | Implemented, not automatically tested |
| Labels (browse, create, apply), attachment open/summarize, attaching files, formatted email, scheduled send | Implemented 25 November 2026; backend and Android unit tests pass. Migration applied and deployed to the live service on 25 November 2026; not yet exercised on a device or against real Gmail. See [capabilities](docs/MAIL_ASSISTANT_CAPABILITIES.md) |
| Native push (FCM) | **Not implemented**: needs a Firebase project and a server-side sender |
| Share-to-app text import | Implemented as a draft in a new chat; the user sends it deliberately |
| End-to-end against a deployed backend | Authenticated private-assistant setup, Gmail status, Inbox read and AI writing passed on the physical phone against live HTTPS on 20 November 2026 without ADB reverse. Fresh-account email delivery/verification remains unverified |
