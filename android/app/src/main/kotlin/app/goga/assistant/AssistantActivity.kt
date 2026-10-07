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
                    launch = { intents -> launchFromActivity(intents) }
                }
            }
            AssistantOverlay(
                host = host,
                onClose = { finish() },
                onRequestMic = { launchMicPrompt() },
            )
        }
    }

    private fun launchFromActivity(intents: List<android.content.Intent>): Boolean {
        if (intents.isEmpty()) return false
        var opened = false
        for (intent in intents) {
            val copy = android.content.Intent(intent).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(copy)
                SessionTrace.log("act", "activity started ${copy.action}")
                opened = true
                break
            } catch (error: Exception) {
                Log.w(TAG, "activity launch failed", error)
                SessionTrace.log("act", error)
            }
        }
        window.decorView.post { app.goga.assistant.session.LaunchRelay.deliver(opened) }
        return true
    }

    private fun launchMicPrompt() {
        startActivity(android.content.Intent(this, MicPermissionActivity::class.java))
    }

    private companion object {
        const val TAG = "Goga/Session"
    }
}
