package com.ingeint.checkin.ui.child

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ingeint.checkin.R
import kotlinx.coroutines.delay

private const val HELP_HOLD_MS = 2000L

/**
 * Pantalla del niño (docs/06 "Niño (ChildScreen)"). "Ya me revisé" y "Ayuda" ya
 * registran en el outbox y sincronizan de verdad (docs/07, F4).
 */
@Composable
fun ChildScreen(viewModel: ChildViewModel, onOpenDiagnostics: () -> Unit) {
    val state by viewModel.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CheckinButton(onClick = viewModel::onCheckin)

            Spacer(Modifier.height(12.dp))
            CheckinStatusText(state)

            Spacer(Modifier.height(16.dp))
            Text(
                text =
                    state.nextReminderText?.let { stringResource(R.string.child_next_reminder_label, it) }
                        ?: stringResource(R.string.child_next_reminder_none),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )

            state.lastMessage?.let { message ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.child_last_message_label, message.from, message.text),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(48.dp))
            HelpButton(onComplete = viewModel::onSos)
        }

        IconButton(onClick = onOpenDiagnostics, modifier = Modifier.padding(8.dp).align(Alignment.TopEnd)) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.permissions_title))
        }
    }
}

@Composable
private fun CheckinStatusText(state: ChildUiState) {
    val text =
        when (state.checkinStatus) {
            CheckinStatus.SENT -> state.checkinStatusTimeText?.let { stringResource(R.string.child_checkin_sent, it) }
            CheckinStatus.PENDING -> stringResource(R.string.child_checkin_pending, state.pendingCount)
            CheckinStatus.NONE -> null
        }
    text?.let { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
}

@Composable
private fun CheckinButton(onClick: () -> Unit) {
    Box(
        modifier =
            Modifier
                .size(220.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.child_checkin_button),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HelpButton(onComplete: () -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            val start = System.currentTimeMillis()
            while (isPressed) {
                progress = ((System.currentTimeMillis() - start).toFloat() / HELP_HOLD_MS).coerceIn(0f, 1f)
                if (progress >= 1f) {
                    onComplete()
                    break
                }
                delay(16)
            }
        } else {
            progress = 0f
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(96.dp),
                color = MaterialTheme.colorScheme.error,
            )
            Box(
                modifier =
                    Modifier
                        .size(80.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, CircleShape)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    isPressed = true
                                    try {
                                        awaitRelease()
                                    } finally {
                                        isPressed = false
                                    }
                                },
                            )
                        },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.child_help_button), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.child_help_hint), style = MaterialTheme.typography.bodySmall)
    }
}
