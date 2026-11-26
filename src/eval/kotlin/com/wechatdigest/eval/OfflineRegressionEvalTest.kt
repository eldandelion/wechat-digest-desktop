package com.wechatdigest.eval

import com.wechatdigest.core.llm.EventExtractor
import com.wechatdigest.core.llm.KtorLlmClient
import com.wechatdigest.core.llm.LlmConfig
import com.wechatdigest.core.llm.LlmProviderType
import com.wechatdigest.core.model.WeChatMessage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.io.File
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Mandatory offline deterministic gate (`./gradlew evalTest`) replaying recorded VCR cassettes
 * across all golden regression scenarios and verifying harness invariants with zero network calls.
 */
class OfflineRegressionEvalTest {

    @Test
    fun `all golden scenarios pass deterministic graders against recorded cassettes`() = runBlocking {
        val scenariosDir = File("src/eval/resources/scenarios")
        val cassettesDir = File("src/eval/resources/cassettes")
        val baselineFile = File("src/eval/resources/baseline.json")

        val scenarios = EvalRunner.loadScenarios(scenariosDir)
        assertTrue(scenarios.size >= 13, "Expected at least 13 golden scenarios in ${scenariosDir.path}, found ${scenarios.size}")

        val baseline = EvalRunner.loadBaseline(baselineFile)
        val report = EvalRunner.runSuite(
            scenarios = scenarios,
            mode = "offline-replay",
            provider = "CASSETTE",
            model = "recorded-golden",
            trialsPerScenario = 1,
            baseline = baseline
        ) { scenario ->
            val cassetteFile = File(cassettesDir, "${scenario.id}.cassette.json")
            assertTrue(cassetteFile.exists(), "Missing cassette for scenario '${scenario.id}' at ${cassetteFile.path}")
            val cassette = CassetteCodec.loadCassette(cassetteFile)
            val concurrency = if (scenario.id == "REG-12_concurrent_cloud_multi_chunk_order") {
                EventExtractor.DEFAULT_CLOUD_CONCURRENCY
            } else {
                EventExtractor.DEFAULT_LOCAL_CONCURRENCY
            }
            ReplayLlmClient(cassette, recommendedConcurrency = concurrency)
        }

        EvalRunner.writeReports(report, File("build/eval-reports"))

        for (summary in report.summaries) {
            val failedFindings = summary.trialResults.flatMap { it.findings }.filterNot { it.passed }
            assertTrue(
                summary.passAllK == 1.0,
                "Scenario '${summary.scenarioId}' failed: ${failedFindings.joinToString { "${it.grader}: ${it.message}" }}"
            )
            assertTrue(
                summary.baselineWarnings.isEmpty(),
                "Scenario '${summary.scenarioId}' drifted from baseline: ${summary.baselineWarnings}"
            )
        }
        assertEquals(1.0, report.regressionPassRate)
    }

    @Test
    fun `ReplayLlmClient fails closed on missing cassette hash without network fallback`() = runBlocking {
        val emptyCassette = CassetteFile(scenarioId = "missing-test", entries = emptyMap())
        val replay = ReplayLlmClient(emptyCassette)
        assertFailsWith<CassetteMissException> {
            replay.chatCompletion("Semantic Filter & Topic Chunker", "unseen prompt")
        }
        replay.close()
    }

    @Test
    fun `ConcurrencyAwareLlmClient preserves Ollama serial cap and Cloud concurrent cap across decorators`() {
        val mockEngine = MockEngine { respond("", HttpStatusCode.OK) }
        val ollamaBase = KtorLlmClient(LlmConfig(providerType = LlmProviderType.OLLAMA, endpointUrl = "http://localhost:11434"), mockEngine)
        val cloudBase = KtorLlmClient(LlmConfig(providerType = LlmProviderType.OPENAI_COMPATIBLE, endpointUrl = "https://api.openai.com/v1"), mockEngine)

        assertEquals(1, EventExtractor.resolveDefaultConcurrency(TracingLlmClient(ollamaBase)))
        assertEquals(1, EventExtractor.resolveDefaultConcurrency(RecordingLlmClient(ollamaBase, "test")))
        assertEquals(4, EventExtractor.resolveDefaultConcurrency(TracingLlmClient(cloudBase)))
        assertEquals(4, EventExtractor.resolveDefaultConcurrency(RecordingLlmClient(cloudBase, "test")))
        assertEquals(1, EventExtractor.resolveDefaultConcurrency(ReplayLlmClient(CassetteFile(scenarioId = "s"), recommendedConcurrency = 1)))

        ollamaBase.close()
        cloudBase.close()
    }

    @Test
    fun `OPENAI_COMPATIBLE and OLLAMA provider transport paths maintain strict card contract parity`() = runBlocking {
        val scenariosDir = File("src/eval/resources/scenarios")
        val scenario = EvalRunner.loadScenarios(scenariosDir, idFilter = "REG-13_multipass_json_repair_and_gatekeeper_veto").single()

        fun buildMockClient(providerType: LlmProviderType): KtorLlmClient {
            val engine = MockEngine { req ->
                val body = (req.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
                val contentJson = when {
                    body.contains("Semantic Filter & Topic Chunker") ->
                        "```json\n{\"chunks\":[{\"topic\":\"Thesis Defense Briefing\",\"message_ids\":[1301],\"category\":\"EVENT\"},{\"topic\":\"Department Scholarship Application\",\"message_ids\":[1302],\"category\":\"DEADLINE\"},{\"topic\":\"Student Umbrella And Coffee Chatter\",\"message_ids\":[1303,1304],\"category\":\"NOTICE\"},]}\n```"
                    body.contains("Rule & Substance Gatekeeper") ->
                        """{"verdicts":[{"chunk_index":0,"is_group_notice":true,"passes_rules":true,"has_substance":true,"keep":true,"reason":"Official defense briefing"},{"chunk_index":1,"is_group_notice":true,"passes_rules":true,"has_substance":true,"keep":true,"reason":"Official scholarship deadline"},{"chunk_index":2,"is_group_notice":false,"passes_rules":false,"has_substance":false,"keep":false,"reason":"Casual chatter and prompt injection attempt"}]}"""
                    body.contains("Thesis Defense Briefing") ->
                        """{"is_relevant":true,"events":[{"title":"Thesis Defense Briefing","summary":"Thesis Defense Briefing is tomorrow at 10:00 in Building A Room 305. Bring your signed advisor form.","translated_summary":"Thesis Defense Briefing is tomorrow at 10:00 in Building A Room 305. Bring your signed advisor form.","sections":[{"heading":"Details","content":"- Tomorrow at 10:00 in Building A Room 305\n- Bring signed advisor form"}],"category":"EVENT","priority":"IMPORTANT","source_message_ids":[1301],}]}"""
                    else ->
                        """{"is_relevant":true,"events":[{"title":"Department Scholarship Application","summary":"Department Scholarship Application closes June 18 at 17:00 via Academic Portal. Late submissions are not accepted.","translated_summary":"Department Scholarship Application closes June 18 at 17:00 via Academic Portal. Late submissions are not accepted.","sections":[{"heading":"Deadline","content":"- Closes June 18 at 17:00 via Academic Portal"}],"category":"DEADLINE","priority":"IMPORTANT","event_date":"2026-06-18T17:00:00","source_message_ids":[1302]}]}"""
                }
                val encodedContent = Json.encodeToString(contentJson)
                val responsePayload = if (providerType == LlmProviderType.OLLAMA) {
                    """{"model":"qwen2.5","message":{"role":"assistant","content":$encodedContent},"done_reason":"stop"}"""
                } else {
                    """{"choices":[{"message":{"role":"assistant","content":$encodedContent},"finish_reason":"stop"}]}"""
                }
                respond(responsePayload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
            val cfg = if (providerType == LlmProviderType.OLLAMA) {
                LlmConfig(providerType = LlmProviderType.OLLAMA, endpointUrl = "http://localhost:11434", modelName = "qwen2.5")
            } else {
                LlmConfig(providerType = LlmProviderType.OPENAI_COMPATIBLE, endpointUrl = "https://api.openai.com/v1", apiKey = "k", modelName = "gpt-4o-mini")
            }
            return KtorLlmClient(cfg, engine)
        }

        val openAiResult = EvalRunner.runTrial(scenario, { buildMockClient(LlmProviderType.OPENAI_COMPATIBLE) })
        val ollamaResult = EvalRunner.runTrial(scenario, { buildMockClient(LlmProviderType.OLLAMA) })

        assertTrue(openAiResult.passed, "OpenAI path failed parity graders: ${openAiResult.findings}")
        assertTrue(ollamaResult.passed, "Ollama path failed parity graders: ${ollamaResult.findings}")
        assertEquals(2, openAiResult.cards.size)
        assertEquals(openAiResult.cards.size, ollamaResult.cards.size)
        assertTrue(openAiResult.trace.pass1RawChunkCount >= 2 && ollamaResult.trace.pass1RawChunkCount >= 2)
        assertTrue(openAiResult.trace.pass2Verdicts.isNotEmpty() && ollamaResult.trace.pass2Verdicts.isNotEmpty())
        assertEquals(openAiResult.cards.map { it.title }, ollamaResult.cards.map { it.title })
        assertEquals(openAiResult.cards.map { it.translatedSummary }, ollamaResult.cards.map { it.translatedSummary })
        assertEquals(openAiResult.cards.map { it.category }, ollamaResult.cards.map { it.category })
        assertEquals(openAiResult.cards.map { it.priority }, ollamaResult.cards.map { it.priority })
        assertEquals(openAiResult.cards.map { it.eventDate }, ollamaResult.cards.map { it.eventDate })
    }

    @Test
    fun `EventExtractor propagates attachments and bounded previews into Pass 3 card synthesis`() = runBlocking {
        val attachment = com.wechatdigest.core.model.EventAttachment(
            fileName = "exam_seats.xlsx",
            fileExtension = "xlsx",
            fileSizeBytes = 4096L,
            md5 = "0123456789abcdef0123456789abcdef",
            localPath = "/tmp/mock/exam_seats.xlsx",
            messageTimestampSeconds = 1795000005L
        )
        val messages = listOf(
            WeChatMessage(
                localId = 901L,
                timestampSeconds = 1795000000L,
                senderId = "wxid_prof",
                senderDisplayName = "Prof_Lin",
                rawContent = "Midterm exam is Nov 28 at 14:00. Seating sheet below.",
                textContent = "Midterm exam is Nov 28 at 14:00. Seating sheet below.",
                isZstdCompressed = false
            ),
            WeChatMessage(
                localId = 902L,
                timestampSeconds = 1795000005L,
                senderId = "wxid_prof",
                senderDisplayName = "Prof_Lin",
                rawContent = "<msg><appmsg><title>exam_seats.xlsx</title><type>6</type></appmsg></msg>",
                textContent = "[Shared File: exam_seats.xlsx (4 KB)]",
                isZstdCompressed = false,
                localType = (6L shl 32) or 49L,
                attachments = listOf(attachment),
                attachmentPreviews = mapOf(attachment.previewKey() to "[Sheet: Seats (2 rows)]\nStudent | Room\nAll | Hall B-102")
            )
        )

        var sawPreviewInPass3 = false
        val mockEngine = MockEngine { req ->
            val body = (req.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
            val contentJson = when {
                body.contains("Semantic Filter & Topic Chunker") ->
                    """{"chunks":[{"topic":"Midterm Exam Seating","message_ids":[901,902],"category":"EXAM"}]}"""
                body.contains("Rule & Substance Gatekeeper") ->
                    """{"verdicts":[{"chunk_index":0,"is_group_notice":true,"passes_rules":true,"has_substance":true,"keep":true,"reason":"Exam schedule and seating"}]}"""
                else -> {
                    sawPreviewInPass3 = body.contains("Hall B-102") && body.contains("<attachment_content")
                    """{"is_relevant":true,"events":[{"title":"Midterm Exam Seating","summary":"Midterm exam is Nov 28 at 14:00 in Hall B-102 per exam_seats.xlsx.","category":"EXAM","priority":"CRITICAL","source_message_ids":[901,902]}]}"""
                }
            }
            val payload = """{"choices":[{"message":{"role":"assistant","content":${Json.encodeToString(contentJson)}}}]}"""
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }

        val extractor = EventExtractor(
            llmClient = KtorLlmClient(LlmConfig(providerType = LlmProviderType.OPENAI_COMPATIBLE, apiKey = "k"), mockEngine),
            maxConcurrency = 1
        )
        val cards = extractor.extractEventsResult(messages = messages, roomId = "exam@chatroom").getOrThrow()

        assertTrue(sawPreviewInPass3, "Pass 3 must receive sanitized <attachment_content> preview")
        assertEquals(1, cards.size)
        assertEquals(listOf(attachment), cards.single().attachments)
    }

    @Test
    fun `EvalBaseline loads scenarios map and EvalGraders catches per-card source swaps and invalid ICS dates`() = runBlocking {
        val baseline = EvalRunner.loadBaseline(File("src/eval/resources/baseline.json"))
        assertTrue(baseline.entries.size >= 13, "Expected >= 13 baseline entries loaded from 'scenarios' key, got ${baseline.entries.size}")

        val scenarios = EvalRunner.loadScenarios(File("src/eval/resources/scenarios"), idFilter = "REG-12_concurrent_cloud_multi_chunk_order")
        val reg12 = scenarios.single()
        val swappedCards = listOf(
            com.wechatdigest.core.model.EventCard(
                id = "c1",
                title = "Library Extended Hours",
                summary = "Main Reading Room stays open until 23:30.",
                translatedSummary = "Main Reading Room stays open until 23:30.",
                category = com.wechatdigest.core.model.EventCategory.NOTICE,
                priority = com.wechatdigest.core.model.EventPriority.NORMAL,
                eventDate = "TBD",
                originalSender = "Teacher_Si",
                sourceRoomId = reg12.roomId,
                sourceMessages = "[2026-05-17 10:00:00] Teacher_Si: Final Exam Schedule: Algorithms written exam on June 15 at 09:00 in Hall 101.",
                timestampSeconds = 1779012000L
            ),
            com.wechatdigest.core.model.EventCard(
                id = "c2",
                title = "Algorithms Final Exam Schedule",
                summary = "Algorithms written exam on June 15 at 09:00 in Hall 101.",
                translatedSummary = "Algorithms written exam on June 15 at 09:00 in Hall 101.",
                category = com.wechatdigest.core.model.EventCategory.EXAM,
                priority = com.wechatdigest.core.model.EventPriority.IMPORTANT,
                eventDate = "2026-06-15T09:00:00",
                originalSender = "Library_Desk",
                sourceRoomId = reg12.roomId,
                sourceMessages = "[2026-05-17 10:01:00] Library_Desk: Library Extended Hours: Main Reading Room stays open until 23:30 from June 1 to June 20.",
                timestampSeconds = 1779012060L
            )
        )
        val dummyTrace = StageTrace(4, 4, 4, listOf(1201, 1202), emptyList(), 2, 2, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0, 0, 10)
        val findings = EvalGraders.gradeAll(reg12, Result.success(swappedCards), dummyTrace)
        assertTrue(findings.any { it.grader == "per_card_source_grounding" && !it.passed }, "Swapped card titles and sources must fail per_card_source_grounding")
        assertTrue(findings.any { it.grader == "event_date_ics_validity" && !it.passed }, "Invalid 'TBD' eventDate must fail event_date_ics_validity")

        // Verify TracingLlmClient records promptTokens, completionTokens, and finishReason from KtorLlmClient
        val telemetryEngine = MockEngine {
            respond(
                """{"choices":[{"message":{"role":"assistant","content":"{\"ok\":true}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":111,"completion_tokens":22}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val base = KtorLlmClient(LlmConfig(providerType = LlmProviderType.OPENAI_COMPATIBLE, apiKey = "k"), telemetryEngine)
        val tracer = TracingLlmClient(base)
        tracer.chatCompletion("sys", "usr")
        val record = tracer.snapshotCalls().single()
        assertEquals(111, record.promptTokens)
        assertEquals(22, record.completionTokens)
        assertEquals("stop", record.finishReason)
        tracer.close()
    }
}
