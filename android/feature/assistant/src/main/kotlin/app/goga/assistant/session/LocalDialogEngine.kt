package app.goga.assistant.session

import app.goga.model.CommandSources
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

data class DialogTurn(
    val phase: DialogPhase,
    val reply: String,
    val asksConfirmation: Boolean,
    val intent: String,
    val launch: PhoneLaunch? = null,
)

/**
 * On-device replies. Clear phrases run at once. A destructive verb with no object
 * is the only question that is not a phone step. Phone commands go through [PhoneGateway].
 */
class LocalDialogEngine(
    private val phone: PhoneGateway = PlannedPhoneGateway(),
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(MOSCOW) },
) {
    private var pending: Pending? = null
    private var lastReply: String = ""

    fun onUserText(raw: String): DialogTurn {
        val original = raw.trim()
        val text = normalize(original)
        if (text.isEmpty()) {
            return ask("Не расслышал. Напишите или повторите.", "empty")
        }
        when (val held = pending) {
            is Pending.PhoneWait -> return resumePhone(held.followUp, original, text)
            is Pending.NeedObject -> {
                when {
                    isYes(text) || isNo(text) -> return ask(questionFor(held.verb), "need-object")
                    isStandaloneIntent(text) -> {
                        pending = null
                        return handleFresh(original, text)
                    }
                    else -> {
                        pending = null
                        return say(acceptedDeletion(original), "delete")
                    }
                }
            }
            null -> Unit
        }
        return handleFresh(original, text)
    }

    private fun handleFresh(original: String, text: String): DialogTurn {
        val key = stripLeadIn(text)
        when (knownIntent(key)) {
            "greeting" -> return say("Слушаю.", "greeting")
            "time" -> return say(timeReply(), "time")
            "date" -> return say(dateReply(), "date")
            "help" -> return say(HELP_REPLY, "help")
            "thanks" -> return say("Пожалуйста.", "thanks")
            "repeat" -> return say(lastReply.ifBlank { "Пока нечего повторять." }, "repeat")
            "yes", "no" -> return say("Хорошо.", "yes-no")
        }
        parsePhoneCommand(original)?.let { return apply(phone.handle(it)) }
        destructive(text)?.let { parsed ->
            if (parsed.rest.isEmpty()) {
                pending = Pending.NeedObject(parsed.verb)
                return ask(questionFor(parsed.verb), "need-object")
            }
            return say(acceptedDeletion(original), "delete")
        }
        return say(unknown(original), "unknown")
    }

    private fun resumePhone(followUp: PhoneFollowUp, original: String, text: String): DialogTurn {
        val key = stripLeadIn(text)
        if (abandonsPhone(followUp, key)) {
            pending = null
            return handleFresh(original, text)
        }
        if (isNo(key) && followUp !is PhoneFollowUp.NeedSmsBody) {
            pending = null
            return say("Хорошо.", intentOf(followUp))
        }
        if (followUp is PhoneFollowUp.NeedSmsBody && (key == "отмена" || key == "отбой")) {
            pending = null
            return say("Хорошо.", "sms")
        }
        return when (followUp) {
            PhoneFollowUp.NeedCallTarget -> apply(phone.handle(PhoneCommand.Call(key)))
            is PhoneFollowUp.PickCall -> apply(phone.pickCall(key, followUp.options))
            PhoneFollowUp.NeedSmsRecipient -> {
                val parts = key.split(" ").filter { it.isNotEmpty() }
                val recipient = parts.firstOrNull().orEmpty()
                val body = parts.drop(1).joinToString(" ").ifBlank { null }
                apply(phone.handle(PhoneCommand.Sms(recipient, body)))
            }
            is PhoneFollowUp.NeedSmsBody -> apply(phone.continueSms(followUp.choice, original.trim()))
            is PhoneFollowUp.ConfirmSms -> if (isYes(key)) {
                apply(phone.sendConfirmed(followUp.choice, followUp.body))
            } else {
                ask("Отправить ${followUp.choice.name}: «${followUp.body}»?", "sms")
            }
            is PhoneFollowUp.PickSms -> {
                val chosen = chooseContact(key, followUp.options)
                if (chosen == null) {
                    ask(choiceLine(followUp.options), "sms")
                } else {
                    apply(phone.continueSms(chosen, followUp.body))
                }
            }
            PhoneFollowUp.NeedOpenTarget -> apply(phone.handle(PhoneCommand.OpenApp(key)))
        }
    }

    private fun abandonsPhone(followUp: PhoneFollowUp, key: String): Boolean {
        when (knownIntent(key)) {
            "yes", "no", null -> Unit
            "greeting" -> if (followUp is PhoneFollowUp.NeedSmsBody) return false else return true
            else -> return true
        }
        return parsePhoneCommand(key) != null
    }

    private fun apply(outcome: PhoneOutcome): DialogTurn {
        pending = outcome.followUp?.let { Pending.PhoneWait(it) }
        lastReply = outcome.reply
        return DialogTurn(
            phase = if (outcome.asksConfirmation) DialogPhase.AWAITING_SHORT_REPLY else DialogPhase.SPEAKING,
            reply = outcome.reply,
            asksConfirmation = outcome.asksConfirmation,
            intent = outcome.intent,
            launch = outcome.launch,
        )
    }

    private fun say(reply: String, intent: String): DialogTurn {
        lastReply = reply
        return DialogTurn(
            phase = DialogPhase.SPEAKING,
            reply = reply,
            asksConfirmation = false,
            intent = intent,
        )
    }

    private fun ask(reply: String, intent: String): DialogTurn {
        lastReply = reply
        return DialogTurn(
            phase = DialogPhase.AWAITING_SHORT_REPLY,
            reply = reply,
            asksConfirmation = true,
            intent = intent,
        )
    }

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
        data class PhoneWait(val followUp: PhoneFollowUp) : Pending
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
            "который сейчас час",
            "который час сейчас",
            "сейчас который час",
            "сколько времени",
            "сколько сейчас времени",
            "сколько время",
            "сколько сейчас время",
            "сколько время сейчас",
            "скока время",
            "скока времени",
            "сколька время",
            "сколька времени",
            "какое время",
            "какое сейчас время",
            "время",
            "время сейчас",
            "сейчас время",
            "текущее время",
            "подскажи время",
            "которий час",
            "каторый час",
        )
        val WHICH = setOf("который", "которий", "каторый", "которы")
        val HOUR = setOf("час", "часа", "часу", "часом")
        val HOW_MUCH = setOf("сколько", "скока", "сколька", "скольки", "сколко", "склько", "скок")
        val TIME_WORD = setOf(
            "время",
            "времени",
            "времи",
            "време",
            "временя",
            "времяни",
            "времини",
        )
        val TIME_FILLER = setOf(
            "сейчас",
            "щас",
            "ща",
            "мне",
            "пожалуйста",
            "ну",
            "а",
            "и",
            "на",
            "часах",
            "подскажи",
            "скажи",
            "гога",
            "какое",
            "какая",
            "текущее",
            "точное",
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
        val REPEAT_PHRASES = setOf("повтори", "повторите", "еще раз", "повтори еще раз", "повторите еще раз")
        val YES_WORDS = setOf("да", "ага", "угу", "верно", "подтверждаю", "давай", "именно", "хорошо", "ок", "окей")
        val NO_WORDS = setOf("нет", "неа", "не надо", "отмена", "не удаляй", "не надо удалять", "отбой")
        val CONTAINED_INTENTS = listOf(
            "time" to TIME_PHRASES,
            "date" to DATE_PHRASES,
            "help" to HELP_PHRASES,
            "greeting" to GREETINGS,
            "thanks" to THANKS,
        )

        const val HELP_REPLY =
            "Умею слушать и отвечать. Могу сказать время и дату, позвонить, написать смс, открыть приложение, изменить громкость и фонарик, сказать заряд, запомнить заметку и поставить таймер."

        fun normalize(raw: String): String = raw
            .lowercase(Locale.forLanguageTag("ru"))
            .replace('ё', 'е')
            .replace('\u00A0', ' ')
            .replace(Regex("[\\u200B\\uFEFF]"), "")
            .replace(Regex("[^\\p{L}\\p{N}+\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        fun knownIntent(key: String): String? = when {
            key in GREETINGS -> "greeting"
            isTimeAsk(key) -> "time"
            key in DATE_PHRASES -> "date"
            key in HELP_PHRASES -> "help"
            key in THANKS -> "thanks"
            key in REPEAT_PHRASES -> "repeat"
            key in YES_WORDS -> "yes"
            key in NO_WORDS -> "no"
            else -> containedIntent(key)
        }

        /** Clock questions, including the spoken form «сколько время» and STT slips. */
        fun isTimeAsk(key: String): Boolean {
            if (key in TIME_PHRASES) return true
            val words = key.split(" ").filter { it.isNotEmpty() }
            if (words.isEmpty()) return false
            val hasWhich = words.any { it in WHICH }
            val hasHour = words.any { it in HOUR }
            if (hasWhich && hasHour) return true
            val hasMuch = words.any { it in HOW_MUCH }
            val hasTime = words.any { it in TIME_WORD }
            if (hasMuch && hasTime) return true
            val content = words.filterNot { it in TIME_FILLER }
            return content.isNotEmpty() &&
                content.all { it in TIME_WORD || it in HOW_MUCH || it in WHICH || it in HOUR } &&
                content.any { it in TIME_WORD }
        }

        fun containedIntent(key: String): String? {
            val padded = " $key "
            var bestIntent: String? = null
            var bestLength = 0
            for ((intent, phrases) in CONTAINED_INTENTS) {
                for (phrase in phrases) {
                    if (phrase.length < bestLength || phrase.length < 8) continue
                    if (!padded.contains(" $phrase ")) continue
                    bestIntent = intent
                    bestLength = phrase.length
                }
            }
            return bestIntent
        }

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
            val intent = knownIntent(key)
            if (
                intent == "greeting" || intent == "time" || intent == "date" ||
                intent == "help" || intent == "thanks" || intent == "repeat"
            ) {
                return true
            }
            return parsePhoneCommand(text) != null
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

        fun unknown(original: String): String = "Пока не умею: «${clip(original)}»."

        fun intentOf(followUp: PhoneFollowUp): String = when (followUp) {
            PhoneFollowUp.NeedCallTarget, is PhoneFollowUp.PickCall -> "call"
            PhoneFollowUp.NeedOpenTarget -> "open-app"
            else -> "sms"
        }

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
