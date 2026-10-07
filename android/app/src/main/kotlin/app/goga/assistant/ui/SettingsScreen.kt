package app.goga.assistant.ui

import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.goga.assistant.AssistantRoleState
import app.goga.assistant.GogaTileService
import app.goga.assistant.R
import app.goga.assistant.assistantRoleRequest
import app.goga.assistant.assistantRoleState
import app.goga.assistant.batteryOptimizationRequest
import app.goga.assistant.defaultAppsSettings
import app.goga.assistant.digitalAssistantSettings
import app.goga.assistant.ignoresBatteryOptimizations
import app.goga.assistant.systemAssistIntent
import app.goga.assistant.ttsSettingsIntent
import app.goga.assistant.voiceInputSettings

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var role by remember { mutableStateOf(context.assistantRoleState()) }
    var batteryFree by remember { mutableStateOf(context.ignoresBatteryOptimizations()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                role = context.assistantRoleState()
                batteryFree = context.ignoresBatteryOptimizations()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        role = context.assistantRoleState()
    }
    val noActivity = stringResource(R.string.settings_no_activity)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
        Text(text = roleLabel(role), style = MaterialTheme.typography.bodyLarge)
        if (!role.held) {
            Button(
                onClick = {
                    val request = context.assistantRoleRequest()
                    if (request == null) {
                        launchFirst(
                            context,
                            listOf(context.digitalAssistantSettings(), context.defaultAppsSettings()),
                            noActivity,
                        )
                    } else {
                        roleLauncher.launch(request)
                    }
                },
            ) {
                Text(stringResource(R.string.settings_make_assistant))
            }
        }
        OutlinedButton(onClick = { launch(context, context.defaultAppsSettings(), noActivity) }) {
            Text(stringResource(R.string.settings_default_apps))
        }
        if (role.held) {
            OutlinedButton(
                onClick = {
                    launch(context, context.systemAssistIntent(), context.getString(R.string.settings_no_system_assist))
                },
            ) {
                Text(stringResource(R.string.settings_try_system))
            }
        }
        Text(
            text = stringResource(R.string.settings_role_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(text = stringResource(R.string.settings_voice_title), style = MaterialTheme.typography.titleLarge)
        OutlinedButton(onClick = { launch(context, context.voiceInputSettings(), noActivity) }) {
            Text(stringResource(R.string.settings_voice_pack))
        }
        OutlinedButton(
            onClick = { launch(context, context.ttsSettingsIntent(), context.getString(R.string.settings_no_tts)) },
        ) {
            Text(stringResource(R.string.settings_tts))
        }
        Text(
            text = stringResource(R.string.settings_voice_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(text = stringResource(R.string.settings_magicos_title), style = MaterialTheme.typography.titleLarge)
        Text(
            text = stringResource(R.string.settings_magicos),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                if (batteryFree) R.string.settings_battery_free else R.string.settings_battery_restricted,
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (!batteryFree) {
            OutlinedButton(onClick = { launch(context, context.batteryOptimizationRequest(), noActivity) }) {
                Text(stringResource(R.string.settings_battery_button))
            }
        }

        Text(text = stringResource(R.string.settings_tile_title), style = MaterialTheme.typography.titleLarge)
        Button(onClick = { requestTile(context) }) {
            Text(stringResource(R.string.settings_tile_add))
        }
        Text(
            text = stringResource(R.string.settings_tile_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.settings_stage),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun roleLabel(role: AssistantRoleState): String = when {
    role.held -> stringResource(R.string.settings_role_held)
    role.available -> stringResource(R.string.settings_role_available)
    else -> stringResource(R.string.settings_role_missing)
}

private fun launch(context: Context, intent: Intent, fallback: String) {
    launchFirst(context, listOf(intent), fallback)
}

private fun launchFirst(context: Context, intents: List<Intent>, fallback: String) {
    for (intent in intents) {
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            continue
        }
    }
    Toast.makeText(context, fallback, Toast.LENGTH_LONG).show()
}

private fun requestTile(context: Context) {
    val manual = context.getString(R.string.settings_tile_manual)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, manual, Toast.LENGTH_LONG).show()
        return
    }
    val statusBar = context.getSystemService(StatusBarManager::class.java)
    if (statusBar == null) {
        Toast.makeText(context, manual, Toast.LENGTH_LONG).show()
        return
    }
    try {
        statusBar.requestAddTileService(
            ComponentName(context, GogaTileService::class.java),
            context.getString(R.string.qs_tile_label),
            Icon.createWithResource(context, R.drawable.ic_qs_goga),
            context.mainExecutor,
        ) { result ->
            val message = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED,
                -> context.getString(R.string.settings_tile_added)
                else -> manual
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    } catch (_: RuntimeException) {
        Toast.makeText(context, manual, Toast.LENGTH_LONG).show()
    }
}
