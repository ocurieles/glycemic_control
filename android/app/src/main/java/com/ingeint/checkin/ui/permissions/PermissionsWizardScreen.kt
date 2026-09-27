package com.ingeint.checkin.ui.permissions

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ingeint.checkin.R

private data class PermissionItem(
    val titleRes: Int,
    val descRes: Int,
    val isGranted: (android.content.Context) -> Boolean,
    val request: (android.content.Context, () -> Unit) -> Unit,
)

/**
 * Asistente de permisos (docs/06 Setup paso 4). Se muestra una sola vez tras
 * vincular; también se puede reabrir desde Diagnóstico si algo quedó pendiente.
 */
@Composable
fun PermissionsWizardScreen(role: String, onContinue: () -> Unit) {
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    var autoStartOpened by remember { mutableStateOf(false) }
    val manufacturerAutoStartIntent = remember { DevicePermissions.manufacturerAutoStartIntent(context) }

    val notificationsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshTick++ }
    val locationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshTick++ }
    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshTick++ }
    val backgroundLocationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshTick++ }
    val settingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshTick++ }

    val items =
        remember(role) {
            buildList {
                add(
                    PermissionItem(
                        R.string.permissions_notifications,
                        R.string.permissions_notifications_desc,
                        DevicePermissions::hasNotifications,
                    ) { ctx, _ ->
                        if (Build.VERSION.SDK_INT >= 33) {
                            notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            settingsLauncher.launch(appNotificationSettingsIntent(ctx))
                        }
                    },
                )
                add(
                    PermissionItem(
                        R.string.permissions_exact_alarms,
                        R.string.permissions_exact_alarms_desc,
                        DevicePermissions::hasExactAlarms,
                    ) { ctx, _ ->
                        settingsLauncher.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                    },
                )
                add(
                    PermissionItem(
                        R.string.permissions_battery,
                        R.string.permissions_battery_desc,
                        DevicePermissions::isBatteryUnrestricted,
                    ) { ctx, _ ->
                        settingsLauncher.launch(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
                        )
                    },
                )
                manufacturerAutoStartIntent?.let { intent ->
                    add(
                        PermissionItem(
                            R.string.permissions_autostart,
                            R.string.permissions_autostart_desc,
                            { autoStartOpened }, // Android no expone si ya se activó: se marca "listo" al abrir el ajuste.
                        ) { ctx, _ ->
                            ctx.startActivity(intent)
                            autoStartOpened = true
                        },
                    )
                }
                if (role == "child") {
                    add(
                        PermissionItem(
                            R.string.permissions_location,
                            R.string.permissions_location_desc,
                            DevicePermissions::hasLocation,
                        ) { _, _ ->
                            locationLauncher.launch(
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                            )
                        },
                    )
                    add(
                        PermissionItem(
                            R.string.permissions_sms,
                            R.string.permissions_sms_desc,
                            DevicePermissions::hasSms,
                        ) { _, _ -> smsLauncher.launch(Manifest.permission.SEND_SMS) },
                    )
                    if (Build.VERSION.SDK_INT >= 29) {
                        add(
                            PermissionItem(
                                R.string.permissions_location_background,
                                R.string.permissions_location_background_desc,
                                DevicePermissions::hasBackgroundLocation,
                            ) { ctx, _ ->
                                if (DevicePermissions.hasLocation(ctx)) {
                                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                } else {
                                    locationLauncher.launch(
                                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                                    )
                                }
                            },
                        )
                    }
                } else {
                    add(
                        PermissionItem(
                            R.string.permissions_dnd,
                            R.string.permissions_dnd_desc,
                            DevicePermissions::canBypassDnd,
                        ) { _, _ -> settingsLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
                    )
                    add(
                        PermissionItem(
                            R.string.permissions_full_screen,
                            R.string.permissions_full_screen_desc,
                            DevicePermissions::canUseFullScreenIntent,
                        ) { ctx, _ ->
                            if (Build.VERSION.SDK_INT >= 34) {
                                settingsLauncher.launch(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                        Uri.parse("package:${ctx.packageName}"),
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.permissions_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        items.forEach { item ->
            // refreshTick fuerza recomposición tras volver de un launcher del sistema.
            @Suppress("UNUSED_EXPRESSION") refreshTick
            val granted = item.isGranted(context)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.padding(end = 8.dp)) {
                    Text(stringResource(item.titleRes), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(item.descRes), style = MaterialTheme.typography.bodySmall)
                }
                if (granted) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32))
                } else {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { item.request(context) { refreshTick++ } }) {
                        Text(stringResource(R.string.permissions_grant))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.permissions_continue))
        }
        TextButton(onClick = onContinue) { Text(stringResource(R.string.permissions_skip)) }
    }
}

private fun appNotificationSettingsIntent(context: android.content.Context) =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
