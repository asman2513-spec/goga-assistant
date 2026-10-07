package app.goga.assistant.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineReliabilityTest {
    @Test
    fun stablePartialAndEndOfSpeechAndTimeoutCountAsHeard() {
        assertTrue(ListenTiming.shouldPromote("Привет", stableMs = 1_200, endOfSpeech = false, timedOut = false))
        assertFalse(ListenTiming.shouldPromote("Привет", stableMs = 1_000, endOfSpeech = false, timedOut = false))
        assertTrue(ListenTiming.shouldPromote("Открой камеру", stableMs = 0, endOfSpeech = true, timedOut = false))
        assertTrue(ListenTiming.shouldPromote("позвони дорогуш", stableMs = 0, endOfSpeech = false, timedOut = true))
        assertFalse(ListenTiming.shouldPromote("  ", stableMs = 5_000, endOfSpeech = true, timedOut = true))
    }

    @Test
    fun listenTimeoutUsesThePartialInsteadOfGivingUp() {
        var state = reducePipe(PipeState(), PipeIn.ArmListen)
        assertEquals("Готовлюсь", state.status)
        state = reducePipe(state, PipeIn.EarReady)
        assertEquals("Слушаю", state.status)
        state = reducePipe(state, PipeIn.Partial("Привет"))
        state = reducePipe(state, PipeIn.ListenTimeout)
        assertEquals(PipePhase.Thinking, state.phase)
        assertEquals("Привет", state.lastUser)
        assertEquals("voice", state.source)
    }

    @Test
    fun emptyListenTimeoutStillReturnsToIdle() {
        val state = reducePipe(reducePipe(PipeState(), PipeIn.ArmListen), PipeIn.ListenTimeout)
        assertEquals(PipePhase.Idle, state.phase)
        assertEquals("Не расслышал.", state.status)
    }

    @Test
    fun speechWatchdogGrowsWithTheLine() {
        val line = "Сейчас 08:13."
        assertEquals(1_500L + line.length * 90L, speechWatchdogMs(line))
        assertTrue(speechWatchdogMs(line) < 12_000L)
        assertTrue(speechWatchdogMs("Не получилось позвонить.") > speechWatchdogMs("Да."))
    }

    @Test
    fun foreignActivitiesUseTheTrampoline() {
        assertEquals(LaunchPath.TRAMPOLINE, routeLaunch(listOf(null), "app.goga.assistant"))
        assertEquals(LaunchPath.TRAMPOLINE, routeLaunch(listOf("com.hihonor.camera"), "app.goga.assistant"))
        assertEquals(LaunchPath.TRAMPOLINE, routeLaunch(listOf("com.android.deskclock"), "app.goga.assistant"))
        assertEquals(LaunchPath.ASSISTANT, routeLaunch(listOf("app.goga.assistant"), "app.goga.assistant"))
    }

    @Test
    fun inventedCameraClassIsNotUsed() {
        assertNull(usableActivityClass("com.hihonor.camera", "com.hihonor.camera"))
        assertNull(usableActivityClass("com.hihonor.camera", "  "))
        assertEquals(
            "com.hihonor.camera.CameraActivity",
            usableActivityClass("com.hihonor.camera", "com.hihonor.camera.CameraActivity"),
        )
    }

    @Test
    fun callAndCameraFailuresAreSpecific() {
        val call = reducePipe(
            PipeState(
                phase = PipePhase.Acting,
                intent = "call",
                launch = PhoneLaunch.Tel("+7926", "Дорогуша", true),
                reply = "Звоню Дорогуша.",
            ),
            PipeIn.ActDone(false),
        )
        assertEquals(PipePhase.Speaking, call.phase)
        assertEquals("Не получилось позвонить.", call.reply)
        assertNull(call.launch)

        val camera = reducePipe(
            PipeState(
                phase = PipePhase.Acting,
                intent = "open-app",
                launch = PhoneLaunch.OpenPackage("com.hihonor.camera", null),
                reply = "Открываю камеру.",
            ),
            PipeIn.ActTimeout,
        )
        assertEquals("Не получилось открыть камеру.", camera.reply)
    }

    @Test
    fun truncatedCallAsksBeforeDialing() {
        val engine = LocalDialogEngine(phone = object : PhoneGateway by PlannedPhoneGateway() {
            override fun suggestCall(phrase: String): PhoneOutcome? {
                val target = fuzzyCallTarget(phrase) ?: return null
                if (!target.contains("дорогуш")) return null
                val choice = ContactChoice("Дорогуша", "+79269083318")
                return PhoneOutcome(
                    callConfirmLine(choice.name),
                    "call",
                    asksConfirmation = true,
                    followUp = PhoneFollowUp.ConfirmCall(choice),
                )
            }
        })
        assertEquals("позвони дорогуш", repairCallPhrase("они дорогуш"))
        assertEquals("позвони дорогуш", repairCallPhrase("вони дорогуш"))
        assertNull(repairCallPhrase("позвони дорогуша"))
        assertEquals("Дорогуше", dativeName("Дорогуша"))

        val asked = engine.onUserText("они дорогуш")
        assertEquals("Позвонить Дорогуше?", asked.reply)
        assertTrue(asked.asksConfirmation)
        assertNull(asked.launch)

        val declined = LocalDialogEngine(phone = object : PhoneGateway by PlannedPhoneGateway() {
            override fun suggestCall(phrase: String): PhoneOutcome? {
                val choice = ContactChoice("Дорогуша", "+1")
                return PhoneOutcome(callConfirmLine(choice.name), "call", true, PhoneFollowUp.ConfirmCall(choice))
            }
        })
        declined.onUserText("они дорогуш")
        assertEquals("Хорошо.", declined.onUserText("нет").reply)

        val placed = engine.onUserText("да")
        assertEquals("Звоню Дорогуша.", placed.reply)
        assertTrue(placed.launch is PhoneLaunch.Tel)
    }
}
