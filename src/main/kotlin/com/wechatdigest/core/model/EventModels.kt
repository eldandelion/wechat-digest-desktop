package com.wechatdigest.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Locale
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
            is JsonPrimitive -> element.content.take(EventDetailItemParser.MAX_SECTION_CONTENT_CHARS)
            is JsonArray -> element.asSequence().take(64).mapNotNull { item ->
                when (item) {
                    is JsonNull -> null
                    is JsonPrimitive -> item.content.trim().take(EventDetailItemParser.MAX_LEGACY_LINE_CHARS).takeIf { it.isNotBlank() }
                    else -> null
                }
            }.joinToString("\n").take(EventDetailItemParser.MAX_SECTION_CONTENT_CHARS)
            else -> ""
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
        fun fromString(value: String): EventPriority = when (value.uppercase(Locale.ROOT).trim()) {
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
        fun fromString(value: String): EventCategory = when (value.uppercase(Locale.ROOT).trim()) {
            "EXAM" -> EXAM
            "DEADLINE" -> DEADLINE
            "NOTICE", "ACADEMIC" -> NOTICE
            "ACTIVITY", "EVENT", "SPORTS" -> ACTIVITY
            "PROMOTION", "DEAL", "DISCOUNT" -> PROMOTION
            else -> OTHER
        }
    }
}

/** Lifecycle state of a tracked event card supporting living amendments and cancellations. */
@Serializable
enum class EventCardStatus {
    @SerialName("ACTIVE") ACTIVE,
    @SerialName("UPDATED") UPDATED,
    @SerialName("CANCELLED") CANCELLED;

    companion object {
        fun fromString(value: String?): EventCardStatus = when (value?.uppercase(Locale.ROOT)?.trim()) {
            "UPDATED" -> UPDATED
            "CANCELLED", "CANCELED" -> CANCELLED
            else -> ACTIVE
        }
    }
}

/** Semantic metadata category for structured detail mini-tiles inside an [EventSection]. */
@Serializable(with = FlexibleDetailKindSerializer::class)
enum class EventDetailKind {
    DATE,
    TIME,
    DEADLINE,
    LOCATION,
    DOCUMENT,
    COPIES,
    FORMAT,
    ELIGIBILITY,
    EXAM,
    CONTACT,
    PERSON,
    AUDIENCE,
    LINK,
    ACTION,
    REQUIREMENT,
    RULE,
    WARNING,
    PAYMENT,
    DEVICE,
    OTHER;

    companion object {
        /** Maps arbitrary LLM-emitted kind tokens or common synonyms to a canonical [EventDetailKind]. */
        fun fromString(value: String): EventDetailKind = when (value.uppercase(Locale.ROOT).trim()) {
            "DATE", "DAY" -> DATE
            "TIME", "HOURS", "TIME_WINDOW", "SCHEDULE" -> TIME
            "DEADLINE", "DUE", "DUE_DATE" -> DEADLINE
            "LOCATION", "VENUE", "ROOM", "PLACE", "ADDRESS", "CLASSROOM" -> LOCATION
            "DOCUMENT", "DOC", "DOCUMENTS", "FILE", "ATTACHMENT", "FORM", "PAPER", "THESIS" -> DOCUMENT
            "COPIES", "COPY", "PRINT", "HARD_COPY", "BOUND" -> COPIES
            "FORMAT", "NAMING", "TEMPLATE", "SUBJECT", "STYLE" -> FORMAT
            "ELIGIBILITY", "PREREQUISITE", "CREDITS", "QUALIFICATION", "GPA" -> ELIGIBILITY
            "EXAM", "QUIZ", "TEST", "ASSESSMENT", "GRADING" -> EXAM
            "CONTACT", "EMAIL", "PHONE", "ORGANIZER" -> CONTACT
            "PERSON", "ADVISOR", "SPEAKER", "INSTRUCTOR", "TEACHER", "REVIEWER", "SIGNATURE", "TA" -> PERSON
            "AUDIENCE", "GROUP", "PARTICIPANTS", "STUDENTS", "COHORT", "TARGET" -> AUDIENCE
            "LINK", "URL", "WEBSITE", "PORTAL" -> LINK
            "ACTION", "STEP", "TASK", "SUBMIT", "REGISTER" -> ACTION
            "REQUIREMENT", "REQUIREMENTS", "MATERIALS" -> REQUIREMENT
            "RULE", "POLICY", "REGULATION", "PENALTY" -> RULE
            "WARNING", "CAUTION", "ALERT", "NOTICE", "IMPORTANT" -> WARNING
            "PAYMENT", "FEE", "COST", "PRICE", "STIPEND", "SCHOLARSHIP", "AWARD", "REWARD" -> PAYMENT
            "DEVICE", "EQUIPMENT", "TOOL", "LAPTOP", "SOFTWARE", "ID_CARD", "CALCULATOR" -> DEVICE
            else -> OTHER
        }
    }
}

/**
 * Tolerant serializer for [EventDetailKind] so unexpected or lowercase LLM strings degrade
 * cleanly to [EventDetailKind.fromString] instead of failing deserialization.
 */
internal object FlexibleDetailKindSerializer : KSerializer<EventDetailKind> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleDetailKind", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: EventDetailKind) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): EventDetailKind {
        val jsonDecoder = decoder as? JsonDecoder ?: return EventDetailKind.fromString(decoder.decodeString())
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonNull -> EventDetailKind.OTHER
            is JsonPrimitive -> EventDetailKind.fromString(element.content)
            else -> EventDetailKind.OTHER
        }
    }
}

/** Tolerant serializer for string bullet lists accepting JSON arrays, newline-delimited strings, or null. */
internal object FlexibleStringListSerializer : KSerializer<List<String>> {
    private val delegate = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<String>) {
        delegate.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): List<String> {
        val jsonDecoder = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonNull -> emptyList()
            is JsonArray -> element.asSequence().take(EventDetailItemParser.MAX_ITEMS_PER_SECTION * 2).mapNotNull { item ->
                when (item) {
                    is JsonNull -> null
                    is JsonPrimitive -> item.content.trim().take(EventDetailItemParser.MAX_LEGACY_LINE_CHARS).takeIf { it.isNotBlank() }
                    else -> null
                }
            }.toList()
            is JsonPrimitive -> element.content.take(EventDetailItemParser.MAX_SECTION_CONTENT_CHARS)
                .lineSequence()
                .map { it.trim().take(EventDetailItemParser.MAX_LEGACY_LINE_CHARS) }
                .filter { it.isNotBlank() }
                .take(EventDetailItemParser.MAX_ITEMS_PER_SECTION * 2)
                .toList()
            else -> emptyList()
        }
    }
}

/** Single typed key-value logistics snippet (e.g. Date, Time, Location) displayed as an icon mini-tile. */
@Serializable
data class EventDetailItem(
    val kind: EventDetailKind = EventDetailKind.OTHER,
    @Serializable(with = FlexibleStringSerializer::class)
    val label: String = "",
    @Serializable(with = FlexibleStringSerializer::class)
    val value: String = "",
    @Serializable(with = FlexibleStringSerializer::class)
    val icon: String = ""
)

@Serializable
data class EventSection(
    @Serializable(with = FlexibleStringSerializer::class)
    val heading: String = "",
    @Serializable(with = FlexibleStringSerializer::class)
    val content: String = "",
    val items: List<EventDetailItem> = emptyList(),
    @Serializable(with = FlexibleStringListSerializer::class)
    val bullets: List<String> = emptyList()
)

@Serializable
data class EventAttachment(
    val fileName: String,
    val fileExtension: String = "",
    val fileSizeBytes: Long = 0L,
    val md5: String = "",
    val localPath: String? = null,
    val messageTimestampSeconds: Long = 0L,
    val mediaExpireAtSeconds: Long? = null
) {
    /** Returns `true` when [localPath] is populated and points to an existing regular file on disk. */
    fun isLocalFilePresent(): Boolean = !localPath.isNullOrBlank() && File(localPath).isFile

    /** Returns `true` when WeChat's `media_expire_at` timestamp has passed relative to [nowEpochSeconds]. */
    fun isExpired(nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
        mediaExpireAtSeconds != null && mediaExpireAtSeconds > 0L && nowEpochSeconds > mediaExpireAtSeconds

    /** Stable deduplication key built from normalized MD5 (or fallback filename and timestamp). */
    fun previewKey(): String = md5.trim().lowercase(Locale.ROOT).ifBlank { "$fileName:$messageTimestampSeconds" }
}

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
    val attachments: List<EventAttachment> = emptyList(),
    val timestampSeconds: Long,
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    val isDismissed: Boolean = false,
    val status: EventCardStatus = EventCardStatus.ACTIVE,
    val supersedesCardId: String? = null,
    val originalSenderId: String = "",
    val contentFingerprint: String = "",
    val sourceLocalIds: List<Long> = emptyList()
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
    @SerialName("source_message_ids") val sourceMessageIds: List<JsonPrimitive> = emptyList(),
    val status: String = "ACTIVE",
    @SerialName("supersedes_card_id") val supersedesCardId: String? = null
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
