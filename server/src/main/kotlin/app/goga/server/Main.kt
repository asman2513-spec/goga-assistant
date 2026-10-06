package app.goga.server

import app.goga.server.config.AppConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val config = AppConfig.fromEnv()
    embeddedServer(Netty, host = config.host, port = config.port) {
        gatewayModule(config)
    }.start(wait = true)
}
