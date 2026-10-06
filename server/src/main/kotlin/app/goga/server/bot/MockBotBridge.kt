package app.goga.server.bot

import app.goga.model.BotEntity
import app.goga.model.BotHeaders
import app.goga.model.BotResultKinds
import app.goga.model.BotResultRequest
import app.goga.model.EntityTypes
import app.goga.model.OutboundCommandEvent
import app.goga.server.auth.Hmac
import app.goga.server.config.AppConfig
import app.goga.server.json.GatewayJson
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stands in for Grok Bot. It records the outbound event and, when auto-reply is on,
 * calls the real inbound endpoint with a valid HMAC so the phone can poll the result.
 */
class MockBotBridge(
    private val client: HttpClient,
    private val config: AppConfig,
    private val scope: CoroutineScope,
) : BotBridge {
    val dispatched: MutableList<OutboundCommandEvent> = CopyOnWriteArrayList()

    override suspend fun dispatch(event: OutboundCommandEvent) {
        dispatched += event
        if (!config.mockAutoReply) return
        scope.launch(Dispatchers.IO) {
            delay(config.mockReplyDelayMillis)
            runCatching { postResult(event) }
                .onFailure { error ->
                    log.warn(
                        "Mock callback failed for command {}: {}",
                        event.commandId,
                        error.javaClass.simpleName,
                    )
                }
        }
    }

    private suspend fun postResult(event: OutboundCommandEvent) {
        val request = BotResultRequest(
            commandId = event.commandId,
            kind = BotResultKinds.REPLY,
            replyText = "Принято: ${event.text}",
            entities = listOf(
                BotEntity(
                    type = EntityTypes.NOTE,
                    title = "Команда",
                    body = event.text,
                ),
            ),
        )
        val body = GatewayJson.encodeToString(request)
        val timestamp = Instant.now().epochSecond.toString()
        val signature = Hmac.sign(config.inboundSecret, timestamp, body.toByteArray(Charsets.UTF_8))
        val response = client.post(config.internalBaseUrl.trimEnd('/') + "/v1/bot/results") {
            header(BotHeaders.TIMESTAMP, timestamp)
            header(BotHeaders.SIGNATURE, signature)
            setBody(TextContent(body, ContentType.Application.Json))
        }
        if (!response.status.isSuccess()) {
            log.warn("Mock callback HTTP {} for command {}", response.status.value, event.commandId)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(MockBotBridge::class.java)
    }
}
