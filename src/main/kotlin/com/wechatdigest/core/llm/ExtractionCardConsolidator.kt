package com.wechatdigest.core.llm

import com.wechatdigest.core.model.EventAttachment
import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventCategory
import com.wechatdigest.core.model.EventDetailItemParser
import com.wechatdigest.core.model.EventPriority
import com.wechatdigest.core.model.EventSection
import com.wechatdigest.core.model.LlmExtractedEvent
import com.wechatdigest.core.model.WeChatMessage
import java.util.Locale

/**
 * Consolidates related LLM event extractions and builds sanitized [EventCard] instances.
 */
internal object ExtractionCardConsolidator {

    data class ResolvedExtraction(
        val extracted: LlmExtractedEvent,
        val matchedMessages: List<WeChatMessage>,
        val matchedIds: Set<Long>
    )

    fun groupOverlappingExtractions(items: List<ResolvedExtraction>): List<List<ResolvedExtraction>> {
        val clusters = mutableListOf<MutableList<ResolvedExtraction>>()
        val clusterIds = mutableListOf<MutableSet<Long>>()

        for (item in items) {
            if (item.matchedIds.isEmpty()) {
                clusters.add(mutableListOf(item))
                clusterIds.add(mutableSetOf())
                continue
            }

            val matchingIndices = clusterIds.indices.filter { idx ->
                clusterIds[idx].any { it in item.matchedIds }
            }
            if (matchingIndices.isEmpty()) {
                clusters.add(mutableListOf(item))
                clusterIds.add(item.matchedIds.toMutableSet())
            } else {
                mergeCluster(clusters, clusterIds, matchingIndices, item)
            }
        }
        return clusters.map { it.toList() }
    }

    private fun mergeCluster(
        clusters: MutableList<MutableList<ResolvedExtraction>>,
        clusterIds: MutableList<MutableSet<Long>>,
        matchingIndices: List<Int>,
        item: ResolvedExtraction
    ) {
        val targetIdx = matchingIndices.first()
        for (otherIdx in matchingIndices.drop(1).asReversed()) {
            clusters[targetIdx].addAll(clusters.removeAt(otherIdx))
            clusterIds[targetIdx].addAll(clusterIds.removeAt(otherIdx))
        }
        clusters[targetIdx].add(item)
        clusterIds[targetIdx].addAll(item.matchedIds)
    }

    fun buildConsolidatedCard(
        extractedEvents: List<LlmExtractedEvent>,
        sourceMessagesText: String,
        eventTimestamp: Long,
        eventSender: String,
        roomId: String,
        roomDisplayName: String,
        fallbackTitle: String,
        joinDistinctTitles: Boolean,
        sourceAttachments: List<EventAttachment> = emptyList()
    ): EventCard {
        val deduplicatedAttachments = sourceAttachments.distinctBy { it.previewKey() }
        if (extractedEvents.size == 1) {
            return buildSingleEventCard(
                extractedEvents.first(), fallbackTitle, eventSender, roomId, roomDisplayName,
                sourceMessagesText, eventTimestamp, deduplicatedAttachments
            )
        }

        val (collectedSections, promotedFlatItem) = collectConsolidatedSections(extractedEvents)
        return EventCard(
            title = EventDetailItemParser.sanitizeSingleLine(
                resolveConsolidatedTitle(extractedEvents, fallbackTitle, joinDistinctTitles, collectedSections.size),
                EventDetailItemParser.MAX_CARD_TITLE_CHARS
            ),
            summary = EventDetailItemParser.sanitizeMultiLine(
                resolveConsolidatedSummary(extractedEvents, collectedSections.size, promotedFlatItem),
                EventDetailItemParser.MAX_CARD_SUMMARY_CHARS
            ),
            translatedSummary = EventDetailItemParser.sanitizeMultiLine(
                resolveConsolidatedSummary(extractedEvents, collectedSections.size, promotedFlatItem),
                EventDetailItemParser.MAX_CARD_SUMMARY_CHARS
            ),
            sections = collectedSections,
            category = extractedEvents.map { EventCategory.fromString(it.category) }.firstOrNull { it != EventCategory.OTHER } ?: EventCategory.OTHER,
            priority = extractedEvents.minOf { EventPriority.fromString(it.priority) },
            eventDate = extractedEvents.mapNotNull { sanitizeEventDate(it.eventDate) }.minOrNull(),
            originalSender = eventSender,
            sourceRoomId = roomId,
            sourceRoomName = roomDisplayName,
            sourceMessages = sourceMessagesText,
            attachments = deduplicatedAttachments,
            timestampSeconds = eventTimestamp
        )
    }

    private fun buildSingleEventCard(
        single: LlmExtractedEvent,
        fallbackTitle: String,
        eventSender: String,
        roomId: String,
        roomDisplayName: String,
        sourceMessagesText: String,
        eventTimestamp: Long,
        attachments: List<EventAttachment>
    ): EventCard {
        val preferredSummary = single.translatedSummary.ifBlank { single.summary }
        val fallbackSummary = single.summary.ifBlank { single.translatedSummary }
        return EventCard(
            title = EventDetailItemParser.sanitizeSingleLine(
                single.title.ifBlank { fallbackTitle },
                EventDetailItemParser.MAX_CARD_TITLE_CHARS
            ),
            summary = EventDetailItemParser.sanitizeMultiLine(
                fallbackSummary,
                EventDetailItemParser.MAX_CARD_SUMMARY_CHARS
            ),
            translatedSummary = EventDetailItemParser.sanitizeMultiLine(
                preferredSummary,
                EventDetailItemParser.MAX_CARD_SUMMARY_CHARS
            ),
            sections = sanitizeSections(single.sections),
            category = EventCategory.fromString(single.category),
            priority = EventPriority.fromString(single.priority),
            eventDate = sanitizeEventDate(single.eventDate),
            originalSender = eventSender,
            sourceRoomId = roomId,
            sourceRoomName = roomDisplayName,
            sourceMessages = sourceMessagesText,
            attachments = attachments,
            timestampSeconds = eventTimestamp
        )
    }

    private fun sanitizeEventDate(rawDate: String?): String? {
        val trimmed = rawDate?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return ExtractionPrompts.sanitizeInline(trimmed, 32).takeIf { it.isNotBlank() }
    }

    private fun collectConsolidatedSections(extractedEvents: List<LlmExtractedEvent>): Pair<List<EventSection>, Boolean> {
        var promotedFlatItem = false
        val collectedSections = mutableListOf<EventSection>()
        val seenSections = mutableSetOf<String>()
        for (item in extractedEvents) {
            val existingSections = sanitizeSections(item.sections)
            val candidates = if (existingSections.isNotEmpty()) {
                existingSections
            } else {
                val heading = item.title.trim()
                val content = item.translatedSummary.ifBlank { item.summary }.trim()
                if (heading.isNotBlank() || content.isNotBlank()) {
                    promotedFlatItem = true
                    listOf(EventSection(heading = heading, content = content))
                } else {
                    emptyList()
                }
            }
            for (section in candidates) {
                val itemsKey = section.items.joinToString("|") {
                    "${it.kind.name}:${it.label.lowercase(Locale.ROOT)}=${it.value.lowercase(Locale.ROOT)}"
                }
                val bulletsKey = section.bullets.joinToString("|") { it.lowercase(Locale.ROOT) }
                val key = listOf(
                    section.heading.lowercase(Locale.ROOT),
                    section.content.lowercase(Locale.ROOT),
                    itemsKey,
                    bulletsKey
                ).joinToString("::")
                if (seenSections.add(key) && collectedSections.size < MAX_SECTIONS_PER_CARD) {
                    collectedSections.add(section)
                }
            }
        }
        return collectedSections.toList() to promotedFlatItem
    }

    private fun resolveConsolidatedTitle(
        extractedEvents: List<LlmExtractedEvent>,
        fallbackTitle: String,
        joinDistinctTitles: Boolean,
        sectionCount: Int
    ): String {
        val distinctTitles = extractedEvents.map { it.title.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase(Locale.ROOT) }
        return when {
            distinctTitles.isEmpty() -> fallbackTitle
            distinctTitles.size == 1 -> distinctTitles.first()
            !joinDistinctTitles -> fallbackTitle.takeIf { it.isNotBlank() && it != "Group Notice" } ?: distinctTitles.first()
            distinctTitles.joinToString(" / ").length <= 80 -> distinctTitles.joinToString(" / ")
            else -> "${distinctTitles.first()} (${sectionCount.coerceAtLeast(distinctTitles.size)} parts)"
        }
    }

    private fun resolveConsolidatedSummary(
        extractedEvents: List<LlmExtractedEvent>,
        sectionCount: Int,
        promotedFlatItem: Boolean
    ): String {
        val distinctSummaries = extractedEvents.map { it.translatedSummary.ifBlank { it.summary }.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
        val allWereFlat = extractedEvents.all { sanitizeSections(it.sections).isEmpty() }
        return if (promotedFlatItem && allWereFlat && sectionCount > 0) {
            distinctSummaries.firstOrNull().orEmpty()
        } else {
            distinctSummaries.joinToString("\n\n")
        }
    }

    private fun sanitizeSections(rawSections: List<EventSection>): List<EventSection> {
        return rawSections.mapNotNull { section ->
            val heading = EventDetailItemParser.sanitizeSingleLine(
                section.heading,
                EventDetailItemParser.MAX_SECTION_HEADING_CHARS
            )
            val content = EventDetailItemParser.sanitizeMultiLine(
                section.content,
                EventDetailItemParser.MAX_SECTION_CONTENT_CHARS
            )
            val items = EventDetailItemParser.sanitizeItems(section.items)
            val bullets = EventDetailItemParser.sanitizeBullets(section.bullets)
            if (content.isBlank() && items.isEmpty() && bullets.isEmpty()) {
                null
            } else {
                EventSection(heading = heading, content = content, items = items, bullets = bullets)
            }
        }.take(MAX_SECTIONS_PER_CARD)
    }

    private const val MAX_SECTIONS_PER_CARD = 16
}
