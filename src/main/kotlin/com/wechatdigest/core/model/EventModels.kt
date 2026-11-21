package com.wechatdigest.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Serializes section text as a JSON string while tolerating LLM array-of-bullets responses
 * (joining non-blank elements with newlines) or nulls cleanly during deserialization.
 */
internal object FlexibleStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonNull -> ""
            is JsonPrimitive -> element.content
            is JsonArray -> element.mapNotNull { item ->
                when (item) {
                    is JsonPrimitive -> item.content.trim().takeIf { it.isNotBlank() }
                    else -> item.toString().trim().takeIf { it.isNotBlank() }
                }
            }.joinToString("\n")
            else -> element.toString()
        }
    }
}

@Serializable
enum class EventPriority {
    @SerialName("CRITICAL") CRITICAL,
    @SerialName("IMPORTANT") IMPORTANT,
    @SerialName("NORMAL") NORMAL,
    @SerialName("LOW") LOW;

    companion object {
        fun fromString(value: String): EventPriority = when (value.uppercase().trim()) {
            "CRITICAL" -> CRITICAL
            "IMPORTANT", "HIGH" -> IMPORTANT
            "NORMAL", "MEDIUM" -> NORMAL
            else -> LOW
        }
    }
}

@Serializable
enum class EventCategory {
    @SerialName("EXAM") EXAM,
    @SerialName("DEADLINE") DEADLINE,
    @SerialName("NOTICE") NOTICE,
    @SerialName("ACTIVITY") ACTIVITY,
    @SerialName("PROMOTION") PROMOTION,
    @SerialName("OTHER") OTHER;

    companion object {
        fun fromString(value: String): EventCategory = when (value.uppercase().trim()) {
            "EXAM" -> EXAM
            "DEADLINE" -> DEADLINE
            "NOTICE", "ACADEMIC" -> NOTICE
            "ACTIVITY", "EVENT", "SPORTS" -> ACTIVITY
            "PROMOTION", "DEAL", "DISCOUNT" -> PROMOTION
            else -> OTHER
        }
    }
}

@Serializable
data class EventSection(
    @Serializable(with = FlexibleStringSerializer::class)
    val heading: String = "",
    @Serializable(with = FlexibleStringSerializer::class)
    val content: String = ""
)

@Serializable
data class EventCard(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val summary: String,
    val translatedSummary: String,
    val sections: List<EventSection> = emptyList(),
    val category: EventCategory,
    val priority: EventPriority,
    val eventDate: String? = null,
    val originalSender: String,
    val sourceRoomId: String,
    val sourceRoomName: String = "",
    val sourceMessages: String = "",
    val translatedSourceMessages: String = "",
    val timestampSeconds: Long,
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    val isDismissed: Boolean = false
)

@Serializable
data class LlmExtractedEvent(
    val title: String = "",
    val summary: String = "",
    @SerialName("translated_summary") val translatedSummary: String = "",
    val sections: List<EventSection> = emptyList(),
    val category: String = "NOTICE",
    val priority: String = "NORMAL",
    @SerialName("event_date") val eventDate: String? = null,
    @SerialName("source_message_ids") val sourceMessageIds: List<JsonPrimitive> = emptyList()
)

/** Pass 1 triage chunk grouping semantically connected messages of a single announcement. */
@Serializable
data class LlmTriageChunk(
    val topic: String = "",
    @SerialName("message_ids") val messageIds: List<JsonPrimitive> = emptyList(),
    val category: String = "NOTICE",
    val reason: String = ""
)

/** Pass 1 triage response listing filtered single-topic announcement chunks. */
@Serializable
data class LlmTriageResponse(
    val chunks: List<LlmTriageChunk> = emptyList()
)

/** Pass 2 rule & substance gatekeeper verdict for a single candidate chunk. */
@Serializable
data class LlmGatekeeperVerdict(
    val keep: Boolean = false,
    @SerialName("is_group_notice") val isGroupNotice: Boolean = false,
    @SerialName("passes_rules") val passesRules: Boolean = false,
    @SerialName("has_substance") val hasSubstance: Boolean = false,
    val reason: String = ""
)

/** Pass 2 batched gatekeeper verdict item tied to a 0-based `chunk_index` in the batch request. */
@Serializable
data class LlmBatchedGatekeeperItem(
    @SerialName("chunk_index") val chunkIndex: Int = -1,
    val keep: Boolean = false,
    @SerialName("is_group_notice") val isGroupNotice: Boolean = false,
    @SerialName("passes_rules") val passesRules: Boolean = false,
    @SerialName("has_substance") val hasSubstance: Boolean = false,
    val reason: String = ""
)

@Serializable
data class LlmExtractionResponse(
    @SerialName("is_relevant") val isRelevant: Boolean = false,
    val events: List<LlmExtractedEvent> = emptyList()
)
