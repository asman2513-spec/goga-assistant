package app.goga.assistant.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReducerTest {
    private val reducer = SessionReducer(LocalDialogManager(LocalDialogEngine()))

    @Test
    fun twoMissesSwitchToText() {
        val once = reducer.reduce(SessionState(), SessionEvent.ListenFailed(ListenFailure.NO_MATCH))
        assertEquals("Не расслышал.", once.status)
        assertFalse(once.preferText)
        assertEquals(1, once.failedListens)

        val twice = reducer.reduce(once, SessionEvent.ListenFailed(ListenFailure.TIMEOUT))
        assertEquals("Давайте текстом.", twice.status)
        assertTrue(twice.preferText)
        assertEquals(DialogPhase.FAILED, twice.phase)
        assertFalse(twice.listening)
    }

    @Test
    fun languageFailureDoesNotForceTextMode() {
        val failed = reducer.reduce(SessionState(), SessionEvent.ListenFailed(ListenFailure.LANGUAGE))
        assertFalse(failed.preferText)
        assertTrue(failed.status.contains("русского"))
    }

    @Test
    fun recognizedTextClearsFailuresAndSpeaks() {
        val failed = reducer.reduce(SessionState(), SessionEvent.ListenFailed(ListenFailure.NO_MATCH))
        val heard = reducer.reduce(failed, SessionEvent.FinalText("привет", textSource()))
        assertEquals(0, heard.failedListens)
        assertFalse(heard.preferText)
        assertEquals("Слушаю.", heard.reply)
        assertTrue(heard.speaking)
        assertEquals(DialogPhase.SPEAKING, heard.phase)
        assertEquals("привет", heard.lastUser)
    }

    @Test
    fun speechFinishedKeepsTheReply() {
        val heard = reducer.reduce(SessionState(), SessionEvent.FinalText("купи молоко", voiceSource()))
        val done = reducer.reduce(heard, SessionEvent.SpeechFinished)
        assertEquals(heard.reply, done.reply)
        assertFalse(done.speaking)
        assertEquals(DialogPhase.LISTENING, done.phase)
        assertEquals("Слушаю", done.status)
    }

    @Test
    fun speechFinishedWhileWaitingKeepsTheQuestion() {
        val asked = reducer.reduce(SessionState(), SessionEvent.FinalText("удали", voiceSource()))
        val done = reducer.reduce(asked, SessionEvent.SpeechFinished)
        assertEquals(DialogPhase.AWAITING_SHORT_REPLY, done.phase)
        assertEquals("Нужно уточнение", done.status)
        assertEquals(asked.reply, done.reply)
    }
}

class ListenFailureMessageTest {
    @Test
    fun timeoutAndNoMatchShareTheShortLine() {
        assertEquals(listenFailureMessage(ListenFailure.NO_MATCH), listenFailureMessage(ListenFailure.TIMEOUT))
        assertEquals("Не расслышал.", listenFailureMessage(ListenFailure.TIMEOUT))
    }
}
