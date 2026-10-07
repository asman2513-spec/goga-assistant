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
    private val ownPackage = context.packageName
    private val host = OverlayHost(shown = false)

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
        host.launch = { intents -> launchFromSession(intents) }
        Log.i(TAG, "session create")
        SessionTrace.log("session", "create")
        prepareWindow()
        setKeepAwake(true)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "session show flags=$showFlags")
        SessionTrace.log("session", "show flags=$showFlags")
        host.noteShow()
    }

    override fun onHide() {
        SessionTrace.log("session", "hide")
        host.noteHide()
        super.onHide()
        Log.i(TAG, "session hide")
    }

    override fun onDestroy() {
        SessionTrace.log("session", "destroy")
        host.noteHide()
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
                host = host,
                onClose = { finish() },
                onRequestMic = { requestMicPermission() },
            )
        }
        return root
    }

    /**
     * Foreign activities are not started with startVoiceActivity: that API only
     * resolves CATEGORY_VOICE and rejects the dialer and the camera. The trampoline
     * is our activity, so startAssistantActivity is allowed, and it calls startActivity.
     */
    private fun launchFromSession(intents: List<Intent>): Boolean {
        if (intents.isEmpty()) return false
        val path = routeLaunch(intents.map { it.component?.packageName }, ownPackage)
        SessionTrace.log("act", "route $path ${intents.joinToString { it.action ?: "-" }}")
        return try {
            if (path == LaunchPath.ASSISTANT) {
                startAssistantActivity(intents.first())
                main.post { LaunchRelay.deliver(true) }
            } else {
                val handoff = Intent().setClassName(ownPackage, LaunchHandoff.ACTIVITY).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putParcelableArrayListExtra(LaunchHandoff.EXTRA_TARGETS, ArrayList(intents))
                }
                startAssistantActivity(handoff)
            }
            true
        } catch (error: Exception) {
            Log.w(TAG, "assistant activity failed", error)
            SessionTrace.log("act", error)
            false
        }
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
