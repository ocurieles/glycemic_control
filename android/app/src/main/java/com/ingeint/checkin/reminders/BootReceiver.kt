package com.ingeint.checkin.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ingeint.checkin.CheckinApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Reprograma la alarma tras reiniciar el teléfono, actualizar la app o cambiar la hora
 * (docs/06 "BootReceiver"). Solo actúa si el teléfono ya está vinculado como niño.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as CheckinApp
        val pendingResult = goAsync()
        app.applicationScope.launch {
            try {
                if (app.container.prefs.role.first() != "child") return@launch
                val settings = app.container.prefs.settings.first() ?: return@launch
                ReminderScheduler(context).scheduleNext(settings)
                // F4: aquí también se encola el SyncWorker.
            } finally {
                pendingResult.finish()
            }
        }
    }
}
