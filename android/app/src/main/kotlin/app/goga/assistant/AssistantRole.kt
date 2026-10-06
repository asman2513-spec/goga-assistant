package app.goga.assistant

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

data class AssistantRoleState(
    val available: Boolean,
    val held: Boolean,
)

fun Context.assistantRoleState(): AssistantRoleState {
    val roles = getSystemService(RoleManager::class.java)
        ?: return AssistantRoleState(available = false, held = false)
    val available = roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
    val held = available && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    return AssistantRoleState(available = available, held = held)
}

fun Context.assistantRoleRequest(): Intent? {
    val roles = getSystemService(RoleManager::class.java) ?: return null
    if (!roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) || roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
        return null
    }
    return roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
}

fun Context.defaultAppsSettings(): Intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)

fun Context.voiceInputSettings(): Intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)

fun Context.systemAssistIntent(): Intent =
    Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

fun Context.ignoresBatteryOptimizations(): Boolean {
    val power = getSystemService(PowerManager::class.java) ?: return false
    return power.isIgnoringBatteryOptimizations(packageName)
}

fun Context.batteryOptimizationRequest(): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:$packageName")
    }

fun Context.ttsSettingsIntent(): Intent = Intent("com.android.settings.TTS_SETTINGS")
