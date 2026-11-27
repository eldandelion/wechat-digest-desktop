package com.wechatdigest.core.attachment

import com.wechatdigest.core.llm.ExtractionPrompts
import com.wechatdigest.core.llm.LlmJsonUtils
import com.wechatdigest.core.llm.local.LocalModelPaths
import com.wechatdigest.core.llm.local.sha256HexEqualsConstantTime
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Structured JSON output decoded from the native Apple Vision CLI (`text` + deduplicated `qrPayloads`). */
@Serializable
data class VisionOcrOutput(
    val text: String = "",
    val qrPayloads: List<String> = emptyList()
)

/** Subprocess exit code and bounded stdout/stderr streams captured from invoking `vision-ocr`. */
data class VisionProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

/**
 * Local macOS Apple Vision OCR + QR detector backed by the bundled Swift `vision-ocr` CLI helper.
 * Validates SHA-256 integrity, enforces symlink-free owner-only staging, bounds stdout/runtime,
 * and falls back gracefully when unavailable (e.g. non-macOS environments).
 */
class LocalVisionOcrService(
    private val binDir: File = File(LocalModelPaths.defaultBaseDir(), "bin"),
    private val isMacOs: () -> Boolean = ::defaultIsMacOs,
    private val customBinaryResolver: (() -> File?)? = null,
    private val processRunner: (List<String>, Long) -> VisionProcessResult = ::defaultProcessRunner
) {

    private val cachedBinary = AtomicReference<File?>(null)
    private val installLock = Any()

    /**
     * Returns `true` when the macOS `vision-ocr` executable is installed, verified, and runnable.
     */
    fun isAvailable(): Boolean {
        if (!isMacOs()) return false
        return resolveVerifiedBinary() != null
    }

    /**
     * Executes single-pass text OCR (`zh-Hans` + `en-US`) and QR detection against [imageFile].
     * Returns `null` when unavailable, when [imageFile] fails safety checks, or on helper failure.
     */
    fun recognize(imageFile: File, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): VisionOcrOutput? {
        if (!isMacOs()) return null
        if (!isSafeInputImageFile(imageFile)) return null
        val binary = resolveVerifiedBinary() ?: return null

        val result = try {
            processRunner(listOf(binary.absolutePath, "--json", imageFile.canonicalPath), timeoutSeconds)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (e: Exception) {
            System.err.println(
                "[LocalVisionOcrService] Helper execution failed: ${LlmJsonUtils.sanitizeLogMessage(e.message)}"
            )
            return null
        }

        if (result.exitCode != 0) {
            if (result.stderr.isNotBlank()) {
                System.err.println(
                    "[LocalVisionOcrService] Helper exited ${result.exitCode}: ${LlmJsonUtils.sanitizeLogMessage(result.stderr)}"
                )
            }
            return null
        }
        return parseJsonOutput(result.stdout)
    }

    internal fun parseJsonOutput(rawStdout: String): VisionOcrOutput? {
        val trimmed = rawStdout.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val decoded = LlmJsonUtils.sharedJson.decodeFromString<VisionOcrOutput>(trimmed)
            VisionOcrOutput(
                text = ExtractionPrompts.sanitizeTranscriptForPrompt(decoded.text).trim(),
                qrPayloads = decoded.qrPayloads
                    .mapNotNull { payload ->
                        ExtractionPrompts.sanitizeInline(payload, MAX_QR_PAYLOAD_CHARS)
                            .takeIf { it.isNotBlank() }
                    }
                    .distinct()
                    .take(MAX_QR_PAYLOADS)
            )
        }.onFailure { err ->
            System.err.println(
                "[LocalVisionOcrService] Malformed helper JSON: ${LlmJsonUtils.sanitizeLogMessage(err.message)}"
            )
        }.getOrNull()
    }

    /**
     * Formats OCR text and detected QR payloads into a compact bounded preview block strictly within
     * [maxChars] characters, suitable for LLM `<attachment_content>` prompts.
     */
    fun formatAsPreview(output: VisionOcrOutput, maxChars: Int = AttachmentContentExtractor.MAX_PREVIEW_CHARS): String? {
        if (maxChars <= 0) return null
        val sb = StringBuilder()
        for (qr in output.qrPayloads.take(MAX_QR_PAYLOADS)) {
            val safeQr = ExtractionPrompts.sanitizeInline(qr, MAX_QR_PAYLOAD_CHARS)
            if (safeQr.isNotBlank()) {
                sb.append("[QR Code]: ").append(safeQr).append('\n')
            }
        }
        val cleanText = ExtractionPrompts.sanitizeTranscriptForPrompt(output.text).trim()
        if (cleanText.isNotEmpty()) {
            sb.append(cleanText)
        }
        val combined = sb.toString().trim()
        if (combined.isEmpty()) return null
        if (combined.length <= maxChars) return combined
        val suffix = "\n... (truncated)"
        return if (maxChars <= suffix.length) {
            combined.take(maxChars)
        } else {
            combined.take(maxChars - suffix.length).trimEnd() + suffix
        }
    }

    private fun resolveVerifiedBinary(): File? {
        customBinaryResolver?.let { resolver ->
            return resolver()?.takeIf { isVerifiedCustomBinary(it) }
        }
        return runCatching {
            val expectedSha = loadExpectedSha256() ?: return null
            cachedBinary.get()?.let { existing ->
                if (!hasSymlinkInChain(binDir) &&
                    isVerifiedCustomBinary(existing) &&
                    sha256HexEqualsConstantTime(computeSha256Hex(existing), expectedSha)
                ) {
                    return existing
                }
            }
            synchronized(installLock) {
                if (hasSymlinkInChain(binDir)) return@synchronized null
                cachedBinary.get()?.let { existing ->
                    if (isVerifiedCustomBinary(existing) &&
                        sha256HexEqualsConstantTime(computeSha256Hex(existing), expectedSha)
                    ) {
                        return@synchronized existing
                    }
                }
                extractAndVerifyBundledBinary(expectedSha)?.also { cachedBinary.set(it) }
            }
        }.onFailure { err ->
            System.err.println(
                "[LocalVisionOcrService] Binary verification failed: ${LlmJsonUtils.sanitizeLogMessage(err.message)}"
            )
        }.getOrNull()
    }

    private fun loadExpectedSha256(): String? =
        LocalVisionOcrService::class.java.getResourceAsStream(RESOURCE_SHA256_PATH)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText().trim().lowercase(Locale.ROOT) }
            ?.takeIf { it.matches(SHA256_HEX_REGEX) }

    private fun hasSymlinkInChain(target: File): Boolean {
        val parent = target.parentFile
        return (target.exists() && Files.isSymbolicLink(target.toPath())) ||
            (parent != null && parent.exists() && Files.isSymbolicLink(parent.toPath()))
    }

    private fun isVerifiedCustomBinary(file: File): Boolean {
        return file.exists() &&
            file.isFile &&
            !Files.isSymbolicLink(file.toPath()) &&
            file.canExecute()
    }

    private fun extractAndVerifyBundledBinary(expectedSha: String): File? {
        var tempBin: File? = null
        var tempMarker: File? = null
        return try {
            LocalModelPaths.ensureOwnerOnlyDirectory(binDir)
            val installed = File(binDir, HELPER_BINARY_NAME)
            val marker = File(binDir, VERIFIED_MARKER_NAME)

            if (isVerifiedCustomBinary(installed) &&
                marker.isFile &&
                !Files.isSymbolicLink(marker.toPath()) &&
                sha256HexEqualsConstantTime(marker.readText().trim(), expectedSha) &&
                sha256HexEqualsConstantTime(computeSha256Hex(installed), expectedSha)
            ) {
                LocalModelPaths.setOwnerOnlyPermissions(installed, isDirectory = false, executable = true)
                return installed
            }

            if (Files.isSymbolicLink(installed.toPath())) installed.delete()
            if (Files.isSymbolicLink(marker.toPath())) marker.delete()

            tempBin = LocalModelPaths.createOwnerOnlyTempFile(binDir, "vision-ocr-", ".tmp")
            val stream = LocalVisionOcrService::class.java.getResourceAsStream(RESOURCE_BINARY_PATH)
                ?: return null
            stream.use { input ->
                tempBin.outputStream().use { out ->
                    input.copyTo(out)
                }
            }
            val actualSha = computeSha256Hex(tempBin)
            if (!sha256HexEqualsConstantTime(actualSha, expectedSha)) {
                System.err.println("[LocalVisionOcrService] Bundled helper SHA-256 verification failed.")
                return null
            }
            LocalModelPaths.atomicMove(tempBin, installed, executable = true)

            tempMarker = LocalModelPaths.createOwnerOnlyTempFile(binDir, "vision-ocr-", ".sha256.tmp")
            tempMarker.writeText("$expectedSha\n", Charsets.UTF_8)
            LocalModelPaths.atomicMove(tempMarker, marker, executable = false)
            installed
        } catch (e: Exception) {
            System.err.println(
                "[LocalVisionOcrService] Failed to stage vision-ocr binary: ${LlmJsonUtils.sanitizeLogMessage(e.message)}"
            )
            null
        } finally {
            runCatching { tempBin?.takeIf { it.exists() }?.delete() }
            runCatching { tempMarker?.takeIf { it.exists() }?.delete() }
        }
    }

    private fun isSafeInputImageFile(file: File): Boolean {
        return try {
            val path = file.toPath()
            !Files.isSymbolicLink(path) &&
                file.exists() &&
                file.isFile &&
                file.length() in 1L..AttachmentContentExtractor.MAX_FILE_BYTES
        } catch (_: Exception) {
            false
        }
    }

    private fun computeSha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(8192)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val RESOURCE_BINARY_PATH = "/native/darwin/vision-ocr"
        private const val RESOURCE_SHA256_PATH = "/native/darwin/vision-ocr.sha256"
        private const val HELPER_BINARY_NAME = "vision-ocr"
        private const val VERIFIED_MARKER_NAME = ".vision-ocr.sha256"
        private const val DEFAULT_TIMEOUT_SECONDS = 15L
        private const val MAX_STDOUT_BYTES = 64 * 1024
        private const val MAX_STDERR_BYTES = 8 * 1024
        private const val MAX_DRAIN_BYTES = 2 * 1024 * 1024
        private const val MAX_QR_PAYLOADS = 4
        private const val MAX_QR_PAYLOAD_CHARS = 512
        private val SHA256_HEX_REGEX = Regex("^[0-9a-f]{64}$")

        private fun defaultIsMacOs(): Boolean =
            (System.getProperty("os.name") ?: "").lowercase(Locale.ROOT).contains("mac")

        /** Runs [command] with bounded stdout/stderr draining, guaranteed timeout cleanup, and pipe teardown. */
        fun defaultProcessRunner(command: List<String>, timeoutSeconds: Long): VisionProcessResult {
            val process = ProcessBuilder(command).start()
            val stdoutRef = AtomicReference("")
            val stderrRef = AtomicReference("")
            val outThread = Thread({
                stdoutRef.set(readBoundedUtf8(process.inputStream, MAX_STDOUT_BYTES))
            }, "vision-ocr-stdout").apply { isDaemon = true }
            val errThread = Thread({
                stderrRef.set(readBoundedUtf8(process.errorStream, MAX_STDERR_BYTES))
            }, "vision-ocr-stderr").apply { isDaemon = true }

            try {
                runCatching { process.outputStream.close() }
                outThread.start()
                errThread.start()

                val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    runCatching { process.waitFor(1, TimeUnit.SECONDS) }
                    runCatching { outThread.join(500) }
                    runCatching { errThread.join(500) }
                    return VisionProcessResult(-1, stdoutRef.get(), "vision-ocr timed out after ${timeoutSeconds}s")
                }
                outThread.join(1_000)
                errThread.join(1_000)
                return VisionProcessResult(process.exitValue(), stdoutRef.get(), stderrRef.get())
            } catch (e: InterruptedException) {
                process.destroyForcibly()
                throw e
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
            }
        }

        private fun readBoundedUtf8(stream: InputStream, maxBytes: Int): String {
            return runCatching {
                stream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(2048)
                    var captured = 0
                    var drained = 0
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        if (captured < maxBytes) {
                            val toCopy = minOf(read, maxBytes - captured)
                            out.write(buf, 0, toCopy)
                            captured += toCopy
                        }
                        drained += read
                        // Continue draining past maxBytes without buffering so child warnings do not hit SIGPIPE.
                        if (drained >= MAX_DRAIN_BYTES) break
                    }
                    out.toString(Charsets.UTF_8)
                }
            }.getOrDefault("")
        }
    }
}
