package com.ingeint.checkin.ui.child

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
private val QUICK_LOG_DOSES = listOf(0.5, 1.0, 1.5, 2.0, 2.5, 3.0)

/**
 * Pantalla del niño (docs/06 "Niño (ChildScreen)"). "Ya me revisé" y "Ayuda" ya
 * registran en el outbox y sincronizan de verdad (docs/07, F4).
 */
@Composable
fun ChildScreen(viewModel: ChildViewModel, onOpenDiagnostics: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var showQuickLog by remember { mutableStateOf(false) }

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

        // Discreto a propósito: mismo tamaño/tono que el de ajustes, sin texto junto al
        // botón principal (docs/01, pedido 2026-09-24 — ver Punto 3 de la conversación).
        IconButton(onClick = { showQuickLog = true }, modifier = Modifier.padding(8.dp).align(Alignment.TopStart)) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.child_quick_log_button))
        }
    }

    if (showQuickLog) {
        QuickLogDialog(
            doseLogged = state.doseLogged,
            onPick = viewModel::logInsulinDose,
            onDismiss = { showQuickLog = false },
        )
    }
}

@Composable
private fun QuickLogDialog(doseLogged: Boolean, onPick: (Double) -> Unit, onDismiss: () -> Unit) {
    // Bug real reportado 2026-09-27: el diálogo se quedaba abierto tras elegir una
    // dosis y solo cambiaba un texto chico ("Registrado ✓") — parecía que no había
    // pasado nada, así que se cierra solo apenas se confirma.
    LaunchedEffect(doseLogged) {
        if (doseLogged) {
            delay(600)
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.child_quick_log_title)) },
        text = {
            Column {
                if (doseLogged) {
                    Text(stringResource(R.string.child_quick_log_done), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                }
                QUICK_LOG_DOSES.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { dose ->
                            FilterChip(selected = false, onClick = { onPick(dose) }, label = { Text(dose.toString()) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.child_quick_log_cancel)) } },
    )
}

/** Mismas flechas que el backend (`TREND_ARROWS` de `messages.ts`, docs/04). */
private val TREND_ARROWS = mapOf(1L to "↓", 2L to "↘", 3L to "→", 4L to "↗", 5L to "↑")

@Composable
private fun CheckinStatusText(state: ChildUiState) {
    val text =
        when (state.checkinStatus) {
            CheckinStatus.SENT -> state.checkinStatusTimeText?.let { stringResource(R.string.child_checkin_sent, it) }
            CheckinStatus.PENDING -> stringResource(R.string.child_checkin_pending, state.pendingCount)
            CheckinStatus.NONE -> null
        }
    text?.let { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }

    // Solo aparece DESPUÉS de tocar el botón (docs/01, pedido 2026-09-24): nunca antes,
    // nunca en una notificación — solo dentro de la app, en este mismo lugar. Mismo
    // tamaño que "Próximo recordatorio" (pedido 2026-09-27).
    state.lastCheckinGlucoseValueMgDl?.let { value ->
        val arrow = TREND_ARROWS[state.lastCheckinGlucoseTrend] ?: ""
        Text("$value $arrow", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
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
