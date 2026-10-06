package app.goga.assistant

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.goga.assistant.session.AssistantOverlay

/** Same overlay as the system session. Used from the app and the Quick Settings tile. */
class AssistantActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            AssistantOverlay(
                onClose = { finish() },
                onRequestMic = { launchMicPrompt() },
            )
        }
    }

    private fun launchMicPrompt() {
        startActivity(android.content.Intent(this, MicPermissionActivity::class.java))
    }
}
