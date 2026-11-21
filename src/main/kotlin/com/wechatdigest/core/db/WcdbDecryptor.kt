package com.wechatdigest.core.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object WcdbDecryptor {

    const val PAGE_SIZE = 4096
    const val KEY_SIZE = 32
    const val SALT_SIZE = 16
    const val IV_SIZE = 16
    const val HMAC_SIZE = 64
    const val RESERVE_SIZE = 80 // IV(16) + HMAC(64)

    val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

    fun isEncrypted(file: File): Boolean {
        if (!file.exists() || file.length() < 16) return false
        return try {
            FileInputStream(file).use { input ->
                val header = ByteArray(16)
                val read = input.read(header)
                if (read < 16) return false
                !header.contentEquals(SQLITE_HEADER)
            }
        } catch (e: Exception) {
            true
        }
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val len = clean.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4) +
                    Character.digit(clean[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    fun loadKeys(keysFile: File): Map<String, ByteArray> {
        if (!keysFile.exists() || !keysFile.isFile) return emptyMap()
        return try {
            val text = keysFile.readText()
            val jsonElement = Json.parseToJsonElement(text).jsonObject
            val result = mutableMapOf<String, ByteArray>()
            for ((path, obj) in jsonElement) {
                if (obj is kotlinx.serialization.json.JsonObject) {
                    val encKeyHex = obj["enc_key"]?.jsonPrimitive?.contentOrNull
                    if (!encKeyHex.isNullOrBlank()) {
                        val keyBytes = hexToBytes(encKeyHex)
                        result[path] = keyBytes
                        result[path.replace('\\', '/')] = keyBytes
                        val fileName = File(path).name
                        result[fileName] = keyBytes
                    }
                }
            }
            result
        } catch (e: Exception) {
            System.err.println("[WcdbDecryptor] Failed to parse keys file ${keysFile.absolutePath}: ${e.message}")
            emptyMap()
        }
    }

    fun findKeysFile(customKeysFile: File? = null, baseDir: File? = null): File? {
        if (customKeysFile != null && customKeysFile.exists() && customKeysFile.isFile) {
            return customKeysFile
        }
        val candidates = mutableListOf<File>()
        if (baseDir != null) {
            candidates.add(File(baseDir, "all_keys.json"))
            baseDir.parentFile?.let { candidates.add(File(it, "all_keys.json")) }
            baseDir.parentFile?.parentFile?.let { candidates.add(File(it, "all_keys.json")) }
        }
        val userHome = System.getProperty("user.home") ?: "."
        candidates.add(File(userHome, "wcdb-key-tool/all_keys.json"))
        candidates.add(File(userHome, ".wcdb-key-tool/all_keys.json"))
        candidates.add(File(userHome, "Library/Application Support/WeChatDigest/all_keys.json"))

        return candidates.firstOrNull { it.exists() && it.isFile }
    }

    fun decryptPage(cipher: Cipher, keySpec: SecretKeySpec, pageData: ByteArray, pgno: Int): ByteArray {
        val ivOffset = PAGE_SIZE - RESERVE_SIZE
        val iv = IvParameterSpec(pageData, ivOffset, IV_SIZE)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, iv)

        val outPage = ByteArray(PAGE_SIZE)
        if (pgno == 1) {
            val encryptedOffset = SALT_SIZE
            val encryptedLen = PAGE_SIZE - RESERVE_SIZE - SALT_SIZE
            val decryptedBytes = cipher.doFinal(pageData, encryptedOffset, encryptedLen)

            System.arraycopy(SQLITE_HEADER, 0, outPage, 0, 16)
            System.arraycopy(decryptedBytes, 0, outPage, 16, decryptedBytes.size)
        } else {
            val encryptedLen = PAGE_SIZE - RESERVE_SIZE
            val decryptedBytes = cipher.doFinal(pageData, 0, encryptedLen)

            System.arraycopy(decryptedBytes, 0, outPage, 0, decryptedBytes.size)
        }
        return outPage
    }

    fun decryptDatabase(inputFile: File, outputFile: File, encKey: ByteArray): Boolean {
        if (!inputFile.exists()) return false
        val fileSize = inputFile.length()
        val totalPages = (fileSize / PAGE_SIZE).toInt()
        if (totalPages == 0) return false

        outputFile.parentFile?.mkdirs()

        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val keySpec = SecretKeySpec(encKey, "AES")

        RandomAccessFile(inputFile, "r").use { input ->
            val pageBuf = ByteArray(PAGE_SIZE)
            FileOutputStream(outputFile).use { output ->
                for (pgno in 1..totalPages) {
                    val read = input.read(pageBuf)
                    if (read < PAGE_SIZE) {
                        if (read <= 0) break
                        for (i in read until PAGE_SIZE) pageBuf[i] = 0
                    }
                    val decryptedPage = decryptPage(cipher, keySpec, pageBuf, pgno)
                    output.write(decryptedPage)
                }
            }
        }
        return true
    }

    fun resolveDecryptedFile(
        sourceFile: File,
        keys: Map<String, ByteArray>,
        cacheDir: File
    ): File {
        if (!sourceFile.exists()) return sourceFile
        if (!isEncrypted(sourceFile)) {
            return sourceFile
        }

        val relativePath = sourceFile.parentFile?.name + "/" + sourceFile.name
        val key = keys[relativePath]
            ?: keys[sourceFile.name]
            ?: throw IllegalStateException("Database is encrypted (SQLCipher4), but no key found for: $relativePath (${sourceFile.name})")

        val relativeSubdir = sourceFile.parentFile?.name ?: ""
        val targetDir = if (relativeSubdir.isNotBlank() && relativeSubdir != "db_storage" && relativeSubdir != "." && relativeSubdir != "..") {
            File(cacheDir, relativeSubdir)
        } else {
            cacheDir
        }
        val targetFile = File(targetDir, sourceFile.name)
        val canonicalCacheRoot = cacheDir.canonicalFile
        val canonicalTarget = targetFile.canonicalFile
        require(canonicalTarget.path.startsWith(canonicalCacheRoot.path + File.separator)) {
            "Refusing to write decrypted cache file outside cacheDir: ${canonicalTarget.path}"
        }

        if (targetFile.exists() && targetFile.length() > 0 && targetFile.lastModified() >= sourceFile.lastModified()) {
            return targetFile
        }

        setOwnerOnlyPermissions(cacheDir, isDirectory = true)
        targetDir.mkdirs()
        setOwnerOnlyPermissions(targetDir, isDirectory = true)

        val success = decryptDatabase(sourceFile, targetFile, key)
        if (!success) {
            throw IllegalStateException("Failed to decrypt database: ${sourceFile.name}")
        }
        setOwnerOnlyPermissions(targetFile, isDirectory = false)
        return targetFile
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
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
            )
        }
        runCatching { Files.setPosixFilePermissions(path.toPath(), perms) }
    }
}
