package app.goga.server

import app.goga.server.config.AppConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AppConfigTest {
    @Test
    fun defaultsToSqliteAndMockBridge() {
        val config = AppConfig.fromEnv { name ->
            when (name) {
                "GATEWAY_BOOTSTRAP_TOKEN" -> "replace-with-a-long-random-string"
                "BOT_INBOUND_SECRET" -> "replace-with-another-long-random-string"
                else -> null
            }
        }
        assertEquals("mock", config.botBridge)
        assertEquals("jdbc:sqlite:./data/goga.db", config.databaseUrl)
        assertEquals("Europe/Moscow", config.userTimezone)
        assertEquals("http://127.0.0.1:8080/v1/bot/results", config.callbackUrl)
    }

    @Test
    fun webhookModeRequiresUrlAndAuthValue() {
        assertThrows<IllegalArgumentException> {
            AppConfig.fromEnv { name ->
                when (name) {
                    "GATEWAY_BOOTSTRAP_TOKEN" -> "replace-with-a-long-random-string"
                    "BOT_INBOUND_SECRET" -> "replace-with-another-long-random-string"
                    "BOT_BRIDGE" -> "webhook"
                    else -> null
                }
            }
        }
    }
}
