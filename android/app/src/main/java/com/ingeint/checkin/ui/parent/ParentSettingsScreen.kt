package com.ingeint.checkin.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ingeint.checkin.R
import com.ingeint.checkin.data.model.ReminderSettings

private val DAY_LABELS =
    listOf(
        1 to R.string.parent_settings_day_mon,
        2 to R.string.parent_settings_day_tue,
        3 to R.string.parent_settings_day_wed,
        4 to R.string.parent_settings_day_thu,
        5 to R.string.parent_settings_day_fri,
        6 to R.string.parent_settings_day_sat,
        7 to R.string.parent_settings_day_sun,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentSettingsScreen(
    viewModel: ParentSettingsViewModel,
    childName: String,
    onBack: () -> Unit = {},
    onLeftFamily: () -> Unit,
    onOpenAbout: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    var draft by remember(state.settings) { mutableStateOf(state.settings) }

    LaunchedEffect(state.leftFamily) {
        if (state.leftFamily) onLeftFamily()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.parent_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            item { ScheduleSection(draft, onChange = { draft = it }) }
            item { ThresholdsSection(draft, onChange = { draft = it }) }
            item { SosSection(draft, childName, onChange = { draft = it }) }

            item {
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.save(draft) }, enabled = !state.loading) {
                    Text(stringResource(R.string.parent_settings_save))
                }
                state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.savedMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }

            item { LibreLinkUpSection(viewModel, state) }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                FamilySection(viewModel, state)
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                OutlinedButton(onClick = onOpenAbout) { Text(stringResource(R.string.parent_settings_about)) }
            }

            // TEMPORAL (2026-09-27 → quitar tras usarlo): reconstruye el calendario de
            // insulina dañado por un bug real ya arreglado (recomputeDay borraba el campo).
            item {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = viewModel::backfillInsulinDays) { Text("Reparar calendario de insulina") }
                state.insulinBackfillMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun ScheduleSection(settings: ReminderSettings, onChange: (ReminderSettings) -> Unit) {
    Column {
        Text(stringResource(R.string.parent_settings_schedule_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.parent_settings_enabled), modifier = Modifier.weight(1f))
            Switch(checked = settings.enabled, onCheckedChange = { onChange(settings.copy(enabled = it)) })
        }
        Spacer(Modifier.height(8.dp))
        NumberField(
            label = stringResource(R.string.parent_settings_interval),
            value = settings.intervalMinutes,
            onChange = { onChange(settings.copy(intervalMinutes = it)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DAY_LABELS.forEach { (day, labelRes) ->
                FilterChip(
                    selected = day in settings.days,
                    onClick = {
                        val newDays = if (day in settings.days) settings.days - day else settings.days + day
                        onChange(settings.copy(days = newDays.sorted()))
                    },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextField121(
                label = stringResource(R.string.parent_settings_start_time),
                value = settings.startTime,
                onChange = { onChange(settings.copy(startTime = it)) },
                modifier = Modifier.weight(1f),
            )
            TextField121(
                label = stringResource(R.string.parent_settings_end_time),
                value = settings.endTime,
                onChange = { onChange(settings.copy(endTime = it)) },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            NumberField(
                label = stringResource(R.string.parent_settings_escalation),
                value = settings.escalationMinutes,
                onChange = { onChange(settings.copy(escalationMinutes = it)) },
                modifier = Modifier.weight(1f),
            )
            NumberField(
                label = stringResource(R.string.parent_settings_nudge),
                value = settings.nudgeMinutes,
                onChange = { onChange(settings.copy(nudgeMinutes = it)) },
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
    }
}

@Composable
private fun ThresholdsSection(settings: ReminderSettings, onChange: (ReminderSettings) -> Unit) {
    Column {
        Text(stringResource(R.string.parent_settings_thresholds_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            NumberField(
                label = stringResource(R.string.parent_settings_low_threshold),
                value = settings.lowThreshold,
                onChange = { onChange(settings.copy(lowThreshold = it)) },
                modifier = Modifier.weight(1f),
            )
            NumberField(
                label = stringResource(R.string.parent_settings_high_threshold),
                value = settings.highThreshold,
                onChange = { onChange(settings.copy(highThreshold = it)) },
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
    }
}

@Composable
private fun SosSection(settings: ReminderSettings, childName: String, onChange: (ReminderSettings) -> Unit) {
    Column {
        Text(stringResource(R.string.parent_settings_sos_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.parent_settings_sms_enabled), modifier = Modifier.weight(1f))
            Switch(
                checked = settings.smsFallbackEnabled,
                onCheckedChange = { onChange(settings.copy(smsFallbackEnabled = it)) },
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = settings.smsNumbers.joinToString(","),
            onValueChange = { onChange(settings.copy(smsNumbers = it.split(",").map(String::trim).filter(String::isNotEmpty))) },
            label = { Text(stringResource(R.string.parent_settings_sms_numbers)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = settings.childPhone ?: "",
            onValueChange = { onChange(settings.copy(childPhone = it.ifBlank { null })) },
            label = { Text(stringResource(R.string.parent_settings_child_phone, childName)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LibreLinkUpSection(viewModel: ParentSettingsViewModel, state: ParentSettingsUiState) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column {
        Text(stringResource(R.string.parent_settings_libre_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.parent_settings_libre_email)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.parent_settings_libre_password)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.connectLibreLinkUp(email, password) }, enabled = !state.loading) {
                Text(stringResource(R.string.parent_settings_libre_connect))
            }
            OutlinedButton(onClick = viewModel::testLibreLinkUp, enabled = !state.loading) {
                Text(stringResource(R.string.parent_settings_libre_test))
            }
        }
        state.libreMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun FamilySection(viewModel: ParentSettingsViewModel, state: ParentSettingsUiState) {
    Column {
        Text(stringResource(R.string.parent_settings_family_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = viewModel::generatePairingCode) {
            Text(stringResource(R.string.parent_settings_generate_code))
        }
        state.pairingCode?.let {
            Spacer(Modifier.height(8.dp))
            Text(it.chunked(3).joinToString(" "), style = MaterialTheme.typography.displaySmall)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = viewModel::leaveFamily, colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Text(stringResource(R.string.parent_settings_leave_family))
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        modifier = modifier,
    )
}

@Composable
private fun TextField121(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, modifier = modifier)
}
