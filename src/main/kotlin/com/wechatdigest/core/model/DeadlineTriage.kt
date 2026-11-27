package com.wechatdigest.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Actionability urgency bucket derived from an [EventCard.eventDate]. */
enum class DeadlineUrgency {
    OVERDUE,
    DUE_TODAY,
    UPCOMING,
    FUTURE
}

/** Evaluated deadline classification used for `DEADLINE_SOONEST` sorting and urgency pills. */
data class DeadlineInfo(
    val urgency: DeadlineUrgency,
    val label: String,
    val sortEpochSeconds: Long
)

/**
 * Pure deadline classification, chronological sort key calculation, and relative sync time formatting.
 */
object DeadlineTriage {

    private val shortDateFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)

    data class ParsedTemporal(
        val localDate: LocalDate,
        val localDateTime: LocalDateTime?,
        val epochSeconds: Long,
        val hasTime: Boolean
    )

    /**
     * Parses ISO date/datetime strings (including fractional seconds and timezone offsets) into [ParsedTemporal],
     * ordering `OffsetDateTime` before `LocalDateTime` so offsets are never stripped prematurely.
     */
    fun parseTemporal(rawDate: String?, zoneId: ZoneId = ZoneId.systemDefault()): ParsedTemporal? {
        val trimmed = rawDate?.trim()?.take(64)?.takeIf { it.isNotBlank() } ?: return null
        runCatching { LocalDate.parse(trimmed) }.getOrNull()?.let { date ->
            val endOfDay = date.atTime(23, 59, 59).atZone(zoneId).toEpochSecond()
            return ParsedTemporal(date, null, endOfDay, hasTime = false)
        }
        val normalized = trimmed.replace(' ', 'T')
        runCatching { OffsetDateTime.parse(normalized).atZoneSameInstant(zoneId) }.getOrNull()?.let { zoned ->
            return ParsedTemporal(zoned.toLocalDate(), zoned.toLocalDateTime(), zoned.toEpochSecond(), hasTime = true)
        }
        val ldt = runCatching {
            when {
                normalized.length == 16 -> LocalDateTime.parse("$normalized:00")
                else -> LocalDateTime.parse(normalized)
            }
        }.getOrNull()
        if (ldt != null) {
            return ParsedTemporal(ldt.toLocalDate(), ldt, ldt.atZone(zoneId).toEpochSecond(), hasTime = true)
        }
        val prefix = trimmed.take(10)
        return runCatching { LocalDate.parse(prefix) }.getOrNull()?.let { date ->
            ParsedTemporal(date, null, date.atTime(23, 59, 59).atZone(zoneId).toEpochSecond(), hasTime = false)
        }
    }

    /** Strictly validates ISO-8601 date/datetime strings (including fractional seconds and offsets) without trailing-text fallback. */
    fun isValidIsoTemporal(rawDate: String?): Boolean {
        val trimmed = rawDate?.trim()?.take(64)?.takeIf { it.isNotBlank() } ?: return false
        if (runCatching { LocalDate.parse(trimmed) }.isSuccess) return true
        val normalized = trimmed.replace(' ', 'T')
        if (runCatching { OffsetDateTime.parse(normalized) }.isSuccess) return true
        return runCatching {
            if (normalized.length == 16) LocalDateTime.parse("$normalized:00") else LocalDateTime.parse(normalized)
        }.isSuccess
    }

    /** Evaluates an [EventCard]'s [EventCard.eventDate] relative to [nowInstant] in [zoneId]. */
    fun evaluate(
        eventDate: String?,
        nowInstant: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): DeadlineInfo? {
        val parsed = parseTemporal(eventDate, zoneId) ?: return null
        val today = nowInstant.atZone(zoneId).toLocalDate()
        val daysDelta = ChronoUnit.DAYS.between(today, parsed.localDate)
        val isPastMoment = if (parsed.hasTime) {
            parsed.epochSeconds < nowInstant.epochSecond
        } else {
            daysDelta < 0L
        }

        val urgency = when {
            isPastMoment -> DeadlineUrgency.OVERDUE
            daysDelta == 0L -> DeadlineUrgency.DUE_TODAY
            daysDelta in 1L..3L -> DeadlineUrgency.UPCOMING
            else -> DeadlineUrgency.FUTURE
        }

        val label = when {
            urgency == DeadlineUrgency.OVERDUE -> "Overdue"
            urgency == DeadlineUrgency.DUE_TODAY -> "Due Today"
            daysDelta == 1L -> "Tomorrow"
            daysDelta in 2L..3L -> "In ${daysDelta}d"
            else -> shortDateFormatter.format(parsed.localDate)
        }

        return DeadlineInfo(
            urgency = urgency,
            label = label,
            sortEpochSeconds = parsed.epochSeconds
        )
    }

    /** Returns an ascending sort timestamp for `DEADLINE_SOONEST`, pushing undated or completed/cancelled cards later. */
    fun deadlineSortEpoch(card: EventCard, zoneId: ZoneId = ZoneId.systemDefault()): Long {
        if (card.status == EventCardStatus.CANCELLED || card.isCompleted) return Long.MAX_VALUE - 1
        return parseTemporal(card.eventDate, zoneId)?.epochSeconds ?: Long.MAX_VALUE
    }

    /** Formats a Unix epoch seconds checkpoint timestamp into a concise relative string. */
    fun formatRelativeSyncTime(
        syncEpochSeconds: Long,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000
    ): String {
        if (syncEpochSeconds <= 0L) return "Never synced"
        val delta = (nowEpochSeconds - syncEpochSeconds).coerceAtLeast(0L)
        return when {
            delta < 60L -> "Synced just now"
            delta < 3600L -> "Synced ${delta / 60L}m ago"
            delta < 86_400L -> "Synced ${delta / 3600L}h ago"
            else -> "Synced ${delta / 86_400L}d ago"
        }
    }
}
