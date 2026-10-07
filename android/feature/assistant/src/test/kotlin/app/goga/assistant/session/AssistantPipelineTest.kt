package app.goga.assistant.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AssistantPipelineTest {
    private val clock = ZonedDateTime.of(2026, 10, 6, 15, 5, 0, 0, ZoneId.of("Europe/Moscow"))

    @Test
    fun commandThenAnotherReturnsToIdle() {
        val driver = driver()
        driver.show()
        driver.hear("привет")
        assertEquals(PipePhase.Speaking, driver.state.phase)
        assertEquals("Слушаю.", driver.state.reply)
        driver.speechDone()
        assertEquals(PipePhase.Idle, driver.state.phase)
        assertNull(driver.state.launch)

        driver.hear("сколько время")
        assertEquals("time", driver.state.intent)
        assertEquals("Сейчас 15:05.", driver.state.reply)
        driver.speechDone()
        assertEquals(PipePhase.Idle, driver.state.phase)
        assertEquals("Слушаю", driver.state.status)
    }

    @Test
    fun failedActionReturnsToIdleAndTheNextCommandWorks() {
        val driver = driver(actOk = false)
        driver.show()
        driver.hear("открой камеру")
        assertEquals(PipePhase.Speaking, driver.state.phase)
        assertTrue(driver.state.launch is PhoneLaunch.OpenPackage)
        driver.speechDone()
        assertEquals(PipePhase.Speaking, driver.state.phase)
        assertEquals("Не получилось открыть камеру.", driver.state.reply)
        assertNull(driver.state.launch)
        driver.speechDone()
        assertEquals(PipePhase.Idle, driver.state.phase)

        driver.hear("привет")
        assertEquals("Слушаю.", driver.state.reply)
        driver.speechDone()
        assertEquals(PipePhase.Idle, driver.state.phase)
    }

    @Test
    fun successfulHandoffClosesAfterSpeech() {
        val driver = driver()
        driver.hear("открой камеру")
        driver.speechDone()
        assertEquals(PipePhase.Close, driver.state.phase)
        assertFalse(driver.state.phase == PipePhase.Acting)
    }

    @Test
    fun reopenFiveTimesDoesNotKeepALaunch() {
        val driver = driver()
        repeat(5) {
            driver.show()
            assertEquals(PipePhase.Idle, driver.state.phase)
            assertNull(driver.state.launch)
            assertEquals("", driver.state.reply)
            driver.hear("таймер на 5 минут")
            assertEquals(PipePhase.Speaking, driver.state.phase)
            driver.hide()
            assertEquals(PipePhase.Idle, driver.state.phase)
            assertNull(driver.state.launch)
        }
        driver.show()
        driver.hear("привет")
        assertEquals("Слушаю.", driver.state.reply)
    }

    @Test
    fun missingPermissionIsSpokenAndDoesNotLeave() {
        val engine = LocalDialogEngine(phone = DenyCalls(), clock = { clock })
        val driver = PipeDriver(engine)
        driver.show()
        driver.hear("позвони маме")
        assertEquals(PipePhase.Speaking, driver.state.phase)
        assertNull(driver.state.launch)
        assertTrue(driver.state.reply.contains("Выдать разрешения"))
        assertTrue(driver.state.reply.contains("звонки"))
        driver.speechDone()
        assertEquals(PipePhase.Idle, driver.state.phase)
        driver.hear("который час")
        assertEquals("Сейчас 15:05.", driver.state.reply)
    }

    @Test
    fun permissionLaunchIsStrippedEvenIfAGatewayAttachesOne() {
        val state = reducePipe(
            PipeState(phase = PipePhase.Thinking),
            PipeIn.Thought(
                DialogTurn(
                    phase = DialogPhase.SPEAKING,
                    reply = "Нет разрешения на звонки. Откройте Гогу и нажмите «Выдать разрешения».",
                    asksConfirmation = false,
                    intent = "permission",
                    launch = PhoneLaunch.Permissions(listOf("android.permission.CALL_PHONE")),
                ),
            ),
        )
        assertEquals(PipePhase.Speaking, state.phase)
        assertNull(state.launch)
    }

    @Test
    fun timeoutsAndStaleEventsDoNotStick() {
        var state = reducePipe(PipeState(), PipeIn.ArmListen)
        assertEquals(PipePhase.Listening, state.phase)
        state = reducePipe(state, PipeIn.ListenTimeout)
        assertEquals(PipePhase.Idle, state.phase)
        assertEquals("Не расслышал.", state.status)

        state = reducePipe(PipeState(phase = PipePhase.Thinking), PipeIn.ThinkTimeout)
        assertEquals(PipePhase.Speaking, state.phase)
        assertEquals("Не успел разобрать.", state.reply)

        val leaving = PipeState(
            phase = PipePhase.Speaking,
            launch = PhoneLaunch.OpenPackage("com.android.camera", null),
            reply = "Открываю камеру.",
        )
        state = reducePipe(leaving, PipeIn.SpeechTimeout)
        assertEquals(PipePhase.Idle, state.phase)
        assertNull(state.launch)

        val idle = PipeState()
        assertEquals(idle, reducePipe(idle, PipeIn.SpeechDone))
        assertEquals(idle, reducePipe(idle, PipeIn.ActDone(true)))
    }

    @Test
    fun phaseLineNamesTheAutomatonState() {
        assertTrue(pipePhaseLine(PipeState(phase = PipePhase.Acting, status = "Открываю")).startsWith("Acting"))
    }

    private fun driver(actOk: Boolean = true) = PipeDriver(LocalDialogEngine { clock }, actOk)
}

private class PipeDriver(
    private val engine: LocalDialogEngine,
    private val actOk: Boolean = true,
) {
    var state: PipeState = PipeState()

    fun show() {
        state = reducePipe(state, PipeIn.Show)
    }

    fun hide() {
        state = reducePipe(state, PipeIn.Hide)
    }

    fun hear(text: String) {
        state = reducePipe(state, PipeIn.Heard(text, "text"))
        val turn = engine.onUserText(text)
        state = reducePipe(state, PipeIn.Thought(turn))
    }

    fun speechDone() {
        state = reducePipe(state, PipeIn.SpeechDone)
        if (state.phase == PipePhase.Acting) state = reducePipe(state, PipeIn.ActDone(actOk))
    }
}

private class DenyCalls : PhoneGateway {
    override fun handle(command: PhoneCommand): PhoneOutcome = when (command) {
        is PhoneCommand.Call -> PhoneOutcome(
            "Нет разрешения на звонки. Откройте Гогу и нажмите «Выдать разрешения».",
            "permission",
            launch = PhoneLaunch.Permissions(listOf("android.permission.CALL_PHONE")),
        )
        else -> PlannedPhoneGateway().handle(command)
    }

    override fun sendConfirmed(choice: ContactChoice, body: String): PhoneOutcome =
        PhoneOutcome("Отправил.", "sms")

    override fun pickCall(query: String, options: List<ContactChoice>): PhoneOutcome =
        PhoneOutcome("Звоню.", "call")

    override fun continueSms(choice: ContactChoice, body: String?): PhoneOutcome =
        PhoneOutcome("Что написать?", "sms")
}
