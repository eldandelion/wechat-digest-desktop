package com.wechatdigest.core.calendar

import com.wechatdigest.core.model.EventCard
import java.awt.Desktop
import java.io.File

class CalendarLauncher(
    private val generator: IcsCalendarGenerator = IcsCalendarGenerator(),
    private val calendarDir: File = defaultCalendarDir()
) {

    init {
        calendarDir.mkdirs()
    }

    fun exportIcsFile(card: EventCard): File {
        val icsContent = generator.generateIcs(card)
        val file = File(calendarDir, "wechat_event_${card.id}.ics")
        file.writeText(icsContent, Charsets.UTF_8)
        return file
    }

    fun exportAndLaunch(card: EventCard): Result<File> {
        return try {
            val file = exportIcsFile(card)
            launchFile(file)
            Result.success(file)
        } catch (e: Exception) {
            System.err.println("[CalendarLauncher] Failed to export or launch calendar: ${e.message}")
            Result.failure(e)
        }
    }

    private fun launchFile(file: File) {
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        if (os.contains("mac")) {
            // macOS native open uses LaunchServices to open in Calendar.app
            ProcessBuilder("open", file.absolutePath).start()
        } else if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(file)
        } else {
            System.err.println("[CalendarLauncher] Desktop open not supported on this platform: $os")
        }
    }

    companion object {
        fun defaultCalendarDir(): File {
            val userHome = System.getProperty("user.home") ?: "."
            return File(userHome, "Library/Application Support/WeChatDigest/calendars")
        }
    }
}
