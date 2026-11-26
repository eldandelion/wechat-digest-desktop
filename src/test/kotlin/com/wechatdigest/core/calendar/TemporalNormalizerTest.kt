package com.wechatdigest.core.calendar

import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventCategory
import com.wechatdigest.core.model.EventPriority
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemporalNormalizerTest {

    // 2026-05-17T10:00:00Z (Sunday)
    private val sundayAnchor = 1779012000L
    private val utc = ZoneOffset.UTC
    private val icsGenerator = IcsCalendarGenerator()

    private fun assertValidIcsDate(isoDate: String?) {
        assertNotNull(isoDate, "Expected non-null resolved ISO date")
        val card = EventCard(
            id = "test-ics",
            title = "Test Event",
            summary = "Test summary",
            translatedSummary = "Test summary",
            category = EventCategory.DEADLINE,
            priority = EventPriority.NORMAL,
            eventDate = isoDate,
            originalSender = "Teacher",
            sourceRoomId = "room@chatroom",
            timestampSeconds = sundayAnchor
        )
        val ics = icsGenerator.generateIcs(card)
        assertTrue(ics.contains("BEGIN:VEVENT"))
    }

    @Test
    fun `normalizeExplicitDate validates strict ISO dates and rejects placeholders and invalid calendar dates`() {
        assertEquals("2026-06-15", TemporalNormalizer.normalizeExplicitDate("2026-06-15", sundayAnchor, utc))
        assertEquals("2026-06-15T09:00:00", TemporalNormalizer.normalizeExplicitDate("2026-06-15T09:00", sundayAnchor, utc))
        assertEquals("2026-06-15T09:30:00", TemporalNormalizer.normalizeExplicitDate("2026-06-15 09:30:00", sundayAnchor, utc))

        assertNull(TemporalNormalizer.normalizeExplicitDate(null, sundayAnchor, utc))
        assertNull(TemporalNormalizer.normalizeExplicitDate("", sundayAnchor, utc))
        assertNull(TemporalNormalizer.normalizeExplicitDate("TBD", sundayAnchor, utc))
        assertNull(TemporalNormalizer.normalizeExplicitDate("null", sundayAnchor, utc))
        assertNull(TemporalNormalizer.normalizeExplicitDate("N/A", sundayAnchor, utc))
        assertNull(TemporalNormalizer.normalizeExplicitDate("2026-02-30", sundayAnchor, utc))
        assertNull(TemporalNormalizer.normalizeExplicitDate("2026-13-01", sundayAnchor, utc))

        assertEquals("2026-11-26T10:00:00", TemporalNormalizer.normalizeExplicitDate("Nov 26 at 10:00", sundayAnchor, utc))
        assertEquals("2026-06-08T09:00:00", TemporalNormalizer.normalizeExplicitDate("6月8日 09:00", sundayAnchor, utc))
    }

    @Test
    fun `resolveEventDate handles all 12 golden scenario bilingual date formats and passes IcsCalendarGenerator`() {
        val cases = listOf(
            "Final project report submission closes Friday 17:00 on Canvas (Room 305 lab)." to "2026-05-22T17:00:00",
            "Lab safety inspection will be held on Wednesday 14:00 in Building C Room 204." to "2026-05-20T14:00:00",
            "All thesis applicants must submit printed copies on Nov 26 at 10:00 in Room 422-1." to "2026-11-26T10:00:00",
            "All thesis candidates must sign and upload the AI Use Declaration PDF to Canvas before 2026-06-05 23:59." to "2026-06-05T23:59:00",
            "Annual Graduate Academic Conference registration closes 2026-06-12 at Lecture Hall B." to "2026-06-12",
            "Eligibility requires all coursework credits cleared by June 1; Room 422-1 before 17:00." to "2026-06-01",
            "请所有研究生于周五下午16:00前将开题报告纸质版交至行政楼308室张老师处。" to "2026-05-22T16:00:00",
            "请全体硕士生于本周四15:00前往教务处201室领取毕业证封皮及离校手续单。" to "2026-05-21T15:00:00",
            "Midterm Finalized Seating: Building A Room 422-1; email accommodation form to ta@eval.edu before Friday 12:00." to "2026-05-22T12:00:00",
            "Physics Lab Midterm: Bring student ID and signed AI declaration to Room 105 on June 8 at 09:00." to "2026-06-08T09:00:00",
            "Course evaluation survey closes May 30 at 20:00 on Student Portal." to "2026-05-30T20:00:00",
            "Guest lecture on Distributed Systems starts Tuesday 10:00 in Auditorium A." to "2026-05-19T10:00:00",
            "Algorithms written exam on June 15 at 09:00 in Hall 101." to "2026-06-15T09:00:00",
            "Library Extended Hours: Main Reading Room stays open until 23:30 from June 1 to June 20." to "2026-06-01T23:30:00",
            "Summer Career Fair Registration closes May 28 at Student Union Room 202." to "2026-05-28",
            "Mandatory Lab Safety Quiz must be completed before May 29 at 18:00 on Safety Portal." to "2026-05-29T18:00:00"
        )

        for ((text, expected) in cases) {
            val actual = TemporalNormalizer.resolveEventDate(listOf(text), sundayAnchor, utc)
            assertEquals(expected, actual, "Mismatch for text: $text")
            assertValidIcsDate(actual)
        }
    }

    @Test
    fun `resolveEventDate handles Chinese relative days, weeks, AM PM, and year rollover`() {
        val mondayAnchor = Instant.parse("2026-05-18T08:00:00Z").epochSecond

        assertEquals("2026-05-19T15:30:00", TemporalNormalizer.resolveEventDate(listOf("明天下午3点半在报告厅开会"), mondayAnchor, utc))
        assertEquals("2026-05-18T19:00:00", TemporalNormalizer.resolveEventDate(listOf("今晚19:00提交"), mondayAnchor, utc))
        assertEquals("2026-05-25T14:00:00", TemporalNormalizer.resolveEventDate(listOf("下周一 14:00"), mondayAnchor, utc))
        assertEquals("2026-05-23T10:00:00", TemporalNormalizer.resolveEventDate(listOf("周六上午10点"), mondayAnchor, utc))
        assertEquals("2026-05-19T15:00:00", TemporalNormalizer.resolveEventDate(listOf("tomorrow 3pm"), mondayAnchor, utc))
        assertEquals("2026-05-26T14:00:00", TemporalNormalizer.resolveEventDate(listOf("next Tuesday 14:00"), mondayAnchor, utc))
        assertEquals("2026-11-26T10:30:00", TemporalNormalizer.resolveEventDate(listOf("Nov 26, 10:30 AM"), mondayAnchor, utc))

        // Dec -> Jan rollover
        val decAnchor = Instant.parse("2026-12-20T09:00:00Z").epochSecond
        assertEquals("2027-01-15T09:00:00", TemporalNormalizer.resolveEventDate(listOf("Spring registration opens Jan 15 at 09:00"), decAnchor, utc))
    }

    @Test
    fun `resolveEventDate ignores URLs, room codes, and source message timestamp headers`() {
        val withNoise = listOf(
            "[2026-05-17 06:57:50] Teacher_Si: See https://portal.example.edu/2026/01/02/item-1203 and Room 422-1 or Att 20-2."
        )
        assertNull(TemporalNormalizer.resolveEventDate(withNoise, sundayAnchor, utc))

        val withHeaderAndRealDate = listOf(
            "[2026-05-17 06:57:50] Teacher_Si: Submit to https://portal.example.edu/2026/01/02 before June 20 at 17:00."
        )
        assertEquals("2026-06-20T17:00:00", TemporalNormalizer.resolveEventDate(withHeaderAndRealDate, sundayAnchor, utc))
    }

    @Test
    fun `resolveEventDate combines separate DATE and TIME item candidates`() {
        val candidates = listOf(
            "Date: 2026-06-15",
            "Time: 09:00 - 11:00",
            "Room 101"
        )
        assertEquals("2026-06-15T09:00:00", TemporalNormalizer.resolveEventDate(candidates, sundayAnchor, utc))
    }
}
