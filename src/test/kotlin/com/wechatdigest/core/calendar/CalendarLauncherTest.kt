package com.wechatdigest.core.calendar

import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventCategory
import com.wechatdigest.core.model.EventPriority
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalendarLauncherTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `exportIcsFile writes valid ics file to target directory`() {
        val launcher = CalendarLauncher(calendarDir = tempDir)
        val card = EventCard(
            id = "launcher-test",
            title = "Registration Deadline",
            summary = "Register for classes",
            translatedSummary = "课程注册截止",
            category = EventCategory.DEADLINE,
            priority = EventPriority.IMPORTANT,
            eventDate = "2026-11-20",
            originalSender = "Registrar",
            sourceRoomId = "school@chatroom",
            timestampSeconds = 1731000000L
        )

        val file = launcher.exportIcsFile(card)
        assertTrue(file.exists())
        assertEquals("wechat_event_launcher-test.ics", file.name)
        val content = file.readText()
        assertTrue(content.contains("BEGIN:VCALENDAR"))
        assertTrue(content.contains("Registration Deadline"))
    }

    @Test
    fun `exportAndLaunch returns failure when eventDate is null`() {
        val launcher = CalendarLauncher(calendarDir = tempDir)
        val card = EventCard(
            id = "no-date",
            title = "No Date Notice",
            summary = "Summary",
            translatedSummary = "Summary",
            category = EventCategory.NOTICE,
            priority = EventPriority.LOW,
            eventDate = null,
            originalSender = "Sender",
            sourceRoomId = "room@chatroom",
            timestampSeconds = 1731000000L
        )

        val result = launcher.exportAndLaunch(card)
        assertTrue(result.isFailure)
    }
}
