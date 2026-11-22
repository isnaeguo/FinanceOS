package com.financeos.app.ai

import com.financeos.shared.ai.AiApiProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AiEndpointTest {
    @Test
    fun defaultsBaseAddressesToResponsesAndPreservesExplicitPaths() {
        assertEquals(
            "https://api.openai.com/v1/responses",
            aiEndpoint("https://api.openai.com").toString(),
        )
        assertEquals(
            "https://api.example.test:8443/compat/v1/responses",
            aiEndpoint("https://api.example.test:8443/compat/v1/").toString(),
        )
        assertEquals(
            "https://api.example.test/chat/completions",
            aiEndpoint("https://api.example.test/chat/completions").toString(),
        )
    }

    @Test
    fun infersProtocolFromTheResolvedPath() {
        assertEquals(AiApiProtocol.RESPONSES, aiProtocol(aiEndpoint("https://api.openai.com/v1")))
        assertEquals(AiApiProtocol.RESPONSES, aiProtocol(aiEndpoint("https://api.example.test/custom/responses")))
        assertEquals(
            AiApiProtocol.CHAT_COMPLETIONS,
            aiProtocol(aiEndpoint("https://api.example.test/v1/chat/completions/")),
        )
    }

    @Test
    fun rejectsCleartextCredentialsQueriesAndFragments() {
        listOf(
            "http://api.example.test/v1",
            "https://alice:secret@api.example.test/v1",
            "https://api.example.test/v1?token=value",
            "https://api.example.test/v1#fragment",
            "https:///v1",
            "https://api.example.test:65536/v1",
        ).forEach { address ->
            assertFailsWith<IllegalArgumentException> { aiEndpoint(address) }
        }
    }
}
