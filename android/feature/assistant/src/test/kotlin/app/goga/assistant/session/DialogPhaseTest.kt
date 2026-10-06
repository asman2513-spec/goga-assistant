package app.goga.assistant.session

import org.junit.Assert.assertTrue
import org.junit.Test

class DialogPhaseTest {
    @Test
    fun sessionStartsByListening() {
        assertTrue(DialogPhase.entries.contains(DialogPhase.LISTENING))
        assertTrue(DialogPhase.entries.contains(DialogPhase.AWAITING_SHORT_REPLY))
    }
}
