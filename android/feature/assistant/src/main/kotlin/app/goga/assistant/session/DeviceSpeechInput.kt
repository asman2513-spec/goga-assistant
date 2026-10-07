package app.goga.assistant.session

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Listens through an installed recognizer that is not Goga.
 * [SpeechRecognizer.createSpeechRecognizer] without a component follows
 * [android.provider.Settings.Secure.VOICE_RECOGNITION_SERVICE], which points at
 * [GogaRecognitionService] once Goga is the default assistant. That stub does not
 * hear the user. Text input does not use this class.
 */
class DeviceSpeechInput(
    context: Context,
) : SpeechToText {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val order = recognizerOrder(
        configuredOnDevice = configuredOnDeviceRecognizer(),
        installed = installedRecognizers(appContext),
        ownPackage = appContext.packageName,
    )
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var generation = 0

    override val mode: SpeechMode = when {
        order.isEmpty() -> SpeechMode.UNAVAILABLE
        order.first().packageName in ON_DEVICE_PACKAGES -> SpeechMode.ON_DEVICE
        else -> SpeechMode.SYSTEM
    }

    override fun start(listener: SpeechListener) {
        if (order.isEmpty()) {
            Log.w(TAG, "no external recognizer")
            deliver { listener.onFailure(ListenFailure.UNAVAILABLE) }
            return
        }
        val token = beginAttempt()
        listenAt(0, listener, token)
    }

    override fun stop() {
        generation++
        active = false
        recognizer?.cancel()
    }

    override fun release() {
        generation++
        active = false
        val current = recognizer
        recognizer = null
        current?.destroy()
    }

    private fun beginAttempt(): Int {
        generation++
        active = false
        recognizer?.cancel()
        active = true
        return generation
    }

    private fun listenAt(index: Int, listener: SpeechListener, token: Int) {
        if (!active || token != generation) return
        val candidate = order.getOrNull(index)
        if (candidate == null) {
            active = false
            deliver { listener.onFailure(ListenFailure.UNAVAILABLE) }
            return
        }
        val component = ComponentName(candidate.packageName, candidate.className)
        Log.i(TAG, "start ${component.flattenToShortString()} attempt=$index")
        val created = try {
            SpeechRecognizer.createSpeechRecognizer(appContext, component)
        } catch (error: RuntimeException) {
            Log.w(TAG, "create failed ${component.flattenToShortString()}", error)
            listenAt(index + 1, listener, token)
            return
        }
        swapRecognizer(created)
        created.setRecognitionListener(listenerFor(component, index, listener, token))
        try {
            created.startListening(recognizeIntent())
        } catch (error: RuntimeException) {
            Log.w(TAG, "startListening threw ${component.flattenToShortString()}", error)
            if (index + 1 < order.size) {
                main.post { listenAt(index + 1, listener, token) }
            } else {
                active = false
                deliver { listener.onFailure(ListenFailure.UNKNOWN) }
            }
        }
    }

    private fun listenerFor(
        component: ComponentName,
        index: Int,
        listener: SpeechListener,
        token: Int,
    ) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.i(TAG, "ready ${component.flattenToShortString()}")
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            if (!active || token != generation) return
            Log.w(TAG, "error $error ${component.flattenToShortString()}")
            if (shouldTryNext(error) && index + 1 < order.size) {
                main.post { listenAt(index + 1, listener, token) }
                return
            }
            active = false
            deliver { listener.onFailure(mapError(error)) }
        }

        override fun onResults(results: Bundle?) {
            if (!active || token != generation) return
            active = false
            val text = results.bestText()
            Log.i(TAG, "result ${component.flattenToShortString()} text=${text.orEmpty()}")
            deliver {
                if (text.isNullOrBlank()) listener.onFailure(ListenFailure.NO_MATCH)
                else listener.onFinal(text)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!active || token != generation) return
            val text = partialResults.bestText() ?: return
            Log.d(TAG, "partial $text")
            deliver { listener.onPartial(text) }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun swapRecognizer(next: SpeechRecognizer) {
        val previous = recognizer
        recognizer = next
        if (previous != null) {
            main.post {
                previous.cancel()
                previous.destroy()
            }
        }
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
        const val TAG = "Goga/Listen"
        const val LOCALE = "ru-RU"
        val ON_DEVICE_PACKAGES = setOf(
            "com.google.android.tts",
            "com.google.android.as",
        )

        fun configuredOnDeviceRecognizer(): RecognizerCandidate? {
            val id = Resources.getSystem().getIdentifier(
                "config_defaultOnDeviceSpeechRecognitionService",
                "string",
                "android",
            )
            if (id == 0) return null
            val flat = runCatching { Resources.getSystem().getString(id) }.getOrNull()
            val component = ComponentName.unflattenFromString(flat ?: return null) ?: return null
            return RecognizerCandidate(component.packageName, component.className)
        }

        fun installedRecognizers(context: Context): List<RecognizerCandidate> {
            val probe = Intent(RecognitionService.SERVICE_INTERFACE)
            return context.packageManager.queryIntentServices(probe, PackageManager.MATCH_ALL)
                .mapNotNull { resolve ->
                    val service = resolve.serviceInfo ?: return@mapNotNull null
                    RecognizerCandidate(service.packageName, service.name)
                }
        }

        fun shouldTryNext(code: Int): Boolean = when (code) {
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            -> true
            else -> false
        }

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
