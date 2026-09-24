package com.ingeint.checkin.ui.permissions

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService

/** Lecturas de permisos/estado del sistema (docs/06 "pantalla de diagnóstico"). */
object DevicePermissions {
    fun hasNotifications(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            context.getSystemService<NotificationManager>()?.areNotificationsEnabled() ?: true
        }

    fun hasExactAlarms(context: Context): Boolean {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return false
        return if (Build.VERSION.SDK_INT >= 31) manager.canScheduleExactAlarms() else true
    }

    fun isBatteryUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService<PowerManager>() ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun hasLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    fun hasSms(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    fun canBypassDnd(context: Context): Boolean {
        val manager = context.getSystemService<NotificationManager>() ?: return false
        return manager.isNotificationPolicyAccessGranted
    }

    fun canUseFullScreenIntent(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 34) {
            context.getSystemService<NotificationManager>()?.canUseFullScreenIntent() ?: true
        } else {
            true
        }

    fun ringerModeDescription(context: Context): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        return when (audio?.ringerMode) {
            android.media.AudioManager.RINGER_MODE_SILENT -> "silent"
            android.media.AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
    }
}
