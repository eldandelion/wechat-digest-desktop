package com.wechatdigest.storage

import com.wechatdigest.core.model.SyncCheckpoint
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncCheckpointStoreTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var appDb: AppDatabase
    private lateinit var store: SyncCheckpointStore

    @BeforeEach
    fun setUp() {
        val dbFile = File(tempDir, "test_app.db")
        appDb = AppDatabase(dbFile)
        store = SyncCheckpointStore(appDb)
    }

    @Test
    fun `get returns null when no checkpoint exists`() {
        assertNull(store.get("nonexistent@chatroom"))
        assertTrue(store.getAll().isEmpty())
    }

    @Test
    fun `save and get round-trips checkpoint data correctly`() {
        val cp = SyncCheckpoint(
            roomId = "test@chatroom",
            lastLocalId = 10452L,
            lastSyncTimestamp = 1731000000L,
            updatedAt = 1731000010L
        )

        store.save(cp)

        val retrieved = store.get("test@chatroom")
        assertNotNull(retrieved)
        assertEquals("test@chatroom", retrieved.roomId)
        assertEquals(10452L, retrieved.lastLocalId)
        assertEquals(1731000000L, retrieved.lastSyncTimestamp)
        assertEquals(1731000010L, retrieved.updatedAt)

        val all = store.getAll()
        assertEquals(1, all.size)
        assertEquals(retrieved, all["test@chatroom"])
    }

    @Test
    fun `save overwrites existing checkpoint for same room`() {
        val initial = SyncCheckpoint("room1@chatroom", 100L, 1000L, 1000L)
        store.save(initial)

        val updated = SyncCheckpoint("room1@chatroom", 200L, 2000L, 2000L)
        store.save(updated)

        val retrieved = store.get("room1@chatroom")
        assertNotNull(retrieved)
        assertEquals(200L, retrieved.lastLocalId)
        assertEquals(2000L, retrieved.lastSyncTimestamp)
    }

    @Test
    fun `reset removes checkpoint for specific room only`() {
        store.save(SyncCheckpoint("room1@chatroom", 100L, 1000L, 1000L))
        store.save(SyncCheckpoint("room2@chatroom", 200L, 2000L, 2000L))

        store.reset("room1@chatroom")

        assertNull(store.get("room1@chatroom"))
        assertNotNull(store.get("room2@chatroom"))
        assertEquals(1, store.getAll().size)
    }

    @Test
    fun `resetAll removes all checkpoints`() {
        store.save(SyncCheckpoint("room1@chatroom", 100L, 1000L, 1000L))
        store.save(SyncCheckpoint("room2@chatroom", 200L, 2000L, 2000L))

        store.resetAll()

        assertTrue(store.getAll().isEmpty())
    }

    @Test
    fun `getOrThrow and saveOrThrow propagate SQLite failures fail-closed`() {
        val brokenDb = AppDatabase(File(tempDir, "broken_checkpoints.db"))
        val brokenStore = SyncCheckpointStore(brokenDb)
        brokenDb.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DROP TABLE sync_checkpoints")
            }
        }
        kotlin.test.assertFailsWith<java.sql.SQLException> {
            brokenStore.getOrThrow("room@chatroom")
        }
        kotlin.test.assertFailsWith<java.sql.SQLException> {
            brokenStore.saveOrThrow(SyncCheckpoint("room@chatroom", 10L, 1000L, 1000L))
        }
    }

    @Test
    fun `AppDatabase migrates legacy 4-column sync_checkpoints and round-trips shard fields`() {
        val legacyFile = File(tempDir, "legacy_checkpoints.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${legacyFile.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE sync_checkpoints (
                        room_id TEXT PRIMARY KEY,
                        last_local_id INTEGER NOT NULL DEFAULT 0,
                        last_sync_timestamp INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL
                    );
                    """.trimIndent()
                )
                stmt.executeUpdate(
                    "INSERT INTO sync_checkpoints VALUES ('legacy@chatroom', 42, 1730000000, 1730000005)"
                )
            }
        }

        val migratedDb = AppDatabase(legacyFile)
        val migratedStore = SyncCheckpointStore(migratedDb)

        val legacyCp = migratedStore.getOrThrow("legacy@chatroom")
        assertNotNull(legacyCp)
        assertEquals(42L, legacyCp.lastLocalId)
        assertEquals("", legacyCp.lastShardName)
        assertEquals(0L, legacyCp.lastCreateTime)

        val upgraded = legacyCp.copy(
            lastLocalId = 55L,
            lastShardName = "message_1.db",
            lastCreateTime = 1795000123L
        )
        migratedStore.saveOrThrow(upgraded)

        val fetched = migratedStore.getOrThrow("legacy@chatroom")
        assertNotNull(fetched)
        assertEquals("message_1.db", fetched.lastShardName)
        assertEquals(1795000123L, fetched.lastCreateTime)
        assertEquals(fetched, migratedStore.getAll()["legacy@chatroom"])
    }
}
