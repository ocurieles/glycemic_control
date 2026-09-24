package com.ingeint.checkin.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ingeint.checkin.CheckinApp
import com.ingeint.checkin.data.local.OutboxEventType
import com.ingeint.checkin.data.model.ReminderSettings
import com.ingeint.checkin.notify.ChildNotifier
import com.ingeint.checkin.notify.Haptics
import com.ingeint.checkin.notify.VibrationPattern
import com.ingeint.checkin.sync.SyncWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Recibe las alarmas locales del niño (docs/06). `ACTION_REMINDER` siempre reprograma
 * la siguiente alarma (`scheduleNext`), pase lo que pase. Omite el aviso si el outbox
 * ya tiene una revisión asignada a ese slot (docs/06, vía `assign()` de F4).
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
                    ACTION_NUDGE ->
                        onNudge(
                            context,
                            app,
                            intent.getStringExtra(EXTRA_SLOT_HHMM),
                            intent.getStringExtra(EXTRA_SLOT_DATE_KEY),
                        )
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
            val alreadyChecked =
                slotHhmm != null && slotDateKey != null &&
                    app.container.outboxRepository.hasCheckinForSlot(slotDateKey, slotHhmm, settings)
            if (!alreadyChecked) {
                Haptics.vibrate(context, VibrationPattern.REMINDER)
                ChildNotifier.showReminder(context, slotHhmm ?: "")
                if (slotHhmm != null && slotDateKey != null) {
                    ReminderScheduler(context).scheduleNudge(settings, slotHhmm, slotDateKey)
                }
            }
        } finally {
            // Siempre reprograma el siguiente, pase lo que pase arriba (docs/06).
            ReminderScheduler(context).scheduleNext(settings)
        }
    }

    private suspend fun onNudge(context: Context, app: CheckinApp, slotHhmm: String?, slotDateKey: String?) {
        val settings = app.container.prefs.settings.first() ?: ReminderSettings()
        val alreadyChecked =
            slotHhmm != null && slotDateKey != null &&
                app.container.outboxRepository.hasCheckinForSlot(slotDateKey, slotHhmm, settings)
        if (alreadyChecked) return
        Haptics.vibrate(context, VibrationPattern.REMINDER)
        ChildNotifier.showReminder(context, slotHhmm ?: "")
        app.container.prefs.saveLastNudgedSlot(slotHhmm ?: "")
    }

    /** Acción "Listo" de la notificación (docs/06: mismo flujo que el botón de la app). */
    private suspend fun onAck(context: Context, app: CheckinApp) {
        ChildNotifier.cancelReminder(context)
        ReminderScheduler(context).cancelNudge()

        val clientAt = app.container.prefs.correctedNowMillis()
        val offset = app.container.prefs.clockOffsetMs.first()
        app.container.outboxRepository.record(
            type = OutboxEventType.CHECKIN,
            clientAtMillis = clientAt,
            clockOffsetMs = offset,
            source = "notification",
        )
        SyncWorker.enqueue(context)
    }
}
