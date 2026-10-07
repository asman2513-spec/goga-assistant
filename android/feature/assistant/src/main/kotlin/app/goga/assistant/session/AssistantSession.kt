package app.goga.assistant.session

/**
 * Stage 1 session contract. The system host is [GogaInteractionSession]
 * (default assistant, including the lock screen when the firmware allows it).
 * The tile and the in-app button use the same overlay inside AssistantActivity.
 * There is no wake word in this stage.
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

enum class SpeechMode {
    ON_DEVICE,
    SYSTEM,
    UNAVAILABLE,
}

interface SpeechListener {
    fun onReady() {}

    fun onPartial(text: String)

    fun onFinal(text: String)

    fun onFailure(failure: ListenFailure)
}

/** Partial results need a listener. A single suspending call would hide them. */
interface SpeechToText {
    val mode: SpeechMode

    fun start(listener: SpeechListener)

    fun stop()

    fun release()
}

interface TextToSpeech {
    fun speak(text: String, onDone: () -> Unit = {})

    fun stop()

    fun release()
}

/**
 * Decides the next line. A question comes back only when the phrase cannot be
 * carried out without an answer. Stage 3 can replace the local engine with the gateway.
 */
interface DialogManager {
    val phase: DialogPhase

    fun onUserText(text: String, source: String): DialogTurn
}

interface AssistantSession {
    fun show()

    fun hide()
}
