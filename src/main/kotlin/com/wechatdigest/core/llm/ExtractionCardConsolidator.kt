package com.wechatdigest.core.llm

import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventCategory
import com.wechatdigest.core.model.EventPriority
import com.wechatdigest.core.model.EventSection
import com.wechatdigest.core.model.LlmExtractedEvent
import com.wechatdigest.core.model.WeChatMessage

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
        joinDistinctTitles: Boolean
    ): EventCard {
        if (extractedEvents.size == 1) {
            return buildSingleEventCard(
                single = extractedEvents.first(),
                fallbackTitle = fallbackTitle,
                eventSender = eventSender,
                roomId = roomId,
                roomDisplayName = roomDisplayName,
                sourceMessagesText = sourceMessagesText,
                eventTimestamp = eventTimestamp
            )
        }

        val mergedPriority = extractedEvents.minOf { EventPriority.fromString(it.priority) }
        val mergedCategory = extractedEvents.map { EventCategory.fromString(it.category) }
            .firstOrNull { it != EventCategory.OTHER } ?: EventCategory.OTHER
        val earliestEventDate = extractedEvents
            .mapNotNull { sanitizeEventDate(it.eventDate) }
            .minOrNull()
        val (collectedSections, promotedFlatItem) = collectConsolidatedSections(extractedEvents)
        val mergedTitle = resolveConsolidatedTitle(extractedEvents, fallbackTitle, joinDistinctTitles, collectedSections.size)
        val mergedSummary = resolveConsolidatedSummary(extractedEvents, collectedSections.size, promotedFlatItem)

        return EventCard(
            title = mergedTitle,
            summary = mergedSummary,
            translatedSummary = mergedSummary,
            sections = collectedSections,
            category = mergedCategory,
            priority = mergedPriority,
            eventDate = earliestEventDate,
            originalSender = eventSender,
            sourceRoomId = roomId,
            sourceRoomName = roomDisplayName,
            sourceMessages = sourceMessagesText,
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
        eventTimestamp: Long
    ): EventCard {
        val preferredSummary = single.translatedSummary.ifBlank { single.summary }
        val fallbackSummary = single.summary.ifBlank { single.translatedSummary }
        return EventCard(
            title = single.title.ifBlank { fallbackTitle },
            summary = fallbackSummary,
            translatedSummary = preferredSummary,
            sections = sanitizeSections(single.sections),
            category = EventCategory.fromString(single.category),
            priority = EventPriority.fromString(single.priority),
            eventDate = sanitizeEventDate(single.eventDate),
            originalSender = eventSender,
            sourceRoomId = roomId,
            sourceRoomName = roomDisplayName,
            sourceMessages = sourceMessagesText,
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
        val seenSections = mutableSetOf<Pair<String, String>>()
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
                val key = section.heading.lowercase() to section.content.lowercase()
                if (seenSections.add(key)) {
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
        val distinctTitles = extractedEvents.map { it.title.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
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
            .distinctBy { it.lowercase() }
        val allWereFlat = extractedEvents.all { sanitizeSections(it.sections).isEmpty() }
        return if (promotedFlatItem && allWereFlat && sectionCount > 0) {
            distinctSummaries.firstOrNull().orEmpty()
        } else {
            distinctSummaries.joinToString("\n\n")
        }
    }

    private fun sanitizeSections(rawSections: List<EventSection>): List<EventSection> {
        return rawSections.mapNotNull { section ->
            val heading = section.heading.trim()
            val content = section.content.trim()
            if (heading.isBlank() && content.isBlank()) null else EventSection(heading = heading, content = content)
        }
    }
}
