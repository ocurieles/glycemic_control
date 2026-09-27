package com.ingeint.checkin.ui.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ingeint.checkin.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val ES_VE = Locale.Builder().setLanguage("es").setRegion("VE").build()

/** Calendario de insulina (docs/01, pedido 2026-09-27): total por día, detalle al tocar uno. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsulinScreen(viewModel: InsulinViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.yearMonth.month.getDisplayName(TextStyle.FULL, ES_VE).replaceFirstChar { it.uppercase() } +
                            " ${state.yearMonth.year}",
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { viewModel.loadMonth(state.yearMonth.minusMonths(1)) }) {
                    Text(stringResource(R.string.parent_insulin_prev_month))
                }
                TextButton(onClick = { viewModel.loadMonth(state.yearMonth.plusMonths(1)) }) {
                    Text(stringResource(R.string.parent_insulin_next_month))
                }
            }
            Spacer(Modifier.height(8.dp))
            WeekdayHeader()
            Spacer(Modifier.height(4.dp))
            MonthGrid(state.yearMonth, state.dailyTotals, onDayClick = viewModel::selectDay)
        }
    }

    state.selectedDate?.let { dateKey ->
        DayDetailDialog(
            dateKey = dateKey,
            doses = state.selectedDoses,
            onDismiss = viewModel::clearSelection,
        )
    }
}

@Composable
private fun WeekdayHeader() {
    val labels = listOf(
        R.string.parent_settings_day_mon, R.string.parent_settings_day_tue, R.string.parent_settings_day_wed,
        R.string.parent_settings_day_thu, R.string.parent_settings_day_fri, R.string.parent_settings_day_sat,
        R.string.parent_settings_day_sun,
    )
    Row(modifier = Modifier.fillMaxWidth()) {
        labels.forEach { label ->
            Text(
                stringResource(label),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonthGrid(yearMonth: YearMonth, dailyTotals: Map<String, Double>, onDayClick: (LocalDate) -> Unit) {
    val firstDay = yearMonth.atDay(1)
    val leadingBlanks = firstDay.dayOfWeek.value - DayOfWeek.MONDAY.value // 0..6
    val daysInMonth = yearMonth.lengthOfMonth()
    val totalCells = leadingBlanks + daysInMonth
    val rows = (totalCells + 6) / 7
    val dateKeyFormatter = remember { DateTimeFormatter.ISO_LOCAL_DATE }
    val today = remember { LocalDate.now() }

    Column {
        for (row in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val cellIndex = row * 7 + col
                    val dayNumber = cellIndex - leadingBlanks + 1
                    if (dayNumber in 1..daysInMonth) {
                        val date = yearMonth.atDay(dayNumber)
                        val dateKey = date.format(dateKeyFormatter)
                        val total = dailyTotals[dateKey]
                        DayCell(
                            dayNumber = dayNumber,
                            total = total,
                            isToday = date == today,
                            modifier = Modifier.weight(1f).clickable { onDayClick(date) },
                        )
                    } else {
                        Column(modifier = Modifier.weight(1f)) {}
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(dayNumber: Int, total: Double?, isToday: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .aspectRatio(1f)
                .padding(2.dp)
                .background(
                    if (isToday) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(8.dp),
                )
                .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(dayNumber.toString(), style = MaterialTheme.typography.bodySmall)
        total?.let {
            Text("$it U", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun DayDetailDialog(dateKey: String, doses: List<InsulinDoseEntry>, onDismiss: () -> Unit) {
    val timeFormatter = remember { SimpleDateFormatWrapper() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dateKey) },
        text = {
            Column {
                if (doses.isEmpty()) {
                    Text(stringResource(R.string.parent_insulin_no_doses), style = MaterialTheme.typography.bodyMedium)
                } else {
                    doses.forEach { dose ->
                        Text(
                            "${timeFormatter.format(dose.atMillis)} — ${dose.units} U",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.parent_insulin_total, doses.sumOf { it.units }),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.child_quick_log_cancel)) } },
    )
}

private class SimpleDateFormatWrapper {
    private val formatter = java.text.SimpleDateFormat("h:mm a", ES_VE)
    fun format(millis: Long): String = formatter.format(java.util.Date(millis))
}
