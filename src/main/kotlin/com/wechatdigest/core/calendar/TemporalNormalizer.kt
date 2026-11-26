package com.wechatdigest.core.calendar

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Deterministic bilingual (English & Chinese) temporal normalizer anchored on message timestamp.
 * Resolves explicit, partial, and relative dates/times into strict ISO `YYYY-MM-DD` or
 * `YYYY-MM-DDTHH:mm:ss` strings compatible with [IcsCalendarGenerator].
 */
object TemporalNormalizer {

    private val isoDateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val isoDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)

    private val placeholderValues = setOf(
        "null", "none", "n/a", "na", "n.a.", "tbd", "pending", "unknown",
        "not specified", "unspecified", "待定", "无", "暂无", "未知"
    )

    private val urlRegex = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val emailRegex = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val chatLogPrefixRegex = Regex("""(?m)^(?:\[id:\d+\]\s*)?\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\]\s*[^:\n]{1,64}:\s*""")

    private val strictIsoDateRegex = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val strictIsoDateTimeRegex = Regex("""^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(?::\d{2})?(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?$""")

    private val explicitYmdRegex = Regex("""(?<![\d.])(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?![\d.])""")
    private val zhDateRegex = Regex("""(?:(\d{4})\s*年\s*)?(\d{1,2})\s*月\s*(\d{1,2})(?:\s*[-–—~至到]\s*(?:(?:\d{1,2})\s*月\s*)?(\d{1,2}))?\s*[日号]""")
    private val enMonthDayRegex = Regex(
        """\b(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\.?\s+(\d{1,2})(?:st|nd|rd|th)?(?:\s*[-–—~]\s*(\d{1,2})(?:st|nd|rd|th)?)?(?:,?\s*(\d{4}))?\b""",
        RegexOption.IGNORE_CASE
    )
    private val enDayMonthRegex = Regex(
        """\b(\d{1,2})(?:st|nd|rd|th)?\s+(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\.?(?:,?\s*(\d{4}))?\b""",
        RegexOption.IGNORE_CASE
    )

    private val zhRelativeDayRegex = Regex("""(大后天|后天|明天|明日|明早|明晚|今天|今日|今晚)""")
    private val zhWeekdayRegex = Regex("""(?:(下下周|下周|下星期|本周|这周|本星期|这星期|周|星期))([一二三四五六日天])""")
    private val enRelativeDayRegex = Regex("""\b(today|tonight|tomorrow)\b""", RegexOption.IGNORE_CASE)
    private val enWeekdayFullRegex = Regex(
        """\b(?:(next|this)\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""",
        RegexOption.IGNORE_CASE
    )
    private val enWeekdayAbbrevRegex = Regex(
        """\b(?:(next|this)\s+|(?:on|by|before|until)\s+)(mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)\.?\b""",
        RegexOption.IGNORE_CASE
    )

    private val enTimeColonRegex = Regex("""(?<![\d:])(\d{1,2}):(\d{2})(?::(\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?(?!\d)""", RegexOption.IGNORE_CASE)
    private val enTimeAmPmRegex = Regex("""\b(\d{1,2})\s*(a\.?m\.?|p\.?m\.?)\b""", RegexOption.IGNORE_CASE)
    private val zhTimeWithPeriodRegex = Regex("""(上午|早上|早晨|凌晨|中午|下午|晚上|晚间|今晚|明晚|今早|明早)\s*(\d{1,2})\s*(?:[点时:：]\s*(?:(\d{1,2})\s*分?|(半))?|[点时])""")
    private val zhTimePlainRegex = Regex("""(?<![\d.])(\d{1,2})\s*[点时]\s*(?:(\d{1,2})\s*分?|(半))?""")
    private val clauseBreakRegex = Regex("""[;；\n。]""")

    private val deadlineCueRegex = Regex("""(?:before|by|closes?|deadline|due|until|截至|截止|前)\s*$""", RegexOption.IGNORE_CASE)

    private data class DateCandidate(
        val startIndex: Int,
        val endIndex: Int,
        val date: LocalDate
    )

    /**
     * Validates or normalizes an explicit LLM-provided [rawDate] anchored at [anchorTimestampSeconds].
     * Rejects placeholders (`"TBD"`, `"null"`) and invalid calendar dates (`"2026-02-30"`).
     */
    fun normalizeExplicitDate(
        rawDate: String?,
        anchorTimestampSeconds: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String? {
        val cleaned = rawDate?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (cleaned.lowercase(Locale.ROOT) in placeholderValues) return null

        if (strictIsoDateRegex.matches(cleaned)) {
            val parsed = runCatching { LocalDate.parse(cleaned) }.getOrNull() ?: return null
            return isoDateFormatter.format(parsed)
        }

        if (strictIsoDateTimeRegex.matches(cleaned)) {
            val ldt = parseIsoDateTime(cleaned, zoneId) ?: return null
            return isoDateTimeFormatter.format(ldt)
        }

        val anchorDate = Instant.ofEpochSecond(anchorTimestampSeconds).atZone(zoneId).toLocalDate()
        return resolveSingleText(cleaned, anchorDate)
    }

    /**
     * Scans prioritized [candidateTexts] in order and returns the first deterministic anchored ISO
     * date/datetime (`YYYY-MM-DD` or `YYYY-MM-DDTHH:mm:ss`), or `null` if no date can be resolved.
     */
    fun resolveEventDate(
        candidateTexts: List<String>,
        anchorTimestampSeconds: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String? {
        val anchorDate = Instant.ofEpochSecond(anchorTimestampSeconds).atZone(zoneId).toLocalDate()
        var dateOnlyFallback: LocalDate? = null
        var standaloneTimeFallback: LocalTime? = null

        for (rawText in candidateTexts) {
            val sanitized = stripNonTemporalNoise(rawText)
            if (sanitized.isBlank()) continue

            val resolved = resolveSingleText(sanitized, anchorDate)
            if (resolved != null) {
                if ('T' in resolved) return resolved
                if (dateOnlyFallback == null) {
                    dateOnlyFallback = runCatching { LocalDate.parse(resolved) }.getOrNull()
                }
            } else if (standaloneTimeFallback == null) {
                standaloneTimeFallback = extractFirstTime(sanitized)
            }
        }

        return dateOnlyFallback?.let { date ->
            if (standaloneTimeFallback != null) {
                isoDateTimeFormatter.format(LocalDateTime.of(date, standaloneTimeFallback))
            } else {
                isoDateFormatter.format(date)
            }
        }
    }

    private fun resolveSingleText(rawText: String, anchorDate: LocalDate): String? {
        val text = stripNonTemporalNoise(rawText)
        if (text.isBlank()) return null

        val candidates = collectDateCandidates(text, anchorDate)
        if (candidates.isEmpty()) return null

        val chosen = chooseBestDateCandidate(text, candidates)
        val time = extractTimeNearCandidate(text, chosen, candidates)
        return if (time != null) {
            isoDateTimeFormatter.format(LocalDateTime.of(chosen.date, time))
        } else {
            isoDateFormatter.format(chosen.date)
        }
    }

    private fun stripNonTemporalNoise(raw: String): String {
        return raw
            .replace(chatLogPrefixRegex, "")
            .replace(urlRegex, " ")
            .replace(emailRegex, " ")
            .trim()
    }

    private fun parseIsoDateTime(cleaned: String, zoneId: ZoneId): LocalDateTime? {
        val normalized = cleaned.replace(' ', 'T')
        return runCatching {
            when {
                normalized.endsWith("Z", ignoreCase = true) ||
                    Regex("""[+-]\d{2}:?\d{2}$""").containsMatchIn(normalized) ->
                    OffsetDateTime.parse(normalized).atZoneSameInstant(zoneId).toLocalDateTime()
                normalized.length == 16 -> LocalDateTime.parse("$normalized:00")
                '.' in normalized -> LocalDateTime.parse(normalized.substringBefore('.'))
                else -> LocalDateTime.parse(normalized)
            }
        }.getOrNull()
    }

    private fun collectDateCandidates(text: String, anchorDate: LocalDate): List<DateCandidate> {
        val found = mutableListOf<DateCandidate>()

        for (m in explicitYmdRegex.findAll(text)) {
            val y = m.groupValues[1].toIntOrNull() ?: continue
            val mo = m.groupValues[2].toIntOrNull() ?: continue
            val d = m.groupValues[3].toIntOrNull() ?: continue
            val date = safeLocalDate(y, mo, d) ?: continue
            found.add(DateCandidate(m.range.first, m.range.last + 1, date))
        }

        for (m in zhDateRegex.findAll(text)) {
            val y = m.groupValues[1].toIntOrNull()
            val mo = m.groupValues[2].toIntOrNull() ?: continue
            val startDay = m.groupValues[3].toIntOrNull() ?: continue
            val endDay = m.groupValues[4].toIntOrNull()
            val chosenDay = if (endDay != null && hasDeadlineCueBefore(text, m.range.first)) endDay else startDay
            val date = resolveMonthDay(y, mo, chosenDay, anchorDate) ?: continue
            found.add(DateCandidate(m.range.first, m.range.last + 1, date))
        }

        for (m in enMonthDayRegex.findAll(text)) {
            val mo = parseEnglishMonth(m.groupValues[1]) ?: continue
            val startDay = m.groupValues[2].toIntOrNull() ?: continue
            val endDay = m.groupValues[3].toIntOrNull()
            val y = m.groupValues[4].toIntOrNull()
            val chosenDay = if (endDay != null && hasDeadlineCueBefore(text, m.range.first)) endDay else startDay
            val date = resolveMonthDay(y, mo, chosenDay, anchorDate) ?: continue
            found.add(DateCandidate(m.range.first, m.range.last + 1, date))
        }

        for (m in enDayMonthRegex.findAll(text)) {
            val d = m.groupValues[1].toIntOrNull() ?: continue
            val mo = parseEnglishMonth(m.groupValues[2]) ?: continue
            val y = m.groupValues[3].toIntOrNull()
            val date = resolveMonthDay(y, mo, d, anchorDate) ?: continue
            found.add(DateCandidate(m.range.first, m.range.last + 1, date))
        }

        for (m in zhRelativeDayRegex.findAll(text)) {
            val offset = when (m.groupValues[1]) {
                "今天", "今日", "今晚" -> 0L
                "明天", "明日", "明早", "明晚" -> 1L
                "后天" -> 2L
                "大后天" -> 3L
                else -> continue
            }
            found.add(DateCandidate(m.range.first, m.range.last + 1, anchorDate.plusDays(offset)))
        }

        for (m in zhWeekdayRegex.findAll(text)) {
            val prefix = m.groupValues[1]
            val dow = parseChineseDayOfWeek(m.groupValues[2]) ?: continue
            val resolved = resolveRelativeWeekday(anchorDate, dow, prefix)
            found.add(DateCandidate(m.range.first, m.range.last + 1, resolved))
        }

        for (m in enRelativeDayRegex.findAll(text)) {
            val offset = when (m.groupValues[1].lowercase(Locale.ROOT)) {
                "today", "tonight" -> 0L
                "tomorrow" -> 1L
                else -> continue
            }
            found.add(DateCandidate(m.range.first, m.range.last + 1, anchorDate.plusDays(offset)))
        }

        for (m in enWeekdayFullRegex.findAll(text)) {
            val modifier = m.groupValues[1].lowercase(Locale.ROOT)
            val dow = parseEnglishDayOfWeek(m.groupValues[2]) ?: continue
            val resolved = resolveEnglishWeekday(anchorDate, dow, modifier)
            found.add(DateCandidate(m.range.first, m.range.last + 1, resolved))
        }

        for (m in enWeekdayAbbrevRegex.findAll(text)) {
            val modifier = m.groupValues[1].lowercase(Locale.ROOT)
            val dow = parseEnglishDayOfWeek(m.groupValues[2]) ?: continue
            val resolved = resolveEnglishWeekday(anchorDate, dow, modifier)
            found.add(DateCandidate(m.range.first, m.range.last + 1, resolved))
        }

        // Deduplicate overlapping matches, preferring longer spans (e.g. "2026-06-15" or "next Monday")
        return found
            .sortedWith(compareBy<DateCandidate> { it.startIndex }.thenByDescending { it.endIndex - it.startIndex })
            .fold(mutableListOf()) { acc, candidate ->
                val overlaps = acc.any { existing ->
                    candidate.startIndex < existing.endIndex && candidate.endIndex > existing.startIndex
                }
                if (!overlaps) acc.add(candidate)
                acc
            }
    }

    private fun chooseBestDateCandidate(text: String, candidates: List<DateCandidate>): DateCandidate {
        if (candidates.size == 1) return candidates.first()
        val deadlineMatch = candidates.firstOrNull { c -> hasDeadlineCueBefore(text, c.startIndex) }
        return deadlineMatch ?: candidates.first()
    }

    private fun hasDeadlineCueBefore(text: String, index: Int): Boolean {
        val prefix = text.substring((index - 24).coerceAtLeast(0), index)
        return deadlineCueRegex.containsMatchIn(prefix)
    }

    private fun extractTimeNearCandidate(
        text: String,
        chosen: DateCandidate,
        allCandidates: List<DateCandidate>
    ): LocalTime? {
        val nextCandidateStart = allCandidates
            .filter { it.startIndex > chosen.endIndex }
            .minOfOrNull { it.startIndex }
            ?: text.length
        val afterWindowEnd = minOf(nextCandidateStart, chosen.endIndex + 40, text.length)
        val rawAfterSlice = text.substring(chosen.endIndex, afterWindowEnd)
        val afterBreak = clauseBreakRegex.find(rawAfterSlice)?.range?.first
        val afterSlice = if (afterBreak != null) rawAfterSlice.substring(0, afterBreak) else rawAfterSlice
        extractFirstTime(afterSlice)?.let { return it }

        val prevCandidateEnd = allCandidates
            .filter { it.endIndex <= chosen.startIndex }
            .maxOfOrNull { it.endIndex }
            ?: 0
        val beforeWindowStart = maxOf(prevCandidateEnd, chosen.startIndex - 24, 0)
        val rawBeforeSlice = text.substring(beforeWindowStart, chosen.startIndex)
        val beforeBreak = clauseBreakRegex.findAll(rawBeforeSlice).lastOrNull()?.range?.last
        val beforeSlice = if (beforeBreak != null) rawBeforeSlice.substring(beforeBreak + 1) else rawBeforeSlice
        return extractFirstTime(beforeSlice)
    }

    private fun extractFirstTime(slice: String): LocalTime? {
        val zhPeriodMatch = zhTimeWithPeriodRegex.find(slice)
        val enColonMatch = enTimeColonRegex.find(slice)
        val enAmPmMatch = enTimeAmPmRegex.find(slice)
        val zhPlainMatch = zhTimePlainRegex.find(slice)

        val candidates = listOfNotNull(
            zhPeriodMatch?.let { m ->
                parseZhPeriodTime(m.groupValues[1], m.groupValues[2], m.groupValues[3].ifBlank { m.groupValues[4] })
                    ?.let { m.range.first to it }
            },
            enColonMatch?.let { m ->
                parseEnTime(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])?.let { m.range.first to it }
            },
            enAmPmMatch?.let { m ->
                parseEnTime(m.groupValues[1], "0", "", m.groupValues[2])?.let { m.range.first to it }
            },
            zhPlainMatch?.let { m ->
                parseZhPeriodTime("", m.groupValues[1], m.groupValues[2].ifBlank { m.groupValues[3] })
                    ?.let { m.range.first to it }
            }
        )
        return candidates.minByOrNull { it.first }?.second
    }

    private fun parseEnTime(hourStr: String, minStr: String, secStr: String, amPmRaw: String): LocalTime? {
        val rawHour = hourStr.toIntOrNull() ?: return null
        val minute = minStr.toIntOrNull() ?: return null
        val second = if (secStr.isBlank()) 0 else (secStr.toIntOrNull() ?: return null)
        if (minute !in 0..59 || second !in 0..59) return null

        val amPm = amPmRaw.lowercase(Locale.ROOT).replace(".", "")
        val hour = when (amPm) {
            "am" -> {
                if (rawHour !in 1..12) return null
                if (rawHour == 12) 0 else rawHour
            }
            "pm" -> {
                if (rawHour !in 1..12) return null
                if (rawHour == 12) 12 else rawHour + 12
            }
            "" -> {
                if (rawHour !in 0..23) return null
                rawHour
            }
            else -> return null
        }
        return LocalTime.of(hour, minute, second)
    }

    private fun parseZhPeriodTime(period: String, hourStr: String, minOrHalfRaw: String): LocalTime? {
        val rawHour = hourStr.toIntOrNull() ?: return null
        val minute = when {
            minOrHalfRaw == "半" -> 30
            minOrHalfRaw.isBlank() -> 0
            else -> minOrHalfRaw.toIntOrNull() ?: return null
        }
        if (minute !in 0..59) return null

        val adjustedHour = when (period) {
            "下午", "晚上", "晚间", "今晚", "明晚" -> {
                if (rawHour in 1..11) rawHour + 12 else rawHour
            }
            "中午" -> {
                if (rawHour in 1..10) rawHour + 12 else rawHour
            }
            "凌晨" -> {
                if (rawHour == 12) 0 else rawHour
            }
            else -> rawHour
        }
        if (adjustedHour !in 0..23) return null
        return LocalTime.of(adjustedHour, minute, 0)
    }

    private fun resolveMonthDay(year: Int?, month: Int, day: Int, anchorDate: LocalDate): LocalDate? {
        if (year != null) {
            return safeLocalDate(year, month, day)
        }
        val currentYearDate = safeLocalDate(anchorDate.year, month, day)
        if (currentYearDate != null) {
            val daysAgo = ChronoUnit.DAYS.between(currentYearDate, anchorDate)
            return if (daysAgo > 180L) {
                safeLocalDate(anchorDate.year + 1, month, day)
            } else {
                currentYearDate
            }
        }
        // Handle Feb 29 when anchor year is non-leap
        return safeLocalDate(anchorDate.year + 1, month, day)
    }

    private fun safeLocalDate(year: Int, month: Int, day: Int): LocalDate? {
        if (year !in 1970..2100 || month !in 1..12 || day !in 1..31) return null
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    private fun resolveRelativeWeekday(anchorDate: LocalDate, targetDow: DayOfWeek, prefix: String): LocalDate {
        return when (prefix) {
            "下周", "下星期" -> {
                val nextMonday = anchorDate.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                nextMonday.plusDays((targetDow.value - 1).toLong())
            }
            "下下周" -> {
                val nextMonday = anchorDate.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(1)
                nextMonday.plusDays((targetDow.value - 1).toLong())
            }
            "本周", "这周", "本星期", "这星期" -> {
                if (anchorDate.dayOfWeek == DayOfWeek.SUNDAY) {
                    anchorDate.with(TemporalAdjusters.nextOrSame(targetDow))
                } else {
                    val thisMonday = anchorDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    thisMonday.plusDays((targetDow.value - 1).toLong())
                }
            }
            else -> anchorDate.with(TemporalAdjusters.nextOrSame(targetDow))
        }
    }

    private fun resolveEnglishWeekday(anchorDate: LocalDate, targetDow: DayOfWeek, modifier: String): LocalDate {
        return when (modifier) {
            "next" -> {
                val nextMonday = anchorDate.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                nextMonday.plusDays((targetDow.value - 1).toLong())
            }
            "this" -> {
                if (anchorDate.dayOfWeek == DayOfWeek.SUNDAY) {
                    anchorDate.with(TemporalAdjusters.nextOrSame(targetDow))
                } else {
                    anchorDate.with(TemporalAdjusters.nextOrSame(targetDow))
                }
            }
            else -> anchorDate.with(TemporalAdjusters.nextOrSame(targetDow))
        }
    }

    private fun parseChineseDayOfWeek(ch: String): DayOfWeek? = when (ch) {
        "一" -> DayOfWeek.MONDAY
        "二" -> DayOfWeek.TUESDAY
        "三" -> DayOfWeek.WEDNESDAY
        "四" -> DayOfWeek.THURSDAY
        "五" -> DayOfWeek.FRIDAY
        "六" -> DayOfWeek.SATURDAY
        "日", "天" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun parseEnglishDayOfWeek(token: String): DayOfWeek? = when (token.lowercase(Locale.ROOT).trimEnd('.')) {
        "monday", "mon" -> DayOfWeek.MONDAY
        "tuesday", "tue", "tues" -> DayOfWeek.TUESDAY
        "wednesday", "wed" -> DayOfWeek.WEDNESDAY
        "thursday", "thu", "thur", "thurs" -> DayOfWeek.THURSDAY
        "friday", "fri" -> DayOfWeek.FRIDAY
        "saturday", "sat" -> DayOfWeek.SATURDAY
        "sunday", "sun" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun parseEnglishMonth(token: String): Int? = when (token.lowercase(Locale.ROOT).trimEnd('.').take(3)) {
        "jan" -> 1
        "feb" -> 2
        "mar" -> 3
        "apr" -> 4
        "may" -> 5
        "jun" -> 6
        "jul" -> 7
        "aug" -> 8
        "sep" -> 9
        "oct" -> 10
        "nov" -> 11
        "dec" -> 12
        else -> null
    }
}
