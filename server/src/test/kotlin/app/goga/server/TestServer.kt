package app.goga.server

import app.goga.model.CommandDto
import app.goga.model.CreatePairingCodeResponse
import app.goga.model.PairDeviceRequest
import app.goga.model.PairDeviceResponse
import app.goga.server.config.AppConfig
import app.goga.server.json.GatewayJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.nio.file.Files
import java.time.Instant

internal class RunningGateway(
    val baseUrl: String,
    val config: AppConfig,
    val client: HttpClient,
)

internal fun testConfig(
    port: Int,
    databaseUrl: String,
    bridge: String = "mock",
    autoReply: Boolean = true,
): AppConfig = AppConfig(
    host = "127.0.0.1",
    port = port,
    databaseUrl = databaseUrl,
    databaseUser = null,
    databasePassword = null,
    bootstrapToken = "test-bootstrap-token",
    inboundSecret = "test-inbound-secret",
    inboundMaxSkewSeconds = 300,
    botBridge = bridge,
    webhookUrl = null,
    webhookAuthHeader = "Authorization",
    webhookAuthValue = "",
    webhookTimeoutSeconds = 5,
    webhookMaxRetries = 3,
    webhookRetryBaseMillis = 20,
    publicBaseUrl = "http://127.0.0.1:$port",
    internalBaseUrl = "http://127.0.0.1:$port",
    userTimezone = "Europe/Moscow",
    userDisplayName = "Пользователь",
    singleUserId = null,
    pairingCodeTtlSeconds = 600,
    mockAutoReply = autoReply,
    mockReplyDelayMillis = 30,
)

internal fun withGateway(
    autoReply: Boolean = true,
    clock: () -> Instant = { Instant.now() },
    block: suspend RunningGateway.() -> Unit,
) {
    val port = ServerSocket(0).use { it.localPort }
    val database = Files.createTempFile("goga-", ".db")
    val config = testConfig(
        port = port,
        databaseUrl = "jdbc:sqlite:${database.toAbsolutePath()}",
        autoReply = autoReply,
    )
    val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
        gatewayModule(config, clock)
    }
    val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(GatewayJson) }
        expectSuccess = false
    }
    server.start(wait = false)
    val running = RunningGateway("http://127.0.0.1:$port", config, client)
    try {
        runBlocking { running.block() }
    } finally {
        client.close()
        server.stop(200, 1_000)
        Files.deleteIfExists(database)
        Files.deleteIfExists(database.resolveSibling("${database.fileName}-wal"))
        Files.deleteIfExists(database.resolveSibling("${database.fileName}-shm"))
    }
}

internal suspend fun RunningGateway.pair(deviceName: String = "Honor Magic 7 Pro"): PairDeviceResponse {
    val issued = client.post("$baseUrl/v1/pairing-codes") {
        header(HttpHeaders.Authorization, "Bearer ${config.bootstrapToken}")
    }
    check(issued.status.value == 201) { "pairing code status ${issued.status}" }
    val code = issued.body<CreatePairingCodeResponse>().code
    val paired = client.post("$baseUrl/v1/devices/pair") {
        contentType(ContentType.Application.Json)
        setBody(PairDeviceRequest(code = code, deviceName = deviceName))
    }
    check(paired.status.value == 201) { "pair status ${paired.status}" }
    return paired.body()
}

internal suspend fun RunningGateway.awaitCommand(token: String, id: String): CommandDto {
    repeat(50) {
        val response = client.get("$baseUrl/v1/commands/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val dto = response.body<CommandDto>()
        if (dto.status == "completed" || dto.status == "failed") return dto
        delay(100)
    }
    error("timed out waiting for command $id")
}
