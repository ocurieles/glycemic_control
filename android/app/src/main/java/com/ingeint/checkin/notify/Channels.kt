package com.ingeint.checkin.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import androidx.core.content.getSystemService

/** IDs de canal (docs/06 "Canales de notificación"). */
object ChannelIds {
    const val CHILD_REMINDER = "child_reminder"
    const val CHILD_MESSAGE = "child_message"
    const val SYNC_STATUS = "sync_status"
    const val PARENT_CHECKIN = "parent_checkin"
    const val PARENT_ALERT = "parent_alert"
    const val PARENT_SOS = "parent_sos"
}

/**
 * Crea SOLO los canales del rol activo (+ `sync_status`), al vincular el teléfono
 * (docs/06). CLAUDE.md regla 2: los canales `child_*` se crean SIEMPRE con
 * `setSound(null, null)` — la vibración la maneja [Haptics], nunca el canal.
 */
object Channels {
    fun createForRole(context: Context, role: String) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        val channels =
            buildList {
                when (role) {
                    "child" -> {
                        add(childReminder())
                        add(childMessage())
                    }
                    "parent" -> {
                        add(parentCheckin())
                        add(parentAlert())
                        add(parentSos(context))
                    }
                }
                add(syncStatus())
            }
        manager.createNotificationChannels(channels)
    }

    /** Al desvincular el teléfono (docs/06: "Al desvincularlo se borran"). */
    fun deleteAll(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        listOf(
            ChannelIds.CHILD_REMINDER,
            ChannelIds.CHILD_MESSAGE,
            ChannelIds.SYNC_STATUS,
            ChannelIds.PARENT_CHECKIN,
            ChannelIds.PARENT_ALERT,
            ChannelIds.PARENT_SOS,
        ).forEach(manager::deleteNotificationChannel)
    }

    private fun childReminder() =
        NotificationChannel(ChannelIds.CHILD_REMINDER, "Recordatorio", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false) // la vibración la maneja Haptics, no el canal
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }

    private fun childMessage() =
        NotificationChannel(ChannelIds.CHILD_MESSAGE, "Mensaje", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }

    private fun syncStatus() =
        NotificationChannel(ChannelIds.SYNC_STATUS, "Sincronización", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
        }

    private fun parentCheckin() =
        NotificationChannel(ChannelIds.PARENT_CHECKIN, "Revisiones", NotificationManager.IMPORTANCE_DEFAULT)

    private fun parentAlert() =
        NotificationChannel(ChannelIds.PARENT_ALERT, "Alertas", NotificationManager.IMPORTANCE_HIGH)

    private fun parentSos(context: Context) =
        NotificationChannel(ChannelIds.PARENT_SOS, "SOS", NotificationManager.IMPORTANCE_HIGH).apply {
            val alarmSound = android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
            setSound(
                alarmSound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = VibrationPattern.CONFIRM
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
}
