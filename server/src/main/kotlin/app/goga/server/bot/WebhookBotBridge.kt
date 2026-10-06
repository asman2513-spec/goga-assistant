package app.goga.server.bot

import app.goga.model.BotHeaders
import app.goga.model.OutboundCommandEvent
import app.goga.server.config.AppConfig
import app.goga.server.json.GatewayJson
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString

class WebhookBotBridge(
    private val client: HttpClient,
    private val config: AppConfig,
) : BotBridge {
    override suspend fun dispatch(event: OutboundCommandEvent) {
        val url = config.webhookUrl ?: error("BOT_WEBHOOK_URL is not set")
        val payload = GatewayJson.encodeToString(event)
        val attempts = config.webhookMaxRetries.coerceAtLeast(1)
        var lastStatus: Int? = null
        var lastError: Exception? = null
        for (attempt in 0 until attempts) {
            try {
                val response = post(url, payload)
                if (response.status.isSuccess()) return
                lastStatus = response.status.value
                if (response.status.value in 400..499) break
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
            }
            if (attempt < attempts - 1) {
                delay(config.webhookRetryBaseMillis shl attempt)
            }
        }
        val detail = when {
            lastStatus != null -> "status=$lastStatus"
            lastError != null -> lastError.javaClass.simpleName
            else -> "failed"
        }
        throw WebhookDeliveryException(lastStatus, "Webhook delivery failed ($detail)")
    }

    private suspend fun post(url: String, payload: String): HttpResponse =
        client.post(url) {
            header(HttpHeaders.UserAgent, BotHeaders.USER_AGENT)
            header(config.webhookAuthHeader, config.webhookAuthValue)
            setBody(TextContent(payload, ContentType.Application.Json))
        }
}
