package app.goga.calendar

import app.goga.model.CalendarEventDraft
import app.goga.model.CalendarEventRef
import app.goga.model.CalendarProvider
import app.goga.model.CalendarProviderIds
import app.goga.model.StageNotImplementedException

/** v1: the bot's Google Calendar connector. The phone never holds a Google token. */
class BotGoogleCalendarProvider : CalendarProvider {
    override val id: String = CalendarProviderIds.GOOGLE_VIA_BOT

    override suspend fun createEvent(draft: CalendarEventDraft): CalendarEventRef = notReady()

    override suspend fun updateEvent(externalId: String, draft: CalendarEventDraft): CalendarEventRef = notReady()

    override suspend fun deleteEvent(externalId: String) = notReady()

    override suspend fun listEvents(fromEpochMillis: Long, toEpochMillis: Long): List<CalendarEventRef> = notReady()

    private fun notReady(): Nothing =
        throw StageNotImplementedException("Google Calendar via the bot is a later stage")
}

class CalDavCalendarProvider : CalendarProvider {
    override val id: String = CalendarProviderIds.CALDAV

    override suspend fun createEvent(draft: CalendarEventDraft): CalendarEventRef = notReady()

    override suspend fun updateEvent(externalId: String, draft: CalendarEventDraft): CalendarEventRef = notReady()

    override suspend fun deleteEvent(externalId: String) = notReady()

    override suspend fun listEvents(fromEpochMillis: Long, toEpochMillis: Long): List<CalendarEventRef> = notReady()

    private fun notReady(): Nothing = throw StageNotImplementedException("CalDAV is not in v1")
}

class AndroidCalendarProvider : CalendarProvider {
    override val id: String = CalendarProviderIds.ANDROID_CALENDAR

    override suspend fun createEvent(draft: CalendarEventDraft): CalendarEventRef = notReady()

    override suspend fun updateEvent(externalId: String, draft: CalendarEventDraft): CalendarEventRef = notReady()

    override suspend fun deleteEvent(externalId: String) = notReady()

    override suspend fun listEvents(fromEpochMillis: Long, toEpochMillis: Long): List<CalendarEventRef> = notReady()

    private fun notReady(): Nothing =
        throw StageNotImplementedException("CalendarContract is not in v1")
}
