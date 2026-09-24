package com.ingeint.checkin.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.ingeint.checkin.CheckinApp
import com.ingeint.checkin.data.model.ReminderSettings
import com.ingeint.checkin.notify.ChildNotifier
import com.ingeint.checkin.notify.Haptics
import com.ingeint.checkin.notify.ParentNotifier
import com.ingeint.checkin.notify.VibrationPattern
import com.ingeint.checkin.reminders.ReminderScheduler
import com.ingeint.checkin.reminders.ReminderTime
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
                    // Redundancia por si el fabricante mató la alarma (docs/06): se ignora
                    // si el refuerzo local ya ocurrió o si ya hay una revisión asignada.
                    val slot = data["slot"].orEmpty()
                    val settings = app.container.prefs.settings.first()
                    val alreadyNudged = app.container.prefs.lastNudgedSlot.first() == slot
                    val alreadyChecked =
                        settings != null && slot.isNotEmpty() &&
                            app.container.outboxRepository.hasCheckinForSlot(
                                ReminderTime.dateKeyOf(System.currentTimeMillis(), settings.timezone),
                                slot,
                                settings,
                            )
                    if (!alreadyNudged && !alreadyChecked) {
                        Haptics.vibrate(this@PushService, VibrationPattern.REMINDER)
                        ChildNotifier.showReminder(this@PushService, slot)
                        app.container.prefs.saveLastNudgedSlot(slot)
                    }
                }
                "parent_message" -> {
                    val at = data["at"]?.toLongOrNull() ?: System.currentTimeMillis()
                    app.container.prefs.saveLastMessage(data["text"].orEmpty(), data["senderName"].orEmpty(), at)
                    Haptics.vibrate(this@PushService, VibrationPattern.MESSAGE)
                    ChildNotifier.showMessage(this@PushService, data["text"].orEmpty())
                }
                "sos_ack" -> {
                    val at = data["at"]?.toLongOrNull() ?: System.currentTimeMillis()
                    app.container.prefs.saveLastSosAckAt(at)
                    Haptics.vibrate(this@PushService, VibrationPattern.SEEN)
                    ChildNotifier.showMessage(this@PushService, data["text"].orEmpty())
                }
                // --- Padres ---
                "checkin" ->
                    ParentNotifier.notifyCheckin(
                        this@PushService,
                        data["eventId"].orEmpty(),
                        data["title"].orEmpty(),
                        data["body"].orEmpty(),
                        alert = data["level"] != null && data["level"] != "normal",
                    )
                "checkin_late" ->
                    ParentNotifier.notifyCheckin(
                        this@PushService,
                        data["eventId"].orEmpty(),
                        data["title"].orEmpty(),
                        data["body"].orEmpty(),
                        alert = false,
                    )
                "missed" ->
                    ParentNotifier.notifyAlert(
                        this@PushService,
                        data["slot"].orEmpty(),
                        data["title"].orEmpty(),
                        data["body"].orEmpty(),
                    )
                "sos" ->
                    ParentNotifier.notifySos(
                        this@PushService,
                        data["eventId"].orEmpty(),
                        data["title"].orEmpty(),
                        data["body"].orEmpty(),
                        data["lat"]?.toDoubleOrNull(),
                        data["lng"]?.toDoubleOrNull(),
                    )
                "sos_glucose", "sos_ack_info" ->
                    ParentNotifier.notifyAlert(
                        this@PushService,
                        data["eventId"] ?: data["type"].orEmpty(),
                        data["title"].orEmpty(),
                        data["body"].orEmpty(),
                    )
                "day_summary" ->
                    ParentNotifier.notifyCheckin(
                        this@PushService,
                        data["date"].orEmpty(),
                        data["title"].orEmpty(),
                        data["body"].orEmpty(),
                        alert = false,
                    )
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
