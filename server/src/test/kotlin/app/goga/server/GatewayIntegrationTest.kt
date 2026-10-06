package app.goga.server

import app.goga.model.CommandDto
import app.goga.model.CreateCommandRequest
import app.goga.model.ErrorResponse
import app.goga.model.EventListResponse
import app.goga.model.Note
import app.goga.model.NoteListResponse
import app.goga.model.PatchNoteRequest
import app.goga.model.UpsertNoteRequest
import app.goga.server.auth.Hmac
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class GatewayIntegrationTest {
    @Test
    fun healthAndOpenApi() = withGateway {
        val health = client.get("$baseUrl/health")
        assertEquals(200, health.status.value)
        assertTrue(health.bodyAsText().contains("\"bridge\":\"mock\""))
        val spec = client.get("$baseUrl/openapi.yaml")
        assertEquals(200, spec.status.value)
        assertTrue(spec.bodyAsText().contains("/v1/commands"))
        assertTrue(spec.bodyAsText().contains("/v1/bot/results"))
    }

    @Test
    fun mockRoundTripCreatesANoteThePhoneCanRead() = withGateway {
        val device = pair()
        val key = UUID.randomUUID().toString()
        val text = "заметка про тарифы хостинга"
        val created = client.post("$baseUrl/v1/commands") {
            bearer(device.deviceToken)
            contentType(ContentType.Application.Json)
            setBody(CreateCommandRequest(idempotencyKey = key, text = text, source = "text"))
        }
        assertEquals(202, created.status.value)
        val queued = created.body<CommandDto>()
        assertEquals("queued", queued.status)

        val done = awaitCommand(device.deviceToken, queued.id)
        assertEquals("completed", done.status, done.error)
        assertEquals("Принято: $text", done.result?.replyText)
        assertTrue(done.result?.entities?.any { it.type == "note" && it.body == text } == true)

        val notes = client.get("$baseUrl/v1/notes") { bearer(device.deviceToken) }.body<NoteListResponse>()
        assertTrue(notes.notes.any { it.body == text && it.userId == device.userId })

        val again = client.post("$baseUrl/v1/commands") {
            bearer(device.deviceToken)
            contentType(ContentType.Application.Json)
            setBody(CreateCommandRequest(idempotencyKey = key, text = text, source = "text"))
        }
        assertEquals(200, again.status.value)
        assertEquals(queued.id, again.body<CommandDto>().id)
        assertTrue(again.body<CommandDto>().idempotent)
    }

    @Test
    fun idempotencyKeyRejectsADifferentText() = withGateway(autoReply = false) {
        val device = pair()
        val key = UUID.randomUUID().toString()
        val first = client.post("$baseUrl/v1/commands") {
            bearer(device.deviceToken)
            contentType(ContentType.Application.Json)
            setBody(CreateCommandRequest(idempotencyKey = key, text = "первая"))
        }
        assertEquals(202, first.status.value)
        val second = client.post("$baseUrl/v1/commands") {
            bearer(device.deviceToken)
            contentType(ContentType.Application.Json)
            setBody(CreateCommandRequest(idempotencyKey = key, text = "вторая"))
        }
        assertEquals(409, second.status.value)
    }

    @Test
    fun notesCrudAndDeviceTokenRequired() = withGateway {
        val missing = client.get("$baseUrl/v1/notes")
        assertEquals(401, missing.status.value)

        val device = pair()
        val created = client.post("$baseUrl/v1/notes") {
            bearer(device.deviceToken)
            contentType(ContentType.Application.Json)
            setBody(UpsertNoteRequest(title = "Хостинг", body = "сравнить тарифы"))
        }
        assertEquals(201, created.status.value)
        val note = created.body<Note>()
        assertEquals(device.userId, note.userId)
        assertEquals(device.deviceId, note.deviceId)

        val patched = client.patch("$baseUrl/v1/notes/${note.id}") {
            bearer(device.deviceToken)
            contentType(ContentType.Application.Json)
            setBody(PatchNoteRequest(body = "сравнить тарифы и сроки"))
        }
        assertEquals(200, patched.status.value)
        assertEquals("сравнить тарифы и сроки", patched.body<Note>().body)

        val deleted = client.delete("$baseUrl/v1/notes/${note.id}") { bearer(device.deviceToken) }
        assertEquals(204, deleted.status.value)
        val gone = client.get("$baseUrl/v1/notes/${note.id}") { bearer(device.deviceToken) }
        assertEquals(404, gone.status.value)
    }

    @Test
    fun pairingCodeIsSingleUseAndPlaintextIsNotStored() = withGateway {
        val issued = client.post("$baseUrl/v1/pairing-codes") {
            header(HttpHeaders.Authorization, "Bearer ${config.bootstrapToken}")
        }
        assertEquals(201, issued.status.value)
        val code = issued.body<app.goga.model.CreatePairingCodeResponse>().code
        val paired = client.post("$baseUrl/v1/devices/pair") {
            contentType(ContentType.Application.Json)
            setBody(app.goga.model.PairDeviceRequest(code = code, deviceName = "phone"))
        }
        assertEquals(201, paired.status.value)
        val token = paired.body<app.goga.model.PairDeviceResponse>().deviceToken
        val reuse = client.post("$baseUrl/v1/devices/pair") {
            contentType(ContentType.Application.Json)
            setBody(app.goga.model.PairDeviceRequest(code = code, deviceName = "phone"))
        }
        assertEquals(401, reuse.status.value)

        val dbPath = Path.of(config.databaseUrl.removePrefix("jdbc:sqlite:"))
        val stored = buildString {
            append(String(Files.readAllBytes(dbPath), Charsets.ISO_8859_1))
            val wal = dbPath.resolveSibling("${dbPath.fileName}-wal")
            if (Files.exists(wal)) append(String(Files.readAllBytes(wal), Charsets.ISO_8859_1))
        }
        assertFalse(stored.contains(token))
        assertFalse(stored.contains(code))
    }

    @Test
    fun expiredPairingCodeIsRejected() {
        val clock = AtomicReference(Instant.parse("2026-10-06T03:00:00Z"))
        withGateway(clock = { clock.get() }) {
            val issued = client.post("$baseUrl/v1/pairing-codes") {
                header(HttpHeaders.Authorization, "Bearer ${config.bootstrapToken}")
            }
            val code = issued.body<app.goga.model.CreatePairingCodeResponse>().code
            clock.set(Instant.parse("2026-10-06T04:00:00Z"))
            val paired = client.post("$baseUrl/v1/devices/pair") {
                contentType(ContentType.Application.Json)
                setBody(app.goga.model.PairDeviceRequest(code = code, deviceName = "phone"))
            }
            assertEquals(401, paired.status.value)
            assertEquals("invalid_code", paired.body<ErrorResponse>().error)
        }
    }

    @Test
    fun shellScriptDeliversAnUnsolicitedSummary() = withGateway {
        val device = pair()
        val eventId = UUID.randomUUID().toString()
        val body = """{"event_id":"$eventId","kind":"event","reply_text":"Сводка на сегодня"}"""
        val process = ProcessBuilder("bash", "scripts/post-bot-result.sh", body)
            .redirectErrorStream(true)
            .apply {
                environment()["BOT_INBOUND_SECRET"] = config.inboundSecret
                environment()["GATEWAY_URL"] = baseUrl
            }
            .start()
        val output = process.inputStream.readBytes().decodeToString()
        assertEquals(0, process.waitFor(), output)
        assertTrue(output.contains("\"result_id\""))

        val events = client.get("$baseUrl/v1/events") { bearer(device.deviceToken) }.body<EventListResponse>()
        assertTrue(events.events.any { it.eventId == eventId && it.replyText == "Сводка на сегодня" })

        val replay = ProcessBuilder("bash", "scripts/post-bot-result.sh", body)
            .redirectErrorStream(true)
            .apply {
                environment()["BOT_INBOUND_SECRET"] = config.inboundSecret
                environment()["GATEWAY_URL"] = baseUrl
                environment()["GOGA_TIMESTAMP"] = (Instant.now().epochSecond - 10).toString()
            }
            .start()
        val replayOut = replay.inputStream.readBytes().decodeToString()
        assertEquals(0, replay.waitFor(), replayOut)
        assertTrue(replayOut.contains("\"idempotent\":true"))
    }

    @Test
    fun knownHmacVector() {
        val body = """{"command_id":"11111111-1111-1111-1111-111111111111","kind":"reply","reply_text":"Готово"}"""
        val signature = Hmac.sign("test-inbound-secret", "1760000000", body.toByteArray(Charsets.UTF_8))
        assertEquals("70f325c871ec736ddbc7ae1ba5f38023a09fbf2486208bce5077ac3934a3d185", signature)
    }

    @Test
    fun rejectsBadSignatureSkewAndReplayOfUnknownRequest() = withGateway {
        val fresh = Instant.now().epochSecond.toString()
        val body = """{"event_id":"${UUID.randomUUID()}","kind":"event","reply_text":"ping"}"""
        val bad = postSigned(body, fresh, "00".repeat(32))
        assertEquals(401, bad.status.value)
        assertEquals("unauthorized", bad.body<ErrorResponse>().error)

        val stale = postSigned(body, "1700000000", Hmac.sign(config.inboundSecret, "1700000000", body.toByteArray()))
        assertEquals(401, stale.status.value)
        assertEquals("expired_timestamp", stale.body<ErrorResponse>().error)
    }
}

private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
    header(HttpHeaders.Authorization, "Bearer $token")
}

private suspend fun RunningGateway.postSigned(
    body: String,
    timestamp: String,
    signature: String,
) = client.post("$baseUrl/v1/bot/results") {
    contentType(ContentType.Application.Json)
    header("X-Goga-Timestamp", timestamp)
    header("X-Goga-Signature", signature)
    setBody(body)
}
