package com.ingeint.checkin.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ingeint.checkin.CheckinApp
import com.ingeint.checkin.sync.SyncWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Tras reiniciar el teléfono, actualizar la app o cambiar la hora (docs/06
 * "BootReceiver"): el niño reprograma su alarma y, en ambos roles, se encola el
 * outbox pendiente (docs/07).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as CheckinApp
        val pendingResult = goAsync()
        app.applicationScope.launch {
            try {
                val role = app.container.prefs.role.first() ?: return@launch
                if (role == "child") {
                    app.container.prefs.settings.first()?.let { settings ->
                        ReminderScheduler(context).scheduleNext(settings)
                    }
                }
                SyncWorker.enqueue(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
