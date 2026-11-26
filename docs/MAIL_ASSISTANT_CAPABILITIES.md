# Mail Assistant capabilities

Updated 26 November 2026. The AI agent can read, organize, label and draft emails in your connected Gmail account. Sending (now or at a scheduled time) and moving messages to Trash require your approval.

## Capability

- Find emails
- Read and summarize, including PDF, Word (.docx), text, Markdown and HTML attachments, and images
- Organize mail, including your own Gmail labels
- Draft emails and replies, plain or formatted, with file attachments (in the app)
- Send emails, now or scheduled
- Trash emails

## Current behavior

- Search by sender, subject, keywords, unread status, dates, folders and labels. Searches default to Inbox.
- Read message text, summarize it, and identify requested details or action items. Document attachments and images can be read and summarized (PDFs are read on the phone on Android 15 and newer, or by Gemini when the server has `GOOGLE_GENERATIVE_AI_API_KEY`; images need that key); Excel (.xlsx), PowerPoint (.pptx) and legacy Word (.doc, best effort) are read on the phone. Other files, such as archives, can be opened in another app on the phone.
- Archive, move to Inbox, mark read/unread, star/unstar, mark spam, and add or remove your own labels. These actions happen immediately when requested. The agent creates, renames or recolors a label only when you ask. Deleting a label is done in the app (drawer → edit icon), after a confirmation; its emails are kept. System folders cannot be changed through labels, so labels can never bypass Trash approval.
- Pin emails (message menu or reader). Pinned emails appear above the inbox and are kept on the server, so every device signed in to the account shows the same pins (up to 50 per mailbox).
- While you type a recipient, the composer suggests people from your recent mail (the last year of Sent and Inbox, people you wrote to first).
- The color theme you choose is saved to your account and applied on every phone you sign in on.
- Offline, the app shows a read-only copy saved on the phone: the first page of each folder or label you viewed and the last 50 emails you opened. Searching, later pages and every action need a connection. The copy is deleted on sign-out and never backed up.
- Save a Gmail draft and create a send request for your review. Reply recipients come from the original email. Drafts can be formatted (bold, italic, lists, links) and carry up to 10 files (10 MB in total): picked in the app's composer, or, when you ask the assistant, copied from emails already in the mailbox (for example to forward a document). Review shows the plain-text copy of a formatted email.
- The approval workflow can send the reviewed draft now, or at a time you choose (from one minute to 60 days ahead). A scheduled email is sent by the server within about 10 minutes of that time, and only if the draft was not edited after you approved it. You can cancel a schedule or keep the email as a draft until it is sent. The AI cannot send or schedule independently.
- Queue selected messages for review; move them to Trash.

## It currently cannot

- Connect Outlook, Yahoo, iCloud, or other IMAP providers.
- Manage multiple mailboxes through a unified inbox.
- Read archives, legacy Excel (.xls) or PowerPoint (.ppt) files; legacy Word (.doc) reading is best effort.
- Attach files from the phone when the AI writes a draft in chat (it can attach files already in the mailbox; phone files go through the composer).
- Run ongoing automatic replies, or continuously manage your inbox unattended.
- Deliver native real-time new-email push notifications.
- Control other Android apps, SMS, contacts, or calendars.
- Act offline: the saved copy is read-only; AI processing and every Gmail action require internet access.
- Large mailbox requests may need several rounds: the agent uses limited search results and processing steps.

## Verification state

| Area | State |
|---|---|
| On the OPPO CPH2695 against the live service, 26 November 2026 (migration `20261126090000_mail_pins_preferences.sql` applied, Pages deployment `00879ea9`) | Passed: rename and recolor a Gmail label (then renamed back); pin an email, see it above the inbox after a refresh from the server, unpin it; recipient suggestions ("pipe" → both Pipedrive addresses) and choosing one; theme change stored on the server (then restored); with the app's network blocked, a cold start opens the saved inbox and a previously opened email. Fixed during the run: suggestions used Gmail word search, which missed partial names, so the phone now filters a list of recent correspondents; a cold start without a connection stopped at "Setting up your assistant" |
| AI attaching mailbox files to a draft, reading .xlsx/.pptx/.doc on the phone, deleting a label | Unit-tested; **not yet run on the phone** |
| Gmail status, Inbox reading, private-assistant setup, AI writing | Passed on the phone against the live service (20 November 2026) |
| Search, summaries, draft creation, review and decline | Exercised earlier against the real mailbox |
| On the OPPO CPH2695 (Android 16) against the live service, 25 November 2026 | Passed: mail UI tests (7/7); create a label, apply it, browse it (Gmail returned exactly that message); open a PDF attachment in a viewer; summarize a PDF attachment (read on the phone, then summarized by the assistant); formatted draft with a file attachment saved and reviewed; schedule for a later time, confirmed in the database, cancelled, then kept as draft (nothing sent); the AI assistant listed labels and found the labelled email. Fixed during the run: lost characters when typing quickly in the composer, 4xx errors shown as generic server errors, label rows needing a tap on the box itself, the same file attaching twice |
| Real send and real Trash execution | Approval workflows implemented and unit-tested; **not yet run against a real mailbox** |
| Labels, attachments (open, summarize, attach), formatted email, scheduled send | Implemented and **deployed 25 November 2026** (migration `20261125090000_mail_scheduled_send.sql` applied first; Pages deployment `882bc098`). All backend and Android unit tests pass; live routes answer and require sign-in. **Not yet run on the phone or against real Gmail**; the scheduled sender runs on the 10-minute maintenance cron |

## Why the remaining items are still open

| Item | What it needs |
|---|---|
| Outlook / Microsoft 365 | A Microsoft Entra app registration (client ID/secret, Mail.ReadWrite + Mail.Send consent) and a Graph-based `MailApi` implementation |
| Yahoo / iCloud / IMAP | App-specific passwords per account and an IMAP/SMTP client over Cloudflare TCP sockets; handling those passwords needs a security review first |
| Multiple mailboxes / unified inbox | Schema change (`mail_connections` is one row per user) and mailbox selection across every route and the agent |
| Native push | A Firebase project (`google-services.json`), device-token storage and a server-side FCM sender; Gmail `watch` + Pub/Sub for true real-time |
| Unattended auto-replies | Deliberately excluded: the AI never sends without the owner's explicit approval |
| Legacy .xls / .ppt, archives | Binary formats need full parsers; the Worker is near Cloudflare's 25 MiB limit, so they would have to run on the phone |
