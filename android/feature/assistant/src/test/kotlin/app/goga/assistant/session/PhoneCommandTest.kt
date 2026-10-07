package app.goga.assistant.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCommandTest {
    @Test
    fun clockQuestionIsNotAPhoneCommand() {
        assertNull(parsePhoneCommand("сколько время"))
        assertNull(parsePhoneCommand("который час"))
    }

    @Test
    fun callSmsAndOpenPhrases() {
        assertEquals(PhoneCommand.Call(""), parsePhoneCommand("позвони"))
        assertEquals(PhoneCommand.Call("маме"), parsePhoneCommand("Позвони маме!"))
        assertEquals(PhoneCommand.Call("+7 999 000 00 00"), parsePhoneCommand("набери +7 999 000 00 00"))
        assertEquals(PhoneCommand.Sms("маме", "привет"), parsePhoneCommand("отправь смс маме привет"))
        assertEquals(PhoneCommand.Sms("ивану", "я опаздываю"), parsePhoneCommand("напиши ивану я опаздываю"))
        assertEquals(PhoneCommand.OpenApp("камеру"), parsePhoneCommand("открой камеру"))
        assertEquals(PhoneCommand.Settings(SettingsTarget.Battery), parsePhoneCommand("открой настройки батареи"))
    }

    @Test
    fun volumeTorchNotesAndClock() {
        assertEquals(PhoneCommand.Volume(VolumeDirection.Up), parsePhoneCommand("громче"))
        assertEquals(PhoneCommand.Volume(VolumeDirection.Mute), parsePhoneCommand("без звука"))
        assertEquals(PhoneCommand.Torch(null), parsePhoneCommand("фонарик"))
        assertEquals(PhoneCommand.Torch(false), parsePhoneCommand("выключи фонарик"))
        assertEquals(PhoneCommand.Remember("купить молоко"), parsePhoneCommand("запомни купить молоко"))
        assertEquals(PhoneCommand.Recall, parsePhoneCommand("что я просил запомнить"))
        assertEquals(PhoneCommand.ForgetNotes, parsePhoneCommand("удали заметки"))
        assertEquals(PhoneCommand.Timer(300), parsePhoneCommand("таймер на 5 минут"))
        assertEquals(PhoneCommand.Timer(300), parsePhoneCommand("таймер на пять минут"))
        assertEquals(PhoneCommand.Alarm(19, 0), parsePhoneCommand("будильник на 7 вечера"))
        assertEquals(PhoneCommand.Wireless("wifi"), parsePhoneCommand("включи вайфай"))
        assertEquals(PhoneCommand.Wireless("bluetooth"), parsePhoneCommand("блютуз"))
    }

    @Test
    fun stemMatchesMomAndClockAliasDoesNotEatNow() {
        val ranked = rankContacts(
            "маме",
            listOf(ContactChoice("Иван", "1"), ContactChoice("Мама", "2")),
        )
        assertEquals("Мама", ranked.first().name)
        assertTrue(aliasPackages("часы").isNotEmpty())
        assertTrue(aliasPackages("сейчас").isEmpty())
        assertEquals(1800, parseDurationSeconds("полчаса"))
    }
}
