package app.goga.server.bot

import app.goga.model.OutboundCommandEvent

/** Outbound link from the gateway to Grok Bot. */
interface BotBridge {
    suspend fun dispatch(event: OutboundCommandEvent)
}

class WebhookDeliveryException(
    val status: Int?,
    message: String,
) : RuntimeException(message)
