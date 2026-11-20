# Native new-user onboarding

Implemented on 19 November 2026 and deployed live on 20 November 2026. This document describes the current native flow and its validation limits.

On 20 November the backend passed the production webpack build, OpenNext packaging, Wrangler dry run and five local Cloudflare runtime checks. After preview checks passed, Cloudflare Pages and the maintenance Worker were promoted live. The rebuilt APK migrated the existing PC URL to live HTTPS while preserving the signed-in account. Private-assistant setup, active Gmail status, Inbox reading and synthetic AI writing passed directly from the physical phone without ADB reverse. These checks did not create Gmail drafts, send email or modify mail. Confirmation redirect/SMTP configuration verification and a genuinely new signup-email test remain pending.

## User flow

1. A clean installation opens Welcome, with Get started and an existing-account option. Public platform configuration is bundled with the app; users do not enter server, workspace, organization, or chatbot identifiers.
2. Create account accepts an email, a password of at least 12 characters, and matching confirmation. Validation happens before submission. Passwords remain in memory and are cleared after successful registration.
3. When Supabase requires verification, Check your email offers confirmation resend with a cooldown and Continue to sign in. Confirmation opens a return-to-app page. The user signs in after verification; the app does not trust session tokens supplied through a custom-scheme URL. If the configured auth service immediately issues a valid session, the existing encrypted session store adopts it instead.
4. After authentication, POST /api/v1/client/onboarding provisions or reuses the user's private organization, workspace, and active mail assistant. The client cannot choose the owner or tenant. Setup failures offer retry and sign out.
5. A user without an active mailbox sees Connect Gmail and an explanation of permissions. Google account selection and consent happen in the browser. The existing native OAuth transaction returns to the app and completes the connection.
6. Explore AI tools first allows skipping mailbox connection. Mailbox-dependent actions still require a connection. Completion is stored per signed-in user. Gmail remains available from the mailbox screen later.
7. Connected returning users open Inbox after the same idempotent account check. Sending and moving messages to Trash retain explicit approval.

## Architecture and account protection

Compose screens use ViewModels and StateFlow. Domain registration/onboarding interfaces are implemented in core:data and injected with Hilt. Registration uses only the public Supabase client key. Session adoption uses the existing encrypted store and account-epoch checks; a delayed registration response cannot replace a newer sign-in.

Trusted server provisioning runs in one transaction with a per-user advisory lock. The protected native_accounts mapping has RLS enabled and no anon/authenticated/chatbot_app grants. Existing mappings must still reference an active assistant and valid owner membership. Native chatbot access verifies the personal assistant's owner. Existing generic public-chatbot behavior is retained.

Migration: packages/database/migrations/20261119075549_native_onboarding.sql in the backend repository. Applied to the configured shared database and verified on 19 November 2026. No production application deployment accompanied the database migration.

## Validation

- 113 Android unit tests passed on 20 November (including eight URL-migration safety tests), including confirmation-without-session, immediate signup session adoption, existing-account protection, and delayed registration response checks.
- 10 UI tests passed on 19 November on the physical OPPO CPH2695, Android API 36: 4 authentication/onboarding and 6 mail tests.
- A separate clean-install QA package opened Welcome, navigated to registration, rejected an empty submission, and reached existing-account sign-in without developer configuration fields. Only that disposable package was removed afterward.
- 66 backend tests passed on 19 November. The normally skipped database integration test was also run explicitly against Postgres and passed; its fixtures were rolled back. It checks idempotent provisioning, tenant isolation, and restricted access to the mapping table.
- Android lint: 0 errors, 14 warnings. Backend TypeScript check passed.
- The existing requested account retained its signed-in session and active Gmail connection. Its private assistant was provisioned automatically and the real Inbox loaded.
- On 20 November, six cloud route checks passed in each of Worker preview, live Pages and live Worker (18 checks). One authenticated physical-device integration scenario passed in each of preview, live Pages through a temporary bridge, and final direct live HTTPS. The final visible Inbox had no sign-in or retry screen.

## Deployment and test limits

The delivered APK is a debug-signed test build using https://ai-chatbot-9wg.pages.dev directly. Authentication, database, Gmail and the configured AI provider are live. The PC backend and ADB reverse are no longer required. A release keystore is not configured.

The public confirmation page now returns 200, and the onboarding endpoint correctly rejects invalid authentication with 401. Authenticated onboarding passed on the phone. Pages deployment: 016aa41f-e4a0-41e2-83de-3ec1228096dc. Maintenance Worker version: 0e5d920d-5fb2-4731-8aff-24a5cfbd7d83, at 100% traffic with the existing schedule and bindings preserved. Temporary preview settings were restored after testing.

Public Supabase Auth settings show email signup enabled and confirmation required. The exact redirect allowlist and SMTP settings could not be inspected using the available management tools or credentials. Confirm that https://ai-chatbot-9wg.pages.dev/auth/email-confirmed is permitted, retain existing /auth/callback entries, and verify signup mail delivery before opening registration to new users.

The supplied signup-test email already belongs to an account. Its credentials were not reset and no duplicate account was created. Registration/confirmation/resend logic was tested with isolated API and UI fixtures; delivery and verification of a genuinely new account's confirmation email remain unverified end to end.

Google OAuth remains subject to the project's configured testing-user restrictions and consent verification. Native Gmail consent was previously completed with the requested account; no additional consent or email send was performed during the final clean-install check.

This feature does not establish full Genie Mail parity. Other email providers, unified inboxes, full attachment handling, rich HTML composition, and native new-mail push remain separate work.
