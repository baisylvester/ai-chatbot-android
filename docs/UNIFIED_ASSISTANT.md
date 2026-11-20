# Unified AI Assistant

The native app has one AI Assistant entry point for general conversation, email writing and connected-Gmail requests. Inbox and Prompts remain available alongside it. The former separate AI mail navigation item is removed.

## Conversation flow

- AI Assistant resumes the most recently updated private conversation, or creates one if there is no history. Conversation history supports opening, creating and deleting threads.
- Prompt-library selections open a new conversation with an editable draft. They are never submitted automatically and do not replace text in another thread.
- General questions and Gmail requests use the same conversation repository, saved transcript, request IDs, streaming transport and recovery rules.
- Connected, eligible Gmail tools are available automatically. There is no separate chatbot/email-manager mode switch. The backend chooses whether a request needs the mailbox tool and still enforces owner/admin and connection checks.
- Without an active authorized mailbox, chat requests omit mailbox capability. Writing tools do not require a mailbox. A failed status refresh clears stale capability and offers a connection refresh.
- Send and Trash retain explicit approval. Other supported organizing actions can execute immediately when requested.

## Writing tools

The writing icon in the assistant opens Write, Reply, Summary, Grammar and Translate with language, tone and length controls. Continue in AI Assistant returns generated text to the same conversation's composer. Existing composer text is retained, the handoff is consumed once across recreation, and the user decides whether to send it. Text exceeding the chat limit is shown for editing rather than silently truncated. Copy and Use in email remain available.

Message-reader writing actions still operate on the selected message, and do not automatically submit it to chat or create a Gmail draft. Saving an email draft remains a deliberate composer action.

## Boundaries

Feature modules depend on domain contracts, not on one another or on core:data. The app navigation layer composes chat and writing screens. AssistantEntryViewModel resolves conversations using the existing account-scoped repository. ChatViewModel manages transcript/composer state and capability status; the existing SendCoordinator retains idempotency and uncertain-send handling.

The signed-in conversation API supports email_manager alongside general chat. A backend routing fix also makes private personal assistants use the deployed assistant provider when mailbox tools are off, instead of retaining an older PC-local chatbot model. Public chatbot model selection is unchanged. Missing assistant-provider configuration returns 503 before claiming a turn. This uses the existing private-assistant ownership check and needs no schema migration or API contract change.

## Scope

This change does not add background autonomous actions, additional mail providers, attachment processing, native new-mail push or release signing. The onboarding confirmation-email delivery and Auth configuration verification limits in NEW_USER_ONBOARDING.md still apply.

## Verified on 20 November 2026

123 Android unit tests, 68 backend tests, seven isolated physical-device mail UI tests, two final live integration tests and one final live UI test passed. Android lint reported 0 errors and 16 warnings. The preview phone test verified general chat without mailbox capability before the backend routing fix was promoted live. Fresh-account confirmation delivery and release signing remain unverified/incomplete.
