package app.goga.calendar

import app.goga.model.CalendarProviderIds
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarProvidersTest {
    @Test
    fun providerIdsStayStable() {
        assertEquals(CalendarProviderIds.GOOGLE_VIA_BOT, BotGoogleCalendarProvider().id)
        assertEquals(CalendarProviderIds.CALDAV, CalDavCalendarProvider().id)
        assertEquals(CalendarProviderIds.ANDROID_CALENDAR, AndroidCalendarProvider().id)
    }
}
