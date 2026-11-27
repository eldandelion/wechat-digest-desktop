package com.wechatdigest.core.attachment

import java.awt.Desktop
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Opens safe downloaded document attachments in their default macOS application
 * or reveals any regular attachment file in Finder.
 */
class AttachmentLauncher {

    /**
     * Opens [file] in its default viewer after verifying canonical containment inside [allowedRoot]
     * and validating the extension against [SAFE_DOCUMENT_EXTENSIONS].
     */
    fun openFile(file: File, allowedRoot: File): Result<Unit> =
        openFile(file, allowedRoots = listOf(allowedRoot))

    /**
     * Opens [file] in its default viewer after verifying canonical containment inside any root in
     * non-empty [allowedRoots] and validating the extension against [SAFE_DOCUMENT_EXTENSIONS].
     */
    fun openFile(file: File, allowedRoots: List<File>): Result<Unit> {
        return runCatching {
            val canonical = resolveAndValidateFile(file, allowedRoots)
            val ext = canonical.extension.lowercase(Locale.ROOT)
            if (ext == "dat") {
                error("This WeChat image uses an obfuscated .dat cache file. Use Reveal in Finder or read the AI summary.")
            }
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
     * Reveals [file] in Finder after verifying canonical containment inside [allowedRoot].
     */
    fun revealInFinder(file: File, allowedRoot: File): Result<Unit> =
        revealInFinder(file, allowedRoots = listOf(allowedRoot))

    /**
     * Reveals [file] in Finder after verifying canonical containment inside any root in non-empty [allowedRoots].
     */
    fun revealInFinder(file: File, allowedRoots: List<File>): Result<Unit> {
        return runCatching {
            val canonical = resolveAndValidateFile(file, allowedRoots)
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

    private fun resolveAndValidateFile(file: File, allowedRoots: List<File>): File {
        require(allowedRoots.isNotEmpty()) {
            "WeChat attachments folder could not be verified."
        }
        require(!Files.isSymbolicLink(file.toPath())) {
            "Refusing to access symlinked attachment file"
        }
        val canonical = file.canonicalFile
        require(canonical.exists() && canonical.isFile) {
            "Attachment file not found on disk: ${file.name}"
        }
        val canonicalPath = canonical.toPath()
        val insideAnyRoot = allowedRoots.any { root ->
            canonicalPath.startsWith(root.canonicalFile.toPath())
        }
        require(insideAnyRoot) {
            "Refusing to access file outside WeChat attachments folder"
        }
        return canonical
    }

    private inline fun launchMacOpen(vararg args: String, fallback: () -> Unit) {
        val os = System.getProperty("os.name")?.lowercase(Locale.ROOT) ?: ""
        if (os.contains("mac")) {
            val openBinary = File("/usr/bin/open")
            require(openBinary.canExecute()) {
                "Missing /usr/bin/open required to launch attachment on macOS"
            }
            val proc = ProcessBuilder(openBinary.absolutePath, *args).apply {
                redirectOutput(ProcessBuilder.Redirect.DISCARD)
                redirectErrorStream(false)
            }.start()
            try {
                proc.outputStream.close()
                if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    error("macOS open timed out")
                }
                if (proc.exitValue() != 0) {
                    val buf = CharArray(512)
                    val errMsg = proc.errorStream.bufferedReader().use { reader ->
                        val n = reader.read(buf)
                        if (n > 0) String(buf, 0, n).trim() else ""
                    }
                    error(errMsg.ifBlank { "macOS open failed with exit code ${proc.exitValue()}" })
                }
            } finally {
                runCatching { proc.errorStream.close() }
            }
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
