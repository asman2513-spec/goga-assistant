package app.goga.assistant

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.goga.assistant.session.PhonePermissionExtra
import app.goga.assistant.session.PhoneRuntimePermissions

/** Asks for the phone-control permissions the voice session cannot request itself. */
class PhonePermissionActivity : ComponentActivity() {
    private val request = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val asked = intent.getStringArrayExtra(PhonePermissionExtra)
        val wanted = if (asked.isNullOrEmpty()) PhoneRuntimePermissions.toList() else asked.toList()
        val missing = wanted.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) finish() else request.launch(missing.toTypedArray())
    }
}
