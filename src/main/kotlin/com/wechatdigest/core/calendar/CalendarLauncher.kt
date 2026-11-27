package com.wechatdigest.core.calendar

import com.wechatdigest.core.llm.LlmJsonUtils
import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.notification.AppleScriptRunner
import java.awt.Desktop
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale
import java.util.concurrent.TimeUnit

class CalendarLauncher(
    private val generator: IcsCalendarGenerator = IcsCalendarGenerator(),
    private val calendarDir: File = defaultCalendarDir(),
    private val remindersRunner: (String) -> Unit = { script -> AppleScriptRunner.runScriptViaStdin(script, timeoutSeconds = 5L) }
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
        val tmpPath = runCatching {
            Files.createTempFile(
                canonicalRoot.toPath(),
                "wechat_event_",
                ".ics.tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            )
        }.getOrElse {
            Files.createTempFile(canonicalRoot.toPath(), "wechat_event_", ".ics.tmp")
        }
        try {
            Files.writeString(tmpPath, icsContent, Charsets.UTF_8)
            setOwnerOnlyPermissions(tmpPath.toFile(), isDirectory = false)
            runCatching {
                Files.move(tmpPath, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(tmpPath, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            setOwnerOnlyPermissions(file, isDirectory = false)
        } finally {
            runCatching { Files.deleteIfExists(tmpPath) }
        }
        return file
    }

    /** Removes previously exported `.ics` artifacts inside [calendarDir] without following symlinks. */
    fun clearExportedFiles() {
        val canonicalRoot = runCatching { calendarDir.canonicalFile }.getOrNull() ?: return
        if (Files.isSymbolicLink(calendarDir.toPath()) || !canonicalRoot.isDirectory) return
        canonicalRoot.listFiles()?.forEach { child ->
            val path = child.toPath()
            if (!Files.isSymbolicLink(path) &&
                child.isFile &&
                child.name.endsWith(".ics", ignoreCase = true) &&
                child.canonicalFile.toPath().startsWith(canonicalRoot.toPath())
            ) {
                runCatching { Files.deleteIfExists(path) }
            }
        }
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

    /** Creates an Apple Reminders item on macOS for [card] using safely escaped AppleScript. */
    fun exportToReminders(card: EventCard): Result<Unit> {
        return try {
            val script = buildRemindersAppleScript(card)
            remindersRunner(script)
            Result.success(Unit)
        } catch (e: Exception) {
            System.err.println(
                "[CalendarLauncher] Failed to export to Reminders: ${LlmJsonUtils.sanitizeLogMessage(e.message)}"
            )
            Result.failure(e)
        }
    }

    internal fun buildRemindersAppleScript(card: EventCard): String {
        val cleanName = AppleScriptRunner.escapeString(
            AppleScriptRunner.truncateCodePointSafe(card.title.ifBlank { "WeChat Notice" }, 200),
            replaceNewlinesWithSpace = true
        )
        val body = buildString {
            append(card.translatedSummary.ifBlank { card.summary })
            card.eventDate?.takeIf { it.isNotBlank() }?.let { append("\nDue: $it") }
            if (card.originalSender.isNotBlank()) append("\nFrom: ${card.originalSender}")
        }
        val cleanBody = AppleScriptRunner.escapeString(
            AppleScriptRunner.truncateCodePointSafe(body, 1_200),
            replaceNewlinesWithSpace = false
        )
        return """tell application "Reminders" to make new reminder with properties {name:"$cleanName", body:"$cleanBody"}"""
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
            try {
                val finished = proc.waitFor(2, TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    throw IllegalStateException("Timed out opening calendar export")
                }
                val exit = proc.exitValue()
                if (exit != 0) {
                    throw IllegalStateException("/usr/bin/open exited with $exit")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted opening calendar export", e)
            } finally {
                if (proc.isAlive) proc.destroyForcibly()
            }
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
