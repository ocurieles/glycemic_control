package com.ingeint.checkin.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.ingeint.checkin.push.ParentActionReceiver
import com.ingeint.checkin.ui.MainActivity

/**
 * Notificaciones del rol padre (docs/04 "Payloads", docs/06 "Notificaciones de los
 * padres"). A diferencia del niño, aquí sí se puede usar sonido y pantalla completa.
 */
object ParentNotifier {
    fun notifyCheckin(context: Context, eventId: String, title: String, body: String, alert: Boolean) {
        val channel = if (alert) ChannelIds.PARENT_ALERT else ChannelIds.PARENT_CHECKIN
        val notification =
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_menu_myplaces)
                .setContentTitle(title)
                .setContentText(body)
                .setGroup("checkins")
                .setAutoCancel(true)
                .setContentIntent(openAppPendingIntent(context, eventId.hashCode()))
                .build()
        context.getSystemService<NotificationManager>()?.notify(eventId.hashCode(), notification)
    }

    fun notifyAlert(context: Context, id: String, title: String, body: String) {
        val notification =
            NotificationCompat.Builder(context, ChannelIds.PARENT_ALERT)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(openAppPendingIntent(context, id.hashCode()))
                .build()
        context.getSystemService<NotificationManager>()?.notify(id.hashCode(), notification)
    }

    /** SOS (docs/01 H5, docs/06): sonido de alarma, pantalla completa, bypassa DND. */
    fun notifySos(
        context: Context,
        eventId: String,
        title: String,
        body: String,
        lat: Double?,
        lng: Double?,
    ) {
        val fullScreenIntent =
            PendingIntent.getActivity(
                context,
                eventId.hashCode(),
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val ackIntent =
            Intent(context, ParentActionReceiver::class.java).apply {
                action = ParentActionReceiver.ACTION_SOS_ACK
                putExtra(ParentActionReceiver.EXTRA_SOS_EVENT_ID, eventId)
            }
        val ackPendingIntent =
            PendingIntent.getBroadcast(
                context,
                eventId.hashCode() + 1,
                ackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val builder =
            NotificationCompat.Builder(context, ChannelIds.PARENT_SOS)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setFullScreenIntent(fullScreenIntent, true)
                .setAutoCancel(false)
                .addAction(0, "Voy en camino", ackPendingIntent)

        if (lat != null && lng != null) {
            val locationIntent =
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng"))
            val locationPendingIntent =
                PendingIntent.getActivity(
                    context,
                    eventId.hashCode() + 2,
                    locationIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            builder.addAction(0, "Ver ubicación", locationPendingIntent)
        }

        context.getSystemService<NotificationManager>()?.notify(eventId.hashCode(), builder.build())
    }

    fun cancelSos(context: Context, eventId: String) {
        context.getSystemService<NotificationManager>()?.cancel(eventId.hashCode())
    }

    private fun openAppPendingIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
