package app.goga.server.http

import app.goga.server.auth.Tokens
import app.goga.server.config.AppConfig
import app.goga.server.db.DevicePrincipal
import app.goga.server.db.GatewayStore
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.routing.RoutingContext
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun <T> blockingDb(block: () -> T): T = withContext(Dispatchers.IO) { block() }

fun RoutingContext.requireBootstrap(config: AppConfig) {
    val header = call.request.header(HttpHeaders.Authorization).orEmpty()
    val token = if (header.startsWith("Bearer ")) header.removePrefix("Bearer ").trim() else ""
    if (!Tokens.constantTimeEquals(token, config.bootstrapToken)) {
        unauthorized("unauthorized", "Bootstrap token is required")
    }
}

suspend fun RoutingContext.requireDevice(store: GatewayStore): DevicePrincipal {
    val header = call.request.header(HttpHeaders.Authorization).orEmpty()
    if (!header.startsWith("Bearer ")) unauthorized("unauthorized", "Device token is required")
    val token = header.removePrefix("Bearer ").trim()
    if (token.isEmpty()) unauthorized("unauthorized", "Device token is required")
    return blockingDb { store.authenticate(token) } ?: unauthorized("unauthorized", "Invalid device token")
}

suspend inline fun <reified T : Any> ApplicationCall.receiveBody(): T = try {
    receive()
} catch (error: BadRequestException) {
    badRequest("Malformed JSON")
}

fun RoutingContext.pathUuid(name: String): String {
    val value = call.parameters[name] ?: badRequest("Missing $name")
    val ok = value.matches(
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"),
    )
    if (!ok) badRequest("$name must be a UUID")
    return value
}

fun limitParam(raw: String?, default: Int, max: Int): Int {
    val parsed = raw?.toIntOrNull() ?: return default
    if (parsed < 1) badRequest("limit must be positive")
    return parsed.coerceAtMost(max)
}
