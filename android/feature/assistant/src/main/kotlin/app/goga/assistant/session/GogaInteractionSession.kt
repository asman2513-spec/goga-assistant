package app.goga.assistant.session

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.WindowManager
import android.view.ContextThemeWrapper
import androidx.core.view.WindowCompat

class GogaInteractionSession(context: Context) : VoiceInteractionSession(
    ContextThemeWrapper(context, R.style.Theme_Goga_Session),
) {
    private val main = Handler(Looper.getMainLooper())

    init {
        setTheme(R.style.Theme_Goga_Session)
    }

    override fun onGetSupportedCommands(commands: Array<out String>?): BooleanArray {
        Log.i(TAG, "commands ${commands?.joinToString().orEmpty()}")
        return BooleanArray(commands?.size ?: 0)
    }

    override fun onRequestCommand(request: CommandRequest) {
        Log.w(TAG, "command ${request.command} from ${request.callingPackage}")
        refuse(request)
    }

    override fun onRequestCompleteVoice(request: CompleteVoiceRequest) {
        Log.w(TAG, "complete voice from ${request.callingPackage}")
        refuse(request)
    }

    override fun onRequestConfirmation(request: ConfirmationRequest) {
        Log.w(TAG, "confirmation from ${request.callingPackage}")
        refuse(request)
    }

    override fun onRequestPickOption(request: PickOptionRequest) {
        Log.w(TAG, "pick option from ${request.callingPackage}")
        refuse(request)
    }

    override fun onRequestAbortVoice(request: AbortVoiceRequest) {
        Log.w(TAG, "abort voice from ${request.callingPackage}")
        refuse(request)
    }

    private fun refuse(request: Request) {
        main.post {
            try {
                if (request.isActive) request.cancel()
                Log.i(TAG, "voice request cancelled")
            } catch (error: RuntimeException) {
                Log.w(TAG, "voice request cancel failed", error)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "session create")
        prepareWindow()
        setKeepAwake(true)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "session show flags=$showFlags")
    }

    override fun onHide() {
        super.onHide()
        Log.i(TAG, "session hide")
    }

    override fun onDestroy() {
        Log.i(TAG, "session destroy")
        super.onDestroy()
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
        @Suppress("DEPRECATION")
        dialogWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
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
        const val TAG = "Goga/Session"
        const val MIC_ACTIVITY = "app.goga.assistant.MicPermissionActivity"
    }
}
