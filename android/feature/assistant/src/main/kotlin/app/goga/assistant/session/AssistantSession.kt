package app.goga.assistant.session

/**
 * Stage 1 fills these in: VoiceInteractionSession overlay, on-device SpeechRecognizer,
 * system TextToSpeech, and the listen → send → speak state machine.
 * Nothing here talks to Android yet, so the role of default assistant is not declared.
 */
enum class DialogPhase {
    LISTENING,
    RECOGNIZED,
    SENT,
    WAITING,
    SPEAKING,
    AWAITING_SHORT_REPLY,
    FAILED,
    CLOSED,
}

data class Transcript(
    val text: String,
    val confidence: Float?,
)

interface SpeechToText {
    suspend fun transcribe(localeTag: String = "ru-RU"): Transcript
}

interface TextToSpeech {
    suspend fun speak(text: String, localeTag: String = "ru-RU")
}

interface DialogManager {
    val phase: DialogPhase

    suspend fun onUserText(text: String, source: String): DialogPhase
}

interface AssistantSession {
    fun show()

    fun hide()
}
