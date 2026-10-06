package app.goga.data.local

import app.goga.model.Note
import org.junit.Assert.assertEquals
import org.junit.Test

class MappersTest {
    @Test
    fun noteRoundTripKeepsSyncFields() {
        val note = Note(
            id = "11111111-1111-1111-1111-111111111111",
            userId = "22222222-2222-2222-2222-222222222222",
            deviceId = "33333333-3333-3333-3333-333333333333",
            title = "Хостинг",
            body = "сравнить тарифы",
            createdAt = "2026-10-06T03:00:00.000Z",
            updatedAt = "2026-10-06T03:00:00.000Z",
        )
        assertEquals(note, note.toEntity().toNote())
    }
}
