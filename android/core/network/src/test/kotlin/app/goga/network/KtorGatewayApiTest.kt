package app.goga.network

import app.goga.model.CreateCommandRequest
import app.goga.model.GatewayException
import app.goga.model.PairDeviceRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KtorGatewayApiTest {
    @Test
    fun pairAndCreateCommandSendTheContract() = runBlocking {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/devices/pair" -> respond(
                    """{"device_id":"d","user_id":"u","device_token":"secret-token","token_type":"Bearer"}""",
                    headers = jsonHeaders(),
                )
                "/v1/commands" -> {
                    assertEquals("Bearer secret-token", request.headers[HttpHeaders.Authorization])
                    respond(
                        """
                        {"id":"c","user_id":"u","device_id":"d","idempotency_key":"11111111-1111-1111-1111-111111111111",
                         "text":"привет","source":"text","status":"queued",
                         "created_at":"2026-10-06T03:00:00.000Z","updated_at":"2026-10-06T03:00:00.000Z"}
                        """.trimIndent(),
                        HttpStatusCode.Accepted,
                        jsonHeaders(),
                    )
                }
                else -> respond("no", HttpStatusCode.NotFound)
            }
        }
        val api = KtorGatewayApi(client(engine), "http://gateway.local/")
        val paired = api.pair(PairDeviceRequest(code = "ABCD2345", deviceName = "phone"))
        assertEquals("secret-token", paired.deviceToken)
        val command = api.createCommand(
            paired.deviceToken,
            CreateCommandRequest(
                idempotencyKey = "11111111-1111-1111-1111-111111111111",
                text = "привет",
            ),
        )
        assertEquals("queued", command.status)
        assertEquals("u", command.userId)
    }

    @Test
    fun errorBodyBecomesGatewayException() = runBlocking {
        val engine = MockEngine {
            respond(
                """{"error":"unauthorized","message":"Invalid device token"}""",
                HttpStatusCode.Unauthorized,
                jsonHeaders(),
            )
        }
        val api = KtorGatewayApi(client(engine), "http://gateway.local")
        try {
            api.listNotes("nope")
            fail("expected GatewayException")
        } catch (error: GatewayException) {
            assertEquals(401, error.status)
            assertEquals("unauthorized", error.error)
            assertTrue(error.message!!.contains("Invalid"))
        }
    }

    private fun client(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) { json(GatewayClientJson) }
        expectSuccess = false
    }

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")
}
