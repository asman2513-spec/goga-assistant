package app.goga.assistant.session

import android.content.AttributionSource
import android.content.Intent
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Present so [android.service.voice.VoiceInteractionServiceInfo] accepts Goga as an
 * assistant. It does not capture audio. After the role is granted the system selects
 * this component as the default recognizer; the overlay binds to another engine instead.
 */
class GogaRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        Log.i(TAG, "start ignored; session listens through an external recognizer")
        fail(listener, SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback) {
        Log.i(TAG, "cancel")
    }

    override fun onStopListening(listener: Callback) {
        Log.i(TAG, "stop")
    }

    override fun onCheckRecognitionSupport(recognizerIntent: Intent, supportCallback: SupportCallback) {
        supportCallback.onError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
    }

    override fun onCheckRecognitionSupport(
        recognizerIntent: Intent,
        attributionSource: AttributionSource,
        supportCallback: SupportCallback,
    ) {
        onCheckRecognitionSupport(recognizerIntent, supportCallback)
    }

    private companion object {
        const val TAG = "Goga/Recognition"

        fun fail(listener: Callback, code: Int) {
            try {
                listener.error(code)
            } catch (_: RemoteException) {
                Log.w(TAG, "listener gone")
            }
        }
    }
}
