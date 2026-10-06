package app.goga.model

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * API timestamps are UTC with millisecond precision.
 * Events sent to the bot carry a Moscow offset so routines do not guess the zone.
 */
object Timestamps {
    val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")

    private val utc: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    private val moscow: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").withZone(MOSCOW)

    fun formatUtc(instant: Instant): String = utc.format(instant)

    fun formatMoscow(instant: Instant): String = moscow.format(instant)
}
