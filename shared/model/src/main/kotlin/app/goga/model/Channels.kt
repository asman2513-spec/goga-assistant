package app.goga.model

/**
 * How a reminder reaches the user.
 * v1 implements [ReminderChannelIds.LOCAL_ALARM] on the phone.
 * Push, SMS, and calls are further implementations of the same interface.
 */
interface ReminderChannel {
    val id: String

    suspend fun deliver(request: ReminderDelivery): DeliveryAck
}

data class ReminderDelivery(
    val reminderId: String,
    val userId: String,
    val deviceId: String?,
    val title: String,
    val body: String,
    val fireAtEpochMillis: Long,
)

data class DeliveryAck(
    val accepted: Boolean,
    val detail: String? = null,
)

/**
 * Calendar backend. v1 is [CalendarProviderIds.GOOGLE_VIA_BOT].
 * CalDAV and the on-device calendar plug in behind the same interface later.
 */
interface CalendarProvider {
    val id: String

    suspend fun createEvent(draft: CalendarEventDraft): CalendarEventRef

    suspend fun updateEvent(externalId: String, draft: CalendarEventDraft): CalendarEventRef

    suspend fun deleteEvent(externalId: String)

    suspend fun listEvents(fromEpochMillis: Long, toEpochMillis: Long): List<CalendarEventRef>
}

data class CalendarEventDraft(
    val userId: String,
    val title: String,
    val startsAtEpochMillis: Long,
    val endsAtEpochMillis: Long,
    val description: String? = null,
)

data class CalendarEventRef(
    val providerId: String,
    val externalId: String,
    val title: String,
    val startsAtEpochMillis: Long,
    val endsAtEpochMillis: Long,
)

/**
 * Push transport. FCM is the v1 candidate; RuStore and a WebSocket
 * while a foreground service is running are the other implementations.
 * Stage 0 does not link Firebase, so the project builds without google-services.json.
 */
interface PushChannel {
    val id: String

    suspend fun registerToken(deviceId: String, token: String): DeliveryAck
}

/** Thrown by stage-0 stubs so later stages have an obvious place to fill in. */
class StageNotImplementedException(message: String) : UnsupportedOperationException(message)
