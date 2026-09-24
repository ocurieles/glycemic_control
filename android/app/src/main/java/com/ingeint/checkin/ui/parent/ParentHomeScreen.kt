package com.ingeint.checkin.ui.parent

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ingeint.checkin.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ParentHomeScreen(viewModel: ParentViewModel, onOpenSettings: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(state.childName, style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_icon_description))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            state.activeSos?.let { sos ->
                item {
                    SosBanner(sos, state.childName, state.childPhone, onGoing = { viewModel.ackSos(sos.eventId) })
                    Spacer(Modifier.height(16.dp))
                }
            }

            item {
                ComplianceCard(state.dayCompliance)
                Spacer(Modifier.height(16.dp))
            }

            item {
                QuickMessages(onSend = viewModel::sendMessage)
                Spacer(Modifier.height(16.dp))
            }

            item {
                Text(stringResource(R.string.parent_timeline_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }

            if (state.timeline.isEmpty()) {
                item { Text(stringResource(R.string.parent_timeline_empty), style = MaterialTheme.typography.bodyMedium) }
            } else {
                items(state.timeline) { event -> TimelineRow(event) }
            }
        }
    }
}

@Composable
private fun SosBanner(sos: ActiveSos, childName: String, childPhone: String?, onGoing: () -> Unit) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.parent_banner_sos_title, childName),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onGoing) { Text(stringResource(R.string.parent_banner_sos_going)) }
                if (childPhone != null) {
                    Button(onClick = {
                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$childPhone")))
                    }) { Text(stringResource(R.string.parent_banner_sos_call, childName)) }
                }
            }
        }
    }
}

@Composable
private fun ComplianceCard(compliance: DayCompliance?) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.parent_compliance_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (compliance == null) {
                Text(stringResource(R.string.parent_compliance_missing), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    stringResource(
                        R.string.parent_compliance_counts,
                        compliance.onTime,
                        compliance.late,
                        compliance.missed,
                        compliance.pending,
                        compliance.upcoming,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun QuickMessages(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val reminderText = stringResource(R.string.parent_quick_message_reminder)
    val okText = stringResource(R.string.parent_quick_message_ok)
    val goingText = stringResource(R.string.parent_quick_message_going)
    Column {
        Text(stringResource(R.string.parent_quick_messages_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { onSend(reminderText) }, label = { Text(reminderText) })
            AssistChip(onClick = { onSend(okText) }, label = { Text(okText) })
            AssistChip(onClick = { onSend(goingText) }, label = { Text(goingText) })
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 120) text = it },
                label = { Text(stringResource(R.string.parent_quick_message_hint)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onSend(text); text = "" }) { Text(stringResource(R.string.parent_quick_message_send)) }
        }
    }
}

/** Mismas flechas que `TREND_ARROWS` de `messages.ts` (docs/04). */
private val TREND_ARROWS = mapOf(1L to "↓", 2L to "↘", 3L to "→", 4L to "↗", 5L to "↑")

@Composable
private fun TimelineRow(event: TimelineEvent) {
    val label =
        when (event.type) {
            "checkin" -> stringResource(R.string.parent_event_checkin)
            "sos" -> stringResource(R.string.parent_event_sos)
            "parent_message" -> stringResource(R.string.parent_event_parent_message)
            "sos_ack" -> stringResource(R.string.parent_event_sos_ack)
            "insulin_dose" -> stringResource(R.string.parent_event_insulin_dose)
            else -> event.type
        }
    val esVe = remember { Locale.Builder().setLanguage("es").setRegion("VE").build() }
    val time = remember(event.atMillis) { SimpleDateFormat("h:mm a", esVe).format(Date(event.atMillis)) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("$label · $time", style = MaterialTheme.typography.bodyMedium)
            if (event.syncedLate) {
                Text(
                    stringResource(R.string.parent_timeline_synced_late),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        event.glucoseValueMgDl?.let { value ->
            val arrow = TREND_ARROWS[event.glucoseTrend] ?: ""
            val color =
                when (event.glucoseLevel) {
                    "low", "high" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
            Text("$value mg/dL $arrow", style = MaterialTheme.typography.bodySmall, color = color)
        }
        event.doseUnits?.let { Text("$it U", style = MaterialTheme.typography.bodySmall) }
        event.text?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        event.senderName?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color.Gray) }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}
