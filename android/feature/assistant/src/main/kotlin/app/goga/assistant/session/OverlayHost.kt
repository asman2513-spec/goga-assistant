package app.goga.assistant.session

import android.content.Intent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * Signals owned by one session or one activity. Nothing here is process-global,
 * so a dead session cannot leave the next one in Acting or Speaking.
 */
class OverlayHost(shown: Boolean) {
    val showToken = mutableIntStateOf(if (shown) 1 else 0)
    val hidden = mutableStateOf(!shown)
    var launch: (Intent) -> Boolean = { false }

    fun noteShow() {
        hidden.value = false
        showToken.intValue = showToken.intValue + 1
    }

    fun noteHide() {
        hidden.value = true
    }
}
