package app.goga.network

import app.goga.model.CommandDto
import app.goga.model.CreateCommandRequest
import app.goga.model.ErrorResponse
import app.goga.model.EventListResponse
import app.goga.model.GatewayException
import app.goga.model.Note
import app.goga.model.NoteListResponse
import app.goga.model.PairDeviceRequest
import app.goga.model.PairDeviceResponse
import app.goga.model.PushRegistrationDto
import app.goga.model.RegisterPushRequest
import app.goga.model.UpsertNoteRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

fun gatewayHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) { json(GatewayClientJson) }
    expectSuccess = false
}

val GatewayClientJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

class KtorGatewayApi(
    private val client: HttpClient,
    baseUrl: String,
) : GatewayApi {
    private val root = baseUrl.trimEnd('/')

    override suspend fun pair(request: PairDeviceRequest): PairDeviceResponse =
        client.post("$root/v1/devices/pair") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.bodyOrThrow()

    override suspend fun createCommand(deviceToken: String, request: CreateCommandRequest): CommandDto =
        client.post("$root/v1/commands") {
            bearer(deviceToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.bodyOrThrow()

    override suspend fun getCommand(deviceToken: String, commandId: String): CommandDto =
        client.get("$root/v1/commands/$commandId") { bearer(deviceToken) }.bodyOrThrow()

    override suspend fun listNotes(deviceToken: String): NoteListResponse =
        client.get("$root/v1/notes") { bearer(deviceToken) }.bodyOrThrow()

    override suspend fun createNote(deviceToken: String, request: UpsertNoteRequest): Note =
        client.post("$root/v1/notes") {
            bearer(deviceToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.bodyOrThrow()

    override suspend fun listEvents(deviceToken: String, since: String?): EventListResponse =
        client.get("$root/v1/events") {
            bearer(deviceToken)
            if (since != null) parameter("since", since)
        }.bodyOrThrow()

    override suspend fun registerPush(deviceToken: String, request: RegisterPushRequest): PushRegistrationDto =
        client.post("$root/v1/devices/push") {
            bearer(deviceToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.bodyOrThrow()
}

private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
    header(HttpHeaders.Authorization, "Bearer $token")
}

private suspend inline fun <reified T> HttpResponse.bodyOrThrow(): T {
    if (!status.isSuccess()) {
        val error = runCatching { body<ErrorResponse>() }.getOrNull()
        throw GatewayException(
            status = status.value,
            error = error?.error ?: "http_error",
            message = error?.message ?: status.description,
        )
    }
    return body()
}
