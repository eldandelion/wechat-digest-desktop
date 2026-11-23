package com.wechatdigest.core.decompress

import com.github.luben.zstd.ZstdInputStream
import java.io.ByteArrayInputStream

object ZstdDecompressor {
    // Zstandard Frame Magic Number: 0xFD2FB528 (little-endian byte order: 0x28, 0xB5, 0x2F, 0xFD)
    private val ZSTD_MAGIC = byteArrayOf(0x28.toByte(), 0xB5.toByte(), 0x2F.toByte(), 0xFD.toByte())
    private const val MAX_DECOMPRESSED_BYTES = 1_048_576

    /**
     * Checks if the byte array starts with the Zstandard frame magic bytes.
     */
    fun isZstdCompressed(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        return bytes[0] == ZSTD_MAGIC[0] &&
               bytes[1] == ZSTD_MAGIC[1] &&
               bytes[2] == ZSTD_MAGIC[2] &&
               bytes[3] == ZSTD_MAGIC[3]
    }

    /**
     * Decompresses the given byte array if Zstandard compressed; otherwise decodes as UTF-8 string.
     */
    fun decompressToString(bytes: ByteArray): String {
        if (!isZstdCompressed(bytes)) {
            return bytes.toString(Charsets.UTF_8)
        }
        return runCatching { readBoundedZstdStream(bytes) }.getOrDefault("")
    }

    private fun readBoundedZstdStream(bytes: ByteArray): String {
        ByteArrayInputStream(bytes).use { bais ->
            ZstdInputStream(bais).use { zis ->
                val buffer = ByteArray(8192)
                val out = java.io.ByteArrayOutputStream()
                while (out.size() < MAX_DECOMPRESSED_BYTES) {
                    val remaining = MAX_DECOMPRESSED_BYTES - out.size()
                    val read = zis.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
                return out.toByteArray().toString(Charsets.UTF_8)
            }
        }
    }
}
