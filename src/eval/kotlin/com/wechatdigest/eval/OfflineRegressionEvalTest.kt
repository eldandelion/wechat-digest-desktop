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
        assertTrue(scenarios.size >= 12, "Expected at least 12 golden scenarios in ${scenariosDir.path}, found ${scenarios.size}")

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
        val scenario = EvalRunner.loadScenarios(scenariosDir, idFilter = "REG-03_pass2_student_ping_veto").single()

        fun buildMockClient(providerType: LlmProviderType): KtorLlmClient {
            val engine = MockEngine { req ->
                val body = (req.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
                val contentJson = when {
                    body.contains("Semantic Filter & Topic Chunker") ->
                        """{"chunks":[{"topic":"Hard Copy Submission","message_ids":[301],"category":"DEADLINE"},{"topic":"Student Email","message_ids":[302],"category":"NOTICE"}]}"""
                    body.contains("Rule & Substance Gatekeeper") ->
                        """{"verdicts":[{"chunk_index":0,"is_group_notice":true,"passes_rules":true,"has_substance":true,"keep":true,"reason":"Official submission deadline"},{"chunk_index":1,"is_group_notice":false,"passes_rules":false,"has_substance":false,"keep":false,"reason":"Personal student email request"}]}"""
                    else ->
                        """{"is_relevant":true,"events":[{"title":"Thesis Hard Copy Submission Deadline","summary":"All thesis applicants must submit printed copies on Nov 26 at 10:00 in Room 422-1.","category":"DEADLINE","priority":"IMPORTANT","source_message_ids":[301]}]}"""
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
        assertEquals(openAiResult.cards.size, ollamaResult.cards.size)
        assertEquals(openAiResult.cards.single().title, ollamaResult.cards.single().title)
        assertEquals(openAiResult.cards.single().translatedSummary, ollamaResult.cards.single().translatedSummary)
        assertEquals(openAiResult.cards.single().category, ollamaResult.cards.single().category)
        assertEquals(openAiResult.cards.single().priority, ollamaResult.cards.single().priority)
    }
}
