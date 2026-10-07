package app.goga.assistant

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import app.goga.assistant.session.AssistantOverlay
import app.goga.assistant.session.OverlayHost
import app.goga.assistant.session.SessionTrace

/** Same overlay as the system session. Used from the app and the Quick Settings tile. */
class AssistantActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "activity overlay")
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            val host = remember {
                OverlayHost(shown = true).apply {
                    launch = { intent -> launchFromActivity(intent) }
                }
            }
            AssistantOverlay(
                host = host,
                onClose = { finish() },
                onRequestMic = { launchMicPrompt() },
            )
        }
    }

    private fun launchFromActivity(intent: android.content.Intent): Boolean {
        SessionTrace.log("act", "activity ${intent.action}")
        return try {
            startActivity(intent)
            true
        } catch (error: Exception) {
            Log.w(TAG, "activity launch failed", error)
            SessionTrace.log("act", error)
            false
        }
    }

    private fun launchMicPrompt() {
        startActivity(android.content.Intent(this, MicPermissionActivity::class.java))
    }

    private companion object {
        const val TAG = "Goga/Session"
    }
}
