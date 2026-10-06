package app.goga.assistant.session

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Prefers [SpeechRecognizer.createOnDeviceSpeechRecognizer] (API 31, ru-RU).
 * If the offline recognizer is missing, falls back to the system recognizer
 * with [RecognizerIntent.EXTRA_PREFER_OFFLINE]. Text input does not depend on either.
 */
class DeviceSpeechInput(
    context: Context,
) : SpeechToText {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false

    override val mode: SpeechMode = when {
        SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) -> SpeechMode.ON_DEVICE
        SpeechRecognizer.isRecognitionAvailable(appContext) -> SpeechMode.SYSTEM
        else -> SpeechMode.UNAVAILABLE
    }

    override fun start(listener: SpeechListener) {
        if (mode == SpeechMode.UNAVAILABLE) {
            deliver { listener.onFailure(ListenFailure.UNAVAILABLE) }
            return
        }
        stop()
        val rec = recognizer ?: createRecognizer().also { recognizer = it }
        active = true
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                if (!active) return
                active = false
                deliver { listener.onFailure(mapError(error)) }
            }

            override fun onResults(results: Bundle?) {
                if (!active) return
                active = false
                val text = results.bestText()
                deliver {
                    if (text.isNullOrBlank()) listener.onFailure(ListenFailure.NO_MATCH)
                    else listener.onFinal(text)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!active) return
                val text = partialResults.bestText() ?: return
                deliver { listener.onPartial(text) }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        rec.startListening(recognizeIntent())
    }

    override fun stop() {
        active = false
        recognizer?.cancel()
    }

    override fun release() {
        active = false
        recognizer?.destroy()
        recognizer = null
    }

    private fun createRecognizer(): SpeechRecognizer = when (mode) {
        SpeechMode.ON_DEVICE -> SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        SpeechMode.SYSTEM -> SpeechRecognizer.createSpeechRecognizer(appContext)
        SpeechMode.UNAVAILABLE -> error("no recognizer")
    }

    private fun recognizeIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, LOCALE)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LOCALE)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_500)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_400)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_400)
    }

    private fun deliver(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private companion object {
        const val LOCALE = "ru-RU"

        fun Bundle?.bestText(): String? =
            this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

        fun mapError(code: Int): ListenFailure = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH -> ListenFailure.NO_MATCH
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ListenFailure.TIMEOUT
            SpeechRecognizer.ERROR_AUDIO -> ListenFailure.AUDIO
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ListenFailure.PERMISSION
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> ListenFailure.BUSY
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            -> ListenFailure.NETWORK
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            -> ListenFailure.LANGUAGE
            else -> ListenFailure.UNKNOWN
        }
    }
}
