package app.goga.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.goga.assistant.session.MicPermissionChanged

/** Tiny prompt so the system session can ask for the microphone without its own Activity result. */
class MicPermissionActivity : ComponentActivity() {
    private val request = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifyAndFinish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            notifyAndFinish()
        } else {
            request.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun notifyAndFinish() {
        sendBroadcast(Intent(MicPermissionChanged).setPackage(packageName))
        finish()
    }
}
