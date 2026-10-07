package app.goga.assistant.session

/**
 * One session, one phase. Every event either stays put or lands on Idle, Speaking, or Close.
 * A launch that only asks for a permission is dropped: the reply is spoken and listening resumes.
 * Leaving the app happens after speech, in [PipePhase.Acting], and only through the caller's starter.
 */
enum class PipePhase {
    Idle,
    Listening,
    Thinking,
    Speaking,
    Acting,
    Close,
}

data class PipeState(
    val phase: PipePhase = PipePhase.Idle,
    val generation: Int = 0,
    val turn: Int = 0,
    val partial: String = "",
    val lastUser: String = "",
    val source: String = "",
    val reply: String = "",
    val intent: String = "",
    val status: String = "Ожидание",
    val launch: PhoneLaunch? = null,
    val asks: Boolean = false,
    val preferText: Boolean = false,
    val failedListens: Int = 0,
)

sealed interface PipeIn {
    data object Show : PipeIn

    data object Hide : PipeIn

    data object ArmListen : PipeIn

    data object EarReady : PipeIn

    data object Pause : PipeIn

    data class Partial(val text: String) : PipeIn

    data class Heard(val text: String, val source: String) : PipeIn

    data class Missed(val failure: ListenFailure) : PipeIn

    data object ListenTimeout : PipeIn

    data class Thought(val turn: DialogTurn) : PipeIn

    data object ThinkTimeout : PipeIn

    data object SpeechDone : PipeIn

    data object SpeechTimeout : PipeIn

    data class ActDone(val ok: Boolean) : PipeIn

    data object ActTimeout : PipeIn

    data class Fault(val message: String) : PipeIn
}

fun reducePipe(state: PipeState, event: PipeIn): PipeState = try {
    when (event) {
        PipeIn.Show -> state.copy(
            phase = PipePhase.Idle,
            generation = state.generation + 1,
            partial = "",
            lastUser = "",
            reply = "",
            intent = "",
            launch = null,
            asks = false,
            preferText = false,
            failedListens = 0,
            status = "Ожидание",
        )
        PipeIn.Hide -> state.copy(
            phase = PipePhase.Idle,
            generation = state.generation + 1,
            partial = "",
            launch = null,
            status = "Скрыто",
        )
        PipeIn.ArmListen -> if (state.phase == PipePhase.Idle) {
            state.copy(phase = PipePhase.Listening, partial = "", status = "Готовлюсь")
        } else {
            state
        }
        PipeIn.EarReady -> if (state.phase == PipePhase.Listening) {
            state.copy(status = "Слушаю")
        } else {
            state
        }
        PipeIn.Pause -> if (state.phase == PipePhase.Listening) {
            state.copy(phase = PipePhase.Idle, partial = "", status = "Пауза")
        } else {
            state
        }
        is PipeIn.Partial -> if (state.phase == PipePhase.Listening) {
            state.copy(partial = event.text)
        } else {
            state
        }
        is PipeIn.Heard -> if (state.phase == PipePhase.Close) {
            state
        } else {
            hear(state, event.text, event.source)
        }
        is PipeIn.Missed -> miss(state)
        PipeIn.ListenTimeout -> if (state.phase == PipePhase.Listening && state.partial.isNotBlank()) {
            hear(state, state.partial, "voice")
        } else {
            miss(state)
        }
        is PipeIn.Thought -> if (state.phase != PipePhase.Thinking) {
            state
        } else {
            val launch = event.turn.launch?.takeUnless { it is PhoneLaunch.Permissions }
            if (event.turn.reply.isBlank()) {
                state.copy(
                    phase = PipePhase.Idle,
                    launch = null,
                    intent = event.turn.intent,
                    asks = false,
                    status = "Слушаю",
                )
            } else {
                state.copy(
                    phase = PipePhase.Speaking,
                    reply = event.turn.reply,
                    intent = event.turn.intent,
                    asks = event.turn.asksConfirmation,
                    launch = launch,
                    status = "Отвечаю",
                )
            }
        }
        PipeIn.ThinkTimeout -> if (state.phase == PipePhase.Thinking) {
            speakLine(state, "Не успел разобрать.", "timeout")
        } else {
            state
        }
        PipeIn.SpeechDone -> if (state.phase != PipePhase.Speaking) {
            state
        } else if (state.launch?.leavesSession == true) {
            state.copy(phase = PipePhase.Acting, status = "Открываю")
        } else {
            state.copy(
                phase = PipePhase.Idle,
                launch = null,
                status = if (state.asks) "Нужно уточнение" else "Слушаю",
            )
        }
        PipeIn.SpeechTimeout -> if (state.phase == PipePhase.Speaking) {
            state.copy(
                phase = PipePhase.Idle,
                launch = null,
                reply = "Не успел сказать.",
                status = "Слушаю",
                intent = "timeout",
            )
        } else {
            state
        }
        is PipeIn.ActDone -> if (state.phase != PipePhase.Acting) {
            state
        } else if (event.ok && state.launch?.leavesSession == true) {
            state.copy(phase = PipePhase.Close, launch = null, status = "Закрываю")
        } else {
            speakLine(state, actFailureReply(state.intent, state.launch, state.reply), "act-failed")
        }
        PipeIn.ActTimeout -> if (state.phase == PipePhase.Acting) {
            speakLine(state, actFailureReply(state.intent, state.launch, state.reply), "timeout")
        } else {
            state
        }
        is PipeIn.Fault -> speakLine(state, event.message, "fault")
    }
} catch (error: RuntimeException) {
    speakLine(state, "Сбой. Слушаю снова.", "fault")
}

private fun hear(state: PipeState, text: String, source: String): PipeState = state.copy(
    phase = PipePhase.Thinking,
    generation = state.generation + 1,
    turn = state.turn + 1,
    partial = "",
    lastUser = text.trim(),
    source = source,
    reply = "",
    launch = null,
    asks = false,
    failedListens = 0,
    preferText = false,
    status = "Думаю",
)

private fun miss(state: PipeState): PipeState = if (state.phase == PipePhase.Listening) {
    val failed = state.failedListens + 1
    val prefer = failed >= 2
    state.copy(
        phase = PipePhase.Idle,
        partial = "",
        failedListens = failed,
        preferText = prefer,
        status = if (prefer) "Давайте текстом." else "Не расслышал.",
    )
} else {
    state
}

fun actFailureReply(intent: String, launch: PhoneLaunch?, reply: String = ""): String {
    val camera = reply.contains("камер") ||
        (launch is PhoneLaunch.OpenPackage && launch.packageName.contains("camera")) ||
        (launch is PhoneLaunch.ViewAction && (
            launch.action.contains("STILL_IMAGE_CAMERA") || launch.action.contains("IMAGE_CAPTURE")
            ))
    return when {
        intent == "call" || launch is PhoneLaunch.Tel -> "Не получилось позвонить."
        camera -> "Не получилось открыть камеру."
        intent == "sms" || launch is PhoneLaunch.SmsCompose -> "Не получилось открыть сообщение."
        intent == "settings" || launch is PhoneLaunch.OpenSettings -> "Не получилось открыть настройки."
        intent == "alarm" || intent == "timer" || launch is PhoneLaunch.Clock -> "Не получилось поставить."
        intent == "wireless" && reply.contains("блютуз") -> "Не получилось открыть блютуз."
        intent == "wireless" -> "Не получилось открыть вайфай."
        else -> "Не получилось открыть."
    }
}

private fun speakLine(state: PipeState, reply: String, intent: String): PipeState = state.copy(
    phase = PipePhase.Speaking,
    generation = state.generation + 1,
    turn = state.turn + 1,
    launch = null,
    reply = reply,
    intent = intent,
    asks = false,
    status = "Отвечаю",
)

/** Visible on the card so a screenshot shows where the pipeline stopped. */
fun pipePhaseLine(state: PipeState): String = "${state.phase.name} · ${state.status}"
