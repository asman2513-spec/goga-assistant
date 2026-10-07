package app.goga.assistant.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/** System synthesizer. Russian is requested; if the voice pack is missing the line stays on screen. */
class SystemSpeechOutput(
    context: Context,
) : app.goga.assistant.session.TextToSpeech {
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var generation = 0
    private var pendingText: String? = null
    private var pendingDone: (() -> Unit)? = null
    private var pendingToken = -1
    private var deliveredToken = -1
    private var timeout: Runnable? = null

    var russianVoice: Boolean? = null
        private set

    var onReady: ((russian: Boolean) -> Unit)? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            val current = engine
            if (status != TextToSpeech.SUCCESS || current == null) {
                ready = false
                russianVoice = false
                main.post { onReady?.invoke(false) }
                pendingDone?.let { done ->
                    pendingDone = null
                    pendingText = null
                    main.post(done)
                }
                return@TextToSpeech
            }
            val language = current.setLanguage(Locale.forLanguageTag("ru-RU"))
            val russian = language != TextToSpeech.LANG_MISSING_DATA &&
                language != TextToSpeech.LANG_NOT_SUPPORTED
            russianVoice = russian
            ready = true
            main.post { onReady?.invoke(russian) }
            pendingText?.let { text ->
                val done = pendingDone ?: {}
                val token = pendingToken
                pendingText = null
                pendingDone = null
                pendingToken = -1
                main.post {
                    if (token == deliveredToken || token != generation) return@post
                    speak(text, done)
                }
            }
        }
    }

    override fun speak(text: String, onDone: () -> Unit) {
        val token = ++generation
        val wait = speechWatchdogMs(text)
        Log.i(TAG, "speak len=${text.length} ready=$ready watchdog=$wait")
        SessionTrace.log("tts", "speak ready=$ready len=${text.length} watchdog=$wait")
        armWatchdog(token, wait, onDone)
        val current = engine
        if (!ready || current == null) {
            pendingText = text
            pendingDone = onDone
            pendingToken = token
            return
        }
        pendingText = null
        pendingDone = null
        pendingToken = -1
        deliverSpeak(current, text, token, onDone)
    }

    private fun deliverSpeak(current: TextToSpeech, text: String, token: Int, onDone: () -> Unit) {
        val utterance = UUID.randomUUID().toString()
        current.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == utterance) SessionTrace.log("tts", "start")
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == utterance) finish(token, onDone)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            @Deprecated("Deprecated in UtteranceProgressListener")
            override fun onError(utteranceId: String?) {
                if (utteranceId == utterance) finish(token, onDone)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == utterance) finish(token, onDone)
            }
        })
        val queued = current.speak(text, TextToSpeech.QUEUE_FLUSH, null, utterance)
        if (queued == TextToSpeech.ERROR) {
            Log.w(TAG, "speak rejected")
            SessionTrace.log("tts", "rejected")
            finish(token, onDone)
        }
    }

    private fun armWatchdog(token: Int, waitMs: Long, onDone: () -> Unit) {
        cancelTimeout()
        val watchdog = Runnable {
            if (token == generation) {
                Log.w(TAG, "speak timeout")
                SessionTrace.log("tts", "watchdog")
                finish(token, onDone)
            }
        }
        timeout = watchdog
        main.postDelayed(watchdog, waitMs)
    }

    override fun stop() {
        generation++
        pendingText = null
        pendingDone = null
        pendingToken = -1
        cancelTimeout()
        engine?.stop()
    }

    override fun release() {
        generation++
        pendingText = null
        pendingDone = null
        pendingToken = -1
        onReady = null
        cancelTimeout()
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
    }

    private fun finish(token: Int, onDone: () -> Unit) {
        if (token != generation || token == deliveredToken) return
        deliveredToken = token
        if (pendingToken == token) {
            pendingText = null
            pendingDone = null
            pendingToken = -1
        }
        cancelTimeout()
        Log.i(TAG, "speak finished")
        SessionTrace.log("tts", "finished")
        main.post {
            if (token == generation) onDone()
        }
    }

    private fun cancelTimeout() {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
    }

    private companion object {
        const val TAG = "Goga/Tts"
    }
}
