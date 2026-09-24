package com.ingeint.checkin.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.ingeint.checkin.CheckinApp
import com.ingeint.checkin.data.model.ReminderSettings
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
                    // TODO(F3): si no hubo revisión ni refuerzo local para el slot, vibrar
                    // REMINDER y mostrar la notificación (ver ReminderReceiver.ACTION_NUDGE).
                    Log.d(TAG, "nudge recibido para slot=${data["slot"]}")
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
            app.container.prefs.saveSettings(ReminderSettings.fromMap(settingsMap))
            // TODO(F3): ReminderScheduler.scheduleNext() con los settings nuevos.
        }.onFailure { Log.w(TAG, "no se pudo refrescar settings tras push sync", it) }
    }
}
