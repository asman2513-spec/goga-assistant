package app.goga.assistant.session

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.WindowManager
import android.view.ContextThemeWrapper
import androidx.core.view.WindowCompat

class GogaInteractionSession(context: Context) : VoiceInteractionSession(
    ContextThemeWrapper(context, R.style.Theme_Goga_Session),
) {
    init {
        setTheme(R.style.Theme_Goga_Session)
    }

    override fun onCreate() {
        super.onCreate()
        prepareWindow()
        setKeepAwake(true)
    }

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        prepareWindow()
    }

    private fun prepareWindow() {
        val dialogWindow = window?.window ?: return
        dialogWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        @Suppress("DEPRECATION")
        dialogWindow.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        )
        WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
    }

    override fun onCreateContentView(): android.view.View {
        val root = SessionComposeRoot(context)
        root.setSessionContent {
            AssistantOverlay(
                onClose = { finish() },
                onRequestMic = { requestMicPermission() },
            )
        }
        return root
    }

    private fun requestMicPermission() {
        val intent = Intent().setClassName(context.packageName, MIC_ACTIVITY)
        startAssistantActivity(intent)
    }

    private companion object {
        const val MIC_ACTIVITY = "app.goga.assistant.MicPermissionActivity"
    }
}
