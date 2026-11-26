package com.wechatdigest.core.llm

import com.wechatdigest.core.model.WeChatMessage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CustomRuleExtractionTest {

    @Test
    fun `buildSystemPrompt interpolates custom rules when provided`() {
        val mockEngine = MockEngine { respond("", HttpStatusCode.OK) }
        val client = KtorLlmClient(LlmConfig(), engine = mockEngine)
        val extractor = EventExtractor(client)

        val promptWithRules = extractor.buildSystemPrompt(
            userPersona = "Graduate Student",
            targetLanguage = "en",
            customRules = "Ignore Bachelor notices. Focus only on Master thesis deadlines."
        )

        assertTrue(promptWithRules.contains("CHAT-SPECIFIC FOCUS RULES / INSTRUCTIONS:"))
        assertTrue(promptWithRules.contains("Ignore Bachelor notices. Focus only on Master thesis deadlines."))
        assertTrue(promptWithRules.contains("USER CONTEXT / PREFERENCES:"))
        assertTrue(promptWithRules.contains("Graduate Student"))
    }

    @Test
    fun `buildSystemPrompt omits rules section when customRules is blank`() {
        val mockEngine = MockEngine { respond("", HttpStatusCode.OK) }
        val client = KtorLlmClient(LlmConfig(), engine = mockEngine)
        val extractor = EventExtractor(client)

        val promptWithoutRules = extractor.buildSystemPrompt(
            userPersona = "Graduate Student",
            targetLanguage = "en",
            customRules = ""
        )

        assertFalse(promptWithoutRules.contains("CHAT-SPECIFIC FOCUS RULES / INSTRUCTIONS:"))
    }

    @Test
    fun `extractEvents sends custom rules to Pass 1 triage, Pass 2 gatekeeper, and Pass 3 extraction`() = runBlocking {
        val capturedBodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val mockEngine = MockEngine { request ->
            val bodyText = (request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
            capturedBodies.add(bodyText)
            val contentJson = when {
                bodyText.contains("Semantic Filter & Topic Chunker") ->
                    """{"chunks":[{"topic":"Master Thesis Submission","message_ids":[1],"category":"DEADLINE"}]}"""
                bodyText.contains("Rule & Substance Gatekeeper") ->
                    """{"is_group_notice":true,"passes_rules":true,"has_substance":true,"keep":true,"reason":"Valid thesis deadline"}"""
                else ->
                    """{"is_relevant":true,"events":[{"title":"Master Thesis Submission","summary":"Submit draft by Nov 30.","translated_summary":"Submit your Master thesis draft by November 30.","category":"DEADLINE","priority":"CRITICAL","event_date":"2026-11-30","source_message_ids":[1]}]}"""
            }
            val payload = """
                {
                  "id": "chatcmpl-test",
                  "object": "chat.completion",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": ${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), contentJson)}
                      },
                      "finish_reason": "stop"
                    }
                  ]
                }
            """.trimIndent()
            respond(
                content = payload,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = KtorLlmClient(LlmConfig(apiKey = "key"), engine = mockEngine)
        val extractor = EventExtractor(client, enableFastPath = false)

        val messages = listOf(
            WeChatMessage(
                localId = 1L,
                timestampSeconds = 1731000000L,
                senderId = "dean",
                senderDisplayName = "Dean Office",
                rawContent = "Master students please submit your thesis draft by Nov 30",
                textContent = "Master students please submit your thesis draft by Nov 30",
                isZstdCompressed = false
            )
        )

        val events = extractor.extractEvents(
            messages = messages,
            roomId = "grad@chatroom",
            userPersona = "Graduate Student",
            targetLanguage = "en",
            customRules = "Only extract Master thesis deadlines."
        )

        assertEquals(3, capturedBodies.size, "Expected Pass 1 triage + Pass 2 gatekeeper + Pass 3 chunk extraction")
        assertTrue(capturedBodies[0].contains("MANDATORY GROUP FILTERING RULES"))
        assertTrue(capturedBodies[0].contains("Only extract Master thesis deadlines."))
        assertTrue(capturedBodies[1].contains("Only extract Master thesis deadlines."))
        assertTrue(capturedBodies[2].contains("Only extract Master thesis deadlines."))
        assertEquals(1, events.size)
        assertEquals("Master Thesis Submission", events[0].title)
        assertTrue(events[0].sourceMessages.contains("Master students please submit your thesis draft by Nov 30"))
        assertTrue(capturedBodies[0].contains("[id:1]"))
        assertTrue(capturedBodies[2].contains("[id:1]"))
        assertFalse(capturedBodies[2].contains("\"action_items\""))
        client.close()
    }

    @Test
    fun `Pass 1 filtering rule excludes matching noise and skips Pass 2 when no chunks remain`() = runBlocking {
        var callCount = 0
        val mockEngine = MockEngine { request ->
            callCount++
            val bodyText = (request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
            assertTrue(bodyText.contains("ignore personal student issues"))
            assertTrue(bodyText.contains("Semantic Filter & Topic Chunker"))
            val payload = """
                {
                  "id": "chatcmpl-triage-empty",
                  "object": "chat.completion",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "{\"chunks\":[]}"
                      },
                      "finish_reason": "stop"
                    }
                  ]
                }
            """.trimIndent()
            respond(
                content = payload,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = KtorLlmClient(LlmConfig(apiKey = "key"), engine = mockEngine)
        val extractor = EventExtractor(client, enableFastPath = false)
        val studentQuestion = WeChatMessage(
            localId = 10L,
            timestampSeconds = 1794815000L,
            senderId = "student_1",
            senderDisplayName = "Student_A",
            rawContent = "Teacher, where can I pick up my stamp card? What if I have a class?",
            textContent = "Teacher, where can I pick up my stamp card? What if I have a class?",
            isZstdCompressed = false
        )

        val result = extractor.extractEventsResult(
            messages = listOf(studentQuestion),
            roomId = "cse@chatroom",
            customRules = "ignore personal student issues, e.g. when someone asks about deadlines or for help; parse only information posted by a teacher"
        )

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
        assertEquals(1, callCount, "Pass 2 must NOT run when Pass 1 filters all messages out")
        client.close()
    }
}
