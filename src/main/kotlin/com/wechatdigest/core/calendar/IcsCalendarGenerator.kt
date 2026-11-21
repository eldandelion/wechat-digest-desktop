package com.wechatdigest.core.calendar

import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventPriority
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
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
        val uid = "${card.id}@wechatdigest"
        val rfcPriority = when (card.priority) {
            EventPriority.CRITICAL -> 1
            EventPriority.IMPORTANT -> 3
            EventPriority.NORMAL -> 5
            EventPriority.LOW -> 9
        }

        val descriptionText = buildString {
            if (card.translatedSummary.isNotBlank()) {
                append(card.translatedSummary)
            } else {
                append(card.summary)
            }
            if (card.summary.isNotBlank() && card.summary != card.translatedSummary) {
                append("\n\nOriginal Notice:\n")
                append(card.summary)
            }
            if (card.originalSender.isNotBlank()) {
                append("\nSender: ${card.originalSender}")
            }
        }.trim()

        return buildString {
            append("BEGIN:VCALENDAR\r\n")
            append("VERSION:2.0\r\n")
            append("PRODID:-//WeChat Digest//Desktop Calendar Exporter//EN\r\n")
            append("CALSCALE:GREGORIAN\r\n")
            append("METHOD:PUBLISH\r\n")
            append("BEGIN:VEVENT\r\n")
            append("UID:$uid\r\n")
            append("DTSTAMP:$nowStamp\r\n")
            append("$startLine\r\n")
            append("$endLine\r\n")
            append("SUMMARY:${escapeText(card.title)}\r\n")
            append("DESCRIPTION:${escapeText(descriptionText)}\r\n")
            append("CATEGORIES:${card.category.name}\r\n")
            append("PRIORITY:$rfcPriority\r\n")
            append("STATUS:CONFIRMED\r\n")
            append("END:VEVENT\r\n")
            append("END:VCALENDAR\r\n")
        }
    }

    private fun parseDateLines(dateStr: String): Pair<String, String> {
        val cleaned = dateStr.trim()
        // Check for ISO Date Only: YYYY-MM-DD
        if (cleaned.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))) {
            val localDate = LocalDate.parse(cleaned)
            val start = dateOnlyFormatter.format(localDate)
            val end = dateOnlyFormatter.format(localDate.plusDays(1))
            return Pair("DTSTART;VALUE=DATE:$start", "DTEND;VALUE=DATE:$end")
        }

        // Try DateTime parsing
        val normalized = cleaned.replace(" ", "T")
        return try {
            val localDateTime = if (normalized.length == 16) {
                // e.g. YYYY-MM-DDTHH:mm
                LocalDateTime.parse("${normalized}:00")
            } else if (normalized.contains(".")) {
                // with millis
                LocalDateTime.parse(normalized.substringBefore("."))
            } else {
                LocalDateTime.parse(normalized)
            }
            val start = dateTimeFormatter.format(localDateTime)
            val end = dateTimeFormatter.format(localDateTime.plusHours(1))
            Pair("DTSTART:$start", "DTEND:$end")
        } catch (e: Exception) {
            // Fallback: try extracting the first 10 characters as a date
            if (cleaned.length >= 10 && cleaned.substring(0, 10).matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))) {
                val localDate = LocalDate.parse(cleaned.substring(0, 10))
                val start = dateOnlyFormatter.format(localDate)
                val end = dateOnlyFormatter.format(localDate.plusDays(1))
                Pair("DTSTART;VALUE=DATE:$start", "DTEND;VALUE=DATE:$end")
            } else {
                throw IllegalArgumentException("Unsupported date format: $dateStr")
            }
        }
    }

    fun escapeText(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\r\n", "\n")
            .replace("\n", "\\n")
    }
}
