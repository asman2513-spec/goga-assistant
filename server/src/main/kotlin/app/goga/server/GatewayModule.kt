package app.goga.server

import app.goga.model.BotHeaders
import app.goga.model.BotResultRequest
import app.goga.model.CommandSources
import app.goga.model.CreateCommandRequest
import app.goga.model.ErrorResponse
import app.goga.model.EventListResponse
import app.goga.model.NoteListResponse
import app.goga.model.PairDeviceRequest
import app.goga.model.PatchNoteRequest
import app.goga.model.RegisterPushRequest
import app.goga.model.Timestamps
import app.goga.model.UpsertNoteRequest
import app.goga.server.auth.Hmac
import app.goga.server.bot.BotBridge
import app.goga.server.bot.MockBotBridge
import app.goga.server.bot.WebhookBotBridge
import app.goga.server.config.AppConfig
import app.goga.server.db.GatewayDatabase
import app.goga.server.db.GatewayStore
import app.goga.server.db.parseInstant
import app.goga.server.http.RequestException
import app.goga.server.http.badRequest
import app.goga.server.http.blockingDb
import app.goga.server.http.limitParam
import app.goga.server.http.pathUuid
import app.goga.server.http.payloadTooLarge
import app.goga.server.http.receiveBody
import app.goga.server.http.requireBootstrap
import app.goga.server.http.requireDevice
import app.goga.server.http.unauthorized
import app.goga.server.json.GatewayJson
import app.goga.server.queue.CommandWorker
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.patch
import io.ktor.server.routing.delete
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.event.Level
import java.time.Instant

fun Application.gatewayModule(
    config: AppConfig,
    clock: () -> Instant = { Instant.now() },
) {
    val database = GatewayDatabase(config)
    val store = GatewayStore(database, config, clock)
    store.ensureUser()

    val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = config.webhookTimeoutSeconds.coerceAtLeast(1) * 1_000
            connectTimeoutMillis = 5_000
        }
        expectSuccess = false
    }
    val bridge: BotBridge = if (config.botBridge == "webhook") {
        WebhookBotBridge(http, config)
    } else {
        MockBotBridge(http, config, this)
    }
    val workerJob = launch {
        CommandWorker(store, bridge, config, clock).loop()
    }
    monitor.subscribe(ApplicationStopped) {
        workerJob.cancel()
        http.close()
        database.close()
    }
    warnIfPlaceholder(config)

    val openApi = checkNotNull(javaClass.classLoader.getResource("openapi.yaml")) {
        "openapi.yaml is missing from the classpath"
    }.readText()

    install(ContentNegotiation) { json(GatewayJson) }
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path() != "/health" && call.request.path() != "/v1/health" }
    }
    install(StatusPages) {
        exception<RequestException> { call, cause ->
            call.respond(
                HttpStatusCode.fromValue(cause.status),
                ErrorResponse(cause.code, cause.message ?: ""),
            )
        }
        exception<io.ktor.server.plugins.BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Malformed request"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal_error", "Internal error"))
        }
    }

    routing {
        get("/health") { call.respond(health(config)) }
        get("/v1/health") { call.respond(health(config)) }
        get("/openapi.yaml") {
            call.respondText(openApi, ContentType.parse("application/yaml"))
        }

        route("/v1") {
            post("/pairing-codes") {
                requireBootstrap(config)
                call.respond(HttpStatusCode.Created, blockingDb { store.issuePairingCode() })
            }
            post("/devices/pair") {
                val body = call.receiveBody<PairDeviceRequest>()
                call.respond(HttpStatusCode.Created, blockingDb { store.pairDevice(body.code, body.deviceName) })
            }
            delete("/devices/{id}") {
                requireBootstrap(config)
                val id = pathUuid("id")
                blockingDb { store.revokeDevice(id) }
                call.respond(HttpStatusCode.NoContent)
            }
            post("/bot/results") {
                val timestamp = call.request.header(BotHeaders.TIMESTAMP).orEmpty()
                val signature = call.request.header(BotHeaders.SIGNATURE).orEmpty().trim().lowercase()
                val text = call.receiveText()
                val raw = text.toByteArray(Charsets.UTF_8)
                if (raw.size > 100_000) payloadTooLarge()
                if (!Hmac.verify(config.inboundSecret, timestamp, signature, raw)) {
                    unauthorized("unauthorized", "Invalid signature")
                }
                val replay = blockingDb { store.findResultBySignature(signature) }
                if (replay != null) {
                    call.respond(replay)
                    return@post
                }
                if (!Hmac.timestampFresh(timestamp, clock().epochSecond, config.inboundMaxSkewSeconds)) {
                    unauthorized("expired_timestamp", "Timestamp is outside the allowed window")
                }
                val parsed = try {
                    GatewayJson.decodeFromString(BotResultRequest.serializer(), text)
                } catch (error: Exception) {
                    badRequest("Malformed JSON")
                }
                call.respond(blockingDb { store.acceptResult(raw, signature, parsed) })
            }

            get("/devices/me") {
                val device = requireDevice(store)
                call.respond(
                    app.goga.model.DeviceResponse(
                        deviceId = device.id,
                        userId = device.userId,
                        name = device.name,
                        createdAt = device.createdAt,
                    ),
                )
            }
            post("/devices/push") {
                val device = requireDevice(store)
                val body = call.receiveBody<RegisterPushRequest>()
                call.respond(HttpStatusCode.Created, blockingDb { store.registerPush(device, body.channel, body.token) })
            }
            post("/commands") {
                val device = requireDevice(store)
                val body = call.receiveBody<CreateCommandRequest>()
                val (dto, created) = blockingDb { store.createCommand(device, body) }
                if (created) {
                    call.respond(HttpStatusCode.Accepted, dto)
                } else {
                    call.respond(HttpStatusCode.OK, dto.copy(idempotent = true))
                }
            }
            get("/commands/{id}") {
                val device = requireDevice(store)
                val id = pathUuid("id")
                call.respond(blockingDb { store.commandForDevice(device, id) })
            }
            get("/notes") {
                val device = requireDevice(store)
                val limit = limitParam(call.request.queryParameters["limit"], default = 50, max = 200)
                val notes = blockingDb { store.listNotes(device.userId, limit) }
                call.respond(NoteListResponse(notes))
            }
            post("/notes") {
                val device = requireDevice(store)
                val body = call.receiveBody<UpsertNoteRequest>()
                call.respond(HttpStatusCode.Created, blockingDb { store.createNote(device, body) })
            }
            get("/notes/{id}") {
                val device = requireDevice(store)
                val id = pathUuid("id")
                call.respond(blockingDb { store.note(device.userId, id) })
            }
            patch("/notes/{id}") {
                val device = requireDevice(store)
                val id = pathUuid("id")
                val body = call.receiveBody<PatchNoteRequest>()
                call.respond(blockingDb { store.patchNote(device.userId, id, body) })
            }
            delete("/notes/{id}") {
                val device = requireDevice(store)
                val id = pathUuid("id")
                blockingDb { store.deleteNote(device.userId, id) }
                call.respond(HttpStatusCode.NoContent)
            }
            get("/events") {
                val device = requireDevice(store)
                val sinceRaw = call.request.queryParameters["since"]
                val since = sinceRaw?.let { Timestamps.formatUtc(parseInstant(it, "since")) }
                val limit = limitParam(call.request.queryParameters["limit"], default = 50, max = 200)
                val events = blockingDb { store.listEvents(device, since, limit) }
                call.respond(EventListResponse(events))
            }
        }
    }

    // Touch the constant so a future caller can see the allowed command sources next to the module.
    check(CommandSources.all.isNotEmpty())
}

@Serializable
internal data class HealthResponse(
    val status: String,
    val version: String,
    val bridge: String,
    val timezone: String,
)

private fun health(config: AppConfig) = HealthResponse(
    status = "ok",
    version = AppConfig.VERSION,
    bridge = config.botBridge,
    timezone = "Europe/Moscow",
)

private fun Application.warnIfPlaceholder(config: AppConfig) {
    val placeholder = listOf(config.bootstrapToken, config.inboundSecret).any {
        it.contains("change-me") || it.contains("replace-with")
    }
    if (placeholder) {
        log.warn("Gateway is using placeholder credentials. Do not expose it beyond localhost.")
    }
    if (config.userTimezone != "Europe/Moscow") {
        log.warn("USER_TIMEZONE is {}; v1 bot timestamps stay Europe/Moscow.", config.userTimezone)
    }
}
