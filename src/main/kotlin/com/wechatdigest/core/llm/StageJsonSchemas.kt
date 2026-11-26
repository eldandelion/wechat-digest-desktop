package com.wechatdigest.core.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Strict JSON Schemas and per-stage `max_tokens` budgets for all 7 LLM stages.
 * Sent via OpenAI `response_format: { type: "json_schema" }` (compiles to GBNF in `llama-server`)
 * and Ollama `format` schema constraint, with automatic fallback to `json_object` if unsupported.
 */
internal object StageJsonSchemas {

    const val MAX_TOKENS_PASS1_TRIAGE = 768
    const val MAX_TOKENS_PASS2_GATEKEEPER = 768
    const val MAX_TOKENS_PASS3_SYNTHESIS = 2048
    const val MAX_TOKENS_CARD_TRANSLATION = 2048
    const val MAX_TOKENS_PASS4_ENRICHMENT = 2048
    const val MAX_TOKENS_MESSAGE_TRANSLATION = 4096

    private val eventCategories = listOf("NOTICE", "EXAM", "DEADLINE", "ACTIVITY", "PROMOTION", "OTHER")
    private val eventPriorities = listOf("CRITICAL", "IMPORTANT", "NORMAL", "LOW")
    private val detailKinds = listOf(
        "DATE", "TIME", "DEADLINE", "LOCATION", "DOCUMENT", "COPIES", "FORMAT",
        "ELIGIBILITY", "EXAM", "CONTACT", "PERSON", "AUDIENCE", "LINK", "ACTION",
        "REQUIREMENT", "RULE", "WARNING", "PAYMENT", "DEVICE", "OTHER"
    )

    private val stringSchema: JsonObject = buildJsonObject { put("type", "string") }
    private val nullableStringSchema: JsonObject = buildJsonObject {
        put("type", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("string")); add(kotlinx.serialization.json.JsonPrimitive("null")) })
    }
    private val booleanSchema: JsonObject = buildJsonObject { put("type", "boolean") }
    private val integerSchema: JsonObject = buildJsonObject { put("type", "integer") }
    private val stringArraySchema: JsonObject = arrayOfSchema(stringSchema)
    private val integerArraySchema: JsonObject = arrayOfSchema(integerSchema)

    private fun enumStringSchema(values: List<String>): JsonObject = buildJsonObject {
        put("type", "string")
        put("enum", buildJsonArray { values.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
    }

    private fun arrayOfSchema(itemSchema: JsonObject): JsonObject = buildJsonObject {
        put("type", "array")
        put("items", itemSchema)
    }

    private fun objectSchema(
        properties: Map<String, JsonObject>,
        required: List<String> = properties.keys.toList()
    ): JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("properties", buildJsonObject {
            properties.forEach { (k, v) -> put(k, v) }
        })
        put("required", buildJsonArray { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
    }

    private fun detailItemSchema(includeIcon: Boolean): JsonObject {
        val props = linkedMapOf<String, JsonObject>(
            "kind" to enumStringSchema(detailKinds),
            "label" to stringSchema,
            "value" to stringSchema
        )
        if (includeIcon) {
            props["icon"] = stringSchema
        }
        return objectSchema(props)
    }

    val pass1TriageSchema: JsonObject = objectSchema(
        mapOf(
            "chunks" to arrayOfSchema(
                objectSchema(
                    mapOf(
                        "topic" to stringSchema,
                        "message_ids" to integerArraySchema,
                        "category" to enumStringSchema(eventCategories),
                        "reason" to stringSchema
                    )
                )
            )
        )
    )

    val pass2GatekeeperSingleSchema: JsonObject = objectSchema(
        mapOf(
            "is_group_notice" to booleanSchema,
            "passes_rules" to booleanSchema,
            "has_substance" to booleanSchema,
            "keep" to booleanSchema,
            "reason" to stringSchema
        )
    )

    val pass2GatekeeperBatchedSchema: JsonObject = objectSchema(
        mapOf(
            "verdicts" to arrayOfSchema(
                objectSchema(
                    mapOf(
                        "chunk_index" to integerSchema,
                        "is_group_notice" to booleanSchema,
                        "passes_rules" to booleanSchema,
                        "has_substance" to booleanSchema,
                        "keep" to booleanSchema,
                        "reason" to stringSchema
                    )
                )
            )
        )
    )

    val pass3SynthesisSchema: JsonObject = objectSchema(
        mapOf(
            "is_relevant" to booleanSchema,
            "events" to arrayOfSchema(
                objectSchema(
                    mapOf(
                        "title" to stringSchema,
                        "summary" to stringSchema,
                        "sections" to arrayOfSchema(
                            objectSchema(
                                mapOf(
                                    "heading" to stringSchema,
                                    "bullets" to stringArraySchema,
                                    "items" to arrayOfSchema(detailItemSchema(includeIcon = true)),
                                    "content" to stringSchema
                                )
                            )
                        ),
                        "category" to enumStringSchema(eventCategories),
                        "priority" to enumStringSchema(eventPriorities),
                        "event_date" to nullableStringSchema,
                        "source_message_ids" to integerArraySchema
                    )
                )
            )
        )
    )

    val cardTranslationSchema: JsonObject = objectSchema(
        mapOf(
            "title" to stringSchema,
            "summary" to stringSchema,
            "sections" to arrayOfSchema(
                objectSchema(
                    mapOf(
                        "heading" to stringSchema,
                        "items" to arrayOfSchema(detailItemSchema(includeIcon = false)),
                        "content" to stringSchema
                    )
                )
            )
        )
    )

    val pass4SectionEnrichmentSchema: JsonObject = objectSchema(
        mapOf(
            "sections" to arrayOfSchema(
                objectSchema(
                    mapOf(
                        "section_index" to integerSchema,
                        "items" to arrayOfSchema(detailItemSchema(includeIcon = true)),
                        "content" to stringSchema
                    )
                )
            )
        )
    )

    val messageTranslationSchema: JsonObject = objectSchema(
        mapOf("translated_text" to stringSchema)
    )

    fun pass1TriageOptions() = LlmCompletionOptions(
        responseSchema = pass1TriageSchema,
        schemaName = "pass1_triage",
        maxTokens = MAX_TOKENS_PASS1_TRIAGE
    )

    fun pass2GatekeeperSingleOptions() = LlmCompletionOptions(
        responseSchema = pass2GatekeeperSingleSchema,
        schemaName = "pass2_gatekeeper_single",
        maxTokens = MAX_TOKENS_PASS2_GATEKEEPER
    )

    fun pass2GatekeeperBatchedOptions() = LlmCompletionOptions(
        responseSchema = pass2GatekeeperBatchedSchema,
        schemaName = "pass2_gatekeeper_batched",
        maxTokens = MAX_TOKENS_PASS2_GATEKEEPER
    )

    fun pass3SynthesisOptions() = LlmCompletionOptions(
        responseSchema = pass3SynthesisSchema,
        schemaName = "pass3_synthesis",
        maxTokens = MAX_TOKENS_PASS3_SYNTHESIS
    )

    fun cardTranslationOptions() = LlmCompletionOptions(
        responseSchema = cardTranslationSchema,
        schemaName = "card_translation",
        maxTokens = MAX_TOKENS_CARD_TRANSLATION
    )

    fun pass4SectionEnrichmentOptions() = LlmCompletionOptions(
        responseSchema = pass4SectionEnrichmentSchema,
        schemaName = "pass4_section_enrichment",
        maxTokens = MAX_TOKENS_PASS4_ENRICHMENT
    )

    fun messageTranslationOptions() = LlmCompletionOptions(
        responseSchema = messageTranslationSchema,
        schemaName = "message_translation",
        maxTokens = MAX_TOKENS_MESSAGE_TRANSLATION
    )
}
