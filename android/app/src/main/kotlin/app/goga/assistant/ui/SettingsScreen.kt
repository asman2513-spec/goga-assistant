package app.goga.assistant.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.goga.assistant.R

@Composable
fun SettingsScreen() {
    PlaceholderPage(
        title = stringResource(R.string.settings_title),
        body = stringResource(R.string.settings_stage),
        extra = listOf(
            stringResource(R.string.settings_pairing),
            stringResource(R.string.settings_push),
            stringResource(R.string.settings_magicos),
        ).joinToString(separator = "\n\n"),
    )
}
