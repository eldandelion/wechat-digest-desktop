package com.wechatdigest.core.db

import com.wechatdigest.core.llm.LlmJsonUtils
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object WcdbDecryptor {

    private val decryptLocks = ConcurrentHashMap<String, Any>()

    const val PAGE_SIZE = 4096
    const val KEY_SIZE = 32
    const val SALT_SIZE = 16
    const val IV_SIZE = 16
    const val HMAC_SIZE = 64
    const val RESERVE_SIZE = 80 // IV(16) + HMAC(64)
    const val WAL_HEADER_SIZE = 32
    const val WAL_FRAME_HEADER_SIZE = 24
    const val WAL_MAGIC_LE = 0x377F0682
    const val WAL_MAGIC_BE = 0x377F0683
    const val WAL_FORMAT = 3007000
    const val MAX_WAL_BYTES = 512L * 1024 * 1024
    const val MAX_WAL_PAGES = 16_777_216L

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
        require(clean.length % 2 == 0) { "Hex key string must have even length" }
        val data = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            val hi = Character.digit(clean[i], 16)
            val lo = Character.digit(clean[i + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex character in key string" }
            data[i / 2] = ((hi shl 4) + lo).toByte()
            i += 2
        }
        return data
    }

    fun loadKeys(keysFile: File): Map<String, ByteArray> {
        if (!keysFile.exists() || !keysFile.isFile || Files.isSymbolicLink(keysFile.toPath())) return emptyMap()
        return try {
            val text = keysFile.readText()
            val jsonElement = Json.parseToJsonElement(text).jsonObject
            val result = mutableMapOf<String, ByteArray>()
            for ((path, obj) in jsonElement) {
                if (obj is kotlinx.serialization.json.JsonObject) {
                    val encKeyHex = obj["enc_key"]?.jsonPrimitive?.contentOrNull
                    if (!encKeyHex.isNullOrBlank()) {
                        val keyBytes = hexToBytes(encKeyHex)
                        require(keyBytes.size == KEY_SIZE) { "Unexpected key byte length: ${keyBytes.size}" }
                        val normalizedPath = path.replace('\\', '/')
                        result[path] = keyBytes
                        result[normalizedPath] = keyBytes
                        val fileName = File(normalizedPath).name
                        // Prefer full/relative paths when multiple accounts reuse the same filename.
                        result.putIfAbsent(fileName, keyBytes)
                    }
                }
            }
            result
        } catch (_: SerializationException) {
            System.err.println("[WcdbDecryptor] Failed to parse keys file ${keysFile.name}: Invalid JSON syntax")
            emptyMap()
        } catch (_: IllegalArgumentException) {
            System.err.println("[WcdbDecryptor] Failed to parse keys file ${keysFile.name}: Invalid key entry format")
            emptyMap()
        } catch (e: Exception) {
            System.err.println(
                "[WcdbDecryptor] Failed to parse keys file ${keysFile.name}: ${LlmJsonUtils.sanitizeLogMessage(e.message)}"
            )
            emptyMap()
        }
    }

    fun findKeysFile(customKeysFile: File? = null, baseDir: File? = null): File? {
        if (customKeysFile != null) {
            return if (customKeysFile.exists() && customKeysFile.isFile && !Files.isSymbolicLink(customKeysFile.toPath())) {
                customKeysFile
            } else {
                null
            }
        }
        val candidates = mutableListOf<File>()
        if (baseDir != null) {
            candidates.add(File(baseDir, "all_keys.json"))
            baseDir.parentFile?.let { candidates.add(File(it, "all_keys.json")) }
            baseDir.parentFile?.parentFile?.let { candidates.add(File(it, "all_keys.json")) }
        }
        val userHome = System.getProperty("user.home") ?: "."
        val userDir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        candidates.add(File(userDir, "all_keys.json"))
        candidates.add(File(userDir, "wcdb-key-tool/all_keys.json"))
        userDir.parentFile?.let { parent ->
            candidates.add(File(parent, "all_keys.json"))
            candidates.add(File(parent, "wcdb-key-tool/all_keys.json"))
        }
        candidates.add(File(userHome, "wcdb-key-tool/all_keys.json"))
        candidates.add(File(userHome, ".wcdb-key-tool/all_keys.json"))
        candidates.add(File(userHome, "Library/Application Support/WeChatDigest/all_keys.json"))

        return candidates.firstOrNull { it.exists() && it.isFile && !Files.isSymbolicLink(it.toPath()) }
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

        outputFile.parentFile?.let { parent ->
            parent.mkdirs()
            setOwnerOnlyPermissions(parent, isDirectory = true)
        }

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
        setOwnerOnlyPermissions(outputFile, isDirectory = false)
        return true
    }

    fun resolveDecryptedFile(
        sourceFile: File,
        keys: Map<String, ByteArray>,
        cacheDir: File,
        sourceRoot: File? = null
    ): File {
        if (!sourceFile.exists()) return sourceFile
        require(!Files.isSymbolicLink(sourceFile.toPath())) {
            "Refusing to read symlinked database: ${sourceFile.name}"
        }
        if (!isEncrypted(sourceFile)) {
            return sourceFile
        }

        val canonicalSourcePath = sourceFile.canonicalPath.replace('\\', '/')
        val relativePath = sourceFile.parentFile?.name + "/" + sourceFile.name
        val rootRelativePath = sourceRoot?.let { root ->
            runCatching { root.canonicalFile.toPath().relativize(sourceFile.canonicalFile.toPath()).toString().replace('\\', '/') }.getOrNull()
        }
        val key = keys[canonicalSourcePath]
            ?: rootRelativePath?.let { keys[it] }
            ?: keys[relativePath]
            ?: keys.entries.firstOrNull { (k, _) ->
                val norm = k.replace('\\', '/')
                canonicalSourcePath.endsWith("/$norm") || norm.endsWith("/$relativePath")
            }?.value
            ?: keys[sourceFile.name]
            ?: throw IllegalStateException("Database is encrypted (SQLCipher4), but no key found for: $relativePath (${sourceFile.name})")

        val acctNamespace = accountNamespace(sourceRoot ?: sourceFile.parentFile ?: sourceFile)
        val relativeSubdir = resolveRelativeSubdir(sourceFile, sourceRoot)
        val accountRootDir = File(cacheDir, acctNamespace)
        val targetDir = if (relativeSubdir.isNotBlank()) {
            File(accountRootDir, relativeSubdir)
        } else {
            accountRootDir
        }
        val targetFile = File(targetDir, sourceFile.name)
        val walStateFile = File(targetDir, ".${sourceFile.name}.wal_state")
        val canonicalCacheRoot = cacheDir.canonicalFile
        val canonicalTarget = targetFile.canonicalFile
        require(canonicalTarget.toPath().startsWith(canonicalCacheRoot.toPath())) {
            "Refusing to write decrypted cache file outside cacheDir: ${sourceFile.name}"
        }
        requireNoSymlinkBetween(cacheDir, targetDir)
        require(
            !Files.isSymbolicLink(targetFile.toPath()) &&
                !Files.isSymbolicLink(walStateFile.toPath())
        ) {
            "Refusing to write decrypted cache through a symbolic link: ${sourceFile.name}"
        }

        val walFile = File(sourceFile.parentFile, "${sourceFile.name}-wal")
        val lock = decryptLocks.computeIfAbsent(targetFile.canonicalPath) { Any() }
        return synchronized(lock) {
            val hasValidWalFile = walFile.exists() && walFile.isFile && !Files.isSymbolicLink(walFile.toPath())
            val sourceMtime = sourceFile.lastModified()
            val sourceLen = sourceFile.length()
            val walMtime = if (hasValidWalFile) walFile.lastModified() else 0L
            val walSize = if (hasValidWalFile) walFile.length() else 0L
            val effectiveSourceMtime = maxOf(sourceMtime, walMtime)
            val expectedState = "$sourceMtime|$sourceLen|$walMtime|$walSize"

            val stateMatches = walStateFile.exists() &&
                !Files.isSymbolicLink(walStateFile.toPath()) &&
                runCatching { walStateFile.readText().trim() == expectedState }.getOrDefault(false)

            if (targetFile.exists() &&
                targetFile.length() > 0 &&
                targetFile.lastModified() >= effectiveSourceMtime &&
                stateMatches
            ) {
                setOwnerOnlyPermissions(targetFile, isDirectory = false)
                return@synchronized targetFile
            }

            ensureOwnerOnlyDirectoryTree(cacheDir, targetDir)

            val tempFile = createOwnerOnlyTempFile(targetDir)
            try {
                val success = decryptDatabase(sourceFile, tempFile, key)
                if (!success) {
                    throw IllegalStateException("Failed to decrypt database: ${sourceFile.name}")
                }
                val walAppliedOrUnneeded = if (hasValidWalFile && walSize > WAL_HEADER_SIZE) {
                    replayEncryptedWalIfValid(tempFile, walFile, key)
                } else {
                    true
                }
                setOwnerOnlyPermissions(tempFile, isDirectory = false)
                moveAtomicallyWithFallback(tempFile, targetFile)
                setOwnerOnlyPermissions(targetFile, isDirectory = false)
                // Only stamp .wal_state with walMtime|walSize when WAL replay succeeded (or no live WAL frames existed);
                // if WAL replay skipped/failed on a volatile or torn WAL, avoid caching the stale WAL marker.
                if (walAppliedOrUnneeded) {
                    runCatching {
                        writeOwnerOnlyTextAtomically(walStateFile, expectedState)
                    }
                } else {
                    runCatching { walStateFile.delete() }
                }
                targetFile
            } finally {
                runCatching { if (tempFile.exists()) tempFile.delete() }
            }
        }
    }

    /**
     * Validates and replays committed SQLCipher 4 WAL frames from [walFile] onto [mainDecryptedFile]
     * using single-page streaming I/O, cumulative SQLite WAL checksums, salt matching, shrink-safe
     * commit-frame pruning, and upper size bounds. Falls back cleanly to [mainDecryptedFile]-only
     * on any unrecognized header, checksum failure, or out-of-range size.
     */
    internal fun replayEncryptedWalIfValid(
        mainDecryptedFile: File,
        walFile: File,
        key: ByteArray
    ): Boolean {
        return runCatching {
            if (!walFile.exists() || !walFile.isFile || Files.isSymbolicLink(walFile.toPath())) return@runCatching false
            val walLen = walFile.length()
            if (walLen <= WAL_HEADER_SIZE || walLen > MAX_WAL_BYTES) return@runCatching false

            val mainDbPages = mainDecryptedFile.length() / PAGE_SIZE
            val header = ByteArray(WAL_HEADER_SIZE)
            val committedOffsets = LinkedHashMap<Int, Long>()
            val pendingOffsets = LinkedHashMap<Int, Long>()
            var lastCommitDbSize = 0L
            var committedFrameTotal = 0L

            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            val keySpec = SecretKeySpec(key, "AES")
            val encScratch = ByteArray(PAGE_SIZE)

            // Hold ONE open descriptor across Pass 1 (validation) and Pass 2 (page reads) and stage writes
            // onto a sibling temp copy so any mid-loop I/O error never corrupts mainDecryptedFile.
            RandomAccessFile(walFile, "r").use { walRaf ->
                if (readFully(walRaf, header) < WAL_HEADER_SIZE) return@runCatching false
                val hdrBuf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
                val magic = hdrBuf.getInt(0)
                val wordOrder = when (magic) {
                    WAL_MAGIC_LE -> ByteOrder.LITTLE_ENDIAN
                    WAL_MAGIC_BE -> ByteOrder.BIG_ENDIAN
                    else -> return@runCatching false
                }
                val format = hdrBuf.getInt(4)
                val pageSize = hdrBuf.getInt(8)
                if (format != WAL_FORMAT || pageSize != PAGE_SIZE) return@runCatching false

                val headerSalt1 = hdrBuf.getInt(16)
                val headerSalt2 = hdrBuf.getInt(20)
                val headerCksum1 = hdrBuf.getInt(24)
                val headerCksum2 = hdrBuf.getInt(28)

                val (calcH1, calcH2) = computeWalChecksum(header, 0, 24, wordOrder, 0, 0)
                if (calcH1 != headerCksum1 || calcH2 != headerCksum2) return@runCatching false

                var s1 = calcH1
                var s2 = calcH2
                val frameHdr = ByteArray(WAL_FRAME_HEADER_SIZE)
                val framePage = ByteArray(PAGE_SIZE)
                var validFramesSeen = 0L

                while (true) {
                    if (readFully(walRaf, frameHdr) < WAL_FRAME_HEADER_SIZE) break
                    val pageDataOffset = walRaf.filePointer
                    if (readFully(walRaf, framePage) < PAGE_SIZE) break

                    val fBuf = ByteBuffer.wrap(frameHdr).order(ByteOrder.BIG_ENDIAN)
                    val pgno = fBuf.getInt(0)
                    val dbSizeAfterCommit = fBuf.getInt(4).toLong() and 0xFFFFFFFFL
                    val fSalt1 = fBuf.getInt(8)
                    val fSalt2 = fBuf.getInt(12)
                    val fCksum1 = fBuf.getInt(16)
                    val fCksum2 = fBuf.getInt(20)

                    if (pgno <= 0 || pgno.toLong() > MAX_WAL_PAGES) break
                    if (fSalt1 != headerSalt1 || fSalt2 != headerSalt2) break

                    val (afterHdr1, afterHdr2) = computeWalChecksum(frameHdr, 0, 8, wordOrder, s1, s2)
                    val (calcF1, calcF2) = computeWalChecksum(framePage, 0, PAGE_SIZE, wordOrder, afterHdr1, afterHdr2)
                    if (calcF1 != fCksum1 || calcF2 != fCksum2) break

                    s1 = calcF1
                    s2 = calcF2
                    validFramesSeen++
                    pendingOffsets[pgno] = pageDataOffset
                    if (dbSizeAfterCommit > 0L) {
                        val maxBound = minOf(MAX_WAL_PAGES, mainDbPages + validFramesSeen + 16L)
                        if (dbSizeAfterCommit > maxBound) return@runCatching false
                        committedOffsets.putAll(pendingOffsets)
                        pendingOffsets.clear()
                        committedOffsets.entries.removeIf { it.key.toLong() > dbSizeAfterCommit }
                        lastCommitDbSize = dbSizeAfterCommit
                        committedFrameTotal = validFramesSeen
                    }
                }

                if (committedFrameTotal == 0L || lastCommitDbSize <= 0L || committedOffsets.isEmpty()) {
                    return@runCatching false
                }

                val targetDir = mainDecryptedFile.canonicalFile.parentFile ?: error("Missing decrypted parent directory")
                val stagedFile = createOwnerOnlyTempFile(targetDir)
                try {
                    Files.copy(mainDecryptedFile.toPath(), stagedFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    setOwnerOnlyPermissions(stagedFile, isDirectory = false)
                    RandomAccessFile(stagedFile, "rw").use { outRaf ->
                        for ((pgno, pageOffset) in committedOffsets) {
                            walRaf.seek(pageOffset)
                            if (readFully(walRaf, encScratch) < PAGE_SIZE) {
                                return@runCatching false
                            }
                            val plainPage = decryptPage(cipher, keySpec, encScratch, pgno)
                            outRaf.seek((pgno - 1L) * PAGE_SIZE.toLong())
                            outRaf.write(plainPage)
                        }
                        outRaf.setLength(lastCommitDbSize * PAGE_SIZE.toLong())
                    }
                    setOwnerOnlyPermissions(stagedFile, isDirectory = false)
                    moveAtomicallyWithFallback(stagedFile, mainDecryptedFile)
                    setOwnerOnlyPermissions(mainDecryptedFile, isDirectory = false)
                } finally {
                    runCatching { if (stagedFile.exists()) stagedFile.delete() }
                }
            }
            true
        }.getOrElse { e ->
            System.err.println(
                "[WcdbDecryptor] WAL replay skipped (${LlmJsonUtils.sanitizeLogMessage(e.message)})"
            )
            false
        }
    }

    internal fun computeWalChecksum(
        data: ByteArray,
        offset: Int,
        length: Int,
        wordOrder: ByteOrder,
        initS1: Int,
        initS2: Int
    ): Pair<Int, Int> {
        require(length >= 8 && length % 8 == 0) { "WAL checksum length must be a positive multiple of 8" }
        val buf = ByteBuffer.wrap(data).order(wordOrder)
        var s1 = initS1
        var s2 = initS2
        var pos = 0
        while (pos < length) {
            s1 += buf.getInt(offset + pos) + s2
            s2 += buf.getInt(offset + pos + 4) + s1
            pos += 8
        }
        return s1 to s2
    }

    private fun readFully(raf: RandomAccessFile, dst: ByteArray): Int {
        var total = 0
        while (total < dst.size) {
            val read = raf.read(dst, total, dst.size - total)
            if (read <= 0) break
            total += read
        }
        return total
    }

    internal fun accountNamespace(sourceRoot: File): String {
        val canonicalPath = runCatching { sourceRoot.canonicalPath }.getOrElse { sourceRoot.absolutePath }
        return sha256Prefix(canonicalPath, 12)
    }

    internal fun sha256Prefix(input: String, length: Int = 12): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(length)
    }

    internal fun resolveRelativeSubdir(sourceFile: File, sourceRoot: File?): String {
        val parentFile = sourceFile.parentFile ?: return ""
        if (sourceRoot != null) {
            val canonicalParent = runCatching { parentFile.canonicalFile }.getOrElse { parentFile.absoluteFile }
            val canonicalRoot = runCatching { sourceRoot.canonicalFile }.getOrElse { sourceRoot.absoluteFile }
            if (canonicalParent == canonicalRoot) return ""
            if (canonicalParent.toPath().startsWith(canonicalRoot.toPath())) {
                val rel = canonicalRoot.toPath().relativize(canonicalParent.toPath()).toString()
                if (rel.isNotBlank() && !rel.contains("..")) return rel
            }
        }
        val parentName = parentFile.name
        return if (parentName.isNotBlank() && parentName != "db_storage" && parentName != "." && parentName != "..") {
            parentName
        } else {
            ""
        }
    }

    private fun createOwnerOnlyTempFile(directory: File): File {
        val ownerOnlyPerms = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        return runCatching {
            Files.createTempFile(
                directory.toPath(),
                ".decrypt_",
                ".tmp",
                PosixFilePermissions.asFileAttribute(ownerOnlyPerms)
            ).toFile()
        }.getOrElse {
            Files.createTempFile(directory.toPath(), ".decrypt_", ".tmp").toFile().also { file ->
                setOwnerOnlyPermissions(file, isDirectory = false)
            }
        }
    }

    internal fun requireNoSymlinkBetween(root: File, leaf: File) {
        require(!Files.isSymbolicLink(root.toPath())) {
            "Refusing to write through symbolic link directory: ${root.name}"
        }
        val rootCanonical = runCatching { root.canonicalPath }.getOrElse { root.absolutePath }
        var cursor: File? = leaf
        while (cursor != null) {
            require(!Files.isSymbolicLink(cursor.toPath())) {
                "Refusing to write through symbolic link directory: ${cursor?.name}"
            }
            val cursorCanonical = runCatching { cursor.canonicalPath }.getOrNull()
            if (cursor == root || cursorCanonical == rootCanonical) break
            cursor = cursor.parentFile
        }
    }

    internal fun ensureOwnerOnlyDirectoryTree(root: File, leaf: File) {
        val chain = mutableListOf<File>()
        val rootCanonical = runCatching { root.canonicalPath }.getOrElse { root.absolutePath }
        var cursor: File? = leaf
        while (cursor != null) {
            chain.add(cursor)
            val cursorCanonical = runCatching { cursor.canonicalPath }.getOrNull()
            if (cursor == root || cursorCanonical == rootCanonical) break
            cursor = cursor.parentFile
        }
        root.parentFile?.let { parent ->
            if (!parent.exists() && parent.mkdirs()) {
                setOwnerOnlyPermissions(parent, isDirectory = true)
            }
        }
        for (dir in chain.asReversed()) {
            if (!dir.exists()) {
                dir.mkdirs()
            }
            setOwnerOnlyPermissions(dir, isDirectory = true)
        }
    }

    internal fun moveAtomicallyWithFallback(src: File, dst: File) {
        runCatching {
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    internal fun writeOwnerOnlyTextAtomically(targetFile: File, text: String) {
        val parent = targetFile.parentFile ?: return
        val tmp = createOwnerOnlyTempFile(parent)
        try {
            tmp.writeText(text)
            setOwnerOnlyPermissions(tmp, isDirectory = false)
            moveAtomicallyWithFallback(tmp, targetFile)
            setOwnerOnlyPermissions(targetFile, isDirectory = false)
        } finally {
            runCatching { if (tmp.exists()) tmp.delete() }
        }
    }

    internal fun setOwnerOnlyPermissions(path: File, isDirectory: Boolean) {
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
