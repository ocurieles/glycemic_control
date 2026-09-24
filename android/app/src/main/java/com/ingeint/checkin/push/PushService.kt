package com.ingeint.checkin.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.ingeint.checkin.CheckinApp
import com.ingeint.checkin.data.model.ReminderSettings
import com.ingeint.checkin.notify.ChildNotifier
import com.ingeint.checkin.notify.Haptics
import com.ingeint.checkin.notify.VibrationPattern
import com.ingeint.checkin.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TAG = "PushService"

/**
 * Pushes data-only (CLAUDE.md regla 8, docs/04 "Payloads"). La app construye las
 * notificaciones; aquí solo se despacha por `type`. La construcción real de las
 * notificaciones (ChildNotifier/ParentNotifier) llega en F3/F5 — por ahora estos
 * handlers hacen el mínimo necesario (cachear settings/mensajes) y dejan un TODO.
 */
class PushService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onNewToken(token: String) {
        val app = application as CheckinApp
        scope.launch {
            val uid = app.container.auth.currentUser?.uid ?: return@launch
            runCatching {
                app.container.firestore
                    .collection("users")
                    .document(uid)
                    .update(
                        mapOf(
                            "fcmToken" to token,
                            "tokenUpdatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                            "appVersion" to com.ingeint.checkin.BuildConfig.VERSION_NAME,
                        ),
                    )
                    .await()
            }.onFailure { Log.w(TAG, "no se pudo actualizar fcmToken", it) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val app = application as CheckinApp
        val data = message.data
        scope.launch {
            when (data["type"]) {
                // --- Niño ---
                "sync" -> refreshSettings(app)
                "nudge" -> {
                    // Redundancia por si el fabricante mató la alarma (docs/06). El
                    // "máximo un refuerzo por slot" real (con lastNudgedSlot) llega con
                    // el outbox en F4; por ahora siempre refuerza, igual que ACTION_NUDGE.
                    val slot = data["slot"].orEmpty()
                    Haptics.vibrate(this@PushService, VibrationPattern.REMINDER)
                    ChildNotifier.showReminder(this@PushService, slot)
                    app.container.prefs.saveLastNudgedSlot(slot)
                }
                "parent_message" -> {
                    val at = data["at"]?.toLongOrNull() ?: System.currentTimeMillis()
                    app.container.prefs.saveLastMessage(data["text"].orEmpty(), data["from"].orEmpty(), at)
                    // TODO(F5): ChildNotifier — vibración MESSAGE + notificación "Mensaje".
                }
                "sos_ack" -> {
                    val at = data["at"]?.toLongOrNull() ?: System.currentTimeMillis()
                    app.container.prefs.saveLastSosAckAt(at)
                    // TODO(F5): ChildNotifier — vibración SEEN + notificación.
                }
                // --- Padres ---
                "checkin", "checkin_late", "missed", "sos", "sos_glucose", "sos_ack_info", "day_summary" -> {
                    // TODO(F5): ParentNotifier construye la notificación según docs/04/docs/06.
                    Log.d(TAG, "push de padres type=${data["type"]}")
                }
                else -> Log.w(TAG, "tipo de push desconocido: ${data["type"]}")
            }
        }
    }

    private suspend fun refreshSettings(app: CheckinApp) {
        val familyId = app.container.prefs.familyId.first() ?: return
        runCatching {
            val snap = app.container.firestore.collection("families").document(familyId).get().await()
            @Suppress("UNCHECKED_CAST")
            val settingsMap = snap.get("settings") as? Map<String, Any?> ?: return@runCatching
            val settings = ReminderSettings.fromMap(settingsMap)
            app.container.prefs.saveSettings(settings)
            ReminderScheduler(app).scheduleNext(settings)
        }.onFailure { Log.w(TAG, "no se pudo refrescar settings tras push sync", it) }
    }
}
