package app.goga.model

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContractTest {
    @Test
    fun featureModulesStayDecoupledByIdOnly() {
        val reminder = Reminder(
            id = "11111111-1111-1111-1111-111111111111",
            userId = "22222222-2222-2222-2222-222222222222",
            taskId = "33333333-3333-3333-3333-333333333333",
            title = "Отчёт",
            fireAt = "2026-10-08T16:00:00.000Z",
            createdAt = "2026-10-06T03:00:00.000Z",
            updatedAt = "2026-10-06T03:00:00.000Z",
        )
        assertTrue(reminder.taskId != null)
        assertTrue(EntityTypes.all.contains(EntityTypes.NOTE))
        assertTrue(CommandSources.all.contains(CommandSources.VOICE))
    }
}
