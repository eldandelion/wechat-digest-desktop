package com.wechatdigest.core.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceMessageParserTest {

    @Test
    fun `parses timestamped bubbles including owner tags and multi-line bodies`() {
        val transcript = """
            [2026-11-20 09:00:00] Prof. Wang [Owner]: Final exam schedule update:
            Please arrive 15 minutes early at Room B302.
            Bring student ID and calculator.
            [2026-11-20 09:05:12] TA_Chen: Seat assignments will be posted tomorrow morning.
        """.trimIndent()

        val bubbles = SourceMessageParser.parse(transcript)
        assertEquals(2, bubbles.size)

        assertEquals("2026-11-20 09:00:00", bubbles[0].timestamp)
        assertEquals("Prof. Wang", bubbles[0].sender)
        assertTrue(bubbles[0].isOwner)
        assertTrue(bubbles[0].body.contains("Room B302."))
        assertTrue(bubbles[0].body.contains("Bring student ID"))

        assertEquals("TA_Chen", bubbles[1].sender)
        assertEquals(false, bubbles[1].isOwner)
        assertEquals("Seat assignments will be posted tomorrow morning.", bubbles[1].body)
    }

    @Test
    fun `falls back cleanly to single bubble for unattributed text`() {
        val legacy = "Plain notice line 1\nPlain notice line 2"
        val bubbles = SourceMessageParser.parse(legacy)
        assertEquals(1, bubbles.size)
        assertEquals("", bubbles[0].sender)
        assertEquals(legacy, bubbles[0].body)
    }

    @Test
    fun `findMatchingBubbleIndex highlights bubble containing matching entity tokens`() {
        val bubbles = SourceMessageParser.parse(
            """
            [2026-11-20 09:00:00] Admin: Submit forms before 2026-12-01.
            [2026-11-20 09:02:00] TA: Oral defense held at Innovation Building Room 405 at 14:30.
            """.trimIndent()
        )
        assertEquals(1, SourceMessageParser.findMatchingBubbleIndex(bubbles, "Venue: Room 405 at 14:30"))
        assertEquals(0, SourceMessageParser.findMatchingBubbleIndex(bubbles, "Deadline: 2026-12-01"))
        assertNull(SourceMessageParser.findMatchingBubbleIndex(bubbles, "Completely unrelated cafeteria menu"))
    }

    @Test
    fun `handles empty input, CRLF line endings, and CJK bigram matching cleanly`() {
        assertTrue(SourceMessageParser.parse("   ").isEmpty())
        val crlf = "[2026-11-20 09:00:00] Admin: 校园招聘会通知\r\n[2026-11-20 09:01:00] Teacher: 研究生学位论文答辩安排在图书馆报告厅。"
        val bubbles = SourceMessageParser.parse(crlf)
        assertEquals(2, bubbles.size)
        assertEquals(1, SourceMessageParser.findMatchingBubbleIndex(bubbles, "答辩地点：图书馆"))
    }
}
