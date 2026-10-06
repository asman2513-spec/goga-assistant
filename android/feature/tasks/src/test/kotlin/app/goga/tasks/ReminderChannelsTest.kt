package app.goga.tasks

import app.goga.model.ReminderChannelIds
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderChannelsTest {
    @Test
    fun channelIdsStayStable() {
        assertEquals(ReminderChannelIds.LOCAL_ALARM, LocalAlarmReminderChannel().id)
        assertEquals(ReminderChannelIds.PUSH, PushReminderChannel().id)
        assertEquals(ReminderChannelIds.SMS, SmsReminderChannel().id)
        assertEquals(ReminderChannelIds.CALL, CallReminderChannel().id)
    }
}
