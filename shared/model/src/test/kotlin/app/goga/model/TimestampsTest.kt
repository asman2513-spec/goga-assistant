package app.goga.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class TimestampsTest {
    @Test
    fun morningSummaryIsSixAmMoscow() {
        val instant = Instant.parse("2026-10-06T03:00:00Z")
        assertEquals("2026-10-06T06:00:00+03:00", Timestamps.formatMoscow(instant))
    }

    @Test
    fun utcFormatKeepsMillisecondPrecision() {
        assertEquals(
            "2026-10-06T03:00:00.000Z",
            Timestamps.formatUtc(Instant.parse("2026-10-06T03:00:00Z")),
        )
        assertEquals(
            "2026-10-06T03:00:00.100Z",
            Timestamps.formatUtc(Instant.parse("2026-10-06T03:00:00.100Z")),
        )
    }
}
