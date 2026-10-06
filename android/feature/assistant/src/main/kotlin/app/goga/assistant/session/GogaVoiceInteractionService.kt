package app.goga.assistant.session

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class GogaVoiceInteractionService : VoiceInteractionService()

class GogaSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = GogaInteractionSession(this)
}
