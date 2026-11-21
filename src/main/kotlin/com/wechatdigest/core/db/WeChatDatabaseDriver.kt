package com.wechatdigest.core.db

import org.sqlite.SQLiteConfig
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

class WeChatDatabaseDriver(
    val baseDir: File,
    val customKeysFile: File? = null,
    val cacheDir: File = File(System.getProperty("user.home"), "Library/Application Support/WeChatDigest/decrypted")
) {

    init {
        // Register SQLite JDBC driver
        Class.forName("org.sqlite.JDBC")
    }

    /**
     * Resolves a database file: if encrypted with SQLCipher4, transparently decrypts
     * it into the app's local decrypted mirror cache and returns the decrypted file.
     */
    fun resolveDatabaseFile(rawFile: File): File {
        if (!rawFile.exists()) {
            throw FileNotFoundException("Database file not found: ${rawFile.absolutePath}")
        }
        if (!WcdbDecryptor.isEncrypted(rawFile)) {
            return rawFile
        }
        val keysFile = WcdbDecryptor.findKeysFile(customKeysFile, baseDir)
            ?: throw IllegalStateException(
                "WeChat database '${rawFile.name}' is encrypted with SQLCipher4, but all_keys.json was not found. " +
                "Please place all_keys.json in ${baseDir.absolutePath} or specify it in Settings."
            )
        val keys = WcdbDecryptor.loadKeys(keysFile)
        return WcdbDecryptor.resolveDecryptedFile(rawFile, keys, cacheDir)
    }

    /**
     * Opens a read-only, non-locking SQLite connection to the specified database file.
     * Automatically decrypts the file if it is encrypted.
     */
    fun openReadOnlyConnection(dbFile: File): Connection {
        if (!dbFile.exists()) {
            throw FileNotFoundException("WeChat database not found: ${dbFile.absolutePath}")
        }
        val resolvedFile = resolveDatabaseFile(dbFile)
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

