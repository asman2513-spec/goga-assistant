package app.goga.assistant.session

import android.content.AttributionSource
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Required companion of [GogaVoiceInteractionService]. The assistant picker
 * drops a voice interaction service that does not name a recognition service.
 * Listening is forwarded to another installed recognizer so the overlay can
 * keep using the system on-device engine.
 */
class GogaRecognitionService : RecognitionService() {
    private val main = Handler(Looper.getMainLooper())
    private val sessions = LinkedHashMap<Callback, SpeechRecognizer>()

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        onMain {
            release(listener)
            if (recognizerIntent == null) {
                fail(listener, SpeechRecognizer.ERROR_CLIENT)
                return@onMain
            }
            val delegate = pickRecognitionDelegate(loadCandidates(), packageName)
            if (delegate == null) {
                Log.w(TAG, "no recognition delegate outside $packageName")
                fail(listener, SpeechRecognizer.ERROR_CLIENT)
                return@onMain
            }
            val component = ComponentName(delegate.packageName, delegate.className)
            val recognizer = try {
                SpeechRecognizer.createSpeechRecognizer(this, component)
            } catch (error: RuntimeException) {
                Log.w(TAG, "cannot bind recognizer $component", error)
                fail(listener, SpeechRecognizer.ERROR_CLIENT)
                return@onMain
            }
            sessions[listener] = recognizer
            recognizer.setRecognitionListener(
                ForwardingListener(listener) { main.post { release(listener) } },
            )
            try {
                recognizer.startListening(recognizerIntent)
            } catch (error: RuntimeException) {
                Log.w(TAG, "startListening failed for $component", error)
                release(listener)
                fail(listener, SpeechRecognizer.ERROR_CLIENT)
            }
        }
    }

    override fun onCancel(listener: Callback) {
        onMain {
            sessions[listener]?.cancel()
            release(listener)
        }
    }

    override fun onStopListening(listener: Callback) {
        onMain { sessions[listener]?.stopListening() }
    }

    override fun onCheckRecognitionSupport(recognizerIntent: Intent, supportCallback: SupportCallback) {
        supportCallback.onSupportResult(russianSupport())
    }

    override fun onCheckRecognitionSupport(
        recognizerIntent: Intent,
        attributionSource: AttributionSource,
        supportCallback: SupportCallback,
    ) {
        onCheckRecognitionSupport(recognizerIntent, supportCallback)
    }

    override fun onDestroy() {
        sessions.values.toList().forEach { recognizer ->
            recognizer.cancel()
            recognizer.destroy()
        }
        sessions.clear()
        super.onDestroy()
    }

    private fun loadCandidates(): List<RecognizerCandidate> {
        val probe = Intent(SERVICE_INTERFACE)
        return packageManager.queryIntentServices(probe, PackageManager.MATCH_ALL).mapNotNull { resolve ->
            val service = resolve.serviceInfo ?: return@mapNotNull null
            RecognizerCandidate(service.packageName, service.name)
        }
    }

    private fun release(listener: Callback) {
        sessions.remove(listener)?.destroy()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private companion object {
        const val TAG = "GogaRecognition"

        fun russianSupport(): RecognitionSupport = RecognitionSupport.Builder()
            .addSupportedOnDeviceLanguage("ru-RU")
            .addInstalledOnDeviceLanguage("ru-RU")
            .addOnlineLanguage("ru-RU")
            .build()

        fun fail(listener: Callback, code: Int) {
            try {
                listener.error(code)
            } catch (_: RemoteException) {
            }
        }
    }
}

private class ForwardingListener(
    private val callback: RecognitionService.Callback,
    private val onFinished: () -> Unit,
) : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) = forward { callback.readyForSpeech(params ?: Bundle()) }

    override fun onBeginningOfSpeech() = forward { callback.beginningOfSpeech() }

    override fun onRmsChanged(rmsdB: Float) = forward { callback.rmsChanged(rmsdB) }

    override fun onBufferReceived(buffer: ByteArray?) = forward { callback.bufferReceived(buffer ?: ByteArray(0)) }

    override fun onEndOfSpeech() = forward { callback.endOfSpeech() }

    override fun onError(error: Int) {
        forward { callback.error(error) }
        onFinished()
    }

    override fun onResults(results: Bundle?) {
        forward { callback.results(results ?: Bundle()) }
        onFinished()
    }

    override fun onPartialResults(partialResults: Bundle?) =
        forward { callback.partialResults(partialResults ?: Bundle()) }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun forward(block: () -> Unit) {
        try {
            block()
        } catch (_: RemoteException) {
        }
    }
}
