package com.wechatdigest.core.db

import com.wechatdigest.core.llm.LlmJsonUtils
import org.sqlite.SQLiteConfig
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

class WeChatDatabaseDriver(
    val baseDir: File,
    val customKeysFile: File? = null,
    val cacheDir: File = File(System.getProperty("user.home"), "Library/Application Support/WeChatDigest/decrypted")
) {

    @Volatile
    private var attachedSession: WeChatSessionContext? = null

    init {
        // Register SQLite JDBC driver
        Class.forName("org.sqlite.JDBC")
    }

    internal fun attachSession(session: WeChatSessionContext) {
        attachedSession = session
    }

    internal fun detachSession(session: WeChatSessionContext) {
        if (attachedSession === session) {
            attachedSession = null
        }
    }

    /**
     * Resolves a database file: if encrypted with SQLCipher4, transparently decrypts
     * it into the app's local decrypted mirror cache (replaying valid `-wal` frames);
     * if unencrypted with a live `-wal` sibling, creates a read-only flattened snapshot.
     */
    fun resolveDatabaseFile(
        rawFile: File,
        sessionContext: WeChatSessionContext? = attachedSession
    ): File {
        if (!rawFile.exists()) {
            throw FileNotFoundException("Database file not found: ${rawFile.absolutePath}")
        }
        sessionContext?.getResolvedFile(rawFile)?.let { cached ->
            if (cached.exists()) return cached
        }

        val resolved = if (!WcdbDecryptor.isEncrypted(rawFile)) {
            resolvePlaintextWithWalSnapshot(rawFile, sessionContext)
        } else {
            val keys = sessionContext?.getKeysOrThrow(rawFile.name) ?: run {
                val keysFile = WcdbDecryptor.findKeysFile(customKeysFile, baseDir)
                    ?: throw IllegalStateException(
                        "WeChat database '${rawFile.name}' is encrypted with SQLCipher4, but all_keys.json was not found. " +
                            "Please place all_keys.json in ${baseDir.absolutePath} or specify it in Settings."
                    )
                WcdbDecryptor.loadKeys(keysFile)
            }
            WcdbDecryptor.resolveDecryptedFile(rawFile, keys, cacheDir, sourceRoot = baseDir)
        }

        sessionContext?.cacheResolvedFile(rawFile, resolved)
        return resolved
    }

    private fun resolvePlaintextWithWalSnapshot(
        rawFile: File,
        sessionContext: WeChatSessionContext?
    ): File {
        val walFile = File(rawFile.parentFile, "${rawFile.name}-wal")
        val hasLiveWal = walFile.exists() &&
            walFile.isFile &&
            !Files.isSymbolicLink(walFile.toPath()) &&
            walFile.length() > WcdbDecryptor.WAL_HEADER_SIZE
        if (!hasLiveWal) {
            return rawFile
        }
        require(!Files.isSymbolicLink(rawFile.toPath())) {
            "Refusing to snapshot symlinked database: ${rawFile.name}"
        }

        if (sessionContext != null) {
            val snapshotDir = sessionContext.getOrCreateSnapshotDir()
            val prefix = WcdbDecryptor.sha256Prefix(rawFile.canonicalPath, 12)
            val snapshotFile = File(snapshotDir, "${prefix}_${rawFile.name}")
            return createPlaintextWalSnapshot(rawFile, walFile, snapshotFile)
        }

        val acctNamespace = WcdbDecryptor.accountNamespace(baseDir)
        val relativeSubdir = WcdbDecryptor.resolveRelativeSubdir(rawFile, baseDir)
        val snapshotRoot = File(cacheDir, "snapshots")
        val acctDir = File(snapshotRoot, acctNamespace)
        val targetDir = if (relativeSubdir.isNotBlank()) File(acctDir, relativeSubdir) else acctDir
        val snapshotFile = File(targetDir, rawFile.name)
        val walStateFile = File(targetDir, ".${rawFile.name}.wal_state")

        val canonicalCacheRoot = cacheDir.canonicalFile
        require(snapshotFile.canonicalFile.toPath().startsWith(canonicalCacheRoot.toPath())) {
            "Refusing to write snapshot outside cacheDir: ${rawFile.name}"
        }
        require(
            !Files.isSymbolicLink(cacheDir.toPath()) &&
                !Files.isSymbolicLink(snapshotRoot.toPath()) &&
                !Files.isSymbolicLink(acctDir.toPath()) &&
                !Files.isSymbolicLink(targetDir.toPath()) &&
                !Files.isSymbolicLink(snapshotFile.toPath()) &&
                !Files.isSymbolicLink(walStateFile.toPath())
        ) {
            "Refusing to write snapshot through a symbolic link: ${rawFile.name}"
        }

        val sourceMtime = rawFile.lastModified()
        val sourceLen = rawFile.length()
        val walMtime = walFile.lastModified()
        val walLen = walFile.length()
        val effectiveMtime = maxOf(sourceMtime, walMtime)
        val expectedState = "$sourceMtime|$sourceLen|$walMtime|$walLen"
        val stateMatches = walStateFile.exists() &&
            runCatching { walStateFile.readText().trim() == expectedState }.getOrDefault(false)

        if (snapshotFile.exists() &&
            snapshotFile.length() > 0 &&
            snapshotFile.lastModified() >= effectiveMtime &&
            stateMatches
        ) {
            WcdbDecryptor.setOwnerOnlyPermissions(snapshotFile, isDirectory = false)
            return snapshotFile
        }

        cacheDir.mkdirs()
        WcdbDecryptor.setOwnerOnlyPermissions(cacheDir, isDirectory = true)
        snapshotRoot.mkdirs()
        WcdbDecryptor.setOwnerOnlyPermissions(snapshotRoot, isDirectory = true)
        acctDir.mkdirs()
        WcdbDecryptor.setOwnerOnlyPermissions(acctDir, isDirectory = true)
        targetDir.mkdirs()
        WcdbDecryptor.setOwnerOnlyPermissions(targetDir, isDirectory = true)

        val flattened = createPlaintextWalSnapshot(rawFile, walFile, snapshotFile)
        runCatching {
            walStateFile.writeText(expectedState)
            WcdbDecryptor.setOwnerOnlyPermissions(walStateFile, isDirectory = false)
        }
        return flattened
    }

    private fun createPlaintextWalSnapshot(
        rawFile: File,
        walFile: File,
        snapshotFile: File
    ): File {
        val snapshotDir = snapshotFile.parentFile
        val snapshotWal = File(snapshotDir, "${snapshotFile.name}-wal")
        val snapshotShm = File(snapshotDir, "${snapshotFile.name}-shm")
        runCatching { snapshotWal.delete() }
        runCatching { snapshotShm.delete() }

        Files.copy(rawFile.toPath(), snapshotFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        WcdbDecryptor.setOwnerOnlyPermissions(snapshotFile, isDirectory = false)
        Files.copy(walFile.toPath(), snapshotWal.toPath(), StandardCopyOption.REPLACE_EXISTING)
        WcdbDecryptor.setOwnerOnlyPermissions(snapshotWal, isDirectory = false)

        try {
            DriverManager.getConnection("jdbc:sqlite:${snapshotFile.canonicalPath}").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                    stmt.execute("PRAGMA journal_mode=DELETE")
                }
            }
        } catch (e: Exception) {
            System.err.println(
                "[WeChatDatabaseDriver] Plaintext WAL snapshot checkpoint skipped for ${rawFile.name}: " +
                    LlmJsonUtils.sanitizeLogMessage(e.message)
            )
            runCatching { snapshotWal.delete() }
            runCatching { snapshotShm.delete() }
            Files.copy(rawFile.toPath(), snapshotFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            runCatching { snapshotWal.delete() }
            runCatching { snapshotShm.delete() }
            WcdbDecryptor.setOwnerOnlyPermissions(snapshotFile, isDirectory = false)
        }
        return snapshotFile
    }

    /**
     * Opens a read-only, non-locking SQLite connection to the specified database file.
     * Automatically decrypts the file if it is encrypted or snapshots active `-wal` frames.
     */
    fun openReadOnlyConnection(
        dbFile: File,
        sessionContext: WeChatSessionContext? = attachedSession
    ): Connection {
        if (!dbFile.exists()) {
            throw FileNotFoundException("WeChat database not found: ${dbFile.absolutePath}")
        }
        val resolvedFile = resolveDatabaseFile(dbFile, sessionContext)
        // Encode path via URI so '#' or '?' characters cannot drop the immutable=1 query flag
        val fileUri = URI("file", null, resolvedFile.canonicalFile.absolutePath, "immutable=1", null).toASCIIString()
        val url = "jdbc:sqlite:$fileUri"
        val config = SQLiteConfig().apply {
            setReadOnly(true)
            // Enable foreign keys and set busy timeout
            setBusyTimeout(5000)
        }
        return DriverManager.getConnection(url, config.toProperties())
    }

    companion object {
        /**
         * Computes WeChat's internal table name for a chatroom: Msg_<md5(roomId)>
         */
        fun calculateRoomTableName(roomId: String): String {
            val md5 = MessageDigest.getInstance("MD5")
                .digest(roomId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return "Msg_$md5"
        }
    }
}

