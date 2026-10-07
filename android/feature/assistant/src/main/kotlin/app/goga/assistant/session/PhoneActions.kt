package app.goga.assistant.session

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Runs a phone command on the device. Activity launches go out with
 * [Intent.FLAG_ACTIVITY_NEW_TASK] after speech; the recognizer is released
 * separately and is never cancelled on the main thread.
 */
class AndroidPhoneGateway(
    context: Context,
) : PhoneGateway {
    private val app = context.applicationContext
    private val notes = PrefNotes(app)
    private var torchOn = false
    private var torchId: String? = null

    override fun handle(command: PhoneCommand): PhoneOutcome = when (command) {
        is PhoneCommand.Call -> call(command.target)
        is PhoneCommand.Sms -> sms(command.recipient, command.body)
        is PhoneCommand.OpenApp -> openApp(command.name)
        is PhoneCommand.Settings -> settings(command.target)
        is PhoneCommand.Volume -> volume(command.direction)
        is PhoneCommand.Torch -> torch(command.enabled)
        is PhoneCommand.Brightness -> brightness(command.direction)
        PhoneCommand.Battery -> battery()
        is PhoneCommand.Wireless -> wireless(command.kind)
        is PhoneCommand.Remember -> {
            notes.add(command.text)
            PhoneOutcome("Записал: «${command.text}».", "note")
        }
        PhoneCommand.Recall -> PhoneOutcome(recallLine(notes.all()), "note")
        PhoneCommand.ForgetNotes -> {
            notes.clear()
            PhoneOutcome("Заметки стёр.", "note")
        }
        is PhoneCommand.Timer -> PhoneOutcome(
            timerLine(command.seconds),
            "timer",
            launch = PhoneLaunch.Clock(command.seconds, null, null),
        )
        is PhoneCommand.Alarm -> PhoneOutcome(
            "Ставлю будильник на ${clockLabel(command.hour, command.minute)}.",
            "alarm",
            launch = PhoneLaunch.Clock(null, command.hour, command.minute),
        )
    }

    override fun sendConfirmed(choice: ContactChoice, body: String): PhoneOutcome {
        if (!granted(Manifest.permission.SEND_SMS)) {
            return PhoneOutcome(
                "Открываю сообщение для ${choice.name}.",
                "sms",
                launch = PhoneLaunch.SmsCompose(choice.number, body),
            )
        }
        return if (sendText(choice.number, body)) {
            PhoneOutcome("Отправил ${choice.name}.", "sms")
        } else {
            PhoneOutcome(
                "Не вышло отправить. Открываю сообщение.",
                "sms",
                launch = PhoneLaunch.SmsCompose(choice.number, body),
            )
        }
    }

    override fun continueSms(choice: ContactChoice, body: String?): PhoneOutcome {
        if (choice.number.isBlank()) return PhoneOutcome("У ${choice.name} нет номера.", "sms")
        if (body.isNullOrBlank()) {
            return PhoneOutcome(
                "Что написать ${choice.name}?",
                "sms",
                asksConfirmation = true,
                followUp = PhoneFollowUp.NeedSmsBody(choice),
            )
        }
        if (!granted(Manifest.permission.SEND_SMS)) {
            return PhoneOutcome(
                "Открываю сообщение для ${choice.name}.",
                "sms",
                launch = PhoneLaunch.SmsCompose(choice.number, body),
            )
        }
        return PhoneOutcome(
            "Отправить ${choice.name}: «$body»?",
            "sms",
            asksConfirmation = true,
            followUp = PhoneFollowUp.ConfirmSms(choice, body),
        )
    }

    override fun pickCall(query: String, options: List<ContactChoice>): PhoneOutcome {
        val chosen = chooseContact(query, options)
            ?: return PhoneOutcome(choiceLine(options), "call", asksConfirmation = true, followUp = PhoneFollowUp.PickCall(options))
        return dial(chosen, directPreferred = true)
    }

    fun performLaunch(launch: PhoneLaunch): Boolean {
        Log.i(TAG, "launch $launch")
        if (start(intentFor(launch))) return true
        return start(fallbackIntent(launch))
    }

    private fun call(target: String): PhoneOutcome {
        if (target.isBlank()) {
            return PhoneOutcome("Кому позвонить?", "call", asksConfirmation = true, followUp = PhoneFollowUp.NeedCallTarget)
        }
        if (isPhoneNumber(target)) {
            val number = target.filter { it.isDigit() || it == '+' }
            return dial(ContactChoice(number, number), directPreferred = true)
        }
        if (!granted(Manifest.permission.READ_CONTACTS)) {
            return need(listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE), "Нужен доступ к контактам, чтобы найти «$target».")
        }
        val found = lookup(target)
        return when {
            found.isEmpty() -> PhoneOutcome("Не нашёл контакт «$target».", "call")
            found.size == 1 -> dial(found.first(), directPreferred = true)
            else -> PhoneOutcome(choiceLine(found), "call", asksConfirmation = true, followUp = PhoneFollowUp.PickCall(found))
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
        if (isPhoneNumber(recipient)) {
            val number = recipient.filter { it.isDigit() || it == '+' }
            return continueSms(ContactChoice(number, number), body)
        }
        if (!granted(Manifest.permission.READ_CONTACTS)) {
            return need(listOf(Manifest.permission.READ_CONTACTS), "Нужен доступ к контактам, чтобы написать «$recipient».")
        }
        val found = lookup(recipient)
        return when {
            found.isEmpty() -> PhoneOutcome("Не нашёл контакт «$recipient».", "sms")
            found.size > 1 -> PhoneOutcome(
                choiceLine(found),
                "sms",
                asksConfirmation = true,
                followUp = PhoneFollowUp.PickSms(found, body),
            )
            else -> continueSms(found.first(), body)
        }
    }

    private fun dial(choice: ContactChoice, directPreferred: Boolean): PhoneOutcome {
        val number = choice.number.filter { it.isDigit() || it == '+' }
        if (number.isBlank()) return PhoneOutcome("У ${choice.name} нет номера.", "call")
        val direct = directPreferred && granted(Manifest.permission.CALL_PHONE)
        val line = if (direct) "Звоню ${choice.name}." else "Открываю набор ${choice.name}."
        return PhoneOutcome(line, "call", launch = PhoneLaunch.Tel(number, choice.name, direct))
    }

    private fun openApp(name: String): PhoneOutcome {
        if (name.isBlank()) {
            return PhoneOutcome("Что открыть?", "open-app", asksConfirmation = true, followUp = PhoneFollowUp.NeedOpenTarget)
        }
        val key = phraseNormalize(name)
        when {
            key.contains("контакт") -> return PhoneOutcome(
                "Открываю контакты.",
                "open-app",
                launch = PhoneLaunch.ViewAction(Intent.ACTION_VIEW, "content://contacts/people"),
            )
            key.contains("сообщен") || key == "смс" -> return PhoneOutcome(
                "Открываю сообщения.",
                "open-app",
                launch = PhoneLaunch.ViewAction(Intent.ACTION_VIEW, "sms:"),
            )
            key == "телефон" || key.contains("звонил") || key == "набор" -> return PhoneOutcome(
                "Открываю телефон.",
                "open-app",
                launch = PhoneLaunch.ViewAction(Intent.ACTION_DIAL, null),
            )
            key.contains("настройк") -> return settings(SettingsTarget.General)
        }
        val packages = aliasPackages(name)
        for (pkg in packages) {
            val launch = app.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            val component = launch.component
            return PhoneOutcome(
                "Открываю $name.",
                "open-app",
                launch = PhoneLaunch.OpenPackage(pkg, component?.className),
            )
        }
        val labels = launcherLabels()
        val label = matchAppLabel(name, labels.map { it.first })
        val match = labels.firstOrNull { it.first == label }
        if (match != null) {
            return PhoneOutcome(
                "Открываю ${match.first}.",
                "open-app",
                launch = PhoneLaunch.OpenPackage(match.second, match.third),
            )
        }
        if (key.contains("камер")) {
            return PhoneOutcome(
                "Открываю камеру.",
                "open-app",
                launch = PhoneLaunch.ViewAction(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, null),
            )
        }
        if (key.contains("браузер") || key.contains("хром")) {
            return PhoneOutcome(
                "Открываю браузер.",
                "open-app",
                launch = PhoneLaunch.ViewAction(Intent.ACTION_VIEW, "https://"),
            )
        }
        return PhoneOutcome("Не нашёл приложение «$name».", "open-app")
    }

    private fun settings(target: SettingsTarget): PhoneOutcome {
        val line = when (target) {
            SettingsTarget.Battery -> "Открываю настройки батареи."
            SettingsTarget.Apps -> "Открываю настройки приложений."
            SettingsTarget.Language -> "Открываю настройки языка."
            SettingsTarget.Assistant -> "Открываю настройки помощника."
            SettingsTarget.Sound -> "Открываю настройки звука."
            SettingsTarget.Display -> "Открываю настройки экрана."
            SettingsTarget.Wireless -> "Открываю настройки сети."
            SettingsTarget.General -> "Открываю настройки."
        }
        return PhoneOutcome(line, "settings", launch = PhoneLaunch.OpenSettings(target))
    }

    private fun volume(direction: VolumeDirection): PhoneOutcome {
        val audio = app.getSystemService(AudioManager::class.java)
        return try {
            when (direction) {
                VolumeDirection.Up -> {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_RAISE, 0)
                    PhoneOutcome("Делаю громче.", "volume")
                }
                VolumeDirection.Down -> {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_LOWER, 0)
                    PhoneOutcome("Делаю тише.", "volume")
                }
                VolumeDirection.Mute -> {
                    audio.ringerMode = AudioManager.RINGER_MODE_SILENT
                    PhoneOutcome("Выключаю звук.", "volume")
                }
                VolumeDirection.Unmute -> {
                    audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    PhoneOutcome("Включаю звук.", "volume")
                }
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "volume blocked", error)
            PhoneOutcome("Открываю настройки звука.", "volume", launch = PhoneLaunch.OpenSettings(SettingsTarget.Sound))
        }
    }

    private fun torch(enabled: Boolean?): PhoneOutcome {
        if (!granted(Manifest.permission.CAMERA)) {
            return need(listOf(Manifest.permission.CAMERA), "Нужен доступ к камере, чтобы включить фонарик.")
        }
        return try {
            val manager = app.getSystemService(CameraManager::class.java)
            val id = torchId ?: manager.cameraIdList.firstOrNull { cameraId ->
                val chars = manager.getCameraCharacteristics(cameraId)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }.also { torchId = it }
            if (id == null) return PhoneOutcome("На телефоне нет фонарика.", "torch")
            val next = enabled ?: !torchOn
            manager.setTorchMode(id, next)
            torchOn = next
            PhoneOutcome(if (next) "Включаю фонарик." else "Выключаю фонарик.", "torch")
        } catch (error: RuntimeException) {
            Log.w(TAG, "torch failed", error)
            PhoneOutcome("Фонарик не включился.", "torch")
        }
    }

    private fun brightness(direction: Int?): PhoneOutcome {
        if (direction == null || !Settings.System.canWrite(app)) {
            return PhoneOutcome("Открываю яркость.", "brightness", launch = PhoneLaunch.OpenSettings(SettingsTarget.Display))
        }
        return try {
            val current = Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            val next = (current + direction * 40).coerceIn(10, 255)
            Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, next)
            PhoneOutcome(if (direction > 0) "Делаю ярче." else "Делаю темнее.", "brightness")
        } catch (error: SecurityException) {
            Log.w(TAG, "brightness blocked", error)
            PhoneOutcome("Открываю яркость.", "brightness", launch = PhoneLaunch.OpenSettings(SettingsTarget.Display))
        }
    }

    private fun battery(): PhoneOutcome {
        val sticky = app.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        if (level < 0 || scale <= 0) return PhoneOutcome("Не вижу заряд.", "battery")
        val pct = level * 100 / scale
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val tail = if (charging) " Идёт зарядка." else ""
        return PhoneOutcome("Заряд $pct процентов.$tail", "battery")
    }

    private fun wireless(kind: String): PhoneOutcome {
        val bluetooth = kind == "bluetooth"
        val line = if (bluetooth) "Открываю блютуз." else "Открываю вайфай."
        val panel = if (bluetooth) PANEL_BLUETOOTH else Settings.Panel.ACTION_WIFI
        return PhoneOutcome(line, "wireless", launch = PhoneLaunch.ViewAction(panel, null))
    }

    private fun lookup(query: String): List<ContactChoice> = try {
        lookupContacts(query)
    } catch (error: SecurityException) {
        Log.w(TAG, "contacts blocked", error)
        emptyList()
    }

    private fun lookupContacts(query: String): List<ContactChoice> {
        val stem = stemName(phraseNormalize(query))
        if (stem.length < 2) return emptyList()
        val found = mutableListOf<ContactChoice>()
        val cursor = app.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER),
            "${Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$stem%"),
            null,
        ) ?: return emptyList()
        cursor.use {
            val nameCol = it.getColumnIndex(Phone.DISPLAY_NAME)
            val numberCol = it.getColumnIndex(Phone.NUMBER)
            while (it.moveToNext() && found.size < 12) {
                val name = it.getString(nameCol) ?: continue
                val number = it.getString(numberCol) ?: continue
                found += ContactChoice(name, number)
            }
        }
        return rankContacts(query, found)
    }

    private fun launcherLabels(): List<Triple<String, String, String>> = try {
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        app.packageManager.queryIntentActivities(probe, PackageManager.MATCH_ALL).mapNotNull { info ->
            val label = info.loadLabel(app.packageManager)?.toString() ?: return@mapNotNull null
            Triple(label, info.activityInfo.packageName, info.activityInfo.name)
        }
    } catch (error: RuntimeException) {
        Log.w(TAG, "launchers blocked", error)
        emptyList()
    }

    private fun sendText(number: String, body: String): Boolean = try {
        val sms = app.getSystemService(SmsManager::class.java) ?: return false
        val parts = sms.divideMessage(body)
        if (parts.size <= 1) sms.sendTextMessage(number, null, body, null, null)
        else sms.sendMultipartTextMessage(number, null, parts, null, null)
        true
    } catch (error: RuntimeException) {
        Log.w(TAG, "sms failed", error)
        false
    }

    private fun need(permissions: List<String>, reply: String): PhoneOutcome =
        PhoneOutcome(reply, "permission", launch = PhoneLaunch.Permissions(permissions))

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    private fun intentFor(launch: PhoneLaunch): Intent? = when (launch) {
        is PhoneLaunch.Tel -> Intent(if (launch.direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:${launch.number}")).newTask()
        is PhoneLaunch.SmsCompose -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${launch.number}")).apply {
            putExtra("sms_body", launch.body)
        }.newTask()
        is PhoneLaunch.OpenSettings -> Intent(settingsAction(launch.target)).newTask()
        is PhoneLaunch.OpenPackage -> openPackageIntent(launch)
        is PhoneLaunch.ViewAction -> Intent(launch.action, launch.data?.let(Uri::parse)).newTask()
        is PhoneLaunch.Permissions -> Intent().setClassName(app.packageName, PERMISSION_ACTIVITY).apply {
            putExtra(PhonePermissionExtra, launch.permissions.toTypedArray())
        }.newTask()
        is PhoneLaunch.Clock -> clockIntent(launch).newTask()
    }

    private fun openPackageIntent(launch: PhoneLaunch.OpenPackage): Intent? {
        app.packageManager.getLaunchIntentForPackage(launch.packageName)?.newTask()?.let { return it }
        val className = launch.className ?: return null
        return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(launch.packageName, className)
            .newTask()
    }

    private fun clockIntent(launch: PhoneLaunch.Clock): Intent = if (launch.timerSeconds != null) {
        Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, launch.timerSeconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
    } else {
        Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, launch.hour ?: 7)
            .putExtra(AlarmClock.EXTRA_MINUTES, launch.minute ?: 0)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
    }

    private fun settingsAction(target: SettingsTarget): String = when (target) {
        SettingsTarget.Battery -> Settings.ACTION_BATTERY_SAVER_SETTINGS
        SettingsTarget.Apps -> Settings.ACTION_APPLICATION_SETTINGS
        SettingsTarget.Language -> Settings.ACTION_LOCALE_SETTINGS
        SettingsTarget.Assistant -> "android.settings.VOICE_INTERACTION_SETTINGS"
        SettingsTarget.Sound -> Settings.ACTION_SOUND_SETTINGS
        SettingsTarget.Display -> Settings.ACTION_DISPLAY_SETTINGS
        SettingsTarget.Wireless -> Settings.ACTION_WIRELESS_SETTINGS
        SettingsTarget.General -> Settings.ACTION_SETTINGS
    }

    private fun start(intent: Intent?): Boolean {
        if (intent == null) return false
        return try {
            app.startActivity(intent)
            true
        } catch (error: Exception) {
            Log.w(TAG, "launch failed ${intent.action}", error)
            false
        }
    }

    private fun fallbackIntent(launch: PhoneLaunch): Intent? = when (launch) {
        is PhoneLaunch.Tel -> if (launch.direct) {
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:${launch.number}")).newTask()
        } else {
            null
        }
        is PhoneLaunch.ViewAction -> when (launch.action) {
            Settings.Panel.ACTION_WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS).newTask()
            PANEL_BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS).newTask()
            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA ->
                Intent(MediaStore.ACTION_IMAGE_CAPTURE).newTask()
            else -> null
        }
        is PhoneLaunch.Clock -> Intent(AlarmClock.ACTION_SHOW_ALARMS).newTask()
        else -> null
    }

    private fun Intent.newTask(): Intent = addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private class PrefNotes(context: Context) {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun all(): List<String> = prefs.getString(KEY, "").orEmpty().split('\n').filter { it.isNotBlank() }

        fun add(text: String) {
            val next = (all() + text.trim()).takeLast(20)
            prefs.edit().putString(KEY, next.joinToString("\n")).apply()
        }

        fun clear() {
            prefs.edit().remove(KEY).apply()
        }
    }

    private companion object {
        const val TAG = "Goga/Action"
        const val PREFS = "goga_notes"
        const val KEY = "lines"
        const val PERMISSION_ACTIVITY = "app.goga.assistant.PhonePermissionActivity"
        const val PANEL_BLUETOOTH = "android.settings.panel.action.BLUETOOTH"
    }
}

const val PhonePermissionExtra: String = "permissions"

val PhoneRuntimePermissions: Array<String> = arrayOf(
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.CALL_PHONE,
    Manifest.permission.SEND_SMS,
    Manifest.permission.CAMERA,
)
