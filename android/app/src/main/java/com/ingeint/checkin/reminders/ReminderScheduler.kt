package com.ingeint.checkin.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ingeint.checkin.data.model.ReminderSettings

private const val REQUEST_CODE_REMINDER = 1001
private const val REQUEST_CODE_NUDGE = 1002

/**
 * Programa las alarmas locales del niño (docs/06 "Recordatorios"). Funcionan sin
 * internet: no dependen de ningún push del servidor (docs/02 D4).
 */
class ReminderScheduler(private val context: Context) {
    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Calcula `nextSlot(now)` con los settings dados y programa la alarma. */
    fun scheduleNext(settings: ReminderSettings) {
        val slot = ReminderSchedule.nextSlot(System.currentTimeMillis(), settings) ?: return
        val intent =
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_REMINDER
                putExtra(ReminderReceiver.EXTRA_SLOT_HHMM, slot.hhmm)
                putExtra(ReminderReceiver.EXTRA_SLOT_DATE_KEY, slot.dateKey)
            }
        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_REMINDER,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        setExactOrFallback(slot.epochMillis, pendingIntent)
    }

    /** Refuerzo a `now + nudgeMinutes` (docs/01: por defecto 3 min, 0 = sin refuerzo). */
    fun scheduleNudge(settings: ReminderSettings, slotHhmm: String, slotDateKey: String) {
        if (settings.nudgeMinutes <= 0) return
        val intent =
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_NUDGE
                putExtra(ReminderReceiver.EXTRA_SLOT_HHMM, slotHhmm)
                putExtra(ReminderReceiver.EXTRA_SLOT_DATE_KEY, slotDateKey)
            }
        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_NUDGE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val triggerAt = System.currentTimeMillis() + settings.nudgeMinutes * 60_000L
        setExactOrFallback(triggerAt, pendingIntent)
    }

    fun cancelNudge() {
        val intent = Intent(context, ReminderReceiver::class.java).apply { action = ReminderReceiver.ACTION_NUDGE }
        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_NUDGE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        alarmManager.cancel(pendingIntent)
    }

    private fun setExactOrFallback(triggerAtMillis: Long, pendingIntent: PendingIntent) {
        val canExact = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            // Sin el permiso de alarmas exactas: la UI de diagnóstico muestra la advertencia
            // (docs/06). El recordatorio puede llegar con retraso por Doze.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }
}
