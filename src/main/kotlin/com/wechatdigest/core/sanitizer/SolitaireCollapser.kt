package com.wechatdigest.core.sanitizer

import com.wechatdigest.core.model.WeChatMessage

/**
 * Collapses repetitive WeChat `#接龙` check-in / sign-up chains sharing the same topic header
 * into a single bounded summary message before LLM triage while preserving distinct topics
 * and substantive header announcements.
 */
internal object SolitaireCollapser {

    private const val MAX_DISPLAYED_ENTRIES = 10
    private val SOLITAIRE_HEADER_REGEX = Regex("^#(?:接龙|Solitaire)(?:\\s+(.*))?$", RegexOption.IGNORE_CASE)
    private val ROSTER_ITEM_REGEX = Regex("^\\d{1,3}[.、)．）]\\s*\\S.*")
    private val NORMALIZE_TOPIC_NOISE_REGEX = Regex("\\s+")

    /**
     * Collapses consecutive or same-topic `#接龙` check-in snapshots in [messages] down to the latest
     * snapshot per topic, capping roster length to avoid flooding Pass 1 windows.
     */
    fun collapse(messages: List<WeChatMessage>): List<WeChatMessage> {
        if (messages.size <= 1 && messages.none { parseSolitaire(it.textContent) != null }) {
            return messages
        }

        val parsed = messages.map { msg -> msg to parseSolitaire(msg.textContent) }
        val latestIndexByKey = HashMap<String, Int>()
        val countByKey = HashMap<String, Int>()
        for ((idx, pair) in parsed.withIndex()) {
            val sol = pair.second ?: continue
            latestIndexByKey[sol.topicKey] = idx
            countByKey[sol.topicKey] = (countByKey[sol.topicKey] ?: 0) + 1
        }
        if (latestIndexByKey.isEmpty()) return messages

        val output = ArrayList<WeChatMessage>(messages.size)
        for ((idx, pair) in parsed.withIndex()) {
            val (msg, sol) = pair
            if (sol == null) {
                output.add(msg)
                continue
            }
            if (latestIndexByKey[sol.topicKey] != idx) {
                continue
            }
            val chainCount = countByKey[sol.topicKey] ?: 1
            if (chainCount <= 1 && sol.entries.size <= MAX_DISPLAYED_ENTRIES) {
                output.add(msg)
            } else {
                output.add(msg.copy(textContent = formatCollapsedSolitaire(sol, chainCount)))
            }
        }
        return output
    }

    private data class ParsedSolitaire(
        val topicKey: String,
        val headerLines: List<String>,
        val entries: List<String>,
        val footerLines: List<String>
    )

    private fun parseSolitaire(text: String): ParsedSolitaire? {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
        val firstNonBlankIdx = lines.indexOfFirst { it.isNotBlank() }
        if (firstNonBlankIdx < 0) return null

        val firstLine = lines[firstNonBlankIdx].trim()
        val headerMatch = SOLITAIRE_HEADER_REGEX.matchEntire(firstLine) ?: return null
        val inlineTopic = headerMatch.groupValues[1].trim()

        val headerLines = mutableListOf<String>()
        if (inlineTopic.isNotBlank()) headerLines.add(inlineTopic)

        val entries = mutableListOf<String>()
        val footerLines = mutableListOf<String>()
        var seenRoster = false

        for (i in (firstNonBlankIdx + 1) until lines.size) {
            val line = lines[i].trim()
            if (line.isBlank()) continue
            if (ROSTER_ITEM_REGEX.matches(line)) {
                seenRoster = true
                entries.add(line)
            } else if (!seenRoster) {
                headerLines.add(line)
            } else {
                footerLines.add(line)
            }
        }

        // Only treat as collapsible solitaire if there is at least one numbered entry or explicit #接龙 roster
        if (entries.isEmpty()) return null

        val topicBase = (headerLines.firstOrNull() ?: inlineTopic.ifBlank { "check-in" })
            .replace(NORMALIZE_TOPIC_NOISE_REGEX, " ")
            .lowercase()
            .take(80)

        return ParsedSolitaire(
            topicKey = topicBase,
            headerLines = headerLines,
            entries = entries,
            footerLines = footerLines
        )
    }

    private fun formatCollapsedSolitaire(sol: ParsedSolitaire, chainCount: Int): String = buildString {
        append("#接龙")
        val headerBody = sol.headerLines.joinToString("\n").trim()
        if (headerBody.isNotBlank()) {
            append(' ').append(headerBody)
        }
        append('\n')
        val shown = sol.entries.take(MAX_DISPLAYED_ENTRIES)
        shown.forEach { append(it).append('\n') }
        val hiddenEntries = sol.entries.size - shown.size
        val collapsedUpdates = (chainCount - 1).coerceAtLeast(0)
        if (hiddenEntries > 0 || collapsedUpdates > 0) {
            append("[...")
            if (hiddenEntries > 0) append(" +$hiddenEntries more entries (${sol.entries.size} total)")
            if (hiddenEntries > 0 && collapsedUpdates > 0) append(";")
            if (collapsedUpdates > 0) append(" collapsed $collapsedUpdates prior check-in update(s)")
            append("]\n")
        }
        if (sol.footerLines.isNotEmpty()) {
            append(sol.footerLines.joinToString("\n"))
        }
    }.trimEnd()
}
