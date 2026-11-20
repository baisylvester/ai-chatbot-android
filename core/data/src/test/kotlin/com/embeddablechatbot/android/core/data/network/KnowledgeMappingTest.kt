package com.embeddablechatbot.android.core.data.network

import com.embeddablechatbot.android.core.domain.model.KnowledgeState
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class KnowledgeMappingTest {
    @Test fun `old server responses remain compatible without knowledge metadata`() {
        assertNull(ApiJson.decodeFromString<ChatbotConfigDto>("""{"chatbotId":"cb_test"}""").toDomain().knowledge)
    }
    @Test fun `maps source and ready counts without copying document content`() {
        val dto=ApiJson.decodeFromString<ChatbotConfigDto>("""{"chatbotId":"cb_personal","knowledge":{"state":"ready","sourceChatbotId":"cb_shared","sourceName":"System knowledge","readyDocuments":1,"processingDocuments":0,"chunks":12,"shared":true,"updatedAt":"2026-11-20T00:00:00.000Z"}}""")
        val knowledge=dto.toDomain().knowledge!!
        assertEquals(KnowledgeState.Ready,knowledge.state);assertEquals("cb_shared",knowledge.sourceChatbotId)
        assertEquals(12,knowledge.chunks);assertTrue(knowledge.shared);assertNotNull(knowledge.updatedAt)
    }
    @Test fun `unknown server state is unavailable rather than falsely ready`() {
        assertEquals(KnowledgeState.Unavailable,KnowledgeInfoDto(state="future-status").toDomain().state)
    }
    @Test(expected=ContractException::class) fun `rejects negative document counts`() {
        KnowledgeInfoDto(state="ready",readyDocuments=-1).toDomain()
    }
}
