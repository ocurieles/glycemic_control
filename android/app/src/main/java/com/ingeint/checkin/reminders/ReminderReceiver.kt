package com.ingeint.checkin.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ingeint.checkin.CheckinApp
import com.ingeint.checkin.data.model.ReminderSettings
import com.ingeint.checkin.notify.ChildNotifier
import com.ingeint.checkin.notify.Haptics
import com.ingeint.checkin.notify.VibrationPattern
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "ReminderReceiver"

/**
 * Recibe las alarmas locales del niño (docs/06). `ACTION_REMINDER` siempre reprograma
 * la siguiente alarma (`scheduleNext`), pase lo que pase. La omisión de un slot ya
 * cubierto por una revisión (vía outbox) llega en F4; por ahora siempre notifica.
 */
class ReminderReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_REMINDER = "com.ingeint.checkin.action.REMINDER"
        const val ACTION_NUDGE = "com.ingeint.checkin.action.NUDGE"
        const val ACTION_ACK = "com.ingeint.checkin.action.ACK"
        const val EXTRA_SLOT_HHMM = "slot_hhmm"
        const val EXTRA_SLOT_DATE_KEY = "slot_date_key"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as CheckinApp
        val pendingResult = goAsync()
        app.applicationScope.launch {
            try {
                when (intent.action) {
                    ACTION_REMINDER ->
                        onReminder(
                            context,
                            app,
                            intent.getStringExtra(EXTRA_SLOT_HHMM),
                            intent.getStringExtra(EXTRA_SLOT_DATE_KEY),
                        )
                    ACTION_NUDGE -> onNudge(context, app, intent.getStringExtra(EXTRA_SLOT_HHMM))
                    ACTION_ACK -> onAck(context, app)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun onReminder(context: Context, app: CheckinApp, slotHhmm: String?, slotDateKey: String?) {
        val settings = app.container.prefs.settings.first() ?: ReminderSettings()
        try {
            Haptics.vibrate(context, VibrationPattern.REMINDER)
            ChildNotifier.showReminder(context, slotHhmm ?: "")
            if (slotHhmm != null && slotDateKey != null) {
                ReminderScheduler(context).scheduleNudge(settings, slotHhmm, slotDateKey)
            }
        } finally {
            // Siempre reprograma el siguiente, pase lo que pase arriba (docs/06).
            ReminderScheduler(context).scheduleNext(settings)
        }
    }

    private suspend fun onNudge(context: Context, app: CheckinApp, slotHhmm: String?) {
        val settings = app.container.prefs.settings.first() ?: ReminderSettings()
        Haptics.vibrate(context, VibrationPattern.REMINDER)
        ChildNotifier.showReminder(context, slotHhmm ?: "")
        app.container.prefs.saveLastNudgedSlot(slotHhmm ?: "")
    }

    private suspend fun onAck(context: Context, app: CheckinApp) {
        // F4 agrega aquí OutboxRepository.record(checkin). Por ahora solo se cancela
        // la notificación y el refuerzo (docs/08 F3).
        Log.i(TAG, "Ack de recordatorio recibido (outbox llega en F4)")
        ChildNotifier.cancelReminder(context)
        ReminderScheduler(context).cancelNudge()
    }
}
