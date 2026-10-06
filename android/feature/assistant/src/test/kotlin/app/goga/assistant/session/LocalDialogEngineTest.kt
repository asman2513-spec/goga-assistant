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
        assertFalse(turn.asksConfirmation)
    }

    @Test
    fun dateUsesMoscowClock() {
        val turn = engine.onUserText("Какая сегодня дата?")
        assertEquals("Сегодня вторник, 6 октября.", turn.reply)
        assertFalse(turn.asksConfirmation)
    }

    @Test
    fun ordinaryPhraseIsAcceptedWithoutAQuestion() {
        val turn = engine.onUserText("Купи молоко")
        assertEquals("Принял: «Купи молоко».", turn.reply)
        assertFalse(turn.asksConfirmation)
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
