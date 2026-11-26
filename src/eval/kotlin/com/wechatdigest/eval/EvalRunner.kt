package com.wechatdigest.eval

import com.wechatdigest.core.llm.EventExtractor
import com.wechatdigest.core.llm.ExtractionObserver
import com.wechatdigest.core.llm.GatekeeperVerdictTrace
import com.wechatdigest.core.llm.LanguageComplianceResult
import com.wechatdigest.core.llm.LlmClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Thread-safe [ExtractionObserver] collecting full stage traces during an eval or snapshot run. */
class CollectingExtractionObserver(
    private val rawCount: Int,
    private val afterAllowlistCount: Int
) : ExtractionObserver {

    private val pass1Windows = Collections.synchronizedList(mutableListOf<Pass1TraceRecord>())
    private val pass2Verdicts = Collections.synchronizedList(mutableListOf<GatekeeperTraceRecord>())
    private val pass3Chunks = Collections.synchronizedList(mutableListOf<Pass3TraceRecord>())
    private val languageGates = Collections.synchronizedList(mutableListOf<LanguageGateTraceRecord>())

    private val afterPrefilterCount = AtomicInteger(0)
    @Volatile
    private var survivingPrefilterIds: List<Long> = emptyList()
    private val pass1RawChunkCount = AtomicInteger(0)
    private val pass1ConsolidatedChunkCount = AtomicInteger(0)
    @Volatile
    private var pass1Topics: List<String> = emptyList()
    private val jsonRepairCount = AtomicInteger(0)
    private val translationRepairCalls = AtomicInteger(0)

    override fun onPrefilterComplete(rawCount: Int, substantiveCount: Int, survivingMessageIds: List<Long>) {
        afterPrefilterCount.set(substantiveCount)
        survivingPrefilterIds = survivingMessageIds.toList()
    }

    override fun onPass1WindowResult(
        windowMessageIds: List<Long>,
        discoveredChunkCount: Int,
        jsonRepaired: Boolean,
        usedLegacyFallback: Boolean,
        latencyMs: Long
    ) {
        if (jsonRepaired) jsonRepairCount.incrementAndGet()
        pass1Windows.add(
            Pass1TraceRecord(
                windowMessageIds = windowMessageIds,
                discoveredChunkCount = discoveredChunkCount,
                jsonRepaired = jsonRepaired,
                usedLegacyFallback = usedLegacyFallback,
                latencyMs = latencyMs
            )
        )
    }

    override fun onPass1Consolidated(rawChunkCount: Int, consolidatedChunkCount: Int, topics: List<String>) {
        pass1RawChunkCount.set(rawChunkCount)
        pass1ConsolidatedChunkCount.set(consolidatedChunkCount)
        pass1Topics = topics.toList()
    }

    override fun onPass2BatchResult(verdicts: List<GatekeeperVerdictTrace>, jsonRepaired: Boolean, latencyMs: Long) {
        if (jsonRepaired) jsonRepairCount.incrementAndGet()
        pass2Verdicts.addAll(verdicts.map { GatekeeperTraceRecord.from(it) })
    }

    override fun onPass3ChunkResult(
        chunkTopic: String,
        chunkMessageIds: List<Long>,
        attempts: Int,
        jsonRepaired: Boolean,
        cardProduced: Boolean,
        latencyMs: Long
    ) {
        if (jsonRepaired) jsonRepairCount.incrementAndGet()
        pass3Chunks.add(
            Pass3TraceRecord(
                chunkTopic = chunkTopic,
                chunkMessageIds = chunkMessageIds,
                attempts = attempts,
                jsonRepaired = jsonRepaired,
                cardProduced = cardProduced,
                latencyMs = latencyMs
            )
        )
    }

    override fun onLanguageGateResult(
        cardTitle: String,
        compliance: LanguageComplianceResult,
        repairAttempts: Int,
        jsonRepairedCount: Int,
        finalCompliant: Boolean
    ) {
        jsonRepairCount.addAndGet(jsonRepairedCount)
        translationRepairCalls.addAndGet(repairAttempts)
        languageGates.add(
            LanguageGateTraceRecord.from(
                cardTitle = cardTitle,
                compliance = compliance,
                repairAttempts = repairAttempts,
                jsonRepairedCount = jsonRepairedCount,
                finalCompliant = finalCompliant
            )
        )
    }

    fun buildTrace(llmCalls: List<LlmCallRecord>, totalDurationMs: Long): StageTrace = StageTrace(
        rawMessageCount = rawCount,
        afterAllowlistCount = afterAllowlistCount,
        afterPrefilterCount = afterPrefilterCount.get(),
        survivingPrefilterIds = survivingPrefilterIds,
        pass1Windows = synchronized(pass1Windows) { pass1Windows.toList() },
        pass1RawChunkCount = pass1RawChunkCount.get(),
        pass1ConsolidatedChunkCount = pass1ConsolidatedChunkCount.get(),
        pass1Topics = pass1Topics,
        pass2Verdicts = synchronized(pass2Verdicts) { pass2Verdicts.toList() },
        pass3Chunks = synchronized(pass3Chunks) { pass3Chunks.toList() },
        languageGates = synchronized(languageGates) { languageGates.toList() },
        llmCalls = llmCalls,
        jsonRepairCount = jsonRepairCount.get(),
        translationRepairCallCount = translationRepairCalls.get(),
        totalDurationMs = totalDurationMs
    )
}

/**
 * Loads scenarios, executes extraction pipelines with full stage observability & deterministic graders,
 * computes multi-trial pass rates (`pass@1`, `pass@k`, `pass^k`), and persists JSON + Markdown reports.
 */
object EvalRunner {

    val evalJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun loadScenarios(scenariosDir: File, idFilter: String? = null, trackFilter: EvalTrack? = null): List<EvalScenario> {
        val files = scenariosDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?: emptyList()
        return files.map { file -> evalJson.decodeFromString<EvalScenario>(file.readText()) }
            .filter { sc ->
                val matchesId = idFilter.isNullOrBlank() || sc.id.contains(idFilter, ignoreCase = true)
                val matchesTrack = trackFilter == null || sc.track == trackFilter
                matchesId && matchesTrack
            }
    }

    fun loadBaseline(baselineFile: File): EvalBaseline {
        if (!baselineFile.exists() || !baselineFile.isFile) return EvalBaseline()
        return runCatching { evalJson.decodeFromString<EvalBaseline>(baselineFile.readText()) }
            .getOrElse { EvalBaseline() }
    }

    suspend fun runTrial(
        scenario: EvalScenario,
        clientFactory: () -> LlmClient,
        trialIndex: Int = 0
    ): ScenarioTrialResult {
        val rawMessages = scenario.messages.map { it.toWeChatMessage() }
        val recallCleaned = com.wechatdigest.core.sanitizer.MessageSanitizer.suppressSystemAndRecalledMessages(rawMessages)
        val allowedSet = scenario.allowedSenderIds.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val allowlistFiltered = if (allowedSet.isNotEmpty()) {
            recallCleaned.filter { it.senderId in allowedSet }
        } else {
            recallCleaned
        }

        val observer = CollectingExtractionObserver(
            rawCount = rawMessages.size,
            afterAllowlistCount = allowlistFiltered.size
        )

        val startMs = System.currentTimeMillis()
        var recordedCalls: List<LlmCallRecord> = emptyList()
        val result = clientFactory().use { baseClient ->
            val tracingClient = TracingLlmClient(baseClient)
            val extractor = EventExtractor(
                llmClient = tracingClient,
                triageWindowSize = scenario.triageWindowSize,
                triageMaxWindowSize = scenario.triageMaxWindowSize,
                gatekeeperBatchSize = scenario.gatekeeperBatchSize,
                gatekeeperBatchMaxChars = scenario.gatekeeperBatchMaxChars,
                zoneId = ZoneOffset.UTC,
                observer = observer
            )
            val crossRoomFingerprints = scenario.crossRoomDedupTexts
                .mapNotNull { txt -> com.wechatdigest.core.sanitizer.ContentFingerprint.compute(txt).takeIf { it.isNotBlank() } }
                .toSet()
            val res = extractor.extractEventsResult(
                messages = allowlistFiltered,
                roomId = scenario.roomId,
                userPersona = scenario.userPersona,
                targetLanguage = scenario.targetLanguage,
                customRules = scenario.customRules,
                roomDisplayName = scenario.roomDisplayName,
                prefilterConfig = scenario.prefilter,
                recentRoomCards = scenario.recentRoomCards,
                crossRoomFingerprints = crossRoomFingerprints
            )
            recordedCalls = tracingClient.snapshotCalls()
            res
        }

        val trace = observer.buildTrace(recordedCalls, System.currentTimeMillis() - startMs)
        val findings = EvalGraders.gradeAll(scenario, result, trace)
        val passed = findings.all { it.passed }

        return ScenarioTrialResult(
            scenarioId = scenario.id,
            trialIndex = trialIndex,
            passed = passed,
            succeeded = result.isSuccess,
            errorMessage = result.exceptionOrNull()?.message,
            cards = result.getOrElse { emptyList() },
            trace = trace,
            findings = findings
        )
    }

    suspend fun runSuite(
        scenarios: List<EvalScenario>,
        mode: String,
        provider: String,
        model: String,
        trialsPerScenario: Int = 1,
        baseline: EvalBaseline = EvalBaseline(),
        targetLanguageOverride: String? = null,
        clientFactoryForScenario: (EvalScenario) -> LlmClient
    ): EvalSuiteReport {
        val runs = trialsPerScenario.coerceAtLeast(1)
        val summaries = mutableListOf<ScenarioSummary>()

        for (rawScenario in scenarios) {
            val scenario = if (!targetLanguageOverride.isNullOrBlank()) {
                rawScenario.copy(targetLanguage = targetLanguageOverride)
            } else {
                rawScenario
            }

            val trials = mutableListOf<ScenarioTrialResult>()
            repeat(runs) { trialIdx ->
                trials.add(runTrial(scenario, { clientFactoryForScenario(scenario) }, trialIndex = trialIdx))
            }

            val passedCount = trials.count { it.passed }
            val passAt1 = trials.firstOrNull()?.passed == true
            val passAtK = if (passedCount > 0) 1.0 else 0.0
            val passAllK = if (passedCount == trials.size) 1.0 else 0.0
            val avgDuration = trials.map { it.trace.totalDurationMs }.average().toLong()
            val avgCalls = trials.map { it.trace.llmCalls.size }.average()
            val avgJsonRepairs = trials.map { it.trace.jsonRepairCount }.average()
            val avgTransRepairs = trials.map { it.trace.translationRepairCallCount }.average()
            val avgPromptTokens = trials.map { t -> t.trace.llmCalls.sumOf { it.promptTokens ?: 0 } }.average()
            val avgCompletionTokens = trials.map { t -> t.trace.llmCalls.sumOf { it.completionTokens ?: 0 } }.average()

            val provisional = ScenarioSummary(
                scenarioId = scenario.id,
                description = scenario.description,
                track = scenario.track,
                trials = trials.size,
                passedTrials = passedCount,
                passAt1 = passAt1,
                passAtK = passAtK,
                passAllK = passAllK,
                avgDurationMs = avgDuration,
                avgLlmCalls = avgCalls,
                avgJsonRepairs = avgJsonRepairs,
                avgTranslationRepairs = avgTransRepairs,
                avgPromptTokens = avgPromptTokens,
                avgCompletionTokens = avgCompletionTokens,
                trialResults = trials
            )
            val driftWarnings = EvalGraders.checkBaselineDrift(provisional, baseline.entries[scenario.id])
            summaries.add(provisional.copy(baselineWarnings = driftWarnings))
        }

        val regSummaries = summaries.filter { it.track == EvalTrack.REGRESSION }
        val capSummaries = summaries.filter { it.track == EvalTrack.CAPABILITY }
        val regRate = if (regSummaries.isEmpty()) 1.0 else regSummaries.count { it.passAllK == 1.0 }.toDouble() / regSummaries.size
        val capRate = if (capSummaries.isEmpty()) 1.0 else capSummaries.count { it.passAtK == 1.0 }.toDouble() / capSummaries.size
        val passedScenarios = summaries.count { it.passAllK == 1.0 }

        return EvalSuiteReport(
            mode = mode,
            provider = provider,
            model = model,
            targetLanguageOverride = targetLanguageOverride,
            generatedAtIso = Instant.now().toString(),
            totalScenarios = summaries.size,
            passedScenarios = passedScenarios,
            regressionPassRate = regRate,
            capabilityPassRate = capRate,
            summaries = summaries
        )
    }

    fun writeReports(report: EvalSuiteReport, outputDir: File) {
        outputDir.mkdirs()
        File(outputDir, "latest-eval-report.json").writeText(evalJson.encodeToString(report))
        File(outputDir, "latest-eval-report.md").writeText(renderMarkdownReport(report))
    }

    fun renderMarkdownReport(report: EvalSuiteReport): String = buildString {
        append("# Pipeline Eval Report (`${report.mode}`)\n\n")
        append("- **Provider / Model**: `${report.provider}` / `${report.model}`\n")
        append("- **Generated At**: `${report.generatedAtIso}`\n")
        append("- **Scenarios Passed**: `${report.passedScenarios}/${report.totalScenarios}`\n")
        append("- **Regression `pass^k`**: `${"%.1f".format(report.regressionPassRate * 100)}%`\n\n")
        append("| Scenario | Track | Pass@1 | Pass^k | Avg Calls | Avg ms | JSON Repairs | Trans Repairs | Notes |\n")
        append("|---|---|---|---|---|---|---|---|---|\n")
        for (s in report.summaries) {
            val status = if (s.passAllK == 1.0) "PASS" else "FAIL (${s.passedTrials}/${s.trials})"
            val firstFailure = s.trialResults.flatMap { it.findings }.firstOrNull { !it.passed }
            val note = buildString {
                if (firstFailure != null) append("${firstFailure.grader}: ${firstFailure.message}")
                if (s.baselineWarnings.isNotEmpty()) {
                    if (isNotEmpty()) append("; ")
                    append(s.baselineWarnings.joinToString())
                }
            }.ifBlank { status }
            append(
                "| `${s.scenarioId}` | ${s.track} | ${if (s.passAt1) "PASS" else "FAIL"} | " +
                    "${"%.0f".format(s.passAllK * 100)}% | ${"%.1f".format(s.avgLlmCalls)} | " +
                    "${s.avgDurationMs} | ${"%.1f".format(s.avgJsonRepairs)} | " +
                    "${"%.1f".format(s.avgTranslationRepairs)} | ${note.replace("|", "/")} |\n"
            )
        }
    }
}
