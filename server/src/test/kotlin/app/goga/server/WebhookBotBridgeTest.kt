package app.goga.server

import app.goga.model.CallbackInfo
import app.goga.model.OutboundCommandEvent
import app.goga.server.bot.WebhookBotBridge
import app.goga.server.bot.WebhookDeliveryException
import app.goga.server.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class WebhookBotBridgeTest {
    @Test
    fun sendsConfiguredAuthHeaderAndMoscowTimestamp() = runBlocking {
        var header = ""
        var body = ""
        val engine = MockEngine { request ->
            header = request.headers["X-Bot-Key"].orEmpty()
            body = request.body.toByteArray().decodeToString()
            respond("ok", HttpStatusCode.OK)
        }
        val bridge = WebhookBotBridge(HttpClient(engine), webhookConfig())
        bridge.dispatch(sampleEvent())
        assertEquals("sender-key", header)
        assertTrue(body.contains("\"event\":\"command.created\""))
        assertTrue(body.contains("+03:00"))
        assertTrue(body.contains("\"command_id\":\"11111111-1111-1111-1111-111111111111\""))
        engine.close()
    }

    @Test
    fun retriesServerErrorsThenSucceeds() = runBlocking {
        val calls = AtomicInteger()
        val engine = MockEngine {
            val n = calls.incrementAndGet()
            if (n == 1) {
                respond("busy", HttpStatusCode.BadGateway)
            } else {
                respond("ok", HttpStatusCode.OK)
            }
        }
        val bridge = WebhookBotBridge(HttpClient(engine), webhookConfig(retries = 3, retryBase = 1))
        bridge.dispatch(sampleEvent())
        assertEquals(2, calls.get())
        engine.close()
    }

    @Test
    fun doesNotRetryClientErrors() {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("no", HttpStatusCode.BadRequest, headersOf())
        }
        val bridge = WebhookBotBridge(HttpClient(engine), webhookConfig(retries = 3, retryBase = 1))
        assertThrows(WebhookDeliveryException::class.java) {
            runBlocking { bridge.dispatch(sampleEvent()) }
        }
        assertEquals(1, calls.get())
        engine.close()
    }

    private fun webhookConfig(retries: Int = 1, retryBase: Long = 20) = AppConfig(
        host = "127.0.0.1",
        port = 9,
        databaseUrl = "jdbc:sqlite::memory:",
        databaseUser = null,
        databasePassword = null,
        bootstrapToken = "test-bootstrap-token",
        inboundSecret = "test-inbound-secret",
        inboundMaxSkewSeconds = 300,
        botBridge = "webhook",
        webhookUrl = "https://bot.example/hooks/goga",
        webhookAuthHeader = "X-Bot-Key",
        webhookAuthValue = "sender-key",
        webhookTimeoutSeconds = 2,
        webhookMaxRetries = retries,
        webhookRetryBaseMillis = retryBase,
        publicBaseUrl = "https://gateway.example",
        internalBaseUrl = "http://127.0.0.1:9",
        userTimezone = "Europe/Moscow",
        userDisplayName = "Пользователь",
        singleUserId = null,
        pairingCodeTtlSeconds = 600,
        mockAutoReply = false,
        mockReplyDelayMillis = 0,
    )

    private fun sampleEvent() = OutboundCommandEvent(
        commandId = "11111111-1111-1111-1111-111111111111",
        text = "напомни",
        timestamp = "2026-10-06T06:00:00+03:00",
        source = "voice",
        deviceId = "22222222-2222-2222-2222-222222222222",
        userId = "33333333-3333-3333-3333-333333333333",
        callback = CallbackInfo(url = "https://gateway.example/v1/bot/results"),
    )
}
