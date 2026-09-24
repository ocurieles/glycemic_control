package com.ingeint.checkin

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import androidx.core.content.getSystemService
import com.ingeint.checkin.notify.Channels
import com.ingeint.checkin.push.syncFcmToken
import com.ingeint.checkin.reminders.ReminderScheduler
import com.ingeint.checkin.sync.SyncWorker
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

            if (role != null) {
                SyncWorker.enqueue(this@CheckinApp)
                SyncWorker.enqueuePeriodic(this@CheckinApp) // respaldo cada 15 min (docs/07)
                // Reintenta guardar el token FCM en cada arranque: cubre los teléfonos ya
                // vinculados antes de este fix (ver push/FcmTokenSync.kt) y cualquier caso
                // donde el token haya cambiado sin que onNewToken se haya podido guardar.
                syncFcmToken(container)
            }
        }

        registerNetworkCallback()
    }

    /** Encola el outbox al volver la red, mientras la app vive (docs/07 "SyncWorker"). */
    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService<ConnectivityManager>() ?: return
        val request = NetworkRequest.Builder().addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        connectivityManager.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    SyncWorker.enqueue(this@CheckinApp)
                }
            },
        )
    }
}
