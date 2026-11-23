package com.wechatdigest.core.calendar

import com.wechatdigest.core.llm.LlmJsonUtils
import com.wechatdigest.core.model.EventCard
import java.awt.Desktop
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Locale
import java.util.concurrent.TimeUnit

class CalendarLauncher(
    private val generator: IcsCalendarGenerator = IcsCalendarGenerator(),
    private val calendarDir: File = defaultCalendarDir()
) {

    init {
        calendarDir.mkdirs()
        setOwnerOnlyPermissions(calendarDir, isDirectory = true)
    }

    fun exportIcsFile(card: EventCard): File {
        val icsContent = generator.generateIcs(card)
        val safeId = card.id.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "event" }
        val file = File(calendarDir, "wechat_event_$safeId.ics")
        val canonicalRoot = calendarDir.canonicalFile
        val canonicalTarget = file.canonicalFile
        require(canonicalTarget.toPath().startsWith(canonicalRoot.toPath())) {
            "Refusing to write calendar export outside calendar directory"
        }
        require(!Files.isSymbolicLink(file.toPath())) {
            "Refusing to overwrite symbolic link calendar export"
        }
        file.writeText(icsContent, Charsets.UTF_8)
        setOwnerOnlyPermissions(file, isDirectory = false)
        return file
    }

    fun exportAndLaunch(card: EventCard): Result<File> {
        return try {
            val file = exportIcsFile(card)
            launchFile(file)
            Result.success(file)
        } catch (e: Exception) {
            System.err.println(
                "[CalendarLauncher] Failed to export or launch calendar: ${LlmJsonUtils.sanitizeLogMessage(e.message)}"
            )
            Result.failure(e)
        }
    }

    private fun launchFile(file: File) {
        val os = System.getProperty("os.name")?.lowercase(Locale.ROOT) ?: ""
        if (os.contains("mac")) {
            val openBin = File("/usr/bin/open").takeIf { it.canExecute() }?.absolutePath ?: "open"
            val proc = ProcessBuilder(openBin, file.canonicalPath)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            proc.outputStream.close()
            proc.waitFor(2, TimeUnit.SECONDS)
        } else if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(file)
        } else {
            System.err.println("[CalendarLauncher] Desktop open not supported on this platform: $os")
        }
    }

    private fun setOwnerOnlyPermissions(path: File, isDirectory: Boolean) {
        if (!path.exists()) return
        val perms = if (isDirectory) {
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            )
        } else {
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        }
        runCatching { Files.setPosixFilePermissions(path.toPath(), perms) }
    }

    companion object {
        fun defaultCalendarDir(): File {
            val userHome = System.getProperty("user.home") ?: "."
            return File(userHome, "Library/Application Support/WeChatDigest/calendars")
        }
    }
}
