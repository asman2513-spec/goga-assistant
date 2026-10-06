package app.goga.server.queue

import app.goga.model.CallbackInfo
import app.goga.model.OutboundCommandEvent
import app.goga.model.Timestamps
import app.goga.server.bot.BotBridge
import app.goga.server.config.AppConfig
import app.goga.server.db.GatewayStore
import app.goga.server.db.QueuedCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Instant

class CommandWorker(
    private val store: GatewayStore,
    private val bridge: BotBridge,
    private val config: AppConfig,
    private val clock: () -> Instant,
) {
    suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val claimed = withContext(Dispatchers.IO) {
                store.claimNext(Timestamps.formatUtc(clock()))
            }
            if (claimed == null) {
                delay(200)
                continue
            }
            try {
                bridge.dispatch(claimed.toEvent())
                log.info("Dispatched command {}", claimed.id)
            } catch (error: Exception) {
                val safe = error.message?.take(300) ?: error.javaClass.simpleName
                withContext(Dispatchers.IO) { store.markFailed(claimed.id, safe) }
                log.warn("Dispatch failed for command {}: {}", claimed.id, error.javaClass.simpleName)
            }
        }
    }

    private fun QueuedCommand.toEvent(): OutboundCommandEvent {
        val instant = Instant.parse(createdAt)
        return OutboundCommandEvent(
            commandId = id,
            text = text,
            timestamp = Timestamps.formatMoscow(instant),
            source = source,
            deviceId = deviceId,
            userId = userId,
            callback = CallbackInfo(url = config.callbackUrl),
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(CommandWorker::class.java)
    }
}
