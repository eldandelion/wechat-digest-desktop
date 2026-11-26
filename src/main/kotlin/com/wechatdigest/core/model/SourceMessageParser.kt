package com.wechatdigest.core.model

import java.util.Locale

/** Parsed chat bubble extracted from a card's formatted `sourceMessages` or `translatedSourceMessages`. */
data class SourceMessageBubble(
    val index: Int,
    val timestamp: String,
    val sender: String,
    val isOwner: Boolean,
    val body: String
)

/**
 * Pure parser and detail-to-bubble citation matcher for attributed card source transcripts.
 */
object SourceMessageParser {

    private val BUBBLE_HEADER_REGEX = Regex(
        "^\\[(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})]\\s+([^:\\n]+):\\s*(.*)$"
    )
    private val SALIENT_TOKEN_REGEX = Regex("[A-Za-z0-9:./-]{2,}|[\\u4E00-\\u9FFF]{2,}")
    private val HIGH_SIGNAL_REGEX = Regex(".*\\d+.*|.*[:/.-].*")

    /**
     * Parses `[YYYY-MM-DD HH:mm:ss] Sender [Owner]: body` entries into ordered [SourceMessageBubble] items,
     * preserving multi-line bodies and falling back gracefully to a single bubble for legacy plain text.
     */
    fun parse(rawText: String): List<SourceMessageBubble> {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return emptyList()

        val result = mutableListOf<SourceMessageBubble>()
        var currentTimestamp = ""
        var currentSender = ""
        var currentIsOwner = false
        val currentBody = StringBuilder()

        fun flushCurrent() {
            if (currentTimestamp.isNotEmpty() || currentBody.isNotEmpty()) {
                result.add(
                    SourceMessageBubble(
                        index = result.size,
                        timestamp = currentTimestamp,
                        sender = currentSender,
                        isOwner = currentIsOwner,
                        body = currentBody.toString().trimEnd()
                    )
                )
            }
        }

        for (line in trimmed.replace("\r\n", "\n").replace('\r', '\n').lines()) {
            val match = BUBBLE_HEADER_REGEX.matchEntire(line)
            if (match != null) {
                flushCurrent()
                currentTimestamp = match.groupValues[1].trim()
                val rawSender = match.groupValues[2].trim()
                currentIsOwner = rawSender.endsWith("[Owner]")
                currentSender = rawSender.removeSuffix("[Owner]").trim()
                currentBody.clear()
                currentBody.append(match.groupValues[3])
            } else {
                if (currentBody.isNotEmpty()) currentBody.append('\n')
                currentBody.append(line)
            }
        }
        flushCurrent()

        if (result.isEmpty()) {
            return listOf(SourceMessageBubble(index = 0, timestamp = "", sender = "", isOwner = false, body = trimmed))
        }
        return result
    }

    /**
     * Returns the index of the bubble in [bubbles] best matching [queryText] by salient entity/token overlap,
     * or `null` when no bubble shares meaningful tokens with [queryText].
     */
    fun findMatchingBubbleIndex(bubbles: List<SourceMessageBubble>, queryText: String): Int? {
        if (bubbles.isEmpty() || queryText.isBlank()) return null
        val tokens = extractSalientTokens(queryText)
        if (tokens.isEmpty()) return null

        var bestIndex: Int? = null
        var bestScore = 0
        for (bubble in bubbles) {
            val haystack = "${bubble.sender} ${bubble.body}".lowercase(Locale.ROOT)
            var score = 0
            for (token in tokens) {
                if (haystack.contains(token)) {
                    score += if (HIGH_SIGNAL_REGEX.matches(token)) 3 else 1
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestIndex = bubble.index
            }
        }
        return if (bestScore > 0) bestIndex else null
    }

    private fun extractSalientTokens(queryText: String): List<String> {
        val rawMatches = SALIENT_TOKEN_REGEX.findAll(queryText)
            .map { it.value.lowercase(Locale.ROOT) }
            .distinct()
            .toList()
        val expanded = LinkedHashSet<String>()
        for (token in rawMatches) {
            expanded.add(token)
            if (token.length >= 4 && token.all { it in '\u4E00'..'\u9FFF' }) {
                for (i in 0..token.length - 2) {
                    expanded.add(token.substring(i, i + 2))
                }
            }
        }
        return expanded.toList()
    }
}
