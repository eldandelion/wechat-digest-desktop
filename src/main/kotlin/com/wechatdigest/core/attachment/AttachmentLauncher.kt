package com.wechatdigest.core.attachment

import java.awt.Desktop
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Opens safe downloaded document attachments in their default macOS application
 * or reveals any regular attachment file in Finder.
 */
class AttachmentLauncher {

    /**
     * Opens [file] in its default viewer after verifying canonical containment inside [allowedRoot]
     * (when provided) and validating the extension against [SAFE_DOCUMENT_EXTENSIONS].
     */
    fun openFile(file: File, allowedRoot: File? = null): Result<Unit> {
        return runCatching {
            val canonical = resolveAndValidateFile(file, allowedRoot)
            val ext = canonical.extension.lowercase(Locale.ROOT)
            require(ext in SAFE_DOCUMENT_EXTENSIONS) {
                "Opening '.${ext.ifBlank { "unknown" }}' files directly is blocked for safety. Use Reveal in Finder to inspect it."
            }
            launchMacOpen(canonical.absolutePath) {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(canonical)
                } else {
                    error("Desktop open is not supported on this platform")
                }
            }
        }
    }

    /**
     * Reveals [file] in Finder (or opens its parent folder on non-macOS platforms) after verifying
     * canonical containment inside [allowedRoot] when provided.
     */
    fun revealInFinder(file: File, allowedRoot: File? = null): Result<Unit> {
        return runCatching {
            val canonical = resolveAndValidateFile(file, allowedRoot)
            launchMacOpen("-R", canonical.absolutePath) {
                val targetDir = canonical.parentFile
                require(targetDir != null && targetDir.exists()) {
                    "Parent directory not found for attachment"
                }
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(targetDir)
                } else {
                    error("Reveal in file manager is not supported on this platform")
                }
            }
        }
    }

    private fun resolveAndValidateFile(file: File, allowedRoot: File?): File {
        val canonical = file.canonicalFile
        require(canonical.exists() && canonical.isFile) {
            "Attachment file not found on disk: ${file.name}"
        }
        if (allowedRoot != null) {
            val canonicalRoot = allowedRoot.canonicalFile
            require(canonical.toPath().startsWith(canonicalRoot.toPath())) {
                "Refusing to access file outside WeChat attachments folder"
            }
        }
        return canonical
    }

    private inline fun launchMacOpen(vararg args: String, fallback: () -> Unit) {
        val os = System.getProperty("os.name")?.lowercase(Locale.ROOT) ?: ""
        if (os.contains("mac")) {
            val openBinary = File("/usr/bin/open").takeIf { it.canExecute() }?.absolutePath ?: "open"
            val proc = ProcessBuilder(openBinary, *args).apply {
                redirectOutput(ProcessBuilder.Redirect.DISCARD)
                redirectErrorStream(false)
            }.start()
            proc.outputStream.close()
            if (proc.waitFor(3, TimeUnit.SECONDS) && proc.exitValue() != 0) {
                val errMsg = proc.errorStream.bufferedReader().use { it.readText() }.trim()
                error(errMsg.ifBlank { "macOS open failed with exit code ${proc.exitValue()}" })
            }
            runCatching { proc.errorStream.close() }
        } else {
            fallback()
        }
    }

    companion object {
        /** Allowlist of non-executable document and media extensions permitted for direct launch. */
        val SAFE_DOCUMENT_EXTENSIONS: Set<String> = setOf(
            "pdf", "xlsx", "xls", "docx", "doc", "pptx", "ppt",
            "txt", "csv", "tsv", "md", "rtf",
            "png", "jpg", "jpeg", "gif", "webp", "heic",
            "mp3", "m4a", "wav", "mp4", "mov"
        )
    }
}
