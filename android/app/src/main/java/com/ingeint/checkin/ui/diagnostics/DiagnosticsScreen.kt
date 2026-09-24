package com.ingeint.checkin.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ingeint.checkin.R
import com.ingeint.checkin.notify.Haptics
import com.ingeint.checkin.notify.VibrationPattern
import com.ingeint.checkin.ui.permissions.DevicePermissions

/**
 * Pantalla de diagnóstico (docs/06): un check por permiso, el modo de timbre actual
 * y un botón para probar cada patrón de vibración (docs/01, docs/09 checklist de campo).
 */
@Composable
fun DiagnosticsScreen(role: String) {
    val context = LocalContext.current

    LazyColumn(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        item {
            Text(stringResource(R.string.diagnostics_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
        }

        item {
            DiagnosticRow(R.string.permissions_notifications, DevicePermissions.hasNotifications(context))
            DiagnosticRow(R.string.permissions_exact_alarms, DevicePermissions.hasExactAlarms(context))
            DiagnosticRow(R.string.permissions_battery, DevicePermissions.isBatteryUnrestricted(context))
            if (role == "child") {
                DiagnosticRow(R.string.permissions_location, DevicePermissions.hasLocation(context))
                DiagnosticRow(R.string.permissions_sms, DevicePermissions.hasSms(context))
            } else {
                DiagnosticRow(R.string.permissions_dnd, DevicePermissions.canBypassDnd(context))
                DiagnosticRow(R.string.permissions_full_screen, DevicePermissions.canUseFullScreenIntent(context))
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        }

        item {
            Text(stringResource(R.string.diagnostics_ring_mode_title), style = MaterialTheme.typography.titleMedium)
            Text(ringModeLabel(context))
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        }

        item {
            Text(stringResource(R.string.diagnostics_practice_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
        }

        items(practiceButtons()) { (labelRes, pattern) ->
            Button(
                onClick = { Haptics.vibrate(context, pattern) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) { Text(stringResource(labelRes)) }
        }
    }
}

@Composable
private fun DiagnosticRow(labelRes: Int, ok: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(labelRes))
        if (ok) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32))
        } else {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ringModeLabel(context: android.content.Context): String =
    when (DevicePermissions.ringerModeDescription(context)) {
        "silent" -> stringResource(R.string.diagnostics_ring_mode_silent)
        "vibrate" -> stringResource(R.string.diagnostics_ring_mode_vibrate)
        else -> stringResource(R.string.diagnostics_ring_mode_normal)
    }

private fun practiceButtons(): List<Pair<Int, LongArray>> =
    listOf(
        R.string.diagnostics_practice_reminder to VibrationPattern.REMINDER,
        R.string.diagnostics_practice_message to VibrationPattern.MESSAGE,
        R.string.diagnostics_practice_confirm to VibrationPattern.CONFIRM,
        R.string.diagnostics_practice_seen to VibrationPattern.SEEN,
        R.string.diagnostics_practice_tap to VibrationPattern.TAP,
    )
