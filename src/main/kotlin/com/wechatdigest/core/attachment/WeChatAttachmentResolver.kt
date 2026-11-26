package com.wechatdigest.core.attachment

import com.wechatdigest.core.db.WeChatDatabaseDriver
import com.wechatdigest.core.llm.LlmJsonUtils
import com.wechatdigest.core.model.EventAttachment
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Resolves local downloaded file paths inside WeChat's `<account>/msg/file/` container
 * using read-only queries against `hardlink/hardlink.db` plus timestamp-month fallback.
 */
class WeChatAttachmentResolver(
    private val driver: WeChatDatabaseDriver,
    private val explicitAttachmentRootDir: File? = null,
    private val autoDetectSingleAccountRoot: Boolean = true
) {

    private data class HardlinkEntry(
        val md5: String,
        val fileName: String,
        val fileSize: Long,
        val dir1Name: String,
        val dir2Name: String
    )

    private val msgFileRoot: File? by lazy { locateMsgFileRoot() }

    private val hardlinkEntries: List<HardlinkEntry> by lazy { loadHardlinkEntries() }

    private val entriesByMd5: Map<String, List<HardlinkEntry>> by lazy {
        hardlinkEntries.filter { it.md5.isNotBlank() }.groupBy { it.md5 }
    }

    private val entriesByFileName: Map<String, List<HardlinkEntry>> by lazy {
        hardlinkEntries.filter { it.fileName.isNotBlank() }.groupBy { it.fileName }
    }

    /**
     * Resolves the canonical `<account>/msg/file` root directory if present on disk.
     */
    fun resolveMsgFileRoot(): File? = msgFileRoot

    /**
     * Attempts to resolve the on-disk local path for [attachment]. If not downloaded or unavailable,
     * preserves the existing metadata cleanly with `localPath = null`.
     */
    fun resolve(attachment: EventAttachment): EventAttachment {
        val root = msgFileRoot ?: return attachment.copy(localPath = null)
        val canonicalRoot = root.canonicalFile
        val safeName = WeChatFileMessageParser.sanitizeFileName(attachment.fileName)
            ?: return attachment.copy(localPath = null)

        if (!attachment.localPath.isNullOrBlank()) {
            val existing = File(attachment.localPath)
            if (isSafeMatchingFile(existing, canonicalRoot, attachment) && existing.name == safeName) {
                return attachment.copy(localPath = existing.canonicalPath)
            }
        }

        val resolvedFile = findViaHardlinkIndex(attachment, safeName, root, canonicalRoot)
            ?: findViaDirectoryFallback(attachment, safeName, root, canonicalRoot)

        return attachment.copy(localPath = resolvedFile?.canonicalPath)
    }

    /**
     * Re-evaluates local file presence for each attachment in [attachments].
     */
    fun refreshAttachments(attachments: List<EventAttachment>): List<EventAttachment> {
        if (attachments.isEmpty()) return emptyList()
        return attachments.map { resolve(it) }
    }

    private fun locateMsgFileRoot(): File? {
        val candidates = buildList {
            explicitAttachmentRootDir?.let { explicit ->
                add(explicit to File(explicit, "msg/file"))
                add(explicit to File(explicit, "file"))
                if (explicit.name.equals("file", ignoreCase = true)) {
                    add(explicit to explicit)
                }
            }
            add(driver.baseDir to File(driver.baseDir, "msg/file"))
            driver.baseDir.parentFile?.let { parent ->
                add(parent to File(parent, "msg/file"))
            }
        }.distinctBy { (_, leaf) -> leaf.absolutePath }

        candidates.firstOrNull { (anchor, leaf) -> isSymlinkFreeDirectoryChain(anchor, leaf) }
            ?.let { (_, leaf) -> return leaf }

        if (!autoDetectSingleAccountRoot) return null
        val userHome = System.getProperty("user.home")?.takeIf { it.isNotBlank() } ?: return null
        val xwechatFiles = File(userHome, "Library/Containers/com.tencent.xinWeChat/Data/Documents/xwechat_files")
        if (!xwechatFiles.exists() || !xwechatFiles.isDirectory || Files.isSymbolicLink(xwechatFiles.toPath())) {
            return null
        }
        val accountRoots = xwechatFiles.listFiles { f ->
            f.isDirectory && !Files.isSymbolicLink(f.toPath()) && (f.name.startsWith("wxid_") || f.name.contains("_"))
        }?.mapNotNull { account ->
            val msgFile = File(account, "msg/file")
            msgFile.takeIf { isSymlinkFreeDirectoryChain(account, msgFile) }
        }.orEmpty()
        return accountRoots.singleOrNull()
    }

    private fun isSymlinkFreeDirectoryChain(anchor: File, leaf: File): Boolean {
        if (!anchor.exists() || !anchor.isDirectory || Files.isSymbolicLink(anchor.toPath())) return false
        if (!leaf.exists() || !leaf.isDirectory) return false
        val anchorCanonical = runCatching { anchor.canonicalPath }.getOrElse { return false }
        var cursor: File? = leaf
        while (cursor != null) {
            if (Files.isSymbolicLink(cursor.toPath())) return false
            val cursorCanonical = runCatching { cursor.canonicalPath }.getOrNull()
            if (cursor == anchor || cursorCanonical == anchorCanonical) return true
            cursor = cursor.parentFile
        }
        return false
    }

    private fun locateHardlinkDbFile(): File? {
        val hardlinkDir = File(driver.baseDir, "hardlink")
        val nested = File(hardlinkDir, "hardlink.db")
        if (!Files.isSymbolicLink(hardlinkDir.toPath()) &&
            nested.exists() &&
            nested.isFile &&
            !Files.isSymbolicLink(nested.toPath())
        ) {
            return nested
        }
        val flat = File(driver.baseDir, "hardlink.db")
        return flat.takeIf { it.exists() && it.isFile && !Files.isSymbolicLink(it.toPath()) }
    }

    private fun loadHardlinkEntries(): List<HardlinkEntry> {
        val dbFile = locateHardlinkDbFile() ?: return emptyList()
        return try {
            driver.openReadOnlyConnection(dbFile).use { conn ->
                if (!tableExists(conn, "file_hardlink_info_v4")) return emptyList()
                val sql = buildHardlinkQuery(hasDir2Id = tableExists(conn, "dir2id"))
                readHardlinkEntries(conn, sql)
            }
        } catch (e: Exception) {
            System.err.println(
                "[WeChatAttachmentResolver] Failed reading hardlink.db: ${LlmJsonUtils.sanitizeLogMessage(e.message)}"
            )
            emptyList()
        }
    }

    private fun tableExists(conn: java.sql.Connection, tableName: String): Boolean {
        val sql = "SELECT name FROM sqlite_master WHERE type='table' AND name=?"
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, tableName)
            stmt.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun buildHardlinkQuery(hasDir2Id: Boolean): String {
        return if (hasDir2Id) {
            """
            SELECT LOWER(COALESCE(h.md5, '')) AS md5,
                   COALESCE(h.file_name, '') AS file_name,
                   COALESCE(h.file_size, 0) AS file_size,
                   COALESCE(d1.username, '') AS dir1_name,
                   COALESCE(d2.username, '') AS dir2_name
            FROM file_hardlink_info_v4 h
            LEFT JOIN dir2id d1 ON h.dir1 = d1.rowid
            LEFT JOIN dir2id d2 ON h.dir2 = d2.rowid
            """.trimIndent()
        } else {
            """
            SELECT LOWER(COALESCE(h.md5, '')) AS md5,
                   COALESCE(h.file_name, '') AS file_name,
                   COALESCE(h.file_size, 0) AS file_size,
                   '' AS dir1_name,
                   '' AS dir2_name
            FROM file_hardlink_info_v4 h
            """.trimIndent()
        }
    }

    private fun readHardlinkEntries(conn: java.sql.Connection, sql: String): List<HardlinkEntry> {
        val results = mutableListOf<HardlinkEntry>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    val fileName = rs.getString("file_name").orEmpty()
                    if (fileName.isBlank() || fileName == "0") continue
                    results.add(
                        HardlinkEntry(
                            md5 = rs.getString("md5").orEmpty(),
                            fileName = fileName,
                            fileSize = rs.getLong("file_size"),
                            dir1Name = rs.getString("dir1_name").orEmpty(),
                            dir2Name = rs.getString("dir2_name").orEmpty()
                        )
                    )
                }
            }
        }
        return results
    }

    private fun findViaHardlinkIndex(
        attachment: EventAttachment,
        safeName: String,
        root: File,
        canonicalRoot: File
    ): File? {
        val md5Key = attachment.md5.trim().lowercase(Locale.ROOT)
        val expectedExt = attachment.fileExtension.trim().lowercase(Locale.ROOT)
            .ifBlank { safeName.substringAfterLast('.', "").lowercase(Locale.ROOT) }
        val candidates = buildList {
            if (md5Key.isNotBlank()) {
                entriesByMd5[md5Key]?.filter { entry ->
                    isCompatibleHardlinkEntry(entry, safeName, expectedExt)
                }?.let { addAll(it) }
            }
            entriesByFileName[safeName]?.let { matches ->
                val compatible = matches.filter { entry ->
                    (md5Key.isBlank() || entry.md5.isBlank() || entry.md5 == md5Key) &&
                        (attachment.fileSizeBytes <= 0L || entry.fileSize <= 0L || entry.fileSize == attachment.fileSizeBytes)
                }
                addAll(compatible)
            }
        }.distinct()

        for (entry in candidates) {
            val targetName = WeChatFileMessageParser.sanitizeFileName(entry.fileName) ?: safeName
            val dir1 = entry.dir1Name.takeIf { it.isNotBlank() && it != "0" }
            val dir2 = entry.dir2Name.takeIf { it.isNotBlank() && it != "0" }

            val relDirs = listOfNotNull(
                dir1,
                dir2,
                if (dir2 != null && dir1 != null) "$dir2/$dir1" else null,
                if (dir1 != null && dir2 != null) "$dir1/$dir2" else null
            ).distinct()

            for (relDir in relDirs) {
                val candidate = File(File(root, relDir), targetName)
                if (isSafeMatchingFile(candidate, canonicalRoot, attachment)) {
                    return candidate
                }
                if (targetName != safeName) {
                    val altCandidate = File(File(root, relDir), safeName)
                    if (isSafeMatchingFile(altCandidate, canonicalRoot, attachment)) {
                        return altCandidate
                    }
                }
            }
        }
        return null
    }

    private fun isCompatibleHardlinkEntry(
        entry: HardlinkEntry,
        safeName: String,
        expectedExt: String
    ): Boolean {
        val entrySafeName = WeChatFileMessageParser.sanitizeFileName(entry.fileName) ?: return false
        if (entrySafeName == safeName) return true
        if (expectedExt.isBlank()) return false
        val entryExt = entrySafeName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return entryExt == expectedExt
    }

    private fun findViaDirectoryFallback(
        attachment: EventAttachment,
        safeName: String,
        root: File,
        canonicalRoot: File
    ): File? {
        val monthDirs = buildList {
            if (attachment.messageTimestampSeconds > 0L) {
                val ym = YearMonth.from(
                    Instant.ofEpochSecond(attachment.messageTimestampSeconds).atZone(ZoneId.systemDefault())
                )
                add(ym.format(MONTH_FORMATTER))
                add(ym.plusMonths(1).format(MONTH_FORMATTER))
                add(ym.minusMonths(1).format(MONTH_FORMATTER))
            }
            root.listFiles { f -> f.isDirectory && MONTH_DIR_REGEX.matches(f.name) }
                ?.sortedByDescending { it.name }
                ?.forEach { add(it.name) }
        }.distinct()

        for (monthName in monthDirs) {
            val candidate = File(File(root, monthName), safeName)
            if (isSafeMatchingFile(candidate, canonicalRoot, attachment)) {
                return candidate
            }
        }
        return null
    }

    private fun isSafeMatchingFile(candidate: File, canonicalRoot: File, attachment: EventAttachment): Boolean {
        if (!isSafeRegularFile(candidate, canonicalRoot)) return false
        val expectedSize = attachment.fileSizeBytes
        if (expectedSize > 0L && candidate.length() != expectedSize) return false

        val expectedMd5 = attachment.md5.trim().lowercase(Locale.ROOT)
        if (expectedMd5.length == 32 && candidate.length() <= AttachmentContentExtractor.MAX_FILE_BYTES) {
            val actualMd5 = computeFileMd5(candidate) ?: return false
            if (actualMd5 != expectedMd5) return false
        }
        return true
    }

    private fun computeFileMd5(file: File): String? {
        return runCatching {
            val md = MessageDigest.getInstance("MD5")
            val buf = ByteArray(8192)
            file.inputStream().buffered().use { input ->
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    md.update(buf, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    private fun isSafeRegularFile(candidate: File, canonicalRoot: File): Boolean {
        return try {
            val canonical = candidate.canonicalFile
            canonical.toPath().startsWith(canonicalRoot.toPath()) && canonical.isFile
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private val MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM")
        private val MONTH_DIR_REGEX = Regex("^\\d{4}-\\d{2}$")
    }
}
