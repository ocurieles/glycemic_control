package com.ingeint.checkin.ui.parent

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ingeint.checkin.R
import com.ingeint.checkin.ui.theme.brandButtonColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val ES_VE = Locale.Builder().setLanguage("es").setRegion("VE").build()
private val QUICK_CARBS = listOf(15, 30, 45, 60)

/** Comida con carbohidratos y Tresiba (pedido 2026-10-15). `initialKind`: "meal" o "basal". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(viewModel: LogViewModel, initialKind: String, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var kind by remember { mutableStateOf(if (initialKind == "basal") "basal" else "meal") }
    var carbsText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var units by remember(state.basalUnits) { mutableStateOf(state.basalUnits ?: 10) }
    var customAt by remember { mutableStateOf<Long?>(null) }
    var confirmBasal by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) {
        if (state.saved) {
            carbsText = ""
            note = ""
            customAt = null
            delay(2_000)
            viewModel.clearSaved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.parent_log_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
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
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = kind == "meal", onClick = { kind = "meal" }, label = { Text(stringResource(R.string.parent_log_meal)) })
                FilterChip(selected = kind == "basal", onClick = { kind = "basal" }, label = { Text(stringResource(R.string.parent_log_basal)) })
            }
            Spacer(Modifier.height(16.dp))

            if (kind == "meal") {
                OutlinedTextField(
                    value = carbsText,
                    onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) carbsText = it },
                    label = { Text(stringResource(R.string.parent_log_carbs)) },
                    suffix = { Text("g") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QUICK_CARBS.forEach { g ->
                        FilterChip(selected = carbsText == g.toString(), onClick = { carbsText = g.toString() }, label = { Text("$g g") })
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= 120) note = it },
                    label = { Text(stringResource(R.string.parent_log_note)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(stringResource(R.string.parent_log_basal_units), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FilledTonalButton(onClick = { if (units > 1) units-- }) { Text("−") }
                    Text("$units U", style = MaterialTheme.typography.headlineMedium)
                    FilledTonalButton(onClick = { if (units < 80) units++ }) { Text("+") }
                }
            }

            Spacer(Modifier.height(16.dp))
            DateTimeRow(customAt) { customAt = it }
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    if (kind == "meal") viewModel.saveMeal(carbsText.toIntOrNull() ?: 0, note, customAt) else confirmBasal = true
                },
                colors = brandButtonColors(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.parent_log_save)) }

            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            if (state.saved) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.parent_insulin_log_done), style = MaterialTheme.typography.titleMedium)
            }
        }
    }

    if (confirmBasal) {
        val whenText = formatWhen(customAt ?: System.currentTimeMillis())
        AlertDialog(
            onDismissRequest = { confirmBasal = false },
            title = { Text(stringResource(R.string.parent_log_confirm_title)) },
            text = { Text(stringResource(R.string.parent_log_confirm_basal, units, whenText)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmBasal = false
                        viewModel.saveBasal(units, customAt)
                    },
                ) { Text(stringResource(R.string.parent_log_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmBasal = false }) { Text(stringResource(R.string.parent_log_correct)) } },
        )
    }
}

private fun formatWhen(millis: Long): String =
    SimpleDateFormat("dd/MM/yyyy h:mm a", ES_VE).format(Date(millis))

@Composable
private fun DateTimeRow(customAt: Long?, onChange: (Long?) -> Unit) {
    val context = LocalContext.current
    val shown = customAt ?: System.currentTimeMillis()
    Column {
        Text(stringResource(R.string.parent_log_when), style = MaterialTheme.typography.bodySmall)
        Text(
            if (customAt == null) stringResource(R.string.parent_log_now_at, formatWhen(shown)) else formatWhen(shown),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    val c = Calendar.getInstance().apply { timeInMillis = shown }
                    DatePickerDialog(
                        context,
                        { _, y, m, d ->
                            onChange(Calendar.getInstance().apply { timeInMillis = shown; set(y, m, d) }.timeInMillis)
                        },
                        c.get(Calendar.YEAR),
                        c.get(Calendar.MONTH),
                        c.get(Calendar.DAY_OF_MONTH),
                    ).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
                },
            ) { Text(stringResource(R.string.parent_log_change_date)) }
            TextButton(
                onClick = {
                    val c = Calendar.getInstance().apply { timeInMillis = shown }
                    TimePickerDialog(
                        context,
                        { _, h, min ->
                            onChange(
                                Calendar.getInstance().apply {
                                    timeInMillis = shown
                                    set(Calendar.HOUR_OF_DAY, h)
                                    set(Calendar.MINUTE, min)
                                    set(Calendar.SECOND, 0)
                                }.timeInMillis,
                            )
                        },
                        c.get(Calendar.HOUR_OF_DAY),
                        c.get(Calendar.MINUTE),
                        false,
                    ).show()
                },
            ) { Text(stringResource(R.string.parent_log_change_time)) }
            if (customAt != null) TextButton(onClick = { onChange(null) }) { Text(stringResource(R.string.parent_log_use_now)) }
        }
    }
}
