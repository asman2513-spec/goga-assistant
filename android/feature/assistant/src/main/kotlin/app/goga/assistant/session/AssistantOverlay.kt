package app.goga.assistant.session

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun AssistantOverlay(
    onClose: () -> Unit,
    onRequestMic: () -> Unit,
) {
    val context = LocalContext.current
    val dialog = remember { LocalDialogManager() }
    val reducer = remember { SessionReducer(dialog) }
    val stateHolder = remember { mutableStateOf(SessionState()) }
    val state = stateHolder.value
    val dispatch = remember<(SessionEvent) -> Unit> {
        { event -> stateHolder.value = reducer.reduce(stateHolder.value, event) }
    }
    val speech = remember { DeviceSpeechInput(context) }
    val speaker = remember { SystemSpeechOutput(context) }
    var micGranted by remember { mutableStateOf(hasMic(context)) }
    var russianVoice by remember { mutableStateOf<Boolean?>(null) }
    var draft by remember { mutableStateOf("") }
    val listener = remember {
        object : SpeechListener {
            override fun onPartial(text: String) = dispatch(SessionEvent.Partial(text))
            override fun onFinal(text: String) = dispatch(SessionEvent.FinalText(text, voiceSource()))
            override fun onFailure(failure: ListenFailure) = dispatch(SessionEvent.ListenFailed(failure))
        }
    }

    DisposableEffect(speaker) {
        speaker.onReady = { russianVoice = it }
        onDispose {
            speaker.onReady = null
            speech.release()
            speaker.release()
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) micGranted = hasMic(context)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                micGranted = hasMic(context)
            }
        }
        lifecycle.addObserver(observer)
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(MicPermissionChanged),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }

    fun startListening() {
        if (!micGranted || speech.mode == SpeechMode.UNAVAILABLE) return
        dispatch(SessionEvent.ListenStarted)
        speech.start(listener)
    }

    LaunchedEffect(state.turn, state.speaking, micGranted, state.preferText, speech.mode) {
        val current = stateHolder.value
        if (current.speaking || current.preferText || current.listening) return@LaunchedEffect
        if (!micGranted || speech.mode == SpeechMode.UNAVAILABLE) return@LaunchedEffect
        startListening()
    }

    LaunchedEffect(state.turn) {
        val current = stateHolder.value
        if (current.turn == 0 || current.reply.isBlank()) return@LaunchedEffect
        speaker.speak(current.reply) { dispatch(SessionEvent.SpeechFinished) }
    }

    AssistantTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        speech.stop()
                        speaker.stop()
                        onClose()
                    },
                ),
        ) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.overlay_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        TextButton(
                            onClick = {
                                speech.stop()
                                speaker.stop()
                                onClose()
                            },
                        ) {
                            Text(stringResource(R.string.overlay_close))
                        }
                    }
                    Text(
                        text = state.status,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (speech.mode == SpeechMode.SYSTEM) {
                        Text(
                            text = stringResource(R.string.overlay_system_speech),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (russianVoice == false) {
                        Text(
                            text = stringResource(R.string.overlay_no_russian_voice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.reply.isNotBlank()) {
                        SelectionContainer {
                            Text(
                                text = state.reply,
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }
                    }
                    if (state.lastUser.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.overlay_you) + ": " + state.lastUser,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.partial.isNotBlank()) {
                        Text(
                            text = state.partial,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    if (!micGranted) {
                        Text(
                            text = stringResource(R.string.overlay_mic_missing),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = onRequestMic) {
                            Text(stringResource(R.string.overlay_grant_mic))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = {
                                val granted = hasMic(context)
                                micGranted = granted
                                if (state.listening) {
                                    speech.stop()
                                    dispatch(SessionEvent.ListenStopped)
                                } else if (!granted) {
                                    onRequestMic()
                                } else {
                                    startListening()
                                }
                            },
                            enabled = micGranted && speech.mode != SpeechMode.UNAVAILABLE,
                        ) {
                            Text(
                                stringResource(
                                    if (state.listening) R.string.overlay_stop else R.string.overlay_listen,
                                ),
                            )
                        }
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier
                                .weight(1f)
                                .onFocusEvent { focus ->
                                    if (focus.isFocused && stateHolder.value.listening) {
                                        speech.stop()
                                        dispatch(SessionEvent.ListenStopped)
                                    }
                                },
                            placeholder = { Text(stringResource(R.string.overlay_hint)) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    submitDraft(
                                        draft = draft,
                                        onDraft = { draft = it },
                                        speech = speech,
                                        dispatch = dispatch,
                                    )
                                },
                            ),
                            singleLine = true,
                        )
                        TextButton(
                            onClick = {
                                submitDraft(
                                    draft = draft,
                                    onDraft = { draft = it },
                                    speech = speech,
                                    dispatch = dispatch,
                                )
                            },
                            enabled = draft.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.overlay_send))
                        }
                    }
                }
            }
        }
    }
}

private fun submitDraft(
    draft: String,
    onDraft: (String) -> Unit,
    speech: SpeechToText,
    dispatch: (SessionEvent) -> Unit,
) {
    val text = draft.trim()
    if (text.isEmpty()) return
    speech.stop()
    onDraft("")
    dispatch(SessionEvent.FinalText(text, textSource()))
}

private fun hasMic(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

@Composable
private fun AssistantTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val pine = Color(0xFF1B6B4A)
    val pineDark = Color(0xFF8FCBB0)
    val ink = Color(0xFF10231B)
    MaterialTheme(
        colorScheme = if (dark) {
            androidx.compose.material3.darkColorScheme(
                primary = pineDark,
                onPrimary = ink,
                background = Color(0xFF0E1713),
                surface = Color(0xFF17241E),
                onBackground = Color(0xFFE7F2EC),
                onSurface = Color(0xFFE7F2EC),
            )
        } else {
            androidx.compose.material3.lightColorScheme(
                primary = pine,
                onPrimary = Color.White,
                background = Color(0xFFF4F7F5),
                surface = Color.White,
                onBackground = ink,
                onSurface = ink,
            )
        },
        content = content,
    )
}
