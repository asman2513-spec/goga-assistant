package app.goga.assistant.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
                pendingText = null
                pendingDone = null
                speak(text, done)
            }
        }
    }

    override fun speak(text: String, onDone: () -> Unit) {
        val current = engine
        val token = ++generation
        if (!ready || current == null) {
            pendingText = text
            pendingDone = onDone
            return
        }
        val utterance = UUID.randomUUID().toString()
        current.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

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
        if (queued == TextToSpeech.ERROR) finish(token, onDone)
    }

    override fun stop() {
        generation++
        pendingText = null
        pendingDone = null
        engine?.stop()
    }

    override fun release() {
        generation++
        pendingText = null
        pendingDone = null
        onReady = null
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
    }

    private fun finish(token: Int, onDone: () -> Unit) {
        if (token != generation) return
        main.post {
            if (token == generation) onDone()
        }
    }
}
