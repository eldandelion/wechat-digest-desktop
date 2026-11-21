package com.wechatdigest.core.llm

/**
 * Per-chunk verdict details emitted by Pass 2 Gatekeeper for evaluation tracing.
 */
data class GatekeeperVerdictTrace(
    val chunkTopic: String,
    val messageIds: List<Long>,
    val keep: Boolean,
    val isGroupNotice: Boolean,
    val passesRules: Boolean,
    val hasSubstance: Boolean,
    val reason: String
)

/**
 * Optional transport-level telemetry emitted by [KtorLlmClient] when an observer callback is installed.
 */
data class LlmHttpTelemetry(
    val providerType: LlmProviderType,
    val model: String,
    val durationMs: Long,
    val finishReason: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null
)

/**
 * Non-intrusive observer interface for capturing stage-by-stage extraction pipeline diagnostics
 * in eval and test harnesses without affecting production runs (`NoOp` default).
 */
interface ExtractionObserver {
    fun onPrefilterComplete(rawCount: Int, substantiveCount: Int, survivingMessageIds: List<Long>) {}
    fun onPass1WindowResult(
        windowMessageIds: List<Long>,
        discoveredChunkCount: Int,
        jsonRepaired: Boolean,
        usedLegacyFallback: Boolean,
        latencyMs: Long
    ) {}
    fun onPass1Consolidated(rawChunkCount: Int, consolidatedChunkCount: Int, topics: List<String>) {}
    fun onPass2BatchResult(verdicts: List<GatekeeperVerdictTrace>, jsonRepaired: Boolean, latencyMs: Long) {}
    fun onPass3ChunkResult(
        chunkTopic: String,
        chunkMessageIds: List<Long>,
        attempts: Int,
        jsonRepaired: Boolean,
        cardProduced: Boolean,
        latencyMs: Long
    ) {}
    fun onLanguageGateResult(
        cardTitle: String,
        compliance: LanguageComplianceResult,
        repairAttempts: Int,
        jsonRepairedCount: Int,
        finalCompliant: Boolean
    ) {}

    /** Default zero-overhead no-op implementation used by production sync runs. */
    object NoOp : ExtractionObserver
}
