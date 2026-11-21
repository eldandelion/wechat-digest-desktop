package com.wechatdigest.storage

import org.sqlite.SQLiteConfig
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

class AppDatabase(val dbFile: File = defaultDbFile()) {

    init {
        dbFile.parentFile?.mkdirs()
        initSchema()
    }

    fun getConnection(): Connection {
        val config = SQLiteConfig().apply {
            setBusyTimeout(5000)
            setJournalMode(SQLiteConfig.JournalMode.WAL)
        }
        return DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}", config.toProperties())
    }

    private fun initSchema() {
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS settings (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    );
                    """.trimIndent()
                )

                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS group_configs (
                        room_id TEXT PRIMARY KEY,
                        custom_prompt TEXT NOT NULL,
                        custom_persona TEXT NOT NULL,
                        allowed_sender_ids TEXT NOT NULL DEFAULT '[]',
                        updated_at INTEGER NOT NULL
                    );
                    """.trimIndent()
                )
                ensureColumnExists(stmt, "group_configs", "allowed_sender_ids", "TEXT NOT NULL DEFAULT '[]'")

                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS event_cards (
                        id TEXT PRIMARY KEY,
                        dedupe_key TEXT UNIQUE,
                        title TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        translated_summary TEXT NOT NULL,
                        sections TEXT DEFAULT '[]',
                        category TEXT NOT NULL,
                        priority TEXT NOT NULL,
                        event_date TEXT,
                        action_items TEXT NOT NULL,
                        original_sender TEXT NOT NULL,
                        source_room_id TEXT NOT NULL,
                        source_room_name TEXT DEFAULT '',
                        source_messages TEXT DEFAULT '',
                        translated_source_messages TEXT DEFAULT '',
                        timestamp_seconds INTEGER NOT NULL,
                        is_read INTEGER DEFAULT 0,
                        is_starred INTEGER DEFAULT 0,
                        is_dismissed INTEGER DEFAULT 0,
                        created_at INTEGER NOT NULL
                    );
                    """.trimIndent()
                )

                // Safe schema migrations for existing databases
                runCatching { stmt.execute("ALTER TABLE event_cards ADD COLUMN source_room_name TEXT DEFAULT ''") }
                runCatching { stmt.execute("ALTER TABLE event_cards ADD COLUMN source_messages TEXT DEFAULT ''") }
                runCatching { stmt.execute("ALTER TABLE event_cards ADD COLUMN translated_source_messages TEXT DEFAULT ''") }
                runCatching { stmt.execute("ALTER TABLE event_cards ADD COLUMN sections TEXT DEFAULT '[]'") }
                runCatching { stmt.execute("ALTER TABLE event_cards ADD COLUMN is_starred INTEGER DEFAULT 0") }

                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS sync_checkpoints (
                        room_id TEXT PRIMARY KEY,
                        last_local_id INTEGER NOT NULL DEFAULT 0,
                        last_sync_timestamp INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL
                    );
                    """.trimIndent()
                )
            }
        }
    }

    private fun ensureColumnExists(stmt: Statement, table: String, column: String, definition: String) {
        val exists = stmt.executeQuery("PRAGMA table_info($table)").use { rs ->
            var found = false
            while (rs.next()) {
                if (rs.getString("name").equals(column, ignoreCase = true)) {
                    found = true
                    break
                }
            }
            found
        }
        if (!exists) {
            stmt.execute("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }

    companion object {
        fun defaultDbFile(): File {
            val userHome = System.getProperty("user.home") ?: "."
            return File(userHome, "Library/Application Support/WeChatDigest/app.db")
        }
    }
}
