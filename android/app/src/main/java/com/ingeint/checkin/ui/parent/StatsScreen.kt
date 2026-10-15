package com.ingeint.checkin.ui.parent

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ingeint.checkin.R
import kotlinx.coroutines.launch

private val C_LOW = Color(0xFFD03B3B)
private val C_OK = Color(0xFF0CA30C)
private val C_HIGH = Color(0xFFFAB219)
private val C_VERY_HIGH = Color(0xFFEC835A)
private val NAVY = Color(0xFF05213E)
private val ORANGE = Color(0xFFF05A3A)
private const val CHART_MAX_MG_DL = 300f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(viewModel: StatsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.parent_stats_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                val file = viewModel.createPdf() ?: return@launch
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val send =
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "application/pdf"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                context.startActivity(Intent.createChooser(send, null))
                            }
                        },
                    ) { Icon(Icons.Filled.PictureAsPdf, contentDescription = stringResource(R.string.parent_stats_share_pdf)) }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 14, 30).forEach { days ->
                    FilterChip(
                        selected = state.periodDays == days,
                        onClick = { viewModel.setPeriod(days) },
                        label = { Text(stringResource(R.string.parent_stats_period_days, days)) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            val stats = state.stats
            when {
                state.loading -> CircularProgressIndicator()
                state.error -> Text(stringResource(R.string.parent_stats_error), color = MaterialTheme.colorScheme.error)
                stats != null -> StatsContent(stats)
            }
        }
    }
}

@Composable
private fun StatsContent(s: StatsResult) {
    Text(stringResource(R.string.parent_stats_glucose), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    val g = s.glucose
    if (g == null) {
        Text(stringResource(R.string.parent_stats_no_glucose), style = MaterialTheme.typography.bodyMedium)
    } else {
        MetricRow(
            listOf(
                Triple(stringResource(R.string.parent_stats_mean), g.mean.toString(), "mg/dL"),
                Triple(stringResource(R.string.parent_stats_min_max), "${g.min}–${g.max}", "mg/dL"),
                Triple(stringResource(R.string.parent_stats_variation), "±${g.sd}", "mg/dL"),
            ),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.parent_stats_time_in_range, s.lowThreshold, s.highThreshold),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(6.dp))
        RangeBar(g)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(
                R.string.parent_stats_range_legend,
                g.lowPct.toInt(),
                g.inRangePct.toInt(),
                g.highPct.toInt(),
                g.veryHighPct.toInt(),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.parent_stats_by_hour), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        HourChart(s)
    }

    Spacer(Modifier.height(24.dp))
    Text(stringResource(R.string.parent_stats_insulin), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    MetricRow(
        listOf(
            Triple(stringResource(R.string.parent_stats_daily_avg), formatOneDecimal(s.avgDailyUnits), "U"),
            Triple(stringResource(R.string.parent_stats_doses_per_day), formatOneDecimal(s.dosesPerDay), ""),
            Triple(stringResource(R.string.parent_stats_total), formatOneDecimal(s.totalUnits), "U"),
        ),
    )
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.parent_stats_units_per_day), style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(6.dp))
    DayChart(s)

    s.response?.let { r ->
        Spacer(Modifier.height(24.dp))
        Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.parent_stats_response_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.parent_stats_response_body, r.avgBefore, r.avgAfter, r.avgDelta, r.pairs, r.avgMinutes),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.parent_stats_footnote, g?.count ?: 0),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MetricRow(items: List<Triple<String, String, String>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        items.forEach { (label, value, unit) ->
            Card(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    if (unit.isNotEmpty()) Text(unit, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun RangeBar(g: GlucoseStats) {
    Row(
        modifier = Modifier.fillMaxWidth().height(22.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(g.lowPct to C_LOW, g.inRangePct to C_OK, g.highPct to C_HIGH, g.veryHighPct to C_VERY_HIGH).forEach { (pct, color) ->
            if (pct > 0.0) Box(modifier = Modifier.weight(pct.toFloat()).height(22.dp).background(color, RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
private fun HourChart(s: StatsResult) {
    val byHour = s.hourly.associateBy { it.hour }
    Row(modifier = Modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.Bottom) {
        for (h in 0 until 24) {
            val p = byHour[h]
            Box(modifier = Modifier.weight(1f).padding(horizontal = 1.dp), contentAlignment = Alignment.BottomCenter) {
                if (p != null) {
                    val color =
                        when {
                            p.mean < s.lowThreshold -> C_LOW
                            p.mean <= s.highThreshold -> C_OK
                            p.mean <= 250 -> C_HIGH
                            else -> C_VERY_HIGH
                        }
                    Box(
                        modifier =
                            Modifier.fillMaxWidth()
                                .height((p.mean / CHART_MAX_MG_DL * 92f).coerceIn(2f, 92f).dp)
                                .background(color, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
                    )
                }
            }
        }
    }
    AxisLabels((0 until 24).map { if (it % 6 == 0) it.toString() else "" })
}

@Composable
private fun DayChart(s: StatsResult) {
    val max = (s.days.maxOfOrNull { it.units } ?: 0.0).coerceAtLeast(1.0)
    Row(modifier = Modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.Bottom) {
        s.days.forEach { d ->
            Box(modifier = Modifier.weight(1f).padding(horizontal = 1.dp), contentAlignment = Alignment.BottomCenter) {
                if (d.units > 0.0) {
                    Box(
                        modifier =
                            Modifier.fillMaxWidth()
                                .height((d.units / max * 92.0).toFloat().dp)
                                .background(if (d.units == max) ORANGE else NAVY, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
                    )
                }
            }
        }
    }
    val step = if (s.days.size > 14) 5 else 1
    AxisLabels(s.days.mapIndexed { i, d -> if (i % step == 0) d.date.dayOfMonth.toString() else "" })
}

@Composable
private fun AxisLabels(labels: List<String>) {
    Row(modifier = Modifier.fillMaxWidth()) {
        labels.forEach {
            Text(
                it,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
