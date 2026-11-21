package com.wechatdigest.core

import com.wechatdigest.core.llm.EventExtractor
import com.wechatdigest.core.llm.KtorLlmClient
import com.wechatdigest.core.llm.LanguageHelper
import com.wechatdigest.core.llm.LlmConfig
import com.wechatdigest.core.llm.LlmProviderType
import com.wechatdigest.core.llm.MessageTranslator
import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventCategory
import com.wechatdigest.core.model.EventPriority
import com.wechatdigest.core.model.EventSection
import com.wechatdigest.core.model.WeChatMessage
import com.wechatdigest.core.sanitizer.MessageSanitizer
import com.wechatdigest.core.sanitizer.PrefilterConfig
import com.wechatdigest.core.sanitizer.PrefilterMatchType
import com.wechatdigest.core.sanitizer.PrefilterRule
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SanitizationAndLlmTest {

    @Test
    fun testMessageSanitizerFiltersNoise() {
        val noisyMessages = listOf(
            WeChatMessage(1L, 100L, "u1", "Bob", "<msg><img aeskey='abc'/></msg>", "<msg><img aeskey='abc'/></msg>", false),
            WeChatMessage(2L, 101L, "u2", "Alice", "~se12345", "~se12345", false),
            WeChatMessage(3L, 102L, "u3", "Charlie", "[强]", "[强]", false),
            WeChatMessage(4L, 103L, "u4", "David", "[Salute]", "[Salute]", false),
            WeChatMessage(5L, 104L, "u5", "Eva", "+1", "+1", false),
            WeChatMessage(6L, 105L, "u6", "Frank", "收到", "收到", false),
            WeChatMessage(7L, 106L, "u7", "Grace", "ok", "ok", false),
            WeChatMessage(8L, 107L, "u8", "Prof_Lin", 
                "【本科生校运会期间上课通知】各位同学好，校运会将于11月13-15日举办，11月13日停课一天。",
                "【本科生校运会期间上课通知】各位同学好，校运会将于11月13-15日举办，11月13日停课一天。", 
                false
            )
        )

        val filtered = MessageSanitizer.filterSubstantiveMessages(noisyMessages)
        assertEquals(1, filtered.size, "Only the real announcement should survive noise filtering")
        assertEquals("Prof_Lin", filtered.first().senderDisplayName)
        assertTrue(filtered.first().textContent.contains("校运会期间上课通知"))
    }

    @Test
    fun testMessageSanitizerPredicateDirect() {
        assertFalse(MessageSanitizer.isSubstantive(""))
        assertFalse(MessageSanitizer.isSubstantive("   "))
        assertFalse(MessageSanitizer.isSubstantive("ok"))
        assertFalse(MessageSanitizer.isSubstantive("好的"))
        assertFalse(MessageSanitizer.isSubstantive("谢谢老师"))
        assertFalse(MessageSanitizer.isSubstantive("[微笑]"))
        assertFalse(MessageSanitizer.isSubstantive("[Sticker]"))
        assertFalse(MessageSanitizer.isSubstantive("[Red Packet]"))
        assertFalse(MessageSanitizer.isSubstantive("received"))
        assertFalse(MessageSanitizer.isSubstantive("Received!"))
        assertFalse(MessageSanitizer.isSubstantive("收到！"))
        assertFalse(MessageSanitizer.isSubstantive("完成了✅"))
        assertFalse(MessageSanitizer.isSubstantive("完成了！"))
        assertFalse(MessageSanitizer.isSubstantive("已提交✅"))
        assertFalse(MessageSanitizer.isSubstantive("已提交。"))
        assertFalse(MessageSanitizer.isSubstantive("done!"))
        assertFalse(MessageSanitizer.isSubstantive("completed."))
        assertFalse(MessageSanitizer.isSubstantive("@所有人"))
        assertFalse(MessageSanitizer.isSubstantive("@全体成员"))
        assertFalse(MessageSanitizer.isSubstantive("@All"))
        assertFalse(MessageSanitizer.isSubstantive("@All!"))
        assertFalse(MessageSanitizer.isSubstantive("👍🏻👍🏼"))
        assertFalse(MessageSanitizer.isSubstantive("<?xml version=\"1.0\"?>"))
        assertTrue(MessageSanitizer.isSubstantive("38℃!"))
        assertTrue(MessageSanitizer.isSubstantive("90㎡!"))
        assertTrue(MessageSanitizer.isSubstantive("admin@all"))
        assertTrue(MessageSanitizer.isSubstantive("email@all.com"))
        assertTrue(MessageSanitizer.isSubstantive("@所有人，明天上午十点提交论文"))
        assertTrue(MessageSanitizer.isSubstantive("明天上午十点前提交最终论文打印版✅"))
        assertTrue(MessageSanitizer.isSubstantive("We received the final exam room update"))
        assertTrue(MessageSanitizer.isSubstantive("请各位同学在周五前提交毕业论文开题报告"))
        assertEquals("Hello\nWorld", MessageSanitizer.cleanContent("Hello\r\nWorld   "))
    }

    @Test
    fun testConfigurablePrefilterMinLengthAndRuleModes() {
        val len3Config = PrefilterConfig(minMessageLength = 3, rules = emptyList())
        assertFalse(MessageSanitizer.isSubstantive("ok", len3Config))
        assertTrue(MessageSanitizer.isSubstantive("FYA", len3Config))
        assertTrue(MessageSanitizer.isSubstantive("考试了", len3Config))

        val noMinLenConfig = PrefilterConfig(minMessageLength = 0, rules = emptyList())
        assertFalse(MessageSanitizer.isSubstantive("   ", noMinLenConfig))
        assertTrue(MessageSanitizer.isSubstantive("ok", noMinLenConfig))

        val customRulesConfig = PrefilterConfig(
            minMessageLength = 2,
            rules = listOf(
                PrefilterRule(id = "r1", pattern = "copy that", matchType = PrefilterMatchType.WHOLE_MESSAGE, enabled = true),
                PrefilterRule(id = "r2", pattern = "recalled a message", matchType = PrefilterMatchType.CONTAINS, enabled = true),
                PrefilterRule(id = "r3", pattern = "System:", matchType = PrefilterMatchType.STARTS_WITH, enabled = true),
                PrefilterRule(id = "r4", pattern = "^\\d{4,}$", matchType = PrefilterMatchType.REGEX, enabled = true),
                PrefilterRule(id = "r5", pattern = "disabledword", matchType = PrefilterMatchType.CONTAINS, enabled = false),
                PrefilterRule(id = "r6", pattern = "[unclosed-regex", matchType = PrefilterMatchType.REGEX, enabled = true)
            )
        )

        assertFalse(MessageSanitizer.isSubstantive("Copy that!!", customRulesConfig))
        assertTrue(MessageSanitizer.isSubstantive("Copy that, exam is in Room 101", customRulesConfig))
        assertFalse(MessageSanitizer.isSubstantive("Bob recalled a message", customRulesConfig))
        assertFalse(MessageSanitizer.isSubstantive("system: maintenance tonight", customRulesConfig))
        assertFalse(MessageSanitizer.isSubstantive("123456", customRulesConfig))
        assertTrue(MessageSanitizer.isSubstantive("disabledword still passes when rule disabled", customRulesConfig))
    }

    @Test
    fun testPrefilterRuleValidationAndExtractorIntegration() = runBlocking {
        assertNotNull(MessageSanitizer.validateRulePattern("   ", PrefilterMatchType.WHOLE_MESSAGE))
        assertNotNull(MessageSanitizer.validateRulePattern("[unclosed", PrefilterMatchType.REGEX))
        assertNull(MessageSanitizer.validateRulePattern("^\\[Sticker\\]$", PrefilterMatchType.REGEX))
        assertNull(MessageSanitizer.validateRulePattern("received", PrefilterMatchType.WHOLE_MESSAGE))

        var llmCalled = false
        val mockEngine = MockEngine {
            llmCalled = true
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = KtorLlmClient(LlmConfig(), mockEngine)
        val extractor = EventExtractor(client)

        val customConfig = PrefilterConfig(
            minMessageLength = 4,
            rules = listOf(
                PrefilterRule(id = "custom", pattern = "customnoise", matchType = PrefilterMatchType.CONTAINS, enabled = true)
            )
        )
        val events = extractor.extractEvents(
            messages = listOf(
                WeChatMessage(1L, 100L, "u1", "Bob", "customnoise message ignored", "customnoise message ignored", false)
            ),
            roomId = "room@chatroom",
            prefilterConfig = customConfig
        )

        assertTrue(events.isEmpty())
        assertFalse(llmCalled, "LLM must not be called when custom prefilter rules drop all messages")
        client.close()
    }

    @Test
    fun testEventExtractorWithMockOpenAiEngine() = runBlocking {
        val longOriginalMessage = "这些同学的护照好了，请来办公室找我领取。" +
            "补充说明：请携带本人有效学生证和护照复印件，办公时间为工作日9:00-17:00，逾期未领请提前联系辅导员。"
        val classCancellationMsg = "校运会将于11月13日举办，停课一天"

        val triageJsonResponse = """
        {
          "chunks": [
            {
              "topic": "Passport Pickup Notice",
              "message_ids": [1],
              "category": "NOTICE"
            },
            {
              "topic": "Sports Meet Class Cancellation",
              "message_ids": [2],
              "category": "NOTICE"
            },
            {
              "topic": "Empty Id Hallucination",
              "message_ids": [],
              "category": "NOTICE"
            },
            {
              "topic": "Invalid Id Hallucination",
              "message_ids": [999, null],
              "category": "NOTICE"
            }
          ]
        }
        """.trimIndent()

        val passportChunkJson = """
        {
          "is_relevant": true,
          "events": [
            {
              "title": "Passport Pickup Notice",
              "summary": "Passports for listed students are ready for pickup at the office on weekdays from 9:00 to 17:00.",
              "translated_summary": "Passports for listed students are ready. Bring your student ID and passport copy to the office on weekdays 9:00-17:00.",
              "category": "NOTICE",
              "priority": "CRITICAL",
              "event_date": "2026-11-10T15:00:00",
              "source_message_ids": [1]
            }
          ]
        }
        """.trimIndent()

        // Intentionally return empty source_message_ids from Pass 2 to verify chunk attribution guarantee
        val sportsChunkJson = """
        {
          "is_relevant": true,
          "events": [
            {
              "title": "Sports Meet Class Cancellation",
              "summary": "Classes are suspended on Nov 13 for the university sports meet.",
              "translated_summary": "Classes are suspended on Nov 13 due to the university sports meet.",
              "category": "NOTICE",
              "priority": "IMPORTANT",
              "event_date": "2026-11-13T09:00:00",
              "source_message_ids": []
            }
          ]
        }
        """.trimIndent()

        val capturedBodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val mockEngine = MockEngine { request ->
            assertTrue(request.url.encodedPath.contains("chat/completions"))
            val bodyText = (request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
            capturedBodies.add(bodyText)

            val contentJson = when {
                bodyText.contains("Semantic Filter & Topic Chunker") -> triageJsonResponse
                bodyText.contains("Rule & Substance Gatekeeper") ->
                    """{"verdicts":[{"chunk_index":0,"is_group_notice":true,"passes_rules":true,"has_substance":true,"keep":true,"reason":"Valid notice"},{"chunk_index":1,"is_group_notice":true,"passes_rules":true,"has_substance":true,"keep":true,"reason":"Valid notice"}]}"""
                bodyText.contains("[id:1]") -> passportChunkJson
                else -> sportsChunkJson
            }

            val responsePayload = """
            {
              "id": "chatcmpl-test",
              "object": "chat.completion",
              "created": 1700000000,
              "model": "gpt-4o-mini",
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
                content = responsePayload,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val config = LlmConfig(
            providerType = LlmProviderType.OPENAI_COMPATIBLE,
            endpointUrl = "https://api.openai.com/v1",
            apiKey = "test-key",
            modelName = "gpt-4o-mini"
        )
        val client = KtorLlmClient(config, mockEngine)
        val extractor = EventExtractor(client)

        val messages = listOf(
            WeChatMessage(1L, 1794298726L, "wxid_teacher", "Prof_Lin", longOriginalMessage, longOriginalMessage, false),
            WeChatMessage(2L, 1794298800L, "wxid_admin", "Academic_Office", classCancellationMsg, classCancellationMsg, false)
        )

        val events = extractor.extractEvents(
            messages = messages,
            roomId = "10000000001@chatroom",
            roomDisplayName = "2026秋季学期学生群（境内）"
        )
        assertEquals(4, capturedBodies.size, "Expected 1 triage + 1 batched gatekeeper + 2 extraction calls")
        assertEquals(2, events.size)
        assertTrue(capturedBodies[0].contains("[id:1]"))
        assertTrue(capturedBodies[0].contains("[id:2]"))
        val pass3Bodies = capturedBodies.filter { it.contains("Grounded Card Summarizer") }
        assertEquals(2, pass3Bodies.size)
        assertTrue(pass3Bodies.none { it.contains("\"action_items\"") })
        assertTrue(pass3Bodies.none { it.contains("\"source_quote\"") })

        val passportEvent = events[0]
        assertEquals("Passport Pickup Notice", passportEvent.title)
        assertEquals(EventPriority.CRITICAL, passportEvent.priority)
        assertEquals(EventCategory.NOTICE, passportEvent.category)
        assertEquals("10000000001@chatroom", passportEvent.sourceRoomId)
        assertEquals("2026秋季学期学生群（境内）", passportEvent.sourceRoomName)
        assertTrue(passportEvent.sourceMessages.contains("Prof_Lin: $longOriginalMessage"))
        assertFalse(passportEvent.sourceMessages.contains("[id:1]"))
        assertEquals("Prof_Lin", passportEvent.originalSender)
        assertEquals(1794298726L, passportEvent.timestampSeconds)

        val classEvent = events[1]
        assertEquals("Sports Meet Class Cancellation", classEvent.title)
        assertEquals(EventPriority.IMPORTANT, classEvent.priority)
        assertEquals("2026-11-13T09:00:00", classEvent.eventDate)
        assertEquals("2026秋季学期学生群（境内）", classEvent.sourceRoomName)
        assertTrue(classEvent.sourceMessages.contains("Academic_Office: $classCancellationMsg"), "Every card must include its verified chunk sourceMessages even when Pass 2 returns empty IDs")
        assertEquals(1794298800L, classEvent.timestampSeconds)

        client.close()
    }

    @Test
    fun testEventExtractorWithMockOllamaEngine() = runBlocking {
        val triageJson = """
        {
          "chunks": [
            {
              "topic": "Lab Seminar Registration",
              "message_ids": ["[id:2]", "1", "id:1", 2, "invalid", 999],
              "category": "ACTIVITY"
            }
          ]
        }
        """.trimIndent()
        val mockJsonResponse = """
        {
          "is_relevant": true,
          "events": [
            {
              "title": "Lab Seminar Registration",
              "summary": "Lab seminar scheduled for Wednesday afternoon in Room A101.",
              "translated_summary": "Lab seminar scheduled for Wednesday afternoon in Room A101. Arrive 15 minutes early with a laptop.",
              "category": "ACTIVITY",
              "priority": "NORMAL",
              "event_date": "2026-11-12T14:00:00",
              "source_message_ids": ["[id:2]", "1"]
            }
          ]
        }
        """.trimIndent()

        val capturedOllamaBodies = mutableListOf<String>()
        val mockEngine = MockEngine { request ->
            assertTrue(request.url.encodedPath.contains("api/chat"))
            val bodyText = (request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
            capturedOllamaBodies.add(bodyText)
            val contentJson = when {
                bodyText.contains("Semantic Filter & Topic Chunker") -> triageJson
                bodyText.contains("Rule & Substance Gatekeeper") ->
                    """{"is_group_notice":true,"passes_rules":true,"has_substance":true,"keep":true,"reason":"Valid seminar notice"}"""
                else -> mockJsonResponse
            }
            val responsePayload = """
            {
              "model": "qwen2.5:7b",
              "message": {
                "role": "assistant",
                "content": ${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), contentJson)}
              },
              "done": true
            }
            """.trimIndent()

            respond(
                content = responsePayload,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val config = LlmConfig(
            providerType = LlmProviderType.OLLAMA,
            endpointUrl = "http://localhost:11434",
            modelName = "qwen2.5:7b"
        )
        val client = KtorLlmClient(config, mockEngine)
        val extractor = EventExtractor(client)

        val messages = listOf(
            WeChatMessage(1L, 1794298700L, "wxid_prof", "Prof_Zhang", "周三下午在A101举行研讨会，请大家准时参加", "周三下午在A101举行研讨会，请大家准时参加", false),
            WeChatMessage(2L, 1794298760L, "wxid_ta", "TA_Li", "请提前十五分钟到场签到并携带笔记本电脑", "请提前十五分钟到场签到并携带笔记本电脑", false)
        )

        val events = extractor.extractEvents(messages, roomId = "lab@chatroom")
        assertEquals(3, capturedOllamaBodies.size)
        assertTrue(capturedOllamaBodies.first().contains("\"num_ctx\":8192"))
        assertTrue(capturedOllamaBodies.first().contains("\"num_predict\":4096"))
        assertEquals(1, events.size)
        val event = events.first()
        assertEquals("Lab Seminar Registration", event.title)
        assertEquals(EventCategory.ACTIVITY, event.category)
        assertEquals(EventPriority.NORMAL, event.priority)
        assertEquals("TA_Li", event.originalSender)
        assertEquals(1794298760L, event.timestampSeconds)

        val firstMsgIdx = event.sourceMessages.indexOf("Prof_Zhang: 周三下午在A101举行研讨会，请大家准时参加")
        val secondMsgIdx = event.sourceMessages.indexOf("TA_Li: 请提前十五分钟到场签到并携带笔记本电脑")
        assertTrue(firstMsgIdx >= 0, "Expected first matched message in sourceMessages")
        assertTrue(secondMsgIdx > firstMsgIdx, "Expected chronological order [1] before [2]")
        assertEquals(firstMsgIdx, event.sourceMessages.lastIndexOf("Prof_Zhang: "), "Expected duplicate ID 1 deduplicated")
        assertFalse(event.sourceMessages.contains("[id:"))

        client.close()
    }

    @Test
    fun testEventExtractorEdgeCases() = runBlocking {
        val mockEngine = MockEngine {
            respond(
                content = "{ \"is_relevant\": false, \"events\": [] }",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = KtorLlmClient(LlmConfig(), mockEngine)
        val extractor = EventExtractor(client)

        // 1. All noise -> returns empty without making network call
        val allNoise = listOf(
            WeChatMessage(1L, 100L, "u1", "User1", "[强]", "[强]", false),
            WeChatMessage(2L, 101L, "u2", "User2", "+1", "+1", false)
        )
        assertTrue(extractor.extractEvents(allNoise, "room@chatroom").isEmpty())

        // 2. Markdown wrapped JSON parsing
        val wrappedJson = "```json\n{\"is_relevant\": true, \"events\": [{\"title\": \"Exam\", \"summary\": \"Final exam\", \"category\": \"EXAM\", \"priority\": \"CRITICAL\"}]}\n```"
        val parsed = extractor.parseExtractionResponse(
            wrappedJson,
            listOf(WeChatMessage(1L, 100L, "u1", "Teacher", "Final exam next week", "Final exam next week", false)),
            "room@chatroom"
        )
        assertEquals(1, parsed.size)
        assertEquals("Exam", parsed.first().title)
        assertEquals(EventPriority.CRITICAL, parsed.first().priority)
        assertEquals(EventCategory.EXAM, parsed.first().category)

        // 3. Broken JSON handling -> returns empty list without exception
        val broken = extractor.parseExtractionResponse("Invalid json {[[", emptyList(), "room@chatroom")
        assertTrue(broken.isEmpty())

        client.close()
    }

    @Test
    fun testLanguageHelperAndDetailedSystemPrompt() {
        assertEquals("English", LanguageHelper.resolveTargetLanguageName("en"))
        assertEquals("English", LanguageHelper.resolveTargetLanguageName("en-US"))
        assertEquals("Simplified Chinese", LanguageHelper.resolveTargetLanguageName("zh"))
        assertEquals("Simplified Chinese", LanguageHelper.resolveTargetLanguageName("zh-CN"))
        assertEquals("Traditional Chinese", LanguageHelper.resolveTargetLanguageName("zh-TW"))
        assertEquals("Japanese", LanguageHelper.resolveTargetLanguageName("ja"))
        assertEquals("German", LanguageHelper.resolveTargetLanguageName("German"))
        assertEquals("English", LanguageHelper.resolveTargetLanguageName("   "))

        val client = KtorLlmClient(LlmConfig(), MockEngine { respond("", HttpStatusCode.OK) })
        val extractor = EventExtractor(client)
        val prompt = extractor.buildSystemPrompt(
            userPersona = "Computer Science student",
            targetLanguage = "en"
        )
        assertTrue(prompt.contains("English"))
        assertTrue(prompt.contains("2-5 sentences") || prompt.contains("multi-sentence"))
        assertFalse(prompt.contains("Concise summary in original message language"))
        client.close()
    }

    @Test
    fun testMessageTranslatorJsonFallbackAndErrors() = runBlocking {
        var mode = "json"
        val mockEngine = MockEngine { request ->
            val rawBody = (request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
            assertTrue(rawBody.contains("Japanese"))

            when (mode) {
                "json" -> {
                    val content = """{"translated_text": "[2026-11-10 15:00:00] Prof_Lin: Hello everyone."}"""
                    val payload = """
                        {"choices":[{"message":{"role":"assistant","content":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), content)}}}]}
                    """.trimIndent()
                    respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                }
                "fenced" -> {
                    val content = "```json\n{\"translated_text\": \"Fenced translation line.\"}\n```"
                    val payload = """
                        {"choices":[{"message":{"role":"assistant","content":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), content)}}}]}
                    """.trimIndent()
                    respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                }
                "plaintext" -> {
                    val content = "Plain text fallback translation"
                    val payload = """
                        {"choices":[{"message":{"role":"assistant","content":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), content)}}}]}
                    """.trimIndent()
                    respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                }
                else -> respond("Server error", HttpStatusCode.InternalServerError)
            }
        }

        val client = KtorLlmClient(LlmConfig(apiKey = "test-key"), mockEngine)
        val translator = MessageTranslator(client)

        assertTrue(translator.translateMessages("   ", "ja").isFailure)

        val jsonRes = translator.translateMessages("[2026-11-10 15:00:00] Prof_Lin: 大家好", "ja")
        assertTrue(jsonRes.isSuccess)
        assertEquals("[2026-11-10 15:00:00] Prof_Lin: Hello everyone.", jsonRes.getOrThrow())

        mode = "fenced"
        val fencedRes = translator.translateMessages("测试消息", "ja")
        assertEquals("Fenced translation line.", fencedRes.getOrThrow())

        mode = "plaintext"
        val plainRes = translator.translateMessages("测试消息", "ja")
        assertEquals("Plain text fallback translation", plainRes.getOrThrow())

        mode = "error"
        assertTrue(translator.translateMessages("测试消息", "ja").isFailure)

        client.close()
    }

    @Test
    fun testEventExtractorSectionsAndSameSourceConsolidationGuardrail() {
        val client = KtorLlmClient(LlmConfig(), MockEngine { respond("", HttpStatusCode.OK) })
        val extractor = EventExtractor(client)
        val msg1 = WeChatMessage(
            localId = 101L,
            timestampSeconds = 1794815137L,
            senderId = "wxid_advisor",
            senderDisplayName = "Si_Teacher",
            textContent = "Notice on excellent thesis nomination (deadline Nov 25/26) and delayed disclosure (deadline Nov 25/26).",
            rawContent = "Notice on excellent thesis nomination and delayed disclosure.",
            isZstdCompressed = false
        )
        val msg2 = WeChatMessage(
            localId = 102L,
            timestampSeconds = 1794816000L,
            senderId = "wxid_admin",
            senderDisplayName = "Admin_Office",
            textContent = "Campus shuttle schedule changes starting Monday.",
            rawContent = "Campus shuttle schedule changes starting Monday.",
            isZstdCompressed = false
        )

        // 1. Explicit in-card sections parsed cleanly
        val sectionedJson = """
        {
          "is_relevant": true,
          "events": [
            {
              "title": "Graduate Degree Application Submissions",
              "summary": "Overview covering both tracks.",
              "translated_summary": "Overview covering both tracks.",
              "sections": [
                {"heading": "(1) Excellent Thesis Nomination", "content": "Email Att 19 & 22 by Nov 25; hard copies Nov 26 at Room 422-1."},
                {"heading": "(2) Delayed Disclosure Application", "content": "Email Att 20-1 & 20-2 by Nov 25; signed copies Nov 26."},
                {"heading": "   ", "content": ""}
              ],
              "category": "DEADLINE",
              "priority": "IMPORTANT",
              "event_date": "2026-11-25",
              "source_message_ids": [101]
            }
          ]
        }
        """.trimIndent()
        val sectionedCards = extractor.parseExtractionResponse(sectionedJson, listOf(msg1), "room@chatroom", "CSE Group")
        assertEquals(1, sectionedCards.size)
        assertEquals(2, sectionedCards.single().sections.size)
        assertEquals("(1) Excellent Thesis Nomination", sectionedCards.single().sections[0].heading)

        // 2. Static guardrail collapses 3 split items referencing same [id:101] into 1 card with sections
        val splitJson = """
        {
          "is_relevant": true,
          "events": [
            {
              "title": "Exam notice for the current semester",
              "summary": "Guidance for degree applications.",
              "translated_summary": "Guidance for degree applications and required documents.",
              "category": "OTHER",
              "priority": "NORMAL",
              "event_date": "2026-11-26",
              "source_message_ids": [101]
            },
            {
              "title": "Deadline for submitting paper drafts",
              "summary": "Submit electronic files by Nov 25.",
              "translated_summary": "Submit electronic files by Nov 25.",
              "category": "DEADLINE",
              "priority": "CRITICAL",
              "event_date": "2026-11-25",
              "source_message_ids": [101]
            },
            {
              "title": "Deadline for submitting final paper copies",
              "summary": "Submit hard copies on Nov 26.",
              "translated_summary": "Submit hard copies on Nov 26.",
              "category": "DEADLINE",
              "priority": "IMPORTANT",
              "event_date": "2026-11-26",
              "source_message_ids": [101]
            },
            {
              "title": "Campus Shuttle Update",
              "summary": "Bus times changed.",
              "translated_summary": "Bus times changed starting Monday.",
              "category": "NOTICE",
              "priority": "LOW",
              "source_message_ids": [102]
            }
          ]
        }
        """.trimIndent()
        val collapsedCards = extractor.parseExtractionResponse(splitJson, listOf(msg1, msg2), "room@chatroom", "CSE Group")
        assertEquals(2, collapsedCards.size, "Expected [101] splits collapsed to 1 card while disjoint [102] stays separate")
        val mergedCard = collapsedCards[0]
        assertEquals(EventPriority.CRITICAL, mergedCard.priority)
        assertEquals(EventCategory.DEADLINE, mergedCard.category)
        assertEquals("2026-11-25", mergedCard.eventDate)
        assertEquals(3, mergedCard.sections.size)
        assertEquals("Deadline for submitting paper drafts", mergedCard.sections[1].heading)
        assertTrue(mergedCard.sourceMessages.contains("Si_Teacher"))
        assertEquals("Campus Shuttle Update", collapsedCards[1].title)

        // 3. Transitive overlap [101, 102] and [102] merges cleanly into 1 card
        val overlappingJson = """
        {
          "is_relevant": true,
          "events": [
            {"title": "Part A", "translated_summary": "Detail A", "source_message_ids": [101, 102]},
            {"title": "Part B", "translated_summary": "Detail B", "source_message_ids": [102]}
          ]
        }
        """.trimIndent()
        val overlapCards = extractor.parseExtractionResponse(overlappingJson, listOf(msg1, msg2), "room@chatroom")
        assertEquals(1, overlapCards.size)
        assertEquals(2, overlapCards.single().sections.size)

        client.close()
    }

    @Test
    fun testEventExtractorRoomMemoryPromptContext() {
        val client = KtorLlmClient(LlmConfig(), MockEngine { respond("", HttpStatusCode.OK) })
        val extractor = EventExtractor(client)

        val promptWithoutHistory = com.wechatdigest.core.llm.ExtractionPrompts.buildTriageSystemPrompt(
            userPersona = "",
            targetLanguage = "en",
            recentRoomCards = emptyList()
        )
        assertFalse(promptWithoutHistory.contains("EXISTING RECENT CARDS IN THIS ROOM"))
        val synthesisPrompt = extractor.buildSystemPrompt(userPersona = "", targetLanguage = "en")
        assertTrue(synthesisPrompt.contains("\"sections\""))
        assertTrue(synthesisPrompt.contains("ONE consolidated event card"))

        val recentCard = EventCard(
            id = "recent-1",
            title = "Thesis Nomination Notice",
            summary = "Electronic deadline Nov 25",
            translatedSummary = "Electronic deadline Nov 25",
            category = EventCategory.DEADLINE,
            priority = EventPriority.IMPORTANT,
            eventDate = "2026-11-25",
            originalSender = "Si_Teacher",
            sourceRoomId = "room@chatroom",
            timestampSeconds = 1794815137L
        )
        // Triage system prompt must NOT embed untrusted recent card summaries; they belong in user prompt data tags
        val promptWithHistory = com.wechatdigest.core.llm.ExtractionPrompts.buildTriageSystemPrompt(
            userPersona = "",
            targetLanguage = "en",
            recentRoomCards = listOf(recentCard)
        )
        assertFalse(promptWithHistory.contains("Thesis Nomination Notice"))

        val userPromptWithHistory = com.wechatdigest.core.llm.ExtractionPrompts.buildTriageUserPrompt(
            transcript = "[id:1] [2026-11-21 10:00:00] Teacher: Notice",
            recentRoomCards = listOf(recentCard)
        )
        assertTrue(userPromptWithHistory.contains("EXISTING RECENT CARDS IN THIS ROOM"))
        assertTrue(userPromptWithHistory.contains("<existing_cards>"))
        assertTrue(userPromptWithHistory.contains("Thesis Nomination Notice"))
        assertTrue(userPromptWithHistory.contains("2026-11-25"))
        assertTrue(userPromptWithHistory.contains("<untrusted_chat_data>"))

        client.close()
    }

    @Test
    fun testExtractEventsResultPropagatesFailuresAndDefaultTimeoutIsGenerous() = runBlocking {
        assertEquals(180L, LlmConfig().timeoutSeconds)

        val failingClient = KtorLlmClient(
            LlmConfig(apiKey = "test-key"),
            MockEngine { respond("Gateway timeout", HttpStatusCode.GatewayTimeout) }
        )
        val failingExtractor = EventExtractor(failingClient)
        val msg = WeChatMessage(1L, 1794800000L, "u1", "Prof", "Important final exam announcement next Monday", "Important final exam announcement next Monday", false)
        assertTrue(failingExtractor.extractEventsResult(listOf(msg), "room@chatroom").isFailure)
        assertTrue(failingExtractor.extractEvents(listOf(msg), "room@chatroom").isEmpty())
        failingClient.close()

        val badJsonClient = KtorLlmClient(
            LlmConfig(apiKey = "test-key"),
            MockEngine {
                val payload = """{"choices":[{"message":{"role":"assistant","content":"not-json"}}]}"""
                respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
        )
        assertTrue(EventExtractor(badJsonClient).extractEventsResult(listOf(msg), "room@chatroom").isFailure)
        badJsonClient.close()
    }
}
