package app.goga.network

import app.goga.model.DeliveryAck
import app.goga.model.PushChannel
import app.goga.model.PushChannels
import app.goga.model.StageNotImplementedException

/**
 * FCM is the v1 candidate. Firebase is not on the classpath: the debug APK
 * builds without google-services.json. Registration will call
 * POST /v1/devices/push once a real token exists.
 */
class FcmPushChannel : PushChannel {
    override val id: String = PushChannels.FCM

    override suspend fun registerToken(deviceId: String, token: String): DeliveryAck {
        throw StageNotImplementedException("FCM registration is a later stage")
    }
}

class RuStorePushChannel : PushChannel {
    override val id: String = PushChannels.RUSTORE

    override suspend fun registerToken(deviceId: String, token: String): DeliveryAck {
        throw StageNotImplementedException("RuStore Push is not in v1")
    }
}

class WebSocketPushChannel : PushChannel {
    override val id: String = PushChannels.WEBSOCKET

    override suspend fun registerToken(deviceId: String, token: String): DeliveryAck {
        throw StageNotImplementedException("WebSocket push is a later stage")
    }
}
