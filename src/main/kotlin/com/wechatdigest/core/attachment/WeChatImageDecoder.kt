package com.wechatdigest.core.attachment

import com.wechatdigest.core.llm.local.LocalModelPaths
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale

/**
 * Magic-byte detector and single-byte XOR `.dat` decoder for WeChat local image caches.
 * All transient decoded files are written inside `0700` temp directories with `0600`
 * permissions and deleted unconditionally inside `finally`.
 */
object WeChatImageDecoder {

    private val DIRECT_PASSTHROUGH_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic")
    private const val IO_BUFFER_BYTES = 8192

    /** Detected image container format ([formatExtension]) and single-byte XOR cipher key ([xorKey]). */
    data class DecodedImageSpec(
        val formatExtension: String,
        val xorKey: Int
    )

    /**
     * Detects whether [header] represents a plain or single-byte XOR obfuscated JPEG, PNG, GIF,
     * WEBP, or unencrypted HEIC image. Returns `null` when unrecognized.
     */
    fun detectFormat(header: ByteArray): DecodedImageSpec? {
        if (header.size < 3) return null

        // JPEG: FF D8 FF
        findXorKey(header, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))?.let { key ->
            return DecodedImageSpec("jpg", key)
        }

        if (header.size >= 4) {
            // PNG: 89 50 4E 47
            findXorKey(header, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))?.let { key ->
                return DecodedImageSpec("png", key)
            }
            // GIF: 47 49 46 38 ("GIF8")
            findXorKey(header, byteArrayOf(0x47, 0x49, 0x46, 0x38))?.let { key ->
                return DecodedImageSpec("gif", key)
            }
        }

        if (header.size >= 12) {
            // WEBP: "RIFF" + 4-byte size + "WEBP"
            findXorKey(header, byteArrayOf(0x52, 0x49, 0x46, 0x46))?.let { key ->
                if ((header[8].toInt() and 0xFF) xor key == 0x57 &&
                    (header[9].toInt() and 0xFF) xor key == 0x45 &&
                    (header[10].toInt() and 0xFF) xor key == 0x42 &&
                    (header[11].toInt() and 0xFF) xor key == 0x50
                ) {
                    return DecodedImageSpec("webp", key)
                }
            }
            // HEIC / ISOBMFF: unencrypted "ftyp" box marker at offset 4..7
            if (header[4] == 0x66.toByte() &&
                header[5] == 0x74.toByte() &&
                header[6] == 0x79.toByte() &&
                header[7] == 0x70.toByte()
            ) {
                return DecodedImageSpec("heic", 0)
            }
        }

        return null
    }

    /**
     * Reads up to 16 header bytes from [file] and inspects its magic signature / XOR key.
     */
    fun inspectFile(file: File): DecodedImageSpec? {
        if (!isSafeRegularImageSource(file)) return null
        val header = ByteArray(16)
        val read = runCatching {
            file.inputStream().use { it.read(header) }
        }.getOrDefault(-1)
        if (read < 3) return null
        return detectFormat(header.copyOf(read))
    }

    /**
     * Streams [file] applying its detected single-byte XOR key on the fly and verifies whether the
     * resulting plaintext image MD5 matches [expectedMd5Hex] without allocating temp files.
     */
    fun verifyPlaintextMd5(file: File, expectedMd5Hex: String): Boolean {
        val normalizedExpected = expectedMd5Hex.trim().lowercase(Locale.ROOT)
        if (normalizedExpected.length != 32) return false
        val spec = inspectFile(file) ?: return false
        return runCatching {
            val md = MessageDigest.getInstance("MD5")
            val buf = ByteArray(IO_BUFFER_BYTES)
            val key = spec.xorKey
            file.inputStream().buffered().use { input ->
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    if (key != 0) {
                        for (i in 0 until read) {
                            buf[i] = ((buf[i].toInt() and 0xFF) xor key).toByte()
                        }
                    }
                    md.update(buf, 0, read)
                }
            }
            val actualHex = md.digest().joinToString("") { "%02x".format(it) }
            actualHex == normalizedExpected
        }.getOrDefault(false)
    }

    /**
     * Provides [block] with a readable standard image file path (`.jpg`/`.png`/`.gif`/`.webp`/`.heic`).
     * When [sourceFile] is XOR-obfuscated or carries an opaque extension like `.dat`, decodes into
     * an owner-only temp file cleanly deleted inside `finally`.
     */
    fun <T> withDecodedImageFile(sourceFile: File, block: (File) -> T): T? {
        val spec = inspectFile(sourceFile) ?: return null
        val ext = sourceFile.extension.lowercase(Locale.ROOT)
        if (spec.xorKey == 0 && ext in DIRECT_PASSTHROUGH_EXTENSIONS) {
            return block(sourceFile)
        }

        val tempDir = LocalModelPaths.createOwnerOnlyTempDirectory("wechat-digest-img-")
        return try {
            val tempFile = LocalModelPaths.createOwnerOnlyTempFile(tempDir, "decoded-", ".${spec.formatExtension}")
            val buf = ByteArray(IO_BUFFER_BYTES)
            var totalWritten = 0L
            val key = spec.xorKey
            sourceFile.inputStream().buffered().use { input ->
                tempFile.outputStream().buffered().use { output ->
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        totalWritten += read
                        if (totalWritten > AttachmentContentExtractor.MAX_FILE_BYTES) {
                            return null
                        }
                        if (key != 0) {
                            for (i in 0 until read) {
                                buf[i] = ((buf[i].toInt() and 0xFF) xor key).toByte()
                            }
                        }
                        output.write(buf, 0, read)
                    }
                    output.flush()
                }
            }
            block(tempFile)
        } finally {
            runCatching { tempDir.deleteRecursively() }
        }
    }

    private fun findXorKey(header: ByteArray, magic: ByteArray): Int? {
        if (header.size < magic.size) return null
        val candidateKey = (header[0].toInt() and 0xFF) xor (magic[0].toInt() and 0xFF)
        for (i in 1 until magic.size) {
            if (((header[i].toInt() and 0xFF) xor candidateKey) != (magic[i].toInt() and 0xFF)) {
                return null
            }
        }
        return candidateKey
    }

    private fun isSafeRegularImageSource(file: File): Boolean {
        return try {
            val path = file.toPath()
            !Files.isSymbolicLink(path) &&
                file.exists() &&
                file.isFile &&
                file.length() in 3L..AttachmentContentExtractor.MAX_FILE_BYTES
        } catch (_: Exception) {
            false
        }
    }
}
