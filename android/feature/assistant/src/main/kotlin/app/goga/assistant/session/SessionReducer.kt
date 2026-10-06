package app.goga.assistant.session

data class SessionState(
    val turn: Int = 0,
    val phase: DialogPhase = DialogPhase.LISTENING,
    val partial: String = "",
    val lastUser: String = "",
    val reply: String = "",
    val status: String = "Слушаю",
    val failedListens: Int = 0,
    val preferText: Boolean = false,
    val listening: Boolean = false,
    val speaking: Boolean = false,
)

sealed interface SessionEvent {
    data object ListenStarted : SessionEvent

    data object ListenStopped : SessionEvent

    data class Partial(val text: String) : SessionEvent

    data class FinalText(val text: String, val source: String) : SessionEvent

    data class ListenFailed(val failure: ListenFailure) : SessionEvent

    data object SpeechFinished : SessionEvent
}

/**
 * Binds recognition failures to the dialog. Two missed phrases in a row
 * stop the microphone and ask for text. A typed or recognized line clears that.
 */
class SessionReducer(
    private val dialog: DialogManager,
) {
    fun reduce(state: SessionState, event: SessionEvent): SessionState = when (event) {
        SessionEvent.ListenStarted -> state.copy(
            listening = true,
            partial = "",
            status = "Слушаю",
            phase = if (state.phase == DialogPhase.AWAITING_SHORT_REPLY) {
                state.phase
            } else {
                DialogPhase.LISTENING
            },
        )
        SessionEvent.ListenStopped -> state.copy(
            listening = false,
            status = if (state.preferText) "Давайте текстом." else state.status,
        )
        is SessionEvent.Partial -> state.copy(
            partial = event.text,
            listening = true,
            status = "Слушаю",
        )
        is SessionEvent.FinalText -> {
            val turn = dialog.onUserText(event.text, event.source)
            state.copy(
                turn = state.turn + 1,
                phase = turn.phase,
                partial = "",
                lastUser = event.text.trim(),
                reply = turn.reply,
                status = if (turn.asksConfirmation) "Нужно уточнение" else "Отвечаю",
                failedListens = 0,
                preferText = false,
                listening = false,
                speaking = turn.reply.isNotBlank(),
            )
        }
        is SessionEvent.ListenFailed -> {
            val counts = event.failure == ListenFailure.NO_MATCH || event.failure == ListenFailure.TIMEOUT
            val failed = if (counts) state.failedListens + 1 else state.failedListens
            val prefer = counts && failed >= 2
            state.copy(
                listening = false,
                partial = "",
                failedListens = failed,
                preferText = prefer,
                phase = DialogPhase.FAILED,
                status = if (prefer) "Давайте текстом." else listenFailureMessage(event.failure),
            )
        }
        SessionEvent.SpeechFinished -> state.copy(
            speaking = false,
            phase = if (state.phase == DialogPhase.AWAITING_SHORT_REPLY) {
                DialogPhase.AWAITING_SHORT_REPLY
            } else {
                DialogPhase.LISTENING
            },
            status = if (state.phase == DialogPhase.AWAITING_SHORT_REPLY) "Нужно уточнение" else "Слушаю",
        )
    }
}
