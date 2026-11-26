package com.wechatdigest.storage

import com.wechatdigest.core.model.GroupConfig
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupConfigStoreTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var appDb: AppDatabase
    private lateinit var groupConfigStore: GroupConfigStore

    @BeforeEach
    fun setUp() {
        val dbFile = File(tempDir, "test_app.db")
        appDb = AppDatabase(dbFile)
        groupConfigStore = GroupConfigStore(appDb)
    }

    @Test
    fun `get returns null for non-existent room`() {
        assertNull(groupConfigStore.get("unknown@chatroom"))
    }

    @Test
    fun `save and get round-trips GroupConfig including allowedSenderIds and mutedSenderIds`() {
        val config = GroupConfig(
            roomId = "10000000001@chatroom",
            customPrompt = "Focus only on Master thesis deadlines. Ignore Bachelor notices.",
            customPersona = "Graduate Student in Computer Science",
            allowedSenderIds = listOf("wxid_teacher1", "wxid_teacher2", "wxid_teacher1", " "),
            mutedSenderIds = listOf("wxid_spammer", " wxid_spammer ", ""),
            updatedAt = 1731000000L
        )

        groupConfigStore.save(config)
        val loaded = groupConfigStore.get("10000000001@chatroom")

        assertNotNull(loaded)
        assertEquals(config.roomId, loaded.roomId)
        assertEquals(config.customPrompt, loaded.customPrompt)
        assertEquals(config.customPersona, loaded.customPersona)
        assertEquals(listOf("wxid_teacher1", "wxid_teacher2"), loaded.allowedSenderIds)
        assertEquals(listOf("wxid_spammer"), loaded.mutedSenderIds)
        assertEquals(config.updatedAt, loaded.updatedAt)
    }

    @Test
    fun `getAll returns map of all stored configs`() {
        val config1 = GroupConfig(
            roomId = "room1@chatroom",
            customPrompt = "Rule 1",
            customPersona = "Persona 1"
        )
        val config2 = GroupConfig(
            roomId = "room2@chatroom",
            customPrompt = "Rule 2",
            customPersona = "Persona 2"
        )

        groupConfigStore.save(config1)
        groupConfigStore.save(config2)

        val all = groupConfigStore.getAll()
        assertEquals(2, all.size)
        assertTrue(all.containsKey("room1@chatroom"))
        assertTrue(all.containsKey("room2@chatroom"))
        assertEquals("Rule 1", all["room1@chatroom"]?.customPrompt)
    }

    @Test
    fun `save updates existing config and delete removes it`() {
        val original = GroupConfig(
            roomId = "room1@chatroom",
            customPrompt = "Original Rule"
        )
        groupConfigStore.save(original)

        val updated = GroupConfig(
            roomId = "room1@chatroom",
            customPrompt = "Updated Rule",
            customPersona = "New Persona"
        )
        groupConfigStore.save(updated)

        val loaded = groupConfigStore.get("room1@chatroom")
        assertNotNull(loaded)
        assertEquals("Updated Rule", loaded.customPrompt)
        assertEquals("New Persona", loaded.customPersona)

        // Delete
        groupConfigStore.delete("room1@chatroom")
        assertNull(groupConfigStore.get("room1@chatroom"))
        assertTrue(groupConfigStore.getAll().isEmpty())
    }

    @Test
    fun `migrates legacy group_configs table without allowed_sender_ids and fails closed on corrupt JSON`() {
        val legacyFile = File(tempDir, "legacy_app.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${legacyFile.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE group_configs (
                        room_id TEXT PRIMARY KEY,
                        custom_prompt TEXT NOT NULL,
                        custom_persona TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute("INSERT INTO group_configs VALUES ('legacy@chatroom', 'Rule', 'Persona', 1700000000)")
            }
        }

        val migratedDb = AppDatabase(legacyFile)
        val migratedStore = GroupConfigStore(migratedDb)
        val legacyLoaded = migratedStore.getOrThrow("legacy@chatroom")
        assertNotNull(legacyLoaded)
        assertTrue(legacyLoaded.allowedSenderIds.isEmpty())

        // Save trims whitespace before deduplicating
        migratedStore.save(
            GroupConfig(
                roomId = "trim@chatroom",
                allowedSenderIds = listOf(" wxid_a ", "wxid_a", "  ")
            )
        )
        assertEquals(listOf("wxid_a"), migratedStore.getOrThrow("trim@chatroom")?.allowedSenderIds)

        // Corrupt JSON: get() returns null gracefully, getOrThrow() throws so sync fails closed
        migratedDb.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("UPDATE group_configs SET allowed_sender_ids = 'NOT_JSON' WHERE room_id = 'trim@chatroom'")
            }
        }
        assertNull(migratedStore.get("trim@chatroom"))
        kotlin.test.assertFailsWith<Exception> {
            migratedStore.getOrThrow("trim@chatroom")
        }
    }
}
