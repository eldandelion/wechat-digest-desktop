package com.wechatdigest.eval

import com.wechatdigest.core.db.WeChatDatabaseDriver
import com.wechatdigest.core.llm.EventExtractor
import com.wechatdigest.core.llm.KtorLlmClient
import com.wechatdigest.core.llm.LlmConfig
import com.wechatdigest.core.llm.LlmProviderType
import com.wechatdigest.core.llm.OllamaDiscoveryResult
import com.wechatdigest.core.llm.OllamaService
import com.wechatdigest.core.repository.WeChatContactRepository
import com.wechatdigest.core.repository.WeChatMessageRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.ZoneOffset
import kotlin.system.exitProcess

/**
 * CLI entrypoint for Gradle tasks `liveEval`, `recordCassettes`, and `replaySnapshot`.
 * Configured strictly via `-Peval.*` / `eval.*` JVM properties and environment variables (`EVAL_API_KEY`).
 */
object PipelineTestbedCli {

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val mode = System.getProperty("eval.mode", "live").trim().lowercase()
        when (mode) {
            "live" -> runLiveOrRecord(record = false)
            "record" -> runLiveOrRecord(record = true)
            "snapshot" -> runSnapshot()
            else -> {
                System.err.println("Unknown eval.mode='$mode'. Use live, record, or snapshot.")
                exitProcess(2)
            }
        }
    }

    private suspend fun runLiveOrRecord(record: Boolean) {
        val scenariosDir = File(System.getProperty("eval.scenariosDir", "src/eval/resources/scenarios"))
        val cassettesDir = File(System.getProperty("eval.cassettesDir", "src/eval/resources/cassettes"))
        val baselineFile = File(System.getProperty("eval.baseline", "src/eval/resources/baseline.json"))
        val reportDir = File("build/eval-reports")

        val scenarioFilter = System.getProperty("eval.scenario")?.takeIf { it.isNotBlank() }
        val trackFilter = System.getProperty("eval.track")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { EvalTrack.valueOf(it.uppercase()) }.getOrNull() }
        val targetLangOverride = System.getProperty("eval.targetLang")?.takeIf { it.isNotBlank() }
        val runs = System.getProperty("eval.runs")?.toIntOrNull()?.coerceIn(1, 10) ?: if (record) 1 else 1

        val llmConfig = resolveLlmConfig()
        val scenarios = EvalRunner.loadScenarios(scenariosDir, scenarioFilter, trackFilter)
        if (scenarios.isEmpty()) {
            System.err.println("No scenarios matched under ${scenariosDir.absolutePath} (filter='$scenarioFilter').")
            exitProcess(1)
        }

        val recorders = mutableListOf<Pair<File, RecordingLlmClient>>()
        val report = EvalRunner.runSuite(
            scenarios = scenarios,
            mode = if (record) "record" else "live",
            provider = llmConfig.providerType.name,
            model = llmConfig.modelName,
            trialsPerScenario = runs,
            baseline = EvalRunner.loadBaseline(baselineFile),
            targetLanguageOverride = targetLangOverride
        ) { scenario ->
            val liveClient = KtorLlmClient(llmConfig)
            if (record) {
                val recorder = RecordingLlmClient(liveClient, scenario.id)
                recorders.add(File(cassettesDir, "${scenario.id}.cassette.json") to recorder)
                recorder
            } else {
                liveClient
            }
        }

        if (record) {
            recorders.forEach { (file, recorder) -> recorder.saveToFile(file) }
            println("Saved ${recorders.size} cassette file(s) to ${cassettesDir.path}")
        }

        EvalRunner.writeReports(report, reportDir)
        printSuiteSummary(report)

        val regressionFailures = report.summaries.filter { it.track == EvalTrack.REGRESSION && it.passAllK < 1.0 }
        if (regressionFailures.isNotEmpty()) {
            System.err.println("FAILED ${regressionFailures.size} regression scenario(s): ${regressionFailures.map { it.scenarioId }}")
            exitProcess(1)
        }
    }

    private suspend fun runSnapshot() {
        val wechatDirStr = System.getProperty("eval.wechatDir")
            ?: error("Missing -Peval.wechatDir=/path/to/decrypted_wechat_dir")
        val roomId = System.getProperty("eval.room")
            ?: error("Missing -Peval.room=12345@chatroom")
        val limit = System.getProperty("eval.limit")?.toIntOrNull()?.coerceIn(1, 200) ?: 30
        val targetLang = System.getProperty("eval.targetLang", "en")
        val customRules = System.getProperty("eval.rules", "")
        val allowSenders = System.getProperty("eval.allowSenders", "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val dataDir = File(wechatDirStr).canonicalFile
        require(dataDir.exists() && dataDir.isDirectory) { "Invalid wechatDir: $wechatDirStr" }

        val driver = WeChatDatabaseDriver(baseDir = dataDir)
        val contactRepo = WeChatContactRepository(driver)
        val messageRepo = WeChatMessageRepository(driver, contactRepo)
        val rawMessages = messageRepo.getRecentMessages(roomId, limit = limit)
        println("[Snapshot] Loaded ${rawMessages.size} message(s) from room '$roomId' (read-only)")

        val filtered = if (allowSenders.isNotEmpty()) {
            rawMessages.filter { it.senderId in allowSenders }
        } else {
            rawMessages
        }
        val observer = CollectingExtractionObserver(rawMessages.size, filtered.size)
        val llmConfig = resolveLlmConfig()
        var callRecords: List<LlmCallRecord> = emptyList()

        val result = KtorLlmClient(llmConfig).use { liveClient ->
            val tracer = TracingLlmClient(liveClient)
            val extractor = EventExtractor(
                llmClient = tracer,
                zoneId = ZoneOffset.UTC,
                observer = observer
            )
            val res = extractor.extractEventsResult(
                messages = filtered,
                roomId = roomId,
                targetLanguage = targetLang,
                customRules = customRules
            )
            callRecords = tracer.snapshotCalls()
            res
        }

        val trace = observer.buildTrace(callRecords, callRecords.sumOf { it.latencyMs })
        printStageTrace("snapshot:$roomId", trace)
        result.onSuccess { cards ->
            println("\n[Snapshot] Produced ${cards.size} EventCard(s):")
            cards.forEachIndexed { i, c ->
                println("  [$i] ${c.priority} ${c.category} | ${c.title} | sections=${c.sections.size}")
                println("      summary: ${c.translatedSummary}")
            }
        }.onFailure { err ->
            System.err.println("\n[Snapshot] Extraction failed: ${err.message}")
        }

        val exportOut = System.getProperty("eval.exportOut")?.takeIf { it.isNotBlank() }
        if (exportOut != null) {
            val senderMap = rawMessages.map { it.senderId }.distinct().withIndex()
                .associate { it.value to "wxid_anon_${it.index + 1}" }
            val scenario = EvalScenario(
                id = "CAP-snapshot-${roomId.substringBefore('@')}",
                description = "Anonymized snapshot captured from room replay",
                track = EvalTrack.CAPABILITY,
                roomId = "room_snapshot@chatroom",
                roomDisplayName = "Snapshot Group",
                targetLanguage = targetLang,
                customRules = customRules,
                allowedSenderIds = allowSenders.map { senderMap[it] ?: "wxid_anon" },
                messages = rawMessages.map { m ->
                    EvalMessageInput(
                        localId = m.localId,
                        timestampSeconds = m.timestampSeconds,
                        senderId = senderMap[m.senderId] ?: "wxid_anon",
                        senderDisplayName = "Member_${(senderMap[m.senderId] ?: "x").substringAfterLast('_')}",
                        content = m.textContent,
                        rawContent = m.rawContent,
                        isZstdCompressed = m.isZstdCompressed
                    )
                },
                expectations = ScenarioExpectations(minCards = 0, maxCards = 10)
            )
            val outFile = File(exportOut)
            outFile.parentFile?.mkdirs()
            outFile.writeText(EvalRunner.evalJson.encodeToString(scenario))
            println("\n[Snapshot] Exported anonymized scenario template to ${outFile.path}")
        }

        if (result.isFailure) exitProcess(1)
    }

    private suspend fun resolveLlmConfig(): LlmConfig {
        val providerRaw = System.getProperty("eval.provider", "OLLAMA").trim().uppercase()
        val providerType = if (providerRaw == "OPENAI" || providerRaw == "OPENAI_COMPATIBLE") {
            LlmProviderType.OPENAI_COMPATIBLE
        } else {
            LlmProviderType.OLLAMA
        }
        val defaultEndpoint = if (providerType == LlmProviderType.OLLAMA) {
            "http://localhost:11434"
        } else {
            "https://api.openai.com/v1"
        }
        val endpoint = System.getProperty("eval.endpoint")?.takeIf { it.isNotBlank() } ?: defaultEndpoint
        val apiKey = System.getenv("EVAL_API_KEY") ?: System.getenv("OPENAI_API_KEY").orEmpty()
        var model = System.getProperty("eval.model")?.takeIf { it.isNotBlank() }.orEmpty()

        if (model.isBlank()) {
            model = if (providerType == LlmProviderType.OLLAMA) {
                OllamaService().use { ollama ->
                    when (val discovered = ollama.detectLocalOllama(endpoint)) {
                        is OllamaDiscoveryResult.Success -> discovered.models.first().name
                        else -> "qwen2.5:7b"
                    }
                }
            } else {
                "gpt-4o-mini"
            }
        }
        return LlmConfig(
            providerType = providerType,
            endpointUrl = endpoint,
            apiKey = apiKey,
            modelName = model
        )
    }

    private fun printSuiteSummary(report: EvalSuiteReport) {
        println("=== Pipeline Eval Report (${report.mode} | ${report.provider}:${report.model}) ===")
        println("Passed: ${report.passedScenarios}/${report.totalScenarios} | Regression pass^k=${"%.1f".format(report.regressionPassRate * 100)}%")
        for (s in report.summaries) {
            val status = if (s.passAllK == 1.0) "PASS" else "FAIL(${s.passedTrials}/${s.trials})"
            println(" - [$status] ${s.scenarioId} (${s.track}): calls=${"%.1f".format(s.avgLlmCalls)}, repairs=${"%.1f".format(s.avgJsonRepairs)}, langRepairs=${"%.1f".format(s.avgTranslationRepairs)}, ${s.avgDurationMs}ms")
            s.trialResults.firstOrNull { !it.passed }?.let { failedTrial ->
                printStageTrace(s.scenarioId, failedTrial.trace)
                failedTrial.findings.filterNot { it.passed }.forEach { f ->
                    println("     FAILED GRADER [${f.grader}]: ${f.message}")
                }
            }
        }
    }

    private fun printStageTrace(label: String, trace: StageTrace) {
        println("   Trace [$label]: raw=${trace.rawMessageCount} -> allowlist=${trace.afterAllowlistCount} -> prefilter=${trace.afterPrefilterCount} -> pass1Chunks=${trace.pass1ConsolidatedChunkCount}/${trace.pass1RawChunkCount}")
        trace.pass2Verdicts.forEachIndexed { idx, v ->
            println("     Pass2[$idx] topic='${v.chunkTopic}' keep=${v.keep} (notice=${v.isGroupNotice}, rules=${v.passesRules}, sub=${v.hasSubstance}) reason='${v.reason}'")
        }
        trace.pass3Chunks.forEachIndexed { idx, p ->
            println("     Pass3[$idx] topic='${p.chunkTopic}' attempts=${p.attempts} repaired=${p.jsonRepaired} produced=${p.cardProduced} (${p.latencyMs}ms)")
        }
        trace.languageGates.forEachIndexed { idx, g ->
            println("     LangGate[$idx] title='${g.cardTitle}' initialCompliant=${g.initiallyCompliant} (bad=${g.initialNonCompliantFields}) repairAttempts=${g.repairAttempts} finalCompliant=${g.finalCompliant}")
        }
    }
}
