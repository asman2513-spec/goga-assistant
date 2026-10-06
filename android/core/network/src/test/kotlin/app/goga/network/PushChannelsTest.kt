package app.goga.network

import app.goga.model.PushChannels
import org.junit.Assert.assertEquals
import org.junit.Test

class PushChannelsTest {
    @Test
    fun pushIdsStayStableWithoutFirebase() {
        assertEquals(PushChannels.FCM, FcmPushChannel().id)
        assertEquals(PushChannels.RUSTORE, RuStorePushChannel().id)
        assertEquals(PushChannels.WEBSOCKET, WebSocketPushChannel().id)
    }
}
