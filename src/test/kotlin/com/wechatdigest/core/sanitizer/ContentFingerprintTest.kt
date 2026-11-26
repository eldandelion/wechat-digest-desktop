package com.wechatdigest.core.sanitizer

import com.wechatdigest.core.model.EventAttachment
import com.wechatdigest.core.model.WeChatMessage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContentFingerprintTest {

    @Test
    fun `identical notice forwarded across rooms with different headers and timestamps shares fingerprint`() {
        val roomASource = "[2026-11-20 09:15:00] Prof. Zhang [Owner]: @所有人 Please submit Assignment 3 PDF to Canvas before Friday 23:59 🎉"
        val roomBRaw = "[id:101] [2026-11-20 09:16:00] TA_Li: @All\nPlease submit Assignment 3 PDF to Canvas before Friday 23:59!!"

        val fpA = ContentFingerprint.compute(roomASource)
        val fpB = ContentFingerprint.compute(roomBRaw)

        assertTrue(fpA.isNotBlank())
        assertEquals(fpA, fpB)
    }

    @Test
    fun `strips quote context prefix cleanly`() {
        val direct = "[2026-11-20 10:00:00] TA: Exam moved to Hall B302 on Tuesday morning."
        val quoted = "[Context: Replying to Prof] [2026-11-20 10:05:00] Admin: Exam moved to Hall B302 on Tuesday morning."

        assertEquals(ContentFingerprint.compute(direct), ContentFingerprint.compute(quoted))
    }

    @Test
    fun `distinct attachment MD5s differentiate notices with same caption`() {
        val text = "Please review the attached timetable carefully."
        val fp1 = ContentFingerprint.compute(text, listOf("abc123"))
        val fp2 = ContentFingerprint.compute(text, listOf("def456"))

        assertNotEquals(fp1, fp2)
    }

    @Test
    fun `returns empty fingerprint for very short acknowledgments without attachments`() {
        assertEquals("", ContentFingerprint.compute("Received"))
        assertEquals("", ContentFingerprint.compute("考试取消"))
        assertTrue(
            ContentFingerprint.compute("See PDF", listOf("deadbeef")).isNotBlank()
        )
    }

    @Test
    fun `preserves status-carrying symbols to avoid false duplicate collapse`() {
        val approved = ContentFingerprint.compute("Friday Systems Seminar at Hall B302 ✅")
        val cancelled = ContentFingerprint.compute("Friday Systems Seminar at Hall B302 ❌")
        assertNotEquals(approved, cancelled)
    }

    @Test
    fun `computeForMessages combines message text and attachment md5s`() {
        val msg = WeChatMessage(
            localId = 1L,
            timestampSeconds = 1732000000L,
            senderId = "wxid_1",
            senderDisplayName = "Prof",
            rawContent = "",
            textContent = "Final Thesis Defense Schedule posted for all graduate students.",
            isZstdCompressed = false,
            attachments = listOf(EventAttachment(fileName = "schedule.pdf", md5 = "CAFEBABE"))
        )
        val expected = ContentFingerprint.compute(
            "Final Thesis Defense Schedule posted for all graduate students.",
            listOf("cafebabe")
        )
        assertEquals(expected, ContentFingerprint.computeForMessages(listOf(msg)))
    }
}
