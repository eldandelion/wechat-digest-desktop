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
 * Resolves local downloaded file paths inside WeChat's `<account>/msg/file/` and `<account>/msg/attach/`
 * containers using read-only queries against `hardlink/hardlink.db` plus directory fallbacks.
 */
class WeChatAttachmentResolver(
    private val driver: WeChatDatabaseDriver,
    private val explicitAttachmentRootDir: File? = null,
    private val autoDetectSingleAccountRoot: Boolean = true
) {

    private enum class HardlinkKind {
        FILE,
        IMAGE
    }

    private data class HardlinkEntry(
        val kind: HardlinkKind,
        val md5: String,
        val fileName: String,
        val fileSize: Long,
        val dir1Name: String,
        val dir2Name: String
    )

    private val msgFileRoot: File? by lazy { locateMediaRoot(leafRelativePath = "msg/file", fallbackDirName = "file") }

    private val msgAttachRoot: File? by lazy { locateMediaRoot(leafRelativePath = "msg/attach", fallbackDirName = "attach") }

    private val hardlinkEntries: List<HardlinkEntry> by lazy { loadHardlinkEntries() }

    private val fileEntriesByMd5: Map<String, List<HardlinkEntry>> by lazy {
        hardlinkEntries.filter { it.kind == HardlinkKind.FILE && it.md5.isNotBlank() }.groupBy { it.md5 }
    }

    private val fileEntriesByFileName: Map<String, List<HardlinkEntry>> by lazy {
        hardlinkEntries.filter { it.kind == HardlinkKind.FILE && it.fileName.isNotBlank() }.groupBy { it.fileName }
    }

    private val imageEntriesByMd5: Map<String, List<HardlinkEntry>> by lazy {
        hardlinkEntries.filter { it.kind == HardlinkKind.IMAGE && it.md5.isNotBlank() }.groupBy { it.md5 }
    }

    /**
     * Resolves the canonical `<account>/msg/file` root directory if present on disk.
     */
    fun resolveMsgFileRoot(): File? = msgFileRoot

    /**
     * Resolves the canonical `<account>/msg/attach` image cache root directory if present on disk.
     */
    fun resolveMsgAttachRoot(): File? = msgAttachRoot

    /**
     * Returns all verified symlink-free WeChat attachment media roots (`msg/file`, `msg/attach`).
     */
    fun resolveAllowedMediaRoots(): List<File> =
        listOfNotNull(msgFileRoot, msgAttachRoot)
            .map { it.canonicalFile }
            .distinctBy { it.path }

    /**
     * Attempts to resolve the on-disk local path for [attachment]. If not downloaded or unavailable,
     * preserves the existing metadata cleanly with `localPath = null`.
     */
    fun resolve(attachment: EventAttachment): EventAttachment {
        if (attachment.isImageAttachment()) {
            return resolveImageAttachment(attachment)
        }
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

    private fun resolveImageAttachment(attachment: EventAttachment): EventAttachment {
        val allowedRoots = resolveAllowedMediaRoots()
        if (allowedRoots.isEmpty()) return attachment.copy(localPath = null)

        if (!attachment.localPath.isNullOrBlank()) {
            val existing = File(attachment.localPath)
            val matchedExisting = allowedRoots.any { root ->
                isSafeMatchingImageFile(existing, root, attachment)
            }
            if (matchedExisting) {
                return attachment.copy(localPath = existing.canonicalPath)
            }
        }

        val attachRoot = msgAttachRoot
        if (attachRoot != null) {
            val canonicalAttach = attachRoot.canonicalFile
            val viaImageIndex = findViaImageHardlinkIndex(attachment, attachRoot, canonicalAttach)
            if (viaImageIndex != null) {
                return attachment.copy(localPath = viaImageIndex.canonicalPath)
            }
            val viaAttachFallback = findViaAttachDirectoryFallback(attachment, attachRoot, canonicalAttach)
            if (viaAttachFallback != null) {
                return attachment.copy(localPath = viaAttachFallback.canonicalPath)
            }
        }

        // Some images may be stored as file shares in msg/file
        val fileRoot = msgFileRoot
        if (fileRoot != null) {
            val canonicalFile = fileRoot.canonicalFile
            val safeName = WeChatFileMessageParser.sanitizeFileName(attachment.fileName)
            if (safeName != null) {
                val viaFileIndex = findViaHardlinkIndex(attachment, safeName, fileRoot, canonicalFile)
                    ?: findViaDirectoryFallback(attachment, safeName, fileRoot, canonicalFile)
                if (viaFileIndex != null) {
                    return attachment.copy(localPath = viaFileIndex.canonicalPath)
                }
            }
        }

        return attachment.copy(localPath = null)
    }

    /**
     * Re-evaluates local file presence for each attachment in [attachments].
     */
    fun refreshAttachments(attachments: List<EventAttachment>): List<EventAttachment> {
        if (attachments.isEmpty()) return emptyList()
        return attachments.map { resolve(it) }
    }

    private fun locateMediaRoot(leafRelativePath: String, fallbackDirName: String): File? {
        val candidates = buildList {
            explicitAttachmentRootDir?.let { explicit ->
                add(explicit to File(explicit, leafRelativePath))
                add(explicit to File(explicit, fallbackDirName))
                if (explicit.name.equals(fallbackDirName, ignoreCase = true)) {
                    add(explicit to explicit)
                }
                val parent = explicit.parentFile
                if (parent != null && parent.name.equals("msg", ignoreCase = true)) {
                    add(parent to File(parent, fallbackDirName))
                }
            }
            add(driver.baseDir to File(driver.baseDir, leafRelativePath))
            driver.baseDir.parentFile?.let { parent ->
                add(parent to File(parent, leafRelativePath))
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
            val msgMedia = File(account, leafRelativePath)
            msgMedia.takeIf { isSymlinkFreeDirectoryChain(account, msgMedia) }
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
                val hasDir2Id = tableExists(conn, "dir2id")
                val combined = mutableListOf<HardlinkEntry>()
                if (tableExists(conn, "file_hardlink_info_v4")) {
                    val fileColumns = readTableColumns(conn, "file_hardlink_info_v4")
                    buildHardlinkQuery(
                        tableName = "file_hardlink_info_v4",
                        columns = fileColumns,
                        hasDir2Id = hasDir2Id
                    )?.let { sql ->
                        combined += readHardlinkEntries(conn, sql, HardlinkKind.FILE)
                    }
                }
                if (tableExists(conn, "image_hardlink_info_v4")) {
                    val imageColumns = readTableColumns(conn, "image_hardlink_info_v4")
                    buildHardlinkQuery(
                        tableName = "image_hardlink_info_v4",
                        columns = imageColumns,
                        hasDir2Id = hasDir2Id
                    )?.let { sql ->
                        combined += readHardlinkEntries(conn, sql, HardlinkKind.IMAGE)
                    }
                }
                combined
            }
        } catch (e: Exception) {
            System.err.println(
                "[WeChatAttachmentResolver] Failed reading hardlink.db: ${LlmJsonUtils.sanitizeLogMessage(e.message)}"
            )
            emptyList()
        }
    }

    private fun readTableColumns(conn: java.sql.Connection, tableName: String): Set<String> {
        require(tableName == "file_hardlink_info_v4" || tableName == "image_hardlink_info_v4")
        val cols = mutableSetOf<String>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info($tableName)").use { rs ->
                while (rs.next()) {
                    rs.getString("name")?.trim()?.lowercase(Locale.ROOT)?.let { cols.add(it) }
                }
            }
        }
        return cols
    }

    private fun tableExists(conn: java.sql.Connection, tableName: String): Boolean {
        val sql = "SELECT name FROM sqlite_master WHERE type='table' AND name=?"
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, tableName)
            stmt.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun buildHardlinkQuery(
        tableName: String,
        columns: Set<String>,
        hasDir2Id: Boolean
    ): String? {
        require(tableName == "file_hardlink_info_v4" || tableName == "image_hardlink_info_v4")
        if ("file_name" !in columns) return null
        val md5Expr = if ("md5" in columns) "LOWER(COALESCE(h.md5, ''))" else "''"
        val fileSizeExpr = if ("file_size" in columns) "COALESCE(h.file_size, 0)" else "0"
        val hasDir1 = hasDir2Id && "dir1" in columns
        val hasDir2 = hasDir2Id && "dir2" in columns
        val dir1Expr = if (hasDir1) "COALESCE(d1.username, '')" else "''"
        val dir2Expr = if (hasDir2) "COALESCE(d2.username, '')" else "''"
        val dir1Join = if (hasDir1) "LEFT JOIN dir2id d1 ON h.dir1 = d1.rowid" else ""
        val dir2Join = if (hasDir2) "LEFT JOIN dir2id d2 ON h.dir2 = d2.rowid" else ""
        return """
            SELECT $md5Expr AS md5,
                   COALESCE(h.file_name, '') AS file_name,
                   $fileSizeExpr AS file_size,
                   $dir1Expr AS dir1_name,
                   $dir2Expr AS dir2_name
            FROM $tableName h
            $dir1Join
            $dir2Join
        """.trimIndent()
    }

    private fun readHardlinkEntries(conn: java.sql.Connection, sql: String, kind: HardlinkKind): List<HardlinkEntry> {
        val results = mutableListOf<HardlinkEntry>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    val fileName = rs.getString("file_name").orEmpty()
                    if (fileName.isBlank() || fileName == "0") continue
                    results.add(
                        HardlinkEntry(
                            kind = kind,
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
                fileEntriesByMd5[md5Key]?.filter { entry ->
                    isCompatibleHardlinkEntry(entry, safeName, expectedExt)
                }?.let { addAll(it) }
            }
            fileEntriesByFileName[safeName]?.let { matches ->
                val compatible = matches.filter { entry ->
                    (md5Key.isBlank() || entry.md5.isBlank() || entry.md5 == md5Key) &&
                        (attachment.fileSizeBytes <= 0L || entry.fileSize <= 0L || entry.fileSize == attachment.fileSizeBytes)
                }
                addAll(compatible)
            }
        }.distinct()

        for (entry in candidates) {
            val targetName = WeChatFileMessageParser.sanitizeFileName(entry.fileName) ?: safeName
            for (relDir in buildRelativeDirCandidates(entry)) {
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

    private fun findViaImageHardlinkIndex(
        attachment: EventAttachment,
        root: File,
        canonicalRoot: File
    ): File? {
        val md5Key = attachment.md5.trim().lowercase(Locale.ROOT)
        if (md5Key.isBlank()) return null
        val entries = imageEntriesByMd5[md5Key].orEmpty()
            .sortedBy { imageVariantPriority(it.fileName) }

        for (entry in entries) {
            val targetName = WeChatFileMessageParser.sanitizeFileName(entry.fileName) ?: continue
            val candidateDirs = buildRelativeDirCandidates(entry)
            for (relDir in candidateDirs) {
                val dir = File(root, relDir)
                val direct = File(dir, targetName)
                if (isSafeMatchingImageFile(direct, canonicalRoot, attachment)) {
                    return direct
                }
                val imgSubdirCandidate = File(File(dir, "Img"), targetName)
                if (isSafeMatchingImageFile(imgSubdirCandidate, canonicalRoot, attachment)) {
                    return imgSubdirCandidate
                }
            }
        }
        return null
    }

    private fun imageVariantPriority(fileName: String): Int {
        val lower = fileName.lowercase(Locale.ROOT)
        return when {
            lower.endsWith("_t.dat") || lower.contains("_thumb") -> 3
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") -> 0
            lower.endsWith("_h.dat") -> 1
            else -> 2
        }
    }

    private fun buildRelativeDirCandidates(entry: HardlinkEntry): List<String> {
        val dir1 = entry.dir1Name.takeIf { it.isNotBlank() && it != "0" }
        val dir2 = entry.dir2Name.takeIf { it.isNotBlank() && it != "0" }
        return listOfNotNull(
            dir1,
            dir2,
            if (dir2 != null && dir1 != null) "$dir2/$dir1" else null,
            if (dir1 != null && dir2 != null) "$dir1/$dir2" else null
        ).distinct()
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
            root.listFiles { f -> f.isDirectory && !Files.isSymbolicLink(f.toPath()) && MONTH_DIR_REGEX.matches(f.name) }
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

    private fun findViaAttachDirectoryFallback(
        attachment: EventAttachment,
        root: File,
        canonicalRoot: File
    ): File? {
        val md5Key = attachment.md5.trim().lowercase(Locale.ROOT)
        if (md5Key.length != 32) return null

        val targetMonths = buildSet {
            if (attachment.messageTimestampSeconds > 0L) {
                val ym = YearMonth.from(
                    Instant.ofEpochSecond(attachment.messageTimestampSeconds).atZone(ZoneId.systemDefault())
                )
                add(ym.format(MONTH_FORMATTER))
                add(ym.plusMonths(1).format(MONTH_FORMATTER))
                add(ym.minusMonths(1).format(MONTH_FORMATTER))
            }
        }

        val dirsToInspect = buildList {
            for (month in targetMonths) {
                add(File(root, month))
                add(File(File(root, month), "Img"))
            }
            root.listFiles { f -> f.isDirectory && !Files.isSymbolicLink(f.toPath()) }
                ?.take(MAX_ATTACH_SCAN_SUBDIRS)
                ?.forEach { chatDir ->
                    if (MONTH_DIR_REGEX.matches(chatDir.name)) {
                        add(chatDir)
                        add(File(chatDir, "Img"))
                    } else {
                        for (month in targetMonths) {
                            add(File(chatDir, month))
                            add(File(File(chatDir, month), "Img"))
                        }
                        chatDir.listFiles { m -> m.isDirectory && MONTH_DIR_REGEX.matches(m.name) && !Files.isSymbolicLink(m.toPath()) }
                            ?.sortedByDescending { it.name }
                            ?.take(3)
                            ?.forEach { monthDir ->
                                add(monthDir)
                                add(File(monthDir, "Img"))
                            }
                    }
                }
        }.distinctBy { it.absolutePath }

        var matchedCandidate: File? = null
        for (dir in dirsToInspect) {
            if (!dir.exists() || !dir.isDirectory || Files.isSymbolicLink(dir.toPath())) continue
            val files = dir.listFiles { f -> f.isFile && !Files.isSymbolicLink(f.toPath()) } ?: continue
            val sortedFiles = files.sortedBy { imageVariantPriority(it.name) }
            for (candidate in sortedFiles) {
                if (!isSafeRegularFile(candidate, canonicalRoot)) continue
                val lower = candidate.name.lowercase(Locale.ROOT)
                val matchedByHash = if (lower.startsWith(md5Key)) {
                    isSafeMatchingImageFile(candidate, canonicalRoot, attachment)
                } else {
                    candidate.length() <= AttachmentContentExtractor.MAX_FILE_BYTES &&
                        (computeFileMd5(candidate) == md5Key || WeChatImageDecoder.verifyPlaintextMd5(candidate, md5Key))
                }
                if (matchedByHash) {
                    if (imageVariantPriority(candidate.name) < 3) return candidate
                    if (matchedCandidate == null) matchedCandidate = candidate
                }
            }
        }
        return matchedCandidate
    }

    private fun isSafeMatchingImageFile(
        candidate: File,
        canonicalRoot: File,
        attachment: EventAttachment
    ): Boolean {
        if (!isSafeRegularFile(candidate, canonicalRoot)) return false
        val expectedMd5 = attachment.md5.trim().lowercase(Locale.ROOT)
        if (expectedMd5.length == 32) {
            if (candidate.length() > AttachmentContentExtractor.MAX_FILE_BYTES) return false
            val rawMatches = computeFileMd5(candidate) == expectedMd5
            if (rawMatches) return true
            return WeChatImageDecoder.verifyPlaintextMd5(candidate, expectedMd5)
        }
        val expectedSize = attachment.fileSizeBytes
        if (expectedSize > 0L && candidate.length() != expectedSize) return false
        return WeChatImageDecoder.inspectFile(candidate) != null
    }

    private fun isSafeMatchingFile(candidate: File, canonicalRoot: File, attachment: EventAttachment): Boolean {
        if (!isSafeRegularFile(candidate, canonicalRoot)) return false
        val expectedSize = attachment.fileSizeBytes
        if (expectedSize > 0L && candidate.length() != expectedSize) return false

        val expectedMd5 = attachment.md5.trim().lowercase(Locale.ROOT)
        if (expectedMd5.length == 32) {
            if (candidate.length() > AttachmentContentExtractor.MAX_FILE_BYTES) return false
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
        private const val MAX_ATTACH_SCAN_SUBDIRS = 64
    }
}
