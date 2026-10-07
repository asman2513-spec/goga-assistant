package app.goga.assistant.session

import app.goga.model.CommandSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class LocalDialogEngineTest {
    private val clock = ZonedDateTime.of(2026, 10, 6, 15, 5, 0, 0, ZoneId.of("Europe/Moscow"))
    private val engine = LocalDialogEngine { clock }

    @Test
    fun greetingIsImmediate() {
        val turn = engine.onUserText("Привет!")
        assertEquals("Слушаю.", turn.reply)
        assertEquals(DialogPhase.SPEAKING, turn.phase)
        assertFalse(turn.asksConfirmation)
    }

    @Test
    fun timeUsesMoscowClock() {
        val turn = engine.onUserText("который час")
        assertEquals("Сейчас 15:05.", turn.reply)
        assertEquals("time", turn.intent)
        assertFalse(turn.asksConfirmation)
    }

    @Test
    fun colloquialTimeTellsTheClockInsteadOfAccepting() {
        val phrases = listOf(
            "сколько время",
            "Сколько время?",
            "сколько времени",
            "сколько сейчас время",
            "скока время",
            "сколька времени",
            "который сейчас час",
            "которий час",
            "какое сейчас время",
            "ну сколько время",
        )
        for (phrase in phrases) {
            val turn = LocalDialogEngine { clock }.onUserText(phrase)
            assertEquals(phrase, "time", turn.intent)
            assertEquals(phrase, "Сейчас 15:05.", turn.reply)
        }
    }

    @Test
    fun dateUsesMoscowClock() {
        val turn = engine.onUserText("Какая сегодня дата?")
        assertEquals("Сегодня вторник, 6 октября.", turn.reply)
        assertFalse(turn.asksConfirmation)
    }

    @Test
    fun callAsksThenDialsAndStillHearsTheNextPhrase() {
        val ask = engine.onUserText("позвони")
        assertEquals("call", ask.intent)
        assertEquals("Кому позвонить?", ask.reply)
        assertTrue(ask.asksConfirmation)
        val named = engine.onUserText("маме")
        assertEquals("Звоню маме.", named.reply)
        assertTrue(named.launch is PhoneLaunch.Tel)
        assertFalse(named.asksConfirmation)
        assertEquals("Слушаю.", engine.onUserText("привет").reply)

        val direct = LocalDialogEngine { clock }.onUserText("набери +7 999 000 00 00")
        assertEquals("call", direct.intent)
        assertEquals("Звоню +7 999 000 00 00.", direct.reply)
        val tel = direct.launch as PhoneLaunch.Tel
        assertTrue(tel.number.contains("7999"))
    }

    @Test
    fun smsConfirmsThenSendsAndOpenNamesTheApp() {
        val local = LocalDialogEngine { clock }
        val ask = local.onUserText("отправь смс маме привет")
        assertEquals("sms", ask.intent)
        assertEquals("Отправить маме: «привет»?", ask.reply)
        assertTrue(ask.asksConfirmation)
        val sent = local.onUserText("да")
        assertEquals("Отправил маме: «привет».", sent.reply)
        assertFalse(sent.asksConfirmation)
        assertEquals("Слушаю.", local.onUserText("привет").reply)

        val body = LocalDialogEngine { clock }
        assertEquals("Что написать маме?", body.onUserText("напиши сообщение маме").reply)
        assertTrue(body.onUserText("я опаздываю").reply.contains("опаздываю"))

        val open = LocalDialogEngine { clock }
        assertEquals("Открываю камеру.", open.onUserText("открой камеру").reply)
        assertTrue(open.onUserText("открой камеру").launch is PhoneLaunch.OpenPackage)
        assertEquals("Открываю настройки.", open.onUserText("запусти настройки").reply)
        assertEquals("Что открыть?", open.onUserText("открой").reply)
        assertEquals("Открываю календарь.", open.onUserText("календарь").reply)
    }

    @Test
    fun deviceNotesClockAndUnknownStayInTheDialog() {
        val local = LocalDialogEngine { clock }
        assertEquals("Делаю громче.", local.onUserText("громче").reply)
        assertEquals("Выключаю звук.", local.onUserText("без звука").reply)
        assertEquals("Включаю фонарик.", local.onUserText("фонарик").reply)
        assertEquals("Смотрю заряд.", local.onUserText("какой заряд").reply)
        assertEquals("Открываю вайфай.", local.onUserText("вайфай").reply)
        assertEquals("Записал: «купить молоко».", local.onUserText("запомни купить молоко").reply)
        assertTrue(local.onUserText("что я просил запомнить").reply.contains("купить молоко"))
        assertEquals("Заметки стёр.", local.onUserText("удали заметки").reply)
        assertEquals("Пока ничего не запомнил.", local.onUserText("мои заметки").reply)
        assertEquals("Ставлю таймер на 5 мин.", local.onUserText("таймер на 5 минут").reply)
        assertTrue(local.onUserText("таймер на 5 минут").launch is PhoneLaunch.Clock)
        assertEquals("Ставлю будильник на 19:00.", local.onUserText("будильник на 7 вечера").reply)
        val unknown = local.onUserText("Купи молоко")
        assertEquals("Пока не умею: «Купи молоко».", unknown.reply)
        assertEquals("unknown", unknown.intent)
        assertFalse(unknown.asksConfirmation)
        assertEquals("Слушаю.", local.onUserText("привет").reply)
    }

    @Test
    fun repeatSaysThePreviousLine() {
        engine.onUserText("который час")
        assertEquals("Сейчас 15:05.", engine.onUserText("повтори").reply)
        assertEquals("Хорошо.", engine.onUserText("отмена").reply)
    }

    @Test
    fun helpDoesNotAskForMore() {
        val turn = engine.onUserText("что ты умеешь")
        assertTrue(turn.reply.contains("слушать"))
        assertFalse(turn.asksConfirmation)
    }

    @Test
    fun deleteWithoutObjectAsksOnce() {
        val turn = engine.onUserText("удали")
        assertEquals("Что удалить?", turn.reply)
        assertEquals(DialogPhase.AWAITING_SHORT_REPLY, turn.phase)
        assertTrue(turn.asksConfirmation)
    }

    @Test
    fun objectAfterTheQuestionCompletesWithoutAnotherQuestion() {
        engine.onUserText("Удали")
        val turn = engine.onUserText("заметку про хостинг")
        assertEquals("Принял к удалению: «заметку про хостинг». Пока стирать нечего.", turn.reply)
        assertFalse(turn.asksConfirmation)
    }

    @Test
    fun specificDeleteDoesNotAskForConfirmation() {
        val turn = engine.onUserText("удали заметку про хостинг")
        assertEquals("Принял к удалению: «удали заметку про хостинг». Пока стирать нечего.", turn.reply)
        assertFalse(turn.asksConfirmation)
    }

    @Test
    fun deleteEverythingIsDoneImmediately() {
        val turn = engine.onUserText("Удали всё!")
        assertEquals("Принял к удалению: «Удали всё!». Пока стирать нечего.", turn.reply)
        assertFalse(turn.asksConfirmation)
    }

    @Test
    fun clearEverythingIsDoneImmediately() {
        val turn = engine.onUserText("очисти все")
        assertEquals("Принял к удалению: «очисти все». Пока стирать нечего.", turn.reply)
        assertFalse(turn.asksConfirmation)
    }

    @Test
    fun clockReplacesAnUnansweredObjectQuestion() {
        engine.onUserText("удали")
        val turn = engine.onUserText("который час")
        assertEquals("Сейчас 15:05.", turn.reply)
        assertFalse(turn.asksConfirmation)
    }

    @Test
    fun yesAndNoWhileWaitingForAnObjectDoNotCountAsTheObject() {
        engine.onUserText("отмени")
        val again = engine.onUserText("да")
        assertEquals("Что отменить?", again.reply)
        assertTrue(again.asksConfirmation)
        val done = engine.onUserText("встречу")
        assertFalse(done.asksConfirmation)
        assertTrue(done.reply.contains("встречу"))
    }

    @Test
    fun yesWithoutAPendingQuestionIsNotAConfirmation() {
        val turn = engine.onUserText("да")
        assertEquals("Хорошо.", turn.reply)
        assertFalse(turn.asksConfirmation)
    }

    @Test
    fun blankAsksToRepeat() {
        val turn = engine.onUserText("   ")
        assertEquals("Не расслышал. Напишите или повторите.", turn.reply)
        assertTrue(turn.asksConfirmation)
    }

    @Test
    fun leadInStillReachesTheClock() {
        val turn = engine.onUserText("Гога, скажи который час")
        assertEquals("Сейчас 15:05.", turn.reply)
        assertEquals("time", turn.intent)
    }

    @Test
    fun punctuationAndCaseStillMatchTimeAndGreeting() {
        assertEquals("time", engine.onUserText("Который час?").intent)
        assertEquals("Слушаю.", engine.onUserText("Привет").reply)
        assertEquals("time", engine.onUserText("ну который час").intent)
        assertEquals("date", engine.onUserText("Какая сегодня дата?").intent)
    }
}

class TranscriptTest {
    @Test
    fun finalWinsWhenPresent() {
        assertEquals("который час", resolveTranscript("который час", "привет"))
    }

    @Test
    fun partialFillsAnEmptyFinal() {
        assertEquals("который час", resolveTranscript("  ", " который час "))
        assertEquals("привет", resolveTranscript(null, "привет"))
    }

    @Test
    fun blankPartialAndFinalAreMissing() {
        assertEquals(null, resolveTranscript(null, " "))
    }

    @Test
    fun punctuationOnlyFinalKeepsThePartialWords() {
        assertEquals("Который час", resolveTranscript("?", "Который час"))
        assertEquals("привет", resolveTranscript("...", "привет"))
    }
}

class LocalDialogManagerTest {
    @Test
    fun phaseFollowsTheTurn() {
        val manager = LocalDialogManager(LocalDialogEngine())
        assertEquals(DialogPhase.LISTENING, manager.phase)
        manager.onUserText("привет", CommandSources.TEXT)
        assertEquals(DialogPhase.SPEAKING, manager.phase)
        manager.onUserText("удали", CommandSources.VOICE)
        assertEquals(DialogPhase.AWAITING_SHORT_REPLY, manager.phase)
    }
}
