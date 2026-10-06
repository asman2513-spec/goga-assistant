package app.goga.assistant.session

import app.goga.model.CommandSources
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

data class DialogTurn(
    val phase: DialogPhase,
    val reply: String,
    val asksConfirmation: Boolean,
)

/**
 * On-device replies for the stage 1 shell. A clear phrase is accepted at once.
 * The only question is a destructive verb with no object, because there is nothing to do yet.
 * Notes, reminders, and the bot arrive in later stages; this engine does not call them.
 */
class LocalDialogEngine(
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(MOSCOW) },
) {
    private var pending: Pending? = null

    fun onUserText(raw: String): DialogTurn {
        val original = raw.trim()
        val text = normalize(original)
        if (text.isEmpty()) {
            return ask("Не расслышал. Напишите или повторите.")
        }
        when (val held = pending) {
            is Pending.NeedObject -> {
                when {
                    isYes(text) || isNo(text) -> return ask(questionFor(held.verb))
                    isStandaloneIntent(text) -> {
                        pending = null
                        return handleFresh(original, text)
                    }
                    else -> {
                        pending = null
                        return say(acceptedDeletion(original))
                    }
                }
            }
            null -> Unit
        }
        return handleFresh(original, text)
    }

    private fun handleFresh(original: String, text: String): DialogTurn {
        val key = stripLeadIn(text)
        when (key) {
            in GREETINGS -> return say("Слушаю.")
            in TIME_PHRASES -> return say(timeReply())
            in DATE_PHRASES -> return say(dateReply())
            in HELP_PHRASES -> return say(HELP_REPLY)
            in THANKS -> return say("Пожалуйста.")
            in YES_WORDS, in NO_WORDS -> return say("Хорошо.")
        }
        destructive(text)?.let { parsed ->
            if (parsed.rest.isEmpty()) {
                pending = Pending.NeedObject(parsed.verb)
                return ask(questionFor(parsed.verb))
            }
            return say(acceptedDeletion(original))
        }
        return say(accepted(original))
    }

    private fun say(reply: String): DialogTurn = DialogTurn(
        phase = DialogPhase.SPEAKING,
        reply = reply,
        asksConfirmation = false,
    )

    private fun ask(reply: String): DialogTurn = DialogTurn(
        phase = DialogPhase.AWAITING_SHORT_REPLY,
        reply = reply,
        asksConfirmation = true,
    )

    private fun timeReply(): String {
        val now = clock()
        val hour = now.hour.toString().padStart(2, '0')
        val minute = now.minute.toString().padStart(2, '0')
        return "Сейчас $hour:$minute."
    }

    private fun dateReply(): String {
        val now = clock()
        val weekday = WEEKDAYS[now.dayOfWeek.value]
        val month = MONTHS[now.monthValue]
        return "Сегодня $weekday, ${now.dayOfMonth} $month."
    }

    private sealed interface Pending {
        data class NeedObject(val verb: String) : Pending
    }

    private companion object {
        val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")

        val LEAD_IN = setOf("гога", "пожалуйста", "слушай", "скажи")
        val VERBS = listOf(
            "удалить",
            "удали",
            "стереть",
            "сотри",
            "отменить",
            "отмени",
            "очистить",
            "очисти",
        )
        val WEEKDAYS = listOf(
            "",
            "понедельник",
            "вторник",
            "среда",
            "четверг",
            "пятница",
            "суббота",
            "воскресенье",
        )
        val MONTHS = listOf(
            "",
            "января",
            "февраля",
            "марта",
            "апреля",
            "мая",
            "июня",
            "июля",
            "августа",
            "сентября",
            "октября",
            "ноября",
            "декабря",
        )
        val GREETINGS = setOf(
            "привет",
            "привет гога",
            "здравствуй",
            "здравствуйте",
            "здрасьте",
            "добрый день",
            "добрый вечер",
            "доброе утро",
            "алло",
            "гога",
        )
        val TIME_PHRASES = setOf(
            "который час",
            "сколько времени",
            "сколько сейчас времени",
            "который сейчас час",
            "какое время",
            "время",
            "подскажи время",
        )
        val DATE_PHRASES = setOf(
            "какая сегодня дата",
            "какое сегодня число",
            "какой сегодня день",
            "какой сегодня день недели",
            "какое число",
            "какой день",
            "какая дата",
        )
        val HELP_PHRASES = setOf(
            "что ты умеешь",
            "что ты можешь",
            "что ты можешь сделать",
            "что умеешь",
            "помощь",
            "помоги",
        )
        val THANKS = setOf("спасибо", "благодарю", "спасибо гога")
        val YES_WORDS = setOf("да", "ага", "угу", "верно", "подтверждаю", "давай", "именно", "хорошо", "ок", "окей")
        val NO_WORDS = setOf("нет", "неа", "не надо", "отмена", "не удаляй", "не надо удалять", "отбой")

        const val HELP_REPLY =
            "Пока умею слушать и отвечать. Спросите, который час, или просто скажите фразу. Заметки, напоминания и бот подключу позже."

        fun normalize(raw: String): String = raw
            .lowercase(Locale.forLanguageTag("ru"))
            .replace('ё', 'е')
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        fun stripLeadIn(text: String): String {
            var rest = text
            while (true) {
                val prefix = LEAD_IN.firstOrNull { rest == it || rest.startsWith("$it ") } ?: break
                rest = rest.removePrefix(prefix).trim()
            }
            return rest
        }

        fun isYes(text: String): Boolean = stripLeadIn(text) in YES_WORDS

        fun isNo(text: String): Boolean = stripLeadIn(text) in NO_WORDS

        fun isStandaloneIntent(text: String): Boolean {
            val key = stripLeadIn(text)
            return key in GREETINGS || key in TIME_PHRASES || key in DATE_PHRASES ||
                key in HELP_PHRASES || key in THANKS
        }

        fun destructive(text: String): Parsed? {
            val stripped = stripLeadIn(text)
            val verb = VERBS.firstOrNull { stripped == it || stripped.startsWith("$it ") } ?: return null
            return Parsed(verb, stripped.removePrefix(verb).trim())
        }

        fun questionFor(verb: String): String = when (verb) {
            "отмени", "отменить" -> "Что отменить?"
            "очисти", "очистить" -> "Что очистить?"
            "сотри", "стереть" -> "Что стереть?"
            else -> "Что удалить?"
        }

        fun clip(text: String): String {
            val trimmed = text.trim()
            return if (trimmed.length <= 180) trimmed else trimmed.take(179).trimEnd() + "…"
        }

        fun accepted(original: String): String = "Принял: «${clip(original)}»."

        fun acceptedDeletion(original: String): String =
            "Принял к удалению: «${clip(original)}». Пока стирать нечего."
    }

    private data class Parsed(val verb: String, val rest: String)
}

class LocalDialogManager(
    private val engine: LocalDialogEngine = LocalDialogEngine(),
) : DialogManager {
    override var phase: DialogPhase = DialogPhase.LISTENING
        private set

    override fun onUserText(text: String, source: String): DialogTurn {
        require(source.isNotBlank())
        val turn = engine.onUserText(text)
        phase = turn.phase
        return turn
    }
}

/** Kept so call sites name the source the gateway already understands. */
fun voiceSource(): String = CommandSources.VOICE

fun textSource(): String = CommandSources.TEXT
