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
 *
 * Terminal callbacks only record state and post work. After a result the same
 * recognizer is started again later: cancel() plus a new instance deadlocks MagicOS
 * once TTS asks the microphone back.
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
    private var boundComponent: ComponentName? = null
    private var active = false
    private var settled = false
    private var heardSession = false
    private var generation = 0
    private var retries = 0
    private var lastPartial: String = ""
    private var pendingMiss: Runnable? = null
    private var pendingStart: Runnable? = null
    private var callbackListener: SpeechListener? = null
    private var callbackIndex: Int = 0
    private var callbackComponent: ComponentName? = null

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
        if (active && !settled) {
            Log.i(TAG, "start ignored, session still open")
            return
        }
        val token = beginAttempt()
        scheduleListen(0, listener, token, if (heardSession) RESTART_GAP_MS else 0L)
    }

    override fun stop() {
        Log.i(TAG, "stop")
        SessionTrace.log("listen", "park")
        park()
    }

    override fun release() {
        Log.i(TAG, "release")
        SessionTrace.log("listen", "release")
        abandon()
    }

    /**
     * Drops the current listen without [SpeechRecognizer.destroy]. Destroy on the
     * main thread deadlocks MagicOS when it races the result that just arrived.
     */
    private fun park() {
        generation++
        active = false
        settled = true
        retries = 0
        lastPartial = ""
        callbackListener = null
        cancelPendingMiss()
        cancelScheduledListen()
    }

    private fun beginAttempt(): Int {
        generation++
        active = true
        settled = false
        retries = 0
        lastPartial = ""
        cancelPendingMiss()
        cancelScheduledListen()
        return generation
    }

    private fun abandon() {
        val previous = recognizer
        generation++
        active = false
        settled = true
        retries = 0
        lastPartial = ""
        recognizer = null
        boundComponent = null
        callbackListener = null
        callbackComponent = null
        cancelPendingMiss()
        cancelScheduledListen()
        quietDestroy(previous)
    }

    private fun scheduleListen(index: Int, listener: SpeechListener, token: Int, delayMs: Long) {
        cancelScheduledListen()
        Log.i(TAG, "listen scheduled delay=$delayMs attempt=$index")
        val runnable = Runnable {
            pendingStart = null
            if (token != generation || !active || settled) return@Runnable
            listenAt(index, listener, token)
        }
        pendingStart = runnable
        if (delayMs <= 0L) main.post(runnable) else main.postDelayed(runnable, delayMs)
    }

    private fun cancelScheduledListen() {
        pendingStart?.let { main.removeCallbacks(it) }
        pendingStart = null
    }

    private fun listenAt(index: Int, listener: SpeechListener, token: Int) {
        if (!active || token != generation || settled) return
        val candidate = order.getOrNull(index)
        if (candidate == null) {
            settled = true
            active = false
            deliver { listener.onFailure(ListenFailure.UNAVAILABLE) }
            return
        }
        val component = ComponentName(candidate.packageName, candidate.className)
        val existing = recognizer
        if (existing != null && boundComponent == component) {
            Log.i(TAG, "reuse ${component.flattenToShortString()} attempt=$index")
            armCallbacks(component, index, listener)
            try {
                heardSession = true
                existing.startListening(recognizeIntent())
            } catch (error: RuntimeException) {
                Log.w(TAG, "reuse startListening threw", error)
                recognizer = null
                boundComponent = null
                quietDestroy(existing)
                if (index + 1 < order.size) {
                    scheduleListen(index + 1, listener, token, RESTART_GAP_MS)
                } else {
                    settled = true
                    active = false
                    deliver { listener.onFailure(ListenFailure.UNKNOWN) }
                }
            }
            return
        }
        if (existing != null) {
            recognizer = null
            boundComponent = null
            quietDestroy(existing)
            scheduleListen(index, listener, token, RESTART_GAP_MS)
            return
        }
        Log.i(TAG, "start ${component.flattenToShortString()} attempt=$index")
        val created = try {
            SpeechRecognizer.createSpeechRecognizer(appContext, component)
        } catch (error: RuntimeException) {
            Log.w(TAG, "create failed ${component.flattenToShortString()}", error)
            scheduleListen(index + 1, listener, token, RESTART_GAP_MS)
            return
        }
        recognizer = created
        boundComponent = component
        armCallbacks(component, index, listener)
        created.setRecognitionListener(callbacks)
        try {
            heardSession = true
            created.startListening(recognizeIntent())
        } catch (error: RuntimeException) {
            Log.w(TAG, "startListening threw ${component.flattenToShortString()}", error)
            recognizer = null
            boundComponent = null
            quietDestroy(created)
            if (index + 1 < order.size) {
                scheduleListen(index + 1, listener, token, RESTART_GAP_MS)
            } else {
                settled = true
                active = false
                deliver { listener.onFailure(ListenFailure.UNKNOWN) }
            }
        }
    }

    private fun armCallbacks(component: ComponentName, index: Int, listener: SpeechListener) {
        callbackComponent = component
        callbackIndex = index
        callbackListener = listener
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.i(TAG, "ready ${callbackComponent?.flattenToShortString()}")
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            val token = generation
            val listener = callbackListener ?: return
            val component = callbackComponent ?: return
            val index = callbackIndex
            main.post {
                if (token != generation || settled) return@post
                Log.w(
                    TAG,
                    "error=$error ${errorName(error)} ${component.flattenToShortString()} partial=$lastPartial",
                )
                settle(component, index, listener, token, finalText = null, error = error)
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results.bestText()
            val token = generation
            val listener = callbackListener ?: return
            val component = callbackComponent ?: return
            val index = callbackIndex
            main.post {
                if (token != generation || settled) return@post
                Log.i(TAG, "final raw=${text.orEmpty()} partial=$lastPartial")
                settle(component, index, listener, token, finalText = text, error = null)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults.bestText() ?: return
            val token = generation
            val listener = callbackListener ?: return
            main.post {
                if (token != generation || settled) return@post
                lastPartial = text
                Log.i(TAG, "partial raw=$text")
                listener.onPartial(text)
            }
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            val text = segmentResults.bestText() ?: return
            val token = generation
            val listener = callbackListener ?: return
            main.post {
                if (token != generation || settled) return@post
                lastPartial = text
                Log.i(TAG, "segment raw=$text")
                listener.onPartial(text)
            }
        }

        override fun onEndOfSegmentedSession() {
            val token = generation
            val listener = callbackListener ?: return
            val component = callbackComponent ?: return
            val index = callbackIndex
            main.post {
                if (token != generation || settled) return@post
                Log.i(TAG, "segment end partial=$lastPartial")
                settle(component, index, listener, token, finalText = null, error = null)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun settle(
        component: ComponentName,
        index: Int,
        listener: SpeechListener,
        token: Int,
        finalText: String?,
        error: Int?,
    ) {
        if (token != generation || settled) return
        val transcript = resolveTranscript(finalText, lastPartial)
        val softMiss = transcript == null && (
            error == null ||
                error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            )
        if (softMiss) {
            if (pendingMiss != null) return
            val runnable = Runnable {
                pendingMiss = null
                if (token != generation || settled) return@Runnable
                Log.i(TAG, "miss timeout partial=$lastPartial")
                finishAttempt(
                    component,
                    index,
                    listener,
                    token,
                    finalText = null,
                    error = error ?: SpeechRecognizer.ERROR_NO_MATCH,
                )
            }
            pendingMiss = runnable
            main.postDelayed(runnable, MISS_GRACE_MS)
            return
        }
        cancelPendingMiss()
        finishAttempt(component, index, listener, token, finalText, error)
    }

    private fun finishAttempt(
        component: ComponentName,
        index: Int,
        listener: SpeechListener,
        token: Int,
        finalText: String?,
        error: Int?,
    ) {
        if (token != generation || settled) return
        val transcript = resolveTranscript(finalText, lastPartial)
        val errorLabel = error?.let { "$it ${errorName(it)}" } ?: "-"
        Log.i(
            TAG,
            "settle raw=${finalText.orEmpty()} partial=$lastPartial transcript=${transcript.orEmpty()} error=$errorLabel",
        )
        if (transcript == null && error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && retries < MAX_BUSY_RETRIES) {
            retries++
            settled = false
            active = true
            lastPartial = ""
            Log.w(TAG, "busy retry=$retries")
            scheduleListen(index, listener, token, RESTART_GAP_MS)
            return
        }
        settled = true
        active = false
        if (transcript == null && error != null && shouldTryNext(error) && index + 1 < order.size) {
            val captured = recognizer
            recognizer = null
            boundComponent = null
            quietDestroy(captured)
            main.postDelayed({
                if (token != generation) return@postDelayed
                settled = false
                active = true
                lastPartial = ""
                listenAt(index + 1, listener, token)
            }, RESTART_GAP_MS)
            return
        }
        main.post {
            if (token != generation) return@post
            if (!transcript.isNullOrBlank()) {
                if (transcript != finalText?.trim()) Log.i(TAG, "promoted transcript=$transcript")
                listener.onFinal(transcript)
            } else {
                listener.onFailure(if (error != null) mapError(error) else ListenFailure.NO_MATCH)
            }
        }
    }

    private fun cancelPendingMiss() {
        pendingMiss?.let { main.removeCallbacks(it) }
        pendingMiss = null
    }

    /**
     * Drops the client without [SpeechRecognizer.cancel]. On MagicOS cancel()
     * from the main thread waits for a callback that needs that same thread.
     */
    private fun quietDestroy(instance: SpeechRecognizer?) {
        if (instance == null) return
        main.post {
            SessionTrace.log("listen", "destroy")
            try {
                instance.destroy()
                SessionTrace.log("listen", "destroy done")
            } catch (error: RuntimeException) {
                Log.w(TAG, "destroy failed", error)
                SessionTrace.log("listen", error)
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
        // Text only. Without this, «позвони» is a system voice action: the recognizer
        // opens the dialer or waits on the assistant session, and the UI freezes.
        putExtra("android.speech.extra.DICTATION_MODE", true)
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
        const val MISS_GRACE_MS = 300L
        const val RESTART_GAP_MS = 600L
        const val MAX_BUSY_RETRIES = 2
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

        fun Bundle?.bestText(): String? {
            if (this == null) return null
            val keys = listOf(
                SpeechRecognizer.RESULTS_RECOGNITION,
                "android.speech.extra.RESULTS",
                "query",
                "android.speech.extra.UNSTABLE_TEXT",
            )
            for (key in keys) {
                readText(key)?.let { return it }
            }
            return null
        }

        fun Bundle.readText(key: String): String? {
            getString(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            getStringArrayList(key)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            @Suppress("DEPRECATION")
            val raw = get(key) ?: return null
            val text = when (raw) {
                is String -> raw
                is ArrayList<*> -> raw.firstOrNull()?.toString()
                is List<*> -> raw.firstOrNull()?.toString()
                is Array<*> -> raw.firstOrNull()?.toString()
                else -> null
            }
            return text?.trim()?.takeIf { it.isNotEmpty() }
        }

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

        fun errorName(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
            SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
            else -> "UNKNOWN"
        }
    }
}
