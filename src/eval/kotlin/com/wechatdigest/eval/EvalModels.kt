package com.wechatdigest.eval

import com.wechatdigest.core.llm.EventExtractor
import com.wechatdigest.core.llm.GatekeeperVerdictTrace
import com.wechatdigest.core.llm.LanguageComplianceResult
import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.WeChatMessage
import com.wechatdigest.core.sanitizer.PrefilterConfig
import kotlinx.serialization.Serializable

/** Separates release-blocking regression scenarios from open-ended capability benchmarks. */
@Serializable
enum class EvalTrack {
    REGRESSION,
    CAPABILITY
}

/** Serializable message input used inside golden evaluation scenario files. */
@Serializable
data class EvalMessageInput(
    val localId: Long,
    val timestampSeconds: Long,
    val senderId: String,
    val senderDisplayName: String,
    val content: String,
    val rawContent: String = content,
    val isZstdCompressed: Boolean = false
) {
    fun toWeChatMessage(): WeChatMessage = WeChatMessage(
        localId = localId,
        timestampSeconds = timestampSeconds,
        senderId = senderId,
        senderDisplayName = senderDisplayName,
        rawContent = rawContent,
        textContent = content,
        isZstdCompressed = isZstdCompressed
    )
}

/** Deterministic grader thresholds and expected invariants for an [EvalScenario]. */
@Serializable
data class ScenarioExpectations(
    val expectFailure: Boolean = false,
    val minCards: Int = 0,
    val maxCards: Int = 10,
    val minSurvivingPrefilter: Int = 0,
    val maxSurvivingPrefilter: Int? = null,
    val expectedCategories: List<String> = emptyList(),
    val expectedPriorities: List<String> = emptyList(),
    val expectedTitles: List<String> = emptyList(),
    val mustContainFacts: List<String> = emptyList(),
    val forbiddenTitleSubstrings: List<String> = emptyList(),
    val forbiddenContentSubstrings: List<String> = emptyList(),
    val forbiddenSourceSubstrings: List<String> = emptyList(),
    val allowSlashMergedTitles: Boolean = false,
    val requireLanguageCompliance: Boolean = true,
    val maxLlmCalls: Int? = null,
    val minTranslationRepairs: Int = 0,
    val maxTranslationRepairs: Int? = null,
    val minJsonRepairs: Int = 0,
    val requireZeroJsonParseFailures: Boolean = false,
    val minSectionsOnFirstCard: Int = 0
)

/** Self-contained golden test scenario runnable offline (via cassette) or against a live model. */
@Serializable
data class EvalScenario(
    val id: String,
    val description: String,
    val track: EvalTrack = EvalTrack.REGRESSION,
    val roomId: String = "room_eval@chatroom",
    val roomDisplayName: String = "Eval Study Group",
    val targetLanguage: String = "en",
    val userPersona: String = "",
    val customRules: String = "",
    val allowedSenderIds: List<String> = emptyList(),
    val prefilter: PrefilterConfig = PrefilterConfig(),
    val recentRoomCards: List<EventCard> = emptyList(),
    val triageWindowSize: Int = EventExtractor.DEFAULT_TRIAGE_WINDOW_SIZE,
    val triageMaxWindowSize: Int = EventExtractor.DEFAULT_TRIAGE_MAX_WINDOW_SIZE,
    val gatekeeperBatchSize: Int = EventExtractor.DEFAULT_GATEKEEPER_BATCH_SIZE,
    val gatekeeperBatchMaxChars: Int = EventExtractor.DEFAULT_GATEKEEPER_BATCH_MAX_CHARS,
    val messages: List<EvalMessageInput>,
    val expectations: ScenarioExpectations
)

/** Record of a single intercepted LLM request/response interaction. */
@Serializable
data class LlmCallRecord(
    val callIndex: Int,
    val stage: String,
    val promptHash: String,
    val latencyMs: Long,
    val success: Boolean,
    val responseChars: Int = 0,
    val errorSummary: String? = null
)

/** Trace record of Pass 1 triage window execution. */
@Serializable
data class Pass1TraceRecord(
    val windowMessageIds: List<Long>,
    val discoveredChunkCount: Int,
    val jsonRepaired: Boolean,
    val usedLegacyFallback: Boolean,
    val latencyMs: Long
)

/** Trace record of Pass 3 chunk synthesis execution. */
@Serializable
data class Pass3TraceRecord(
    val chunkTopic: String,
    val chunkMessageIds: List<Long>,
    val attempts: Int,
    val jsonRepaired: Boolean,
    val cardProduced: Boolean,
    val latencyMs: Long
)

/** Serializable Gatekeeper verdict trace for offline JSON reports. */
@Serializable
data class GatekeeperTraceRecord(
    val chunkTopic: String,
    val messageIds: List<Long>,
    val keep: Boolean,
    val isGroupNotice: Boolean,
    val passesRules: Boolean,
    val hasSubstance: Boolean,
    val reason: String
) {
    companion object {
        fun from(trace: GatekeeperVerdictTrace): GatekeeperTraceRecord = GatekeeperTraceRecord(
            chunkTopic = trace.chunkTopic,
            messageIds = trace.messageIds,
            keep = trace.keep,
            isGroupNotice = trace.isGroupNotice,
            passesRules = trace.passesRules,
            hasSubstance = trace.hasSubstance,
            reason = trace.reason
        )
    }
}

/** Trace record of the deterministic language gate and conditional CardTranslator repair. */
@Serializable
data class LanguageGateTraceRecord(
    val cardTitle: String,
    val initiallyCompliant: Boolean,
    val initialNonCompliantFields: List<String>,
    val repairAttempts: Int,
    val jsonRepairedCount: Int,
    val finalCompliant: Boolean
) {
    companion object {
        fun from(
            cardTitle: String,
            compliance: LanguageComplianceResult,
            repairAttempts: Int,
            jsonRepairedCount: Int,
            finalCompliant: Boolean
        ): LanguageGateTraceRecord = LanguageGateTraceRecord(
            cardTitle = cardTitle,
            initiallyCompliant = compliance.isCompliant,
            initialNonCompliantFields = compliance.nonCompliantFields,
            repairAttempts = repairAttempts,
            jsonRepairedCount = jsonRepairedCount,
            finalCompliant = finalCompliant
        )
    }
}

/** Full stage-by-stage trace collected during a single scenario or snapshot execution. */
@Serializable
data class StageTrace(
    val rawMessageCount: Int,
    val afterAllowlistCount: Int,
    val afterPrefilterCount: Int,
    val survivingPrefilterIds: List<Long>,
    val pass1Windows: List<Pass1TraceRecord>,
    val pass1RawChunkCount: Int,
    val pass1ConsolidatedChunkCount: Int,
    val pass1Topics: List<String>,
    val pass2Verdicts: List<GatekeeperTraceRecord>,
    val pass3Chunks: List<Pass3TraceRecord>,
    val languageGates: List<LanguageGateTraceRecord>,
    val llmCalls: List<LlmCallRecord>,
    val jsonRepairCount: Int,
    val translationRepairCallCount: Int,
    val totalDurationMs: Long
)

/** Outcome of a single deterministic grader check. */
@Serializable
data class GraderFinding(
    val grader: String,
    val passed: Boolean,
    val message: String
)

/** Full result for one trial execution of an [EvalScenario]. */
@Serializable
data class ScenarioTrialResult(
    val scenarioId: String,
    val trialIndex: Int,
    val passed: Boolean,
    val succeeded: Boolean,
    val errorMessage: String? = null,
    val cards: List<EventCard> = emptyList(),
    val trace: StageTrace,
    val findings: List<GraderFinding>
)

/** Aggregated multi-trial metrics (`pass@1`, `pass@k`, `pass^k`) for one [EvalScenario]. */
@Serializable
data class ScenarioSummary(
    val scenarioId: String,
    val description: String,
    val track: EvalTrack,
    val trials: Int,
    val passedTrials: Int,
    val passAt1: Boolean,
    val passAtK: Double,
    val passAllK: Double,
    val avgDurationMs: Long,
    val avgLlmCalls: Double,
    val avgJsonRepairs: Double,
    val avgTranslationRepairs: Double,
    val baselineWarnings: List<String> = emptyList(),
    val trialResults: List<ScenarioTrialResult>
)

/** Top-level evaluation report saved as JSON and Markdown under `build/eval-reports/`. */
@Serializable
data class EvalSuiteReport(
    val mode: String,
    val provider: String,
    val model: String,
    val targetLanguageOverride: String? = null,
    val generatedAtIso: String,
    val totalScenarios: Int,
    val passedScenarios: Int,
    val regressionPassRate: Double,
    val capabilityPassRate: Double,
    val summaries: List<ScenarioSummary>
)

/** Versioned baseline expectation per scenario used to catch latency or call-count drift. */
@Serializable
data class EvalBaselineEntry(
    val maxLlmCalls: Int,
    val expectedCardCount: Int,
    val maxTranslationRepairs: Int = 2
)

/** Baseline file mapping scenario IDs to reference thresholds. */
@Serializable
data class EvalBaseline(
    val version: Int = 1,
    val entries: Map<String, EvalBaselineEntry> = emptyMap()
)
