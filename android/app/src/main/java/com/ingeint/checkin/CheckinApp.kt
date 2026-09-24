package com.ingeint.checkin

import android.app.Application
import com.ingeint.checkin.notify.Channels
import com.ingeint.checkin.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class CheckinApp : Application() {
    lateinit var container: AppContainer
        private set

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        applicationScope.launch {
            // Auth anónima persistente (docs/02 D9): si ya hay sesión, Firebase la reutiliza sola.
            if (container.auth.currentUser == null) {
                runCatching { container.auth.signInAnonymously().await() }
            }

            // Si el teléfono ya estaba vinculado (reinicio de la app), recreamos sus canales:
            // createNotificationChannels() es idempotente, así que esto es seguro en cada arranque.
            val role = container.prefs.role.first()
            role?.let { Channels.createForRole(this@CheckinApp, it) }

            // El niño reprograma su alarma en cada arranque de la app, no solo tras
            // reiniciar el teléfono (docs/06): más barato que esperar a BootReceiver.
            if (role == "child") {
                container.prefs.settings.first()?.let { settings ->
                    ReminderScheduler(this@CheckinApp).scheduleNext(settings)
                }
            }
        }
    }
}
