package com.wechatdigest.core.db

import com.wechatdigest.core.model.GroupChat
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-sync session cache holding parsed keys, resolved/decrypted database snapshots,
 * contacts lookup map, message shard metadata, and an owner-only ephemeral snapshot
 * directory cleaned up on [close].
 */
class WeChatSessionContext(
    val driver: WeChatDatabaseDriver
) : Closeable {

    data class RoomShardTableBounds(
        val minCreateTime: Long,
        val maxCreateTime: Long,
        val maxLocalId: Long
    )

    data class MessageShardIndexEntry(
        val shardFile: File,
        val name2IdMap: Map<Long, String>,
        val tableBounds: Map<String, RoomShardTableBounds>
    )

    private val resolvedDbPaths = ConcurrentHashMap<String, File>()
    private val contactsLock = Any()
    private val shardIndexLock = Any()
    private val chatroomsLock = Any()
    private val roomOwnersLock = Any()
    private val snapshotDirLock = Any()

    @Volatile
    private var cachedContactsMap: Map<String, String>? = null

    @Volatile
    private var cachedShardIndex: List<MessageShardIndexEntry>? = null

    @Volatile
    private var cachedChatrooms: List<GroupChat>? = null

    @Volatile
    private var cachedRoomOwners: Map<String, String>? = null

    @Volatile
    private var ephemeralSnapshotDir: File? = null

    @Volatile
    private var closed = false

    init {
        driver.attachSession(this)
    }

    private val keysFileLazy: File? by lazy {
        WcdbDecryptor.findKeysFile(driver.customKeysFile, driver.baseDir)
    }

    private val keysMapLazy: Map<String, ByteArray>? by lazy {
        val keysFile = keysFileLazy ?: return@lazy null
        WcdbDecryptor.loadKeys(keysFile)
    }

    /** Returns the cached `all_keys.json` map or throws if missing when opening an encrypted database. */
    fun getKeysOrThrow(forDbName: String): Map<String, ByteArray> {
        check(!closed) { "WeChatSessionContext is already closed" }
        return keysMapLazy ?: throw IllegalStateException(
            "WeChat database '$forDbName' is encrypted with SQLCipher4, but all_keys.json was not found. " +
                "Please place all_keys.json in ${driver.baseDir.absolutePath} or specify it in Settings."
        )
    }

    /** Returns a previously resolved or snapshotted database file for [rawFile] in this session. */
    fun getResolvedFile(rawFile: File): File? {
        if (closed) return null
        return resolvedDbPaths[rawFile.canonicalPath]
    }

    /** Caches the resolved database file for [rawFile] for the remainder of this session. */
    fun cacheResolvedFile(rawFile: File, resolvedFile: File) {
        if (!closed) {
            resolvedDbPaths[rawFile.canonicalPath] = resolvedFile
        }
    }

    /** Returns an owner-only ephemeral directory for plaintext WAL snapshots during this sync. */
    fun getOrCreateSnapshotDir(): File {
        check(!closed) { "WeChatSessionContext is already closed" }
        ephemeralSnapshotDir?.let { return it }
        synchronized(snapshotDirLock) {
            check(!closed) { "WeChatSessionContext is already closed" }
            ephemeralSnapshotDir?.let { return it }
            val ownerOnlyDirPerms = setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            )
            val created = runCatching {
                Files.createTempDirectory(
                    "wd_sync_",
                    PosixFilePermissions.asFileAttribute(ownerOnlyDirPerms)
                ).toFile()
            }.getOrElse {
                Files.createTempDirectory("wd_sync_").toFile().also { dir ->
                    WcdbDecryptor.setOwnerOnlyPermissions(dir, isDirectory = true)
                }
            }
            ephemeralSnapshotDir = created
            return created
        }
    }

    /** Returns the cached contacts map, invoking [loader] at most once per session. */
    fun getOrLoadContactsMap(loader: () -> Map<String, String>): Map<String, String> {
        check(!closed) { "WeChatSessionContext is already closed" }
        cachedContactsMap?.let { return it }
        synchronized(contactsLock) {
            check(!closed) { "WeChatSessionContext is already closed" }
            cachedContactsMap?.let { return it }
            return loader().also { cachedContactsMap = it }
        }
    }

    /** Returns the cached `message_*.db` shard index, invoking [loader] at most once per session. */
    fun getOrLoadShardIndex(loader: () -> List<MessageShardIndexEntry>): List<MessageShardIndexEntry> {
        check(!closed) { "WeChatSessionContext is already closed" }
        cachedShardIndex?.let { return it }
        synchronized(shardIndexLock) {
            check(!closed) { "WeChatSessionContext is already closed" }
            cachedShardIndex?.let { return it }
            return loader().also { cachedShardIndex = it }
        }
    }

    /** Returns the cached chatroom list with metadata, invoking [loader] at most once per session. */
    fun getOrLoadChatrooms(loader: () -> List<GroupChat>): List<GroupChat> {
        check(!closed) { "WeChatSessionContext is already closed" }
        cachedChatrooms?.let { return it }
        synchronized(chatroomsLock) {
            check(!closed) { "WeChatSessionContext is already closed" }
            cachedChatrooms?.let { return it }
            return loader().also { cachedChatrooms = it }
        }
    }

    /** Returns the cached `roomId -> ownerUsername` map, invoking [loader] at most once per session. */
    fun getOrLoadRoomOwners(loader: () -> Map<String, String>): Map<String, String> {
        check(!closed) { "WeChatSessionContext is already closed" }
        cachedRoomOwners?.let { return it }
        synchronized(roomOwnersLock) {
            check(!closed) { "WeChatSessionContext is already closed" }
            cachedRoomOwners?.let { return it }
            return loader().also { cachedRoomOwners = it }
        }
    }

    override fun close() {
        closed = true
        driver.detachSession(this)
        resolvedDbPaths.clear()
        cachedContactsMap = null
        cachedShardIndex = null
        cachedChatrooms = null
        cachedRoomOwners = null
        synchronized(snapshotDirLock) {
            ephemeralSnapshotDir?.let { dir ->
                runCatching {
                    if (!Files.isSymbolicLink(dir.toPath()) && dir.exists()) {
                        dir.deleteRecursively()
                    }
                }
            }
            ephemeralSnapshotDir = null
        }
    }
}
