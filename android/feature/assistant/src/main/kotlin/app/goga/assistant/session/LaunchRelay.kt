package app.goga.assistant.session

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicReference

/**
 * One-shot result from the trampoline. Cleared on hide, so a late activity
 * cannot leave the next session waiting in Acting.
 */
object LaunchRelay {
    private val waiter = AtomicReference<((Boolean) -> Unit)?>(null)

    fun arm(onResult: (Boolean) -> Unit) {
        waiter.set(onResult)
    }

    fun disarm() {
        waiter.set(null)
    }

    fun deliver(ok: Boolean) {
        SessionTrace.log("trampoline", "result ok=$ok")
        val callback = waiter.getAndSet(null) ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            callback(ok)
        } else {
            Handler(Looper.getMainLooper()).post { callback(ok) }
        }
    }
}

object LaunchHandoff {
    const val ACTIVITY = "app.goga.assistant.LaunchTrampolineActivity"
    const val EXTRA_TARGETS = "app.goga.assistant.launch_targets"
}
