package com.ingeint.checkin

import android.app.Application
import com.ingeint.checkin.notify.Channels
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
            container.prefs.role.first()?.let { role -> Channels.createForRole(this@CheckinApp, role) }
        }
    }
}
