package com.wechatdigest.core.llm

import com.wechatdigest.core.model.LlmBatchedGatekeeperItem
import com.wechatdigest.core.model.LlmGatekeeperVerdict
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Pass 2 (Rule & Substance Gatekeeper) batching and fail-closed verdict parsing helpers.
 */
internal object Pass2Gatekeeper {

    fun splitIntoBatches(
        chunks: List<EventExtractor.NoticeChunk>,
        batchSize: Int,
        maxBatchChars: Int
    ): List<List<EventExtractor.NoticeChunk>> {
        if (chunks.isEmpty()) return emptyList()
        val maxItems = batchSize.coerceAtLeast(1)
        val maxChars = maxBatchChars.coerceAtLeast(1).toLong()
        val batches = mutableListOf<List<EventExtractor.NoticeChunk>>()
        var current = mutableListOf<EventExtractor.NoticeChunk>()
        var currentChars = 0L

        for (chunk in chunks) {
            val messageChars = chunk.messages.sumOf {
                it.textContent.length.toLong() + it.senderDisplayName.length.toLong() + 40L
            }
            val chunkChars = minOf(chunk.topic.length, 120).toLong() + messageChars + 100L
            if (current.isNotEmpty() && (current.size >= maxItems || currentChars + chunkChars > maxChars)) {
                batches.add(current.toList())
                current = mutableListOf()
                currentChars = 0L
            }
            current.add(chunk)
            currentChars += chunkChars
        }
        if (current.isNotEmpty()) {
            batches.add(current.toList())
        }
        return batches
    }

    internal data class BatchVerdictEvaluation(
        val approvedChunks: List<EventExtractor.NoticeChunk>,
        val traces: List<GatekeeperVerdictTrace>,
        val jsonRepaired: Boolean
    )

    fun evaluateBatchVerdict(
        batch: List<EventExtractor.NoticeChunk>,
        rawVerdictJson: String
    ): Result<List<EventExtractor.NoticeChunk>> =
        evaluateBatchVerdictDetailed(batch, rawVerdictJson).map { it.approvedChunks }

    fun evaluateBatchVerdictDetailed(
        batch: List<EventExtractor.NoticeChunk>,
        rawVerdictJson: String
    ): Result<BatchVerdictEvaluation> {
        var jsonRepaired = false
        val rootObj = parseRootJsonObject(rawVerdictJson) { jsonRepaired = true }
            .getOrElse { return Result.failure(it) }

        if (batch.size == 1 && "verdicts" !in rootObj) {
            val verdict = parseSingleVerdictObject(rootObj)
                .getOrElse { return Result.failure(it) }
            val keep = verdict.keep && verdict.isGroupNotice && verdict.passesRules && verdict.hasSubstance
            val chunk = batch.single()
            val trace = GatekeeperVerdictTrace(
                chunkTopic = chunk.topic,
                messageIds = chunk.messages.map { it.localId },
                keep = keep,
                isGroupNotice = verdict.isGroupNotice,
                passesRules = verdict.passesRules,
                hasSubstance = verdict.hasSubstance,
                reason = verdict.reason
            )
            return Result.success(
                BatchVerdictEvaluation(
                    approvedChunks = if (keep) batch else emptyList(),
                    traces = listOf(trace),
                    jsonRepaired = jsonRepaired
                )
            )
        }

        val verdictItems = parseBatchedVerdictItems(rootObj, expectedCount = batch.size)
            .getOrElse { return Result.failure(it) }
        val traces = verdictItems.mapIndexed { index, item ->
            val chunk = batch[index]
            val keep = item.keep && item.isGroupNotice && item.passesRules && item.hasSubstance
            GatekeeperVerdictTrace(
                chunkTopic = chunk.topic,
                messageIds = chunk.messages.map { it.localId },
                keep = keep,
                isGroupNotice = item.isGroupNotice,
                passesRules = item.passesRules,
                hasSubstance = item.hasSubstance,
                reason = item.reason
            )
        }
        val approved = batch.filterIndexed { index, _ -> traces[index].keep }
        return Result.success(
            BatchVerdictEvaluation(
                approvedChunks = approved,
                traces = traces,
                jsonRepaired = jsonRepaired
            )
        )
    }

    fun parseBatchedVerdictsResult(rawJson: String, expectedCount: Int): Result<List<Boolean>> {
        val rootObj = parseRootJsonObject(rawJson)
            .getOrElse { return Result.failure(it) }
        return parseBatchedVerdictItems(rootObj, expectedCount)
            .map { items -> items.map { it.keep && it.isGroupNotice && it.passesRules && it.hasSubstance } }
    }

    fun parseSingleVerdictResult(rawJson: String): Result<Boolean> {
        val rootObj = parseRootJsonObject(rawJson)
            .getOrElse { return Result.failure(it) }
        return parseSingleVerdictObject(rootObj)
            .map { it.keep && it.isGroupNotice && it.passesRules && it.hasSubstance }
    }

    private fun parseRootJsonObject(
        rawJson: String,
        onRepaired: (() -> Unit)? = null
    ): Result<JsonObject> {
        return try {
            val obj = LlmJsonUtils.decodeWithRepair(
                raw = rawJson,
                onRepaired = { onRepaired?.invoke() }
            ) { jsonText ->
                LlmJsonUtils.sharedJson.parseToJsonElement(jsonText).jsonObject
            }
            Result.success(obj)
        } catch (e: Exception) {
            System.err.println("[Pass2Gatekeeper] Failed to parse Pass 2 gatekeeper JSON: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
            Result.failure(e)
        }
    }

    private fun parseSingleVerdictObject(rootObj: JsonObject): Result<LlmGatekeeperVerdict> {
        val keepPrimitive = rootObj["keep"] as? JsonPrimitive
        if (keepPrimitive == null || keepPrimitive.booleanOrNull == null) {
            return Result.failure(IllegalStateException("Pass 2 gatekeeper JSON missing boolean 'keep' field"))
        }
        val verdict = try {
            LlmJsonUtils.sharedJson.decodeFromJsonElement<LlmGatekeeperVerdict>(rootObj)
        } catch (e: Exception) {
            System.err.println("[Pass2Gatekeeper] Failed to decode Pass 2 gatekeeper verdict: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
            return Result.failure(e)
        }
        return Result.success(verdict)
    }

    private fun parseBatchedVerdictItems(rootObj: JsonObject, expectedCount: Int): Result<List<LlmBatchedGatekeeperItem>> {
        val verdictsArray = rootObj["verdicts"] as? JsonArray
            ?: return Result.failure(IllegalStateException("Pass 2 batched gatekeeper JSON missing 'verdicts' array"))
        if (verdictsArray.size > (expectedCount * 2).coerceAtLeast(10)) {
            return Result.failure(IllegalStateException("Pass 2 batched gatekeeper response exceeded expected verdict count"))
        }

        val itemsByIndex = mutableMapOf<Int, LlmBatchedGatekeeperItem>()
        for (element in verdictsArray) {
            val item = decodeBatchedItem(element, expectedCount, itemsByIndex)
                .getOrElse { return Result.failure(it) }
            itemsByIndex[item.chunkIndex] = item
        }

        val missing = (0 until expectedCount).firstOrNull { it !in itemsByIndex }
        if (missing != null) {
            return Result.failure(IllegalStateException("Pass 2 batched gatekeeper response missing verdict for chunk_index $missing"))
        }
        return Result.success((0 until expectedCount).map { itemsByIndex.getValue(it) })
    }

    private fun decodeBatchedItem(
        element: kotlinx.serialization.json.JsonElement,
        expectedCount: Int,
        existing: Map<Int, LlmBatchedGatekeeperItem>
    ): Result<LlmBatchedGatekeeperItem> {
        val obj = element as? JsonObject
            ?: return Result.failure(IllegalStateException("Pass 2 batched verdict item is not a JSON object"))
        val keepPrimitive = obj["keep"] as? JsonPrimitive
        if (keepPrimitive == null || keepPrimitive.booleanOrNull == null) {
            return Result.failure(IllegalStateException("Pass 2 batched verdict item missing boolean 'keep' field"))
        }
        val item = try {
            LlmJsonUtils.sharedJson.decodeFromJsonElement<LlmBatchedGatekeeperItem>(obj)
        } catch (e: Exception) {
            System.err.println("[Pass2Gatekeeper] Failed to decode batched verdict item: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
            return Result.failure(e)
        }
        if (item.chunkIndex !in 0 until expectedCount) {
            return Result.failure(IllegalStateException("Pass 2 batched verdict contains out-of-range chunk_index ${item.chunkIndex}"))
        }
        if (item.chunkIndex in existing) {
            return Result.failure(IllegalStateException("Pass 2 batched verdict contains duplicate chunk_index ${item.chunkIndex}"))
        }
        return Result.success(item)
    }
}
