package app.goga.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import app.goga.assistant.session.LaunchHandoff
import app.goga.assistant.session.LaunchRelay
import app.goga.assistant.session.SessionTrace

/**
 * Foreground hop out of the voice session. startVoiceActivity cannot open the dialer,
 * the camera, or settings. This activity is ours, so the session may start it with
 * startAssistantActivity, and from here a normal startActivity is allowed.
 */
class LaunchTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targets = targetsOf(intent)
        var opened = false
        for (target in targets) {
            val copy = Intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(copy)
                SessionTrace.log("trampoline", "started ${copy.action} cmp=${copy.component}")
                opened = true
                break
            } catch (error: Exception) {
                SessionTrace.log("trampoline", "failed ${copy.action} cmp=${copy.component}")
                SessionTrace.log("trampoline", error)
            }
        }
        if (!opened) SessionTrace.log("trampoline", "none started")
        val result = opened
        Handler(Looper.getMainLooper()).post { LaunchRelay.deliver(result) }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    @Suppress("DEPRECATION")
    private fun targetsOf(source: Intent): List<Intent> =
        source.getParcelableArrayListExtra<Intent>(LaunchHandoff.EXTRA_TARGETS).orEmpty()
}
