package com.wechatdigest.core.calendar

import com.wechatdigest.core.llm.EventExtractor
import com.wechatdigest.core.model.DeadlineTriage
import com.wechatdigest.core.model.DetailChecklistKeys
import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventDetailItemParser
import com.wechatdigest.core.model.EventDetailKind
import com.wechatdigest.core.model.EventPriority
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class IcsCalendarGenerator {

    private val dtStampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val dateOnlyFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    fun generateIcs(card: EventCard): String {
        val rawDate = card.eventDate?.trim()
        if (rawDate.isNullOrBlank()) {
            throw IllegalArgumentException("Cannot generate calendar event without event date: ${card.id}")
        }

        val (startLine, endLine) = parseDateLines(rawDate)
        val nowStamp = dtStampFormatter.format(Instant.now())
        val safeUidPrefix = card.id.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "event" }
        val uid = "$safeUidPrefix@wechatdigest"
        val rfcPriority = when (card.priority) {
            EventPriority.CRITICAL -> 1
            EventPriority.IMPORTANT -> 3
            EventPriority.NORMAL -> 5
            EventPriority.LOW -> 9
        }

        val safeTitle = card.title.take(240)
        val locationValue = extractFirstLocation(card)?.take(240)
        val urlValue = extractFirstHttpUrl(card)

        val descriptionText = buildString {
            if (card.translatedSummary.isNotBlank()) {
                append(card.translatedSummary.take(2_000))
            } else {
                append(card.summary.take(2_000))
            }
            appendChecklistSummary(card, this)
            if (card.summary.isNotBlank() && card.summary != card.translatedSummary) {
                append("\n\nOriginal Notice:\n")
                append(card.summary.take(2_000))
            }
            if (card.originalSender.isNotBlank()) {
                append("\nSender: ${card.originalSender.take(120)}")
            }
        }.trim()

        val rawLines = buildList {
            add("BEGIN:VCALENDAR")
            add("VERSION:2.0")
            add("PRODID:-//WeChat Digest//Desktop Calendar Exporter//EN")
            add("CALSCALE:GREGORIAN")
            add("METHOD:PUBLISH")
            add("BEGIN:VEVENT")
            add("UID:$uid")
            add("DTSTAMP:$nowStamp")
            add(startLine)
            add(endLine)
            add("SUMMARY:${escapeText(safeTitle)}")
            add("DESCRIPTION:${escapeText(descriptionText)}")
            if (!locationValue.isNullOrBlank()) {
                add("LOCATION:${escapeText(locationValue)}")
            }
            if (!urlValue.isNullOrBlank()) {
                add("URL:${urlValue.trim()}")
            }
            add("CATEGORIES:${card.category.name}")
            add("PRIORITY:$rfcPriority")
            add("STATUS:CONFIRMED")
            add("BEGIN:VALARM")
            add("ACTION:DISPLAY")
            add("DESCRIPTION:Reminder: ${escapeText(safeTitle)}")
            add("TRIGGER:-PT1H")
            add("END:VALARM")
            add("END:VEVENT")
            add("END:VCALENDAR")
        }
        return rawLines.joinToString(separator = "\r\n", postfix = "\r\n") { foldIcsLine(it) }
    }

    private fun extractFirstLocation(card: EventCard): String? {
        for (section in card.sections) {
            val resolved = EventDetailItemParser.resolveSectionDisplay(section)
            val loc = resolved.items.firstOrNull { it.kind == EventDetailKind.LOCATION }?.value?.trim()
            if (!loc.isNullOrBlank()) return loc
        }
        return null
    }

    private fun extractFirstHttpUrl(card: EventCard): String? {
        for (section in card.sections) {
            val resolved = EventDetailItemParser.resolveSectionDisplay(section)
            for (item in resolved.items) {
                if (item.kind == EventDetailKind.LINK) {
                    val candidate = extractSafeHttpUrl(item.value)
                    if (candidate != null) return candidate
                }
            }
        }
        return null
    }

    private fun appendChecklistSummary(card: EventCard, out: StringBuilder) {
        val lines = mutableListOf<String>()
        val checkedSet = card.checkedItems.toSet()
        card.sections.forEachIndexed { secIdx, section ->
            val resolved = EventDetailItemParser.resolveSectionDisplay(section)
            resolved.items.forEachIndexed { itemIdx, item ->
                if (DetailChecklistKeys.isCheckable(item.kind)) {
                    val key = DetailChecklistKeys.keyFor(secIdx, itemIdx, item)
                    val mark = if (key in checkedSet) "[x]" else "[ ]"
                    lines.add("$mark ${item.label.ifBlank { item.kind.name }}: ${item.value}")
                }
            }
        }
        if (lines.isNotEmpty()) {
            out.append("\n\nChecklist:\n")
            lines.take(20).forEach { line -> out.append(line).append('\n') }
        }
    }

    /** Folds RFC 5545 content lines cleanly at 75 UTF-8 octets without per-code-point allocations. */
    internal fun foldIcsLine(line: String, maxOctets: Int = 75): String {
        if (line.length * 4 <= maxOctets) return line
        val out = StringBuilder(line.length + 16)
        var currentLineOctets = 0
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val charCount = Character.charCount(cp)
            val cpOctets = when {
                cp <= 0x7F -> 1
                cp <= 0x7FF -> 2
                cp <= 0xFFFF -> 3
                else -> 4
            }
            if (currentLineOctets > 0 && currentLineOctets + cpOctets > maxOctets) {
                out.append("\r\n ")
                currentLineOctets = 1
            }
            out.append(line, i, i + charCount)
            currentLineOctets += cpOctets
            i += charCount
        }
        return out.toString()
    }

    companion object {
        private const val MAX_URL_LENGTH = 2048
        private val URL_EXTRACT_REGEX = Regex(
            """https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Returns a validated public `http://` or `https://` URI string or `null` if malformed,
         * containing userinfo credentials, Bidi/control characters, or pointing to local/LAN targets.
         */
        fun extractSafeHttpUrl(text: String): String? {
            if (text.isBlank()) return null
            val regexMatch = URL_EXTRACT_REGEX.find(text) ?: return null
            // Reject poisoned tokens where a non-ASCII Bidi/control char follows immediately inside the same URL token
            val nextIdx = regexMatch.range.last + 1
            if (nextIdx < text.length && !text[nextIdx].isWhitespace()) {
                val trailingCp = text.codePointAt(nextIdx)
                if (Character.isISOControl(trailingCp) || Character.getType(trailingCp) == Character.FORMAT.toInt()) {
                    return null
                }
            }
            val match = regexMatch.value.trim().trimEnd('.', ',', ';', ')', ']')
            if (match.length > MAX_URL_LENGTH) return null
            var i = 0
            while (i < match.length) {
                val cp = match.codePointAt(i)
                val type = Character.getType(cp)
                if (Character.isISOControl(cp) || type == Character.FORMAT.toInt()) return null
                i += Character.charCount(cp)
            }
            val uri = runCatching { URI(match) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            if (!uri.rawUserInfo.isNullOrBlank()) return null
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            if (EventExtractor.isLocalEndpoint("$scheme://$host")) return null
            return match
        }
    }

    private fun parseDateLines(dateStr: String): Pair<String, String> {
        val parsed = DeadlineTriage.parseTemporal(dateStr)
            ?: throw IllegalArgumentException("Unsupported date format: $dateStr")
        return if (!parsed.hasTime || parsed.localDateTime == null) {
            val start = dateOnlyFormatter.format(parsed.localDate)
            val end = dateOnlyFormatter.format(parsed.localDate.plusDays(1))
            Pair("DTSTART;VALUE=DATE:$start", "DTEND;VALUE=DATE:$end")
        } else {
            val start = dateTimeFormatter.format(parsed.localDateTime)
            val end = dateTimeFormatter.format(parsed.localDateTime.plusHours(1))
            Pair("DTSTART:$start", "DTEND:$end")
        }
    }

    fun escapeText(text: String): String {
        return text
            .replace("\u0000", "")
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\n", "\\n")
    }
}
