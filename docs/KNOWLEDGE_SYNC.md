# Shared chatbot knowledge in Android

The native personal assistant retrieves the same assigned knowledge as the configured published chatbot in the Embeddable Chatbot backend. Knowledge stays in the existing platform database and ingestion pipeline; document text is not bundled into the APK or copied into a second knowledge store.

## How updates reach the app

1. Add, replace, reprocess or remove knowledge through the existing system. Global sources still need to be explicitly assigned to the source chatbot.
2. New or changed documents become available after ingestion finishes successfully. Pending, processing or failed documents are not used as ready knowledge.
3. Each assistant request resolves the configured source and retrieves its current permitted chunks. Processed edits, assignments, unassignments and deletions therefore affect later requests without an Android rebuild.
4. Android displays the source name and ready-document status. It refreshes this metadata on foreground entry, manual refresh and periodically while the conversation is visible. The displayed count can briefly lag a dashboard change; answer retrieval reads the current server state independently.
5. Chat answers show source citations. Write and Reply tools can use relevant shared knowledge; Grammar, Translate and supplied-text Summary preserve their input-only behavior.

Previously saved answers remain historical conversation messages. Updating knowledge does not rewrite earlier answers; ask a new question for a response based on current processed content. Internet access is required for new AI answers.

## Configuration and privacy

The backend's server-side NATIVE_KNOWLEDGE_CHATBOT_ID selects one active published chatbot. This deployment links Demo Assistant (cb_DwGszOHPvAdGxI5E83oyjrru), which has the existing Gani Macatoman public-record document. Other unassigned knowledge is excluded.

The source is validated through the same published-chatbot access checks used by the signed-in client API. A private native assistant cannot be selected as the shared source. Generic published chatbots retain their own knowledge assignments. No organization memberships, source assignments, document permissions or RLS policies are changed by the link.

Personal assistant IDs, conversation ownership and Gmail tools remain tied to the signed-in user. Only the retrieval source changes. A missing, inactive, denied or private configured source reports knowledge unavailable rather than silently answering without the requested linked knowledge. With no shared-source configuration, an assistant retains its own knowledge scope.

## Architecture

core:domain models KnowledgeInfo; core:data maps optional configuration metadata; the chat ViewModel refreshes status and Compose renders it. Retrieval and authorization stay on the backend. The configuration response is additive, so older apps can still use the backend and older servers remain readable by the new app.

The backend uses the existing ingestion, source assignment and retrieveForChatbot functions. A rolled-back database test exercises ingestion/reprocessing, source assignment/removal, document deletion and tenant isolation without changing the real knowledge documents.
