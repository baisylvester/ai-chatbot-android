# Genie Mail reference implementation

Reference: [GenieMail on Google Play](https://play.google.com/store/apps/details?id=com.amobear.geniemail&hl=en), reviewed 19 November 2026. The app keeps its own Mail Assistant identity and uses native Kotlin/Compose screens.

## Implemented in source

- Purple/white mail home, sender avatars, unread emphasis, search and rounded filters; folder drawer and cursor pagination.
- Inbox, unread, starred, attachments filter, Sent, Drafts, Archive, Spam, Trash, All mail.
- Plain-text message reader; reply, forward, archive, star/unstar, mark read/unread, move to Inbox/spam, undo for supported label actions.
- Composer with process-restorable content and safe, UUID-tracked draft submission. Send and Trash require the existing explicit review screen.
- AI mail modes: Write, Reply, Summary, Grammar, Translate; language, tone, length, paste/copy, editable generated text.
- Searchable prompt library with Work, Marketing, Sales, Personal categories and persisted pinned prompts.
- Existing secure Gmail connection flow, platform account sign-in, chat-based mailbox agent, approval history, settings, theme preferences, and generic approval reminders.

## Architecture

`:core:domain` holds mail models and the InboxRepository contract. `:core:data` implements bearer-authenticated transport and DTO mapping. `:feature:mail` contains StateFlow ViewModels and Compose rendering, with no data-module dependency. Hilt binds the implementation. Credentials remain on the server. Mail is rendered as plain text with no remote-image or WebView loading.

## Fixes

- Failed server requests no longer imply an empty approval queue or an offline network.
- Folder/search response generations prevent stale requests replacing the current folder. Pagination deduplicates message IDs.
- Draft submission blocks duplicate taps, prevents replacement while saving, and restores uncertain state after process death.
- Rejected Trash requests display the correct result instead of draft-related text.
- Agent searches default to Inbox; reply recipients come from the original Gmail message. Internal ID annotations and fake approval-button text are removed from agent replies.

## Not yet equivalent to Genie Mail

One Gmail mailbox per platform user is supported. Outlook/Microsoft, iCloud, Yahoo/IMAP, account switching/unified inbox, message pinning and real-time new-mail push remain gaps. Custom labels, attachment download/upload, document-attachment summaries, formatted (HTML) email and scheduled send were added in source on 25 November 2026; see [capabilities](MAIL_ASSISTANT_CAPABILITIES.md) for their verification state.

The live platform also needs the native API deployment and database migrations. A signed-in platform account alone does not establish a Gmail connection. Source implementation, unit tests, device UI tests, and real-provider end-to-end tests are separate verification stages.

## Verification

Run `verify-genie.cmd` as your Windows user for an APK build, all Android unit tests, lint, and instrumentation APK compilation. It writes `codex-verification.log`. Connected UI tests require an ADB-connected device and `gradlew.bat connectedDebugAndroidTest`. Use a dedicated test mailbox for external sends or Trash approvals.

## Physical-device verification — 19 November 2026

The normal Android Studio build completed with Java/Hilt compilation enabled. All 91 Android unit tests pass; app lint has zero errors and 11 warnings. Five instrumentation UI tests passed on an OPPO CPH2695, API 36. The rebuilt debug APK was installed without clearing the signed-in app data.

Authenticated Gmail inbox/message reading and real-message summary were exercised through the updated backend on the PC. Write, Grammar, Translate, Reply, composer validation, and persistent prompt pinning were checked on the phone. GET connection recovery is enabled independently of mutations; tests verify that lost approval responses are not replayed. Reader retry, visible inbox refresh, and definite missing-storage errors are included.

The public deployment still lacks native mail routes. The configured database lacks the three native conversation/mail migrations, so conversation list/create and new draft approvals remain blocked. Fresh Gmail OAuth, real Send/Trash execution, and production deployment were not completed. The device test backend is local to the PC, with ADB reverse on port 3100. These results do not establish full Genie Mail parity.
### Retest after approved migrations — 19 November 2026

The three native conversation/mail migrations were applied to the configured database. The phone successfully created and reopened a persistent conversation, used the real Gmail search tool, created one synthetic Gmail draft, reviewed its actual details, cancelled Send confirmation, and declined its send request. Database checks found one completed draft request, one Gmail draft, one declined action, and zero sends for that test. Owner/stranger conversation isolation passed; no second organization member was available for that extra identity case.

Email-manager mode now selects the configured mail provider independently of the ordinary chatbot model. This fixes failure when the chatbot selects an unconfigured Anthropic provider but the mail provider is ready. Backend tests now total 60 passing, with type checking passing. The final reconnect UI APK passed the normal Android build, 91 unit tests, and five on-device UI tests, with zero lint errors and 11 warnings.

Reconnect can start while the existing mailbox is active, preserving it until consent completes. It reaches Google's account chooser on the phone; consent/callback completion is pending user action. The public native API deployment remains pending; actual device checks still use the updated backend on the PC through ADB reverse.