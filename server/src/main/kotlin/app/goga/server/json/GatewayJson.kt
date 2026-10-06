package app.goga.server.json

import kotlinx.serialization.json.Json

val GatewayJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}
