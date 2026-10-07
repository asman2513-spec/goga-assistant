package app.goga.assistant.session

import java.util.Locale

data class ContactChoice(val name: String, val number: String)

sealed interface PhoneCommand {
    data class Call(val target: String) : PhoneCommand
    data class Sms(val recipient: String, val body: String?) : PhoneCommand
    data class OpenApp(val name: String) : PhoneCommand
    data class Settings(val target: SettingsTarget) : PhoneCommand
    data class Volume(val direction: VolumeDirection) : PhoneCommand
    data class Torch(val enabled: Boolean?) : PhoneCommand
    data class Brightness(val direction: Int?) : PhoneCommand
    data object Battery : PhoneCommand
    data class Wireless(val kind: String) : PhoneCommand
    data class Remember(val text: String) : PhoneCommand
    data object Recall : PhoneCommand
    data object ForgetNotes : PhoneCommand
    data class Timer(val seconds: Int) : PhoneCommand
    data class Alarm(val hour: Int, val minute: Int) : PhoneCommand
}

enum class SettingsTarget { General, Battery, Apps, Language, Assistant, Sound, Display, Wireless }

enum class VolumeDirection { Up, Down, Mute, Unmute }

sealed interface PhoneLaunch {
    val leavesSession: Boolean

    data class Tel(val number: String, val display: String, val direct: Boolean) : PhoneLaunch {
        override val leavesSession: Boolean = true
    }

    data class SmsCompose(val number: String, val body: String) : PhoneLaunch {
        override val leavesSession: Boolean = true
    }

    data class OpenSettings(val target: SettingsTarget) : PhoneLaunch {
        override val leavesSession: Boolean = true
    }

    data class OpenPackage(val packageName: String, val className: String?) : PhoneLaunch {
        override val leavesSession: Boolean = true
    }

    data class ViewAction(val action: String, val data: String?) : PhoneLaunch {
        override val leavesSession: Boolean = true
    }

    data class Permissions(val permissions: List<String>) : PhoneLaunch {
        override val leavesSession: Boolean = true
    }

    data class Clock(val timerSeconds: Int?, val hour: Int?, val minute: Int?) : PhoneLaunch {
        override val leavesSession: Boolean = true
    }
}

sealed interface PhoneFollowUp {
    data object NeedCallTarget : PhoneFollowUp
    data class PickCall(val options: List<ContactChoice>) : PhoneFollowUp
    data object NeedSmsRecipient : PhoneFollowUp
    data class NeedSmsBody(val choice: ContactChoice) : PhoneFollowUp
    data class ConfirmSms(val choice: ContactChoice, val body: String) : PhoneFollowUp
    data class PickSms(val options: List<ContactChoice>, val body: String?) : PhoneFollowUp
    data object NeedOpenTarget : PhoneFollowUp
}

data class PhoneOutcome(
    val reply: String,
    val intent: String,
    val asksConfirmation: Boolean = false,
    val followUp: PhoneFollowUp? = null,
    val launch: PhoneLaunch? = null,
)

interface PhoneGateway {
    fun handle(command: PhoneCommand): PhoneOutcome

    fun sendConfirmed(choice: ContactChoice, body: String): PhoneOutcome

    fun pickCall(query: String, options: List<ContactChoice>): PhoneOutcome

    fun continueSms(choice: ContactChoice, body: String?): PhoneOutcome
}

/** Plans the spoken step without touching the phone. The overlay uses [AndroidPhoneGateway]. */
class PlannedPhoneGateway : PhoneGateway {
    private val notes = mutableListOf<String>()
    override fun handle(command: PhoneCommand): PhoneOutcome = when (command) {
        is PhoneCommand.Call -> call(command.target)
        is PhoneCommand.Sms -> sms(command.recipient, command.body)
        is PhoneCommand.OpenApp -> if (command.name.isBlank()) {
            PhoneOutcome("Что открыть?", "open-app", asksConfirmation = true, followUp = PhoneFollowUp.NeedOpenTarget)
        } else {
            PhoneOutcome("Открываю ${command.name}.", "open-app", launch = PhoneLaunch.OpenPackage("", command.name))
        }
        is PhoneCommand.Settings -> PhoneOutcome(settingsLine(command.target), "settings", launch = PhoneLaunch.OpenSettings(command.target))
        is PhoneCommand.Volume -> PhoneOutcome(volumeLine(command.direction), "volume")
        is PhoneCommand.Torch -> PhoneOutcome(if (command.enabled == false) "Выключаю фонарик." else "Включаю фонарик.", "torch")
        is PhoneCommand.Brightness -> PhoneOutcome(
            if (command.direction == null) "Открываю яркость." else if (command.direction > 0) "Делаю ярче." else "Делаю темнее.",
            "brightness",
            launch = if (command.direction == null) PhoneLaunch.OpenSettings(SettingsTarget.Display) else null,
        )
        PhoneCommand.Battery -> PhoneOutcome("Смотрю заряд.", "battery")
        is PhoneCommand.Wireless -> PhoneOutcome(
            if (command.kind == "bluetooth") "Открываю блютуз." else "Открываю вайфай.",
            "wireless",
            launch = PhoneLaunch.OpenSettings(SettingsTarget.Wireless),
        )
        is PhoneCommand.Remember -> {
            notes += command.text
            PhoneOutcome("Записал: «${command.text}».", "note")
        }
        PhoneCommand.Recall -> PhoneOutcome(recallLine(notes), "note")
        PhoneCommand.ForgetNotes -> {
            notes.clear()
            PhoneOutcome("Заметки стёр.", "note")
        }
        is PhoneCommand.Timer -> PhoneOutcome(timerLine(command.seconds), "timer", launch = PhoneLaunch.Clock(command.seconds, null, null))
        is PhoneCommand.Alarm -> PhoneOutcome(
            "Ставлю будильник на ${clockLabel(command.hour, command.minute)}.",
            "alarm",
            launch = PhoneLaunch.Clock(null, command.hour, command.minute),
        )
    }

    override fun sendConfirmed(choice: ContactChoice, body: String): PhoneOutcome =
        PhoneOutcome("Отправил ${choice.name}: «$body».", "sms")

    override fun continueSms(choice: ContactChoice, body: String?): PhoneOutcome = smsBody(choice, body)

    override fun pickCall(query: String, options: List<ContactChoice>): PhoneOutcome {
        val chosen = chooseContact(query, options)
        return if (chosen == null) {
            PhoneOutcome(choiceLine(options), "call", asksConfirmation = true, followUp = PhoneFollowUp.PickCall(options))
        } else {
            PhoneOutcome("Звоню ${chosen.name}.", "call", launch = PhoneLaunch.Tel(chosen.number.ifBlank { chosen.name }, chosen.name, false))
        }
    }

    private fun call(target: String): PhoneOutcome {
        if (target.isBlank()) {
            return PhoneOutcome("Кому позвонить?", "call", asksConfirmation = true, followUp = PhoneFollowUp.NeedCallTarget)
        }
        val number = target.filter { it.isDigit() || it == '+' }
        val display = target.trim()
        return if (isPhoneNumber(display)) {
            PhoneOutcome("Звоню $display.", "call", launch = PhoneLaunch.Tel(number, display, false))
        } else {
            PhoneOutcome("Звоню $display.", "call", launch = PhoneLaunch.Tel("", display, false))
        }
    }

    private fun sms(recipient: String, body: String?): PhoneOutcome {
        if (recipient.isBlank()) {
            return PhoneOutcome(
                "Кому написать?",
                "sms",
                asksConfirmation = true,
                followUp = PhoneFollowUp.NeedSmsRecipient,
            )
        }
        return smsBody(ContactChoice(recipient, ""), body)
    }

    private fun smsBody(choice: ContactChoice, body: String?): PhoneOutcome {
        if (body.isNullOrBlank()) {
            return PhoneOutcome(
                "Что написать ${choice.name}?",
                "sms",
                asksConfirmation = true,
                followUp = PhoneFollowUp.NeedSmsBody(choice),
            )
        }
        return PhoneOutcome(
            "Отправить ${choice.name}: «$body»?",
            "sms",
            asksConfirmation = true,
            followUp = PhoneFollowUp.ConfirmSms(choice, body),
        )
    }
}

fun parsePhoneCommand(raw: String): PhoneCommand? {
    val text = phraseNormalize(raw)
    val key = phraseStripLeadIn(text)
    if (key.isEmpty()) return null
    parseNotes(key)?.let { return it }
    parseClock(key)?.let { return it }
    parseDevice(key)?.let { return it }
    parseSettings(key)?.let { return it }
    parseCall(key)?.let { return it }
    parseSms(key)?.let { return it }
    parseOpen(key)?.let { return it }
    return null
}

fun phraseNormalize(raw: String): String = raw
    .lowercase(Locale.forLanguageTag("ru"))
    .replace('ё', 'е')
    .replace('\u00A0', ' ')
    .replace(Regex("[\\u200B\\uFEFF]"), "")
    .replace(Regex("[^\\p{L}\\p{N}+\\s]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

fun isPhoneNumber(text: String): Boolean {
    val digits = text.count { it.isDigit() }
    val extra = text.any { !it.isDigit() && !it.isWhitespace() && it != '+' }
    return digits >= 3 && !extra
}

fun stemName(query: String): String {
    val endings = listOf("ами", "ями", "ого", "ему", "ому", "ой", "ей", "ах", "ях", "ом", "ем", "у", "ю", "е", "и", "ы", "а", "я")
    val word = query.trim().substringBefore(" ")
    for (ending in endings) {
        if (word.length - ending.length >= 3 && word.endsWith(ending)) return word.dropLast(ending.length)
    }
    return word
}

fun chooseContact(query: String, options: List<ContactChoice>): ContactChoice? {
    val key = phraseNormalize(query)
    if (key in setOf("первый", "1", "первого", "первому")) return options.firstOrNull()
    if (key in setOf("второй", "2", "второго", "второму")) return options.getOrNull(1)
    if (key in setOf("третий", "3", "третьего")) return options.getOrNull(2)
    val ranked = rankContacts(key, options)
    if (ranked.size == 1) return ranked.first()
    val exact = options.filter { phraseNormalize(it.name) == key || phraseNormalize(it.name).startsWith(key) }
    return exact.singleOrNull()
}

fun rankContacts(query: String, contacts: List<ContactChoice>): List<ContactChoice> {
    val key = phraseNormalize(query)
    val stem = stemName(key)
    return contacts
        .map { it to contactScore(key, stem, phraseNormalize(it.name)) }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .map { it.first }
        .distinctBy { it.number.ifBlank { it.name } }
        .take(3)
}

fun contactScore(query: String, stem: String, name: String): Int = when {
    name == query -> 100
    name.startsWith(query) -> 80
    query.length >= 3 && name.contains(query) -> 60
    stem.length >= 3 && (name.contains(stem) || name.startsWith(stem)) -> 40
    else -> 0
}

fun parseDurationSeconds(text: String): Int? {
    val key = phraseNormalize(text)
    if (key.contains("полчаса")) return 30 * 60
    val match = Regex("(\\d+)\\s*(секунд\\p{L}*|минут\\p{L}*|час\\p{L}*)").find(key)
    if (match != null) {
        val value = match.groupValues[1].toIntOrNull() ?: return null
        val unit = match.groupValues[2]
        return when {
            unit.startsWith("секунд") -> value
            unit.startsWith("час") -> value * 3600
            else -> value * 60
        }
    }
    val words = key.split(" ")
    val number = words.firstNotNullOfOrNull { SPOKEN_NUMBERS[it] } ?: return null
    val unit = words.firstOrNull { it.startsWith("секунд") || it.startsWith("минут") || it.startsWith("час") } ?: return null
    return when {
        unit.startsWith("секунд") -> number
        unit.startsWith("час") -> number * 3600
        else -> number * 60
    }
}

fun parseAlarmTime(text: String): Pair<Int, Int>? {
    val key = phraseNormalize(text)
    val digits = Regex("(\\d{1,2})(?:\\s+(\\d{2}))?").find(key) ?: return null
    var hour = digits.groupValues[1].toIntOrNull() ?: return null
    val minute = digits.groupValues[2].toIntOrNull() ?: 0
    if (hour !in 0..23 || minute !in 0..59) return null
    if (hour in 1..11 && (key.contains("вечер") || key.contains("дня") || key.contains("днем"))) hour += 12
    if (hour == 12 && key.contains("ноч")) hour = 0
    return hour to minute
}

fun aliasPackages(query: String): List<String> {
    val key = phraseNormalize(query)
    val stem = stemName(key)
    return when {
        key.contains("камер") || stem.startsWith("камер") -> CAMERA_PACKAGES
        key.contains("галере") || key.contains("фото") || key.contains("снимк") -> GALLERY_PACKAGES
        key.contains("хром") || key == "браузер" || key.contains("браузер") -> BROWSER_PACKAGES
        key.contains("телег") -> listOf("org.telegram.messenger", "org.telegram.messenger.web")
        key.contains("ватсап") || key.contains("вотсап") || key.contains("whatsapp") -> listOf("com.whatsapp")
        key == "часы" || key.contains("будиль") -> CLOCK_PACKAGES
        key.contains("календар") -> CALENDAR_PACKAGES
        key.contains("магазин") || key.contains("плей") || key.contains("play") -> STORE_PACKAGES
        key.contains("контакт") -> emptyList()
        key.contains("сообщен") || key == "смс" -> emptyList()
        key.contains("телефон") || key.contains("звонил") -> emptyList()
        else -> emptyList()
    }
}

fun matchAppLabel(query: String, labels: List<String>): String? {
    val key = phraseNormalize(query)
    val stem = stemName(key)
    val ranked = labels.map { it to contactScore(key, stem, phraseNormalize(it)) }.filter { it.second > 0 }.sortedByDescending { it.second }
    return ranked.firstOrNull()?.first
}

internal fun choiceLine(options: List<ContactChoice>): String {
    val names = options.map { it.name }
    val listed = when (names.size) {
        0 -> return "Не нашёл контакт."
        1 -> names[0]
        2 -> "${names[0]} или ${names[1]}"
        else -> names.dropLast(1).joinToString(", ") + " или " + names.last()
    }
    return "Нашёл несколько: $listed. Кому?"
}

private fun parseNotes(key: String): PhoneCommand? {
    if (key == "что я просил запомнить" || key == "мои заметки" || key == "прочитай заметки" || key == "что ты запомнил") {
        return PhoneCommand.Recall
    }
    if (
        key == "забудь заметки" ||
        key == "удали заметки" ||
        key == "сотри заметки" ||
        key == "удали все заметки" ||
        key == "очисти заметки"
    ) {
        return PhoneCommand.ForgetNotes
    }
    if (key == "запомни" || key.startsWith("запомни ")) {
        val text = key.removePrefix("запомни").trim()
        return if (text.isEmpty()) null else PhoneCommand.Remember(text)
    }
    return null
}

private fun parseClock(key: String): PhoneCommand? {
    if (key.contains("таймер") || key.contains("поставь таймер") || key.startsWith("засеки")) {
        val seconds = parseDurationSeconds(key) ?: return PhoneCommand.Timer(60)
        return PhoneCommand.Timer(seconds)
    }
    if (key.contains("будильник") || key.startsWith("разбуди")) {
        val time = parseAlarmTime(key) ?: return null
        return PhoneCommand.Alarm(time.first, time.second)
    }
    return null
}

private fun parseDevice(key: String): PhoneCommand? {
    when {
        key.contains("без звука") || key.contains("выключи звук") || key.contains("режим без звука") || key == "тише полностью" ->
            return PhoneCommand.Volume(VolumeDirection.Mute)
        key.contains("включи звук") || key.contains("со звуком") -> return PhoneCommand.Volume(VolumeDirection.Unmute)
        key.contains("громче") || key.contains("увеличь громкость") || key.contains("прибавь звук") || key.contains("прибавь громкость") ->
            return PhoneCommand.Volume(VolumeDirection.Up)
        key.contains("тише") || key.contains("уменьши громкость") || key.contains("убавь звук") || key.contains("убавь громкость") ->
            return PhoneCommand.Volume(VolumeDirection.Down)
    }
    if (key.contains("фонар") || key.contains("вспышк")) {
        val off = key.contains("выключ") || key.contains("погаси")
        val on = key.contains("включ") || key.contains("зажги")
        return PhoneCommand.Torch(if (off) false else if (on) true else null)
    }
    if (key.contains("ярк")) {
        return when {
            key.contains("темнее") || key.contains("уменьш") || key.contains("понизь") -> PhoneCommand.Brightness(-1)
            key.contains("ярче") || key.contains("увелич") || key.contains("прибавь") -> PhoneCommand.Brightness(1)
            else -> PhoneCommand.Brightness(null)
        }
    }
    if (key == "темнее" || key.contains("сделай темнее")) return PhoneCommand.Brightness(-1)
    if (key == "ярче" || key.contains("сделай ярче")) return PhoneCommand.Brightness(1)
    if (!key.contains("настройк") && (key.contains("заряд") || key.contains("батаре") || key.contains("сколько процентов"))) {
        return PhoneCommand.Battery
    }
    if (key.contains("вайфай") || key.contains("wifi") || key.contains("wi fi") || key.contains("вай фай")) {
        return PhoneCommand.Wireless("wifi")
    }
    if (key.contains("блютуз") || key.contains("bluetooth") || key.contains("синезуб")) return PhoneCommand.Wireless("bluetooth")
    return null
}

private fun parseSettings(key: String): PhoneCommand? {
    val opened = key.removePrefix("открой ").removePrefix("запусти ").removePrefix("открыть ").trim()
    val source = if (opened.contains("настройк")) opened else key
    if (!source.contains("настройк") && !key.contains("настройк")) return null
    val target = when {
        source.contains("батар") -> SettingsTarget.Battery
        source.contains("приложен") -> SettingsTarget.Apps
        source.contains("язык") -> SettingsTarget.Language
        source.contains("помощник") || source.contains("ассистент") -> SettingsTarget.Assistant
        source.contains("звук") -> SettingsTarget.Sound
        source.contains("диспле") || source.contains("экран") || source.contains("ярк") -> SettingsTarget.Display
        source.contains("вайфай") || source.contains("bluetooth") || source.contains("блютуз") -> SettingsTarget.Wireless
        else -> SettingsTarget.General
    }
    return PhoneCommand.Settings(target)
}

private fun parseCall(key: String): PhoneCommand? {
    val words = key.split(" ")
    val verb = words.firstOrNull { word ->
        word.startsWith("позвон") || word.startsWith("перезвон") || word.startsWith("дозвон") || word in CALL_VERBS
    } ?: return null
    val rest = words.dropWhile { it != verb }.drop(1).filterNot { it in FILLERS }.joinToString(" ")
    return PhoneCommand.Call(rest)
}

private fun parseSms(key: String): PhoneCommand? {
    if (key.startsWith("напиши заметк")) return null
    val words = key.split(" ").filterNot { it in FILLERS }
    val marker = words.indexOfFirst { it == "смс" || it == "sms" || it == "эсэмэс" || it.startsWith("сообщен") }
    val send = words.indexOfFirst { it.startsWith("отправ") || it.startsWith("пошл") || it == "напиши" || it == "написать" || it == "напишите" }
    if (marker < 0 && send < 0) return null
    if (marker < 0 && send >= 0 && words.getOrNull(send)?.startsWith("напиш") == true && words.size < 2) return null
    val tail = when {
        marker >= 0 -> words.drop(marker + 1)
        else -> words.drop(send + 1)
    }
    if (tail.isEmpty()) return PhoneCommand.Sms("", null)
    val recipient = tail.first()
    val body = tail.drop(1).joinToString(" ").ifBlank { null }
    return PhoneCommand.Sms(recipient, body)
}

private fun parseOpen(key: String): PhoneCommand? {
    val words = key.split(" ")
    val verb = words.firstOrNull { it.startsWith("откро") || it.startsWith("запуст") } ?: return null
    val name = words.dropWhile { it != verb }.drop(1).filterNot { it in FILLERS }.joinToString(" ")
    if (name.isBlank()) return PhoneCommand.OpenApp("")
    return PhoneCommand.OpenApp(name)
}

private fun phraseStripLeadIn(text: String): String {
    var rest = text
    while (true) {
        val prefix = LEAD_IN.firstOrNull { rest == it || rest.startsWith("$it ") } ?: break
        rest = rest.removePrefix(prefix).trim()
    }
    return rest
}

internal fun recallLine(notes: List<String>): String = when {
    notes.isEmpty() -> "Пока ничего не запомнил."
    notes.size == 1 -> "Вы просили запомнить: «${notes.last()}»."
    else -> "Вы просили запомнить: " + notes.takeLast(3).joinToString("; ") { "«$it»" } + "."
}

private fun settingsLine(target: SettingsTarget): String = when (target) {
    SettingsTarget.Battery -> "Открываю настройки батареи."
    SettingsTarget.Apps -> "Открываю настройки приложений."
    SettingsTarget.Language -> "Открываю настройки языка."
    SettingsTarget.Assistant -> "Открываю настройки помощника."
    SettingsTarget.Sound -> "Открываю настройки звука."
    SettingsTarget.Display -> "Открываю настройки экрана."
    SettingsTarget.Wireless -> "Открываю настройки сети."
    SettingsTarget.General -> "Открываю настройки."
}

private fun volumeLine(direction: VolumeDirection): String = when (direction) {
    VolumeDirection.Up -> "Делаю громче."
    VolumeDirection.Down -> "Делаю тише."
    VolumeDirection.Mute -> "Выключаю звук."
    VolumeDirection.Unmute -> "Включаю звук."
}

internal fun timerLine(seconds: Int): String = when {
    seconds % 3600 == 0 -> "Ставлю таймер на ${seconds / 3600} ч."
    seconds % 60 == 0 -> "Ставлю таймер на ${seconds / 60} мин."
    else -> "Ставлю таймер на $seconds сек."
}

internal fun clockLabel(hour: Int, minute: Int): String =
    "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

private val LEAD_IN = setOf("гога", "пожалуйста", "слушай", "скажи")
private val FILLERS = setOf("мне", "пожалуйста", "номер", "на", "для")
private val CALL_VERBS = setOf("набери", "набрать", "наберите", "наберу", "наберем", "звонок", "звонки")
private val SPOKEN_NUMBERS = mapOf(
    "один" to 1, "одну" to 1, "два" to 2, "две" to 2, "три" to 3, "четыре" to 4, "пять" to 5,
    "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10, "пятнадцать" to 15,
    "двадцать" to 20, "тридцать" to 30,
)
private val CAMERA_PACKAGES = listOf(
    "com.hihonor.camera",
    "com.huawei.camera",
    "com.android.camera",
    "com.android.camera2",
)
private val GALLERY_PACKAGES = listOf(
    "com.hihonor.photos",
    "com.huawei.photos",
    "com.android.gallery3d",
    "com.google.android.apps.photos",
)
private val BROWSER_PACKAGES = listOf("com.android.chrome", "com.huawei.browser", "com.hihonor.browser")
private val CLOCK_PACKAGES = listOf("com.hihonor.deskclock", "com.huawei.deskclock", "com.google.android.deskclock", "com.android.deskclock")
private val CALENDAR_PACKAGES = listOf("com.google.android.calendar", "com.huawei.calendar", "com.hihonor.calendar", "com.android.calendar")
private val STORE_PACKAGES = listOf("com.android.vending", "com.huawei.appmarket", "com.hihonor.appmarket")
