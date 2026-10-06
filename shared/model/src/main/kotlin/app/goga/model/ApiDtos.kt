package app.goga.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String,
)

@Serializable
data class CreatePairingCodeResponse(
    val code: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("user_id") val userId: String,
)

@Serializable
data class PairDeviceRequest(
    val code: String,
    @SerialName("device_name") val deviceName: String,
)

@Serializable
data class PairDeviceResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("device_token") val deviceToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
)

@Serializable
data class DeviceResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class CreateCommandRequest(
    @SerialName("idempotency_key") val idempotencyKey: String,
    val text: String,
    val source: String = CommandSources.TEXT,
    @SerialName("client_timestamp") val clientTimestamp: String? = null,
)

@Serializable
data class CommandResultDto(
    val id: String,
    val kind: String,
    @SerialName("reply_text") val replyText: String? = null,
    val question: String? = null,
    val entities: List<BotEntity> = emptyList(),
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class CommandDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
    val text: String,
    val source: String,
    val status: String,
    @SerialName("client_timestamp") val clientTimestamp: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val error: String? = null,
    val result: CommandResultDto? = null,
    val idempotent: Boolean = false,
)

@Serializable
data class UpsertNoteRequest(
    val title: String,
    val body: String = "",
)

@Serializable
data class PatchNoteRequest(
    val title: String? = null,
    val body: String? = null,
)

@Serializable
data class NoteListResponse(
    val notes: List<Note>,
)

@Serializable
data class BotEntity(
    val type: String,
    val id: String? = null,
    val title: String? = null,
    val body: String? = null,
    val payload: JsonObject? = null,
)

@Serializable
data class BotResultRequest(
    @SerialName("command_id") val commandId: String? = null,
    @SerialName("event_id") val eventId: String? = null,
    val kind: String,
    @SerialName("reply_text") val replyText: String? = null,
    val question: String? = null,
    val entities: List<BotEntity> = emptyList(),
    @SerialName("device_id") val deviceId: String? = null,
)

@Serializable
data class BotResultResponse(
    @SerialName("result_id") val resultId: String,
    @SerialName("command_id") val commandId: String? = null,
    @SerialName("event_id") val eventId: String? = null,
    val status: String,
    @SerialName("created_note_ids") val createdNoteIds: List<String> = emptyList(),
    val idempotent: Boolean = false,
)

@Serializable
data class CallbackInfo(
    val url: String,
    val scheme: String = "hmac-sha256",
    @SerialName("timestamp_header") val timestampHeader: String = BotHeaders.TIMESTAMP,
    @SerialName("signature_header") val signatureHeader: String = BotHeaders.SIGNATURE,
    @SerialName("signed_payload") val signedPayload: String = "{unix_seconds}.{raw_body}",
)

@Serializable
data class OutboundCommandEvent(
    val event: String = "command.created",
    @SerialName("command_id") val commandId: String,
    val text: String,
    /** ISO-8601 with the Europe/Moscow offset, second precision. */
    val timestamp: String,
    val source: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("user_id") val userId: String,
    val callback: CallbackInfo,
)

@Serializable
data class RegisterPushRequest(
    val channel: String,
    val token: String,
)

@Serializable
data class PushRegistrationDto(
    val id: String,
    @SerialName("device_id") val deviceId: String,
    val channel: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class InboundEventDto(
    val id: String,
    @SerialName("event_id") val eventId: String? = null,
    @SerialName("command_id") val commandId: String? = null,
    val kind: String,
    @SerialName("reply_text") val replyText: String? = null,
    val question: String? = null,
    val entities: List<BotEntity> = emptyList(),
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class EventListResponse(
    val events: List<InboundEventDto>,
)

class GatewayException(
    val status: Int,
    val error: String,
    message: String,
) : RuntimeException(message)
