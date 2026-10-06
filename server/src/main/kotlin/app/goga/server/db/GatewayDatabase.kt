package app.goga.server.db

import app.goga.model.Timestamps
import app.goga.server.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.File
import java.sql.Connection
import java.time.Instant

class GatewayDatabase(config: AppConfig) : AutoCloseable {
    private val sqlite = config.sqlite
    private val pool: HikariDataSource

    init {
        if (sqlite) {
            val path = config.databaseUrl.removePrefix("jdbc:sqlite:")
            if (path.isNotEmpty() && !path.startsWith(":memory")) {
                File(path).parentFile?.mkdirs()
            }
        }
        val hikari = HikariConfig().apply {
            jdbcUrl = config.databaseUrl
            driverClassName = if (sqlite) "org.sqlite.JDBC" else "org.postgresql.Driver"
            maximumPoolSize = if (sqlite) 4 else 10
            poolName = "goga"
            config.databaseUser?.let { username = it }
            config.databasePassword?.let { password = it }
        }
        pool = HikariDataSource(hikari)
        migrate()
        if (sqlite) {
            connect { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA journal_mode = WAL")
                }
            }
        }
    }

    fun <T> connect(block: (Connection) -> T): T = pool.connection.use { connection ->
        if (sqlite) {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute("PRAGMA busy_timeout = 5000")
            }
        }
        block(connection)
    }

    override fun close() {
        pool.close()
    }

    private fun migrate() {
        connect { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version INTEGER PRIMARY KEY,
                        applied_at TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
            }
            val current = connection.prepareStatement("SELECT MAX(version) FROM schema_migrations").use { ps ->
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
            if (current >= 1) return@connect
            connection.autoCommit = false
            try {
                SCHEMA.forEach { sql ->
                    connection.createStatement().use { it.execute(sql) }
                }
                connection.prepareStatement(
                    "INSERT INTO schema_migrations (version, applied_at) VALUES (1, ?)",
                ).use { ps ->
                    ps.setString(1, Timestamps.formatUtc(Instant.now()))
                    ps.executeUpdate()
                }
                connection.commit()
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    companion object {
        private val SCHEMA = listOf(
            """
            CREATE TABLE users (
                id TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                timezone TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE devices (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(id),
                name TEXT NOT NULL,
                token_hash TEXT NOT NULL UNIQUE,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                revoked_at TEXT
            )
            """.trimIndent(),
            """
            CREATE TABLE pairing_codes (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(id),
                code_hash TEXT NOT NULL UNIQUE,
                expires_at TEXT NOT NULL,
                consumed_at TEXT,
                created_at TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE commands (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(id),
                device_id TEXT NOT NULL REFERENCES devices(id),
                idempotency_key TEXT NOT NULL,
                text TEXT NOT NULL,
                source TEXT NOT NULL,
                client_timestamp TEXT,
                status TEXT NOT NULL,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                UNIQUE (device_id, idempotency_key)
            )
            """.trimIndent(),
            """
            CREATE TABLE command_results (
                id TEXT PRIMARY KEY,
                command_id TEXT UNIQUE REFERENCES commands(id),
                event_id TEXT UNIQUE,
                user_id TEXT NOT NULL REFERENCES users(id),
                device_id TEXT REFERENCES devices(id),
                kind TEXT NOT NULL,
                reply_text TEXT,
                question TEXT,
                entities_json TEXT NOT NULL,
                body_hash TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE result_signatures (
                signature TEXT PRIMARY KEY,
                result_id TEXT NOT NULL REFERENCES command_results(id),
                seen_at TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE notes (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(id),
                device_id TEXT REFERENCES devices(id),
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                deleted_at TEXT
            )
            """.trimIndent(),
            """
            CREATE TABLE push_registrations (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(id),
                device_id TEXT NOT NULL REFERENCES devices(id),
                channel TEXT NOT NULL,
                token TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                UNIQUE (device_id, channel)
            )
            """.trimIndent(),
            "CREATE INDEX idx_commands_queue ON commands (status, created_at)",
            "CREATE INDEX idx_notes_user ON notes (user_id, updated_at)",
            "CREATE INDEX idx_results_user ON command_results (user_id, created_at)",
        )
    }
}
