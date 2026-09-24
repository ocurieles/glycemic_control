package com.ingeint.checkin.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.ingeint.checkin.R
import com.ingeint.checkin.reminders.ReminderReceiver

private const val REMINDER_NOTIFICATION_ID = 2001

/**
 * Construye las notificaciones del rol niño (docs/06 "Notificaciones del niño").
 * CLAUDE.md regla 3: título siempre neutro ("Recordatorio"/"Mensaje"), nunca menciona
 * glucosa/diabetes/etc. `VISIBILITY_PRIVATE` + `publicVersion` para la pantalla de bloqueo.
 */
object ChildNotifier {
    fun showReminder(context: Context, slotHhmm: String) {
        val publicVersion =
            NotificationCompat.Builder(context, ChannelIds.CHILD_REMINDER)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(context.getString(R.string.notification_reminder_title))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()

        val ackIntent =
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_ACK
                putExtra(ReminderReceiver.EXTRA_SLOT_HHMM, slotHhmm)
            }
        val ackPendingIntent =
            PendingIntent.getBroadcast(
                context,
                REMINDER_NOTIFICATION_ID,
                ackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            NotificationCompat.Builder(context, ChannelIds.CHILD_REMINDER)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(context.getString(R.string.notification_reminder_title))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setAutoCancel(true)
                .addAction(0, context.getString(R.string.permissions_granted), ackPendingIntent)
                .build()

        context.getSystemService<NotificationManager>()?.notify(REMINDER_NOTIFICATION_ID, notification)
    }

    fun cancelReminder(context: Context) {
        context.getSystemService<NotificationManager>()?.cancel(REMINDER_NOTIFICATION_ID)
    }
}
