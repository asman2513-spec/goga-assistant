package app.goga.assistant

import app.goga.assistant.ui.AppTab
import org.junit.Assert.assertEquals
import org.junit.Test

class AppTabTest {
    @Test
    fun tabsStaySeparate() {
        assertEquals(
            listOf("today", "tasks", "notes", "settings"),
            AppTab.entries.map { it.route },
        )
        assertEquals(AppTab.entries.size, AppTab.entries.map { it.route }.toSet().size)
    }
}
