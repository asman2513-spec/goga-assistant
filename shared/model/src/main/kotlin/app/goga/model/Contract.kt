package app.goga.model

/** Values the phone, the gateway, and the bot must agree on. */
object CommandSources {
    const val VOICE = "voice"
    const val TEXT = "text"
    const val WIDGET = "widget"
    const val NOTIFICATION = "notification"
    const val SYSTEM = "system"

    val all: Set<String> = setOf(VOICE, TEXT, WIDGET, NOTIFICATION, SYSTEM)
}

object CommandStatuses {
    const val QUEUED = "queued"
    const val DISPATCHED = "dispatched"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
}

object BotResultKinds {
    const val REPLY = "reply"
    const val CLARIFICATION = "clarification"
    const val EVENT = "event"

    val all: Set<String> = setOf(REPLY, CLARIFICATION, EVENT)
}

object EntityTypes {
    const val NOTE = "note"
    const val TASK = "task"
    const val REMINDER = "reminder"
    const val CALENDAR_EVENT = "calendar_event"

    val all: Set<String> = setOf(NOTE, TASK, REMINDER, CALENDAR_EVENT)
}

object PushChannels {
    const val FCM = "fcm"
    const val RUSTORE = "rustore"
    const val WEBSOCKET = "websocket"

    val all: Set<String> = setOf(FCM, RUSTORE, WEBSOCKET)
}

object ReminderChannelIds {
    /** Phone alarms. The v1 delivery path. */
    const val LOCAL_ALARM = "local_alarm"
    const val PUSH = "push"
    /** Later, not in v1. */
    const val SMS = "sms"
    /** Later, not in v1. */
    const val CALL = "call"
}

object CalendarProviderIds {
    /** Google Calendar through the Grok Bot connector. The v1 path. */
    const val GOOGLE_VIA_BOT = "google-via-bot"
    const val CALDAV = "caldav"
    const val ANDROID_CALENDAR = "android-calendar"
}

object BotHeaders {
    const val TIMESTAMP = "X-Goga-Timestamp"
    const val SIGNATURE = "X-Goga-Signature"
    const val USER_AGENT = "goga-gateway/0.1.0"
}
