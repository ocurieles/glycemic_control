package com.ingeint.checkin.sync

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.ingeint.checkin.CheckinApp
import com.ingeint.checkin.R
import com.ingeint.checkin.data.local.OutboxEvent
import com.ingeint.checkin.data.local.OutboxEventType
import com.ingeint.checkin.data.local.OutboxRepository
import com.ingeint.checkin.notify.ChannelIds
import com.ingeint.checkin.notify.Haptics
import com.ingeint.checkin.notify.VibrationPattern
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.util.Date
import java.util.concurrent.TimeUnit

private const val TAG = "SyncWorker"
private const val UNIQUE_WORK_NAME = "sync-outbox"
private const val PERIODIC_WORK_NAME = "sync-outbox-periodic"
private const val UPLOAD_TIMEOUT_MS = 15_000L
private const val BATCH_SIZE = 20
private const val SYNC_NOTIFICATION_ID = 3001

/** Resultado de subir un evento (docs/07 "Algoritmo" del SyncWorker). */
private sealed interface UploadResult {
    data object Success : UploadResult

    /** `rejected = true`: el teléfono fue desvinculado (sin los claims). Se marca REJECTED. */
    data class PermissionDenied(val rejected: Boolean, val message: String) : UploadResult

    data class Retry(val message: String?) : UploadResult
}

/**
 * Sube el outbox pendiente (docs/07 "SyncWorker"). Único, encadenado, con timeout por
 * evento e idempotente por `set()` al `eventId` (CLAUDE.md regla 4 y 5).
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CheckinApp
        val container = app.container
        val repository = container.outboxRepository

        if (container.auth.currentUser == null) {
            runCatching { container.auth.signInAnonymously().await() }
        }

        val familyId = container.prefs.familyId.first() ?: return Result.success()
        val batch = repository.pendingBatch(BATCH_SIZE)
        if (batch.isEmpty()) return Result.success()

        var anyRecentUserInitiated = false
        var syncedCount = 0
        // El desfase de reloj SOLO se recalcula desde un evento recién creado en este
        // mismo dispositivo (bug real reportado 2026-09-27): antes se usaba "el primer
        // evento subido en este lote", que con un outbox atascado (o simplemente offline
        // un rato) podía ser un evento de HORAS atrás. Usarlo para "createdAt − clientAt"
        // calculaba un desfase gigante y falso, que quedaba aplicado a todos los toques
        // nuevos hasta la siguiente sincronización — mostrando una hora incorrecta al
        // tocar "Ya me revisé".
        var mostRecentSyncedEvent: OutboxEvent? = null
        // Si un evento falla, no debe bloquear a los que vienen detrás en el mismo lote
        // (bug real reportado 2026-09-27: uno atascado dejaba a todos los demás sin
        // subir, indefinidamente). Se sigue con el resto y se reintenta solo lo que falló.
        var anyNeedsRetry = false

        for (event in batch) {
            when (val outcome = uploadOne(container, familyId, event)) {
                UploadResult.Success -> {
                    repository.markSent(event)
                    syncedCount++
                    val current = mostRecentSyncedEvent
                    if (current == null || event.recordedAt > current.recordedAt) {
                        mostRecentSyncedEvent = event
                    }
                    if (repository.wasRecordedRecently(event)) anyRecentUserInitiated = true
                    Log.i(TAG, "subido: id=${event.id} type=${event.type}")
                }
                is UploadResult.PermissionDenied -> {
                    Log.w(TAG, "PERMISSION_DENIED: id=${event.id} type=${event.type} rejected=${outcome.rejected} msg=${outcome.message}")
                    if (outcome.rejected) {
                        repository.markRejected(event, outcome.message)
                    } else {
                        repository.markRetry(event, outcome.message)
                        anyNeedsRetry = true
                    }
                }
                is UploadResult.Retry -> {
                    Log.w(TAG, "reintentar: id=${event.id} type=${event.type} msg=${outcome.message}")
                    repository.markRetry(event, outcome.message)
                    anyNeedsRetry = true
                }
            }
        }

        // Vibración CONFIRM solo si algo lo inició el usuario hace poco (docs/07: no en
        // ráfaga al sincronizar un lote viejo). Solo tiene sentido en el rol niño.
        if (anyRecentUserInitiated && container.prefs.role.first() == "child") {
            Haptics.vibrate(applicationContext, VibrationPattern.CONFIRM)
        }

        mostRecentSyncedEvent?.let { event ->
            if (repository.wasRecordedRecently(event, withinMs = 300_000L)) {
                updateClockOffset(container, familyId, event)
            }
        }

        Log.i(TAG, "sincronizados $syncedCount evento(s)")

        // Se reintenta si algo falló, o si el lote se llenó (probablemente queden más).
        return if (anyNeedsRetry || batch.size >= BATCH_SIZE) Result.retry() else Result.success()
    }

    private suspend fun uploadOne(
        container: com.ingeint.checkin.AppContainer,
        familyId: String,
        event: OutboxEvent,
    ): UploadResult {
        val docRef =
            container.firestore.collection("families").document(familyId).collection("events").document(event.id)
        val uid = container.auth.currentUser?.uid ?: return UploadResult.Retry("sin sesión")

        return try {
            withTimeout(UPLOAD_TIMEOUT_MS) { docRef.set(buildPayload(event, uid)).await() }
            UploadResult.Success
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                handlePermissionDenied(container, docRef, event, uid)
            } else {
                UploadResult.Retry(e.message)
            }
        } catch (e: TimeoutCancellationException) {
            UploadResult.Retry("timeout")
        } catch (e: Exception) {
            UploadResult.Retry(e.message)
        }
    }

    /** docs/07: distingue "ya existía" (reintento de algo creado) de "de verdad no autorizado". */
    private suspend fun handlePermissionDenied(
        container: com.ingeint.checkin.AppContainer,
        docRef: DocumentReference,
        event: OutboxEvent,
        uid: String,
    ): UploadResult {
        val existing = runCatching { docRef.get(Source.SERVER).await() }.getOrNull()
        if (existing?.exists() == true) return UploadResult.Success

        runCatching { container.auth.currentUser?.getIdToken(true)?.await() }
        val retried =
            runCatching { withTimeout(UPLOAD_TIMEOUT_MS) { docRef.set(buildPayload(event, uid)).await() } }
        if (retried.isSuccess) return UploadResult.Success

        val tokenResult = runCatching { container.auth.currentUser?.getIdToken(false)?.await() }.getOrNull()
        val hasClaims = tokenResult?.claims?.get("familyId") != null && tokenResult.claims["role"] != null
        return if (!hasClaims) {
            UploadResult.PermissionDenied(rejected = true, message = "PERMISSION_DENIED: teléfono desvinculado")
        } else {
            UploadResult.PermissionDenied(rejected = false, message = "PERMISSION_DENIED: reintentar")
        }
    }

    /**
     * `clockOffset = serverTime − deviceTime`, leído del propio evento recién confirmado
     * (docs/07 "Desviación de reloj"). Simplificación: se recalcula en cada sync exitoso
     * en vez de "una vez al día" — más lecturas, pero más simple y sigue siendo correcto.
     */
    private suspend fun updateClockOffset(
        container: com.ingeint.checkin.AppContainer,
        familyId: String,
        event: OutboxEvent,
    ) {
        runCatching {
            val docRef =
                container.firestore.collection("families").document(familyId).collection("events").document(event.id)
            val snap = withTimeout(UPLOAD_TIMEOUT_MS) { docRef.get(Source.SERVER).await() }
            val createdAt = snap.getTimestamp("createdAt") ?: return
            val offset = createdAt.toDate().time - event.clientAt
            container.prefs.saveClockOffsetMs(offset)
        }.onFailure { Log.w(TAG, "no se pudo actualizar clockOffsetMs", it) }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification =
            NotificationCompat.Builder(applicationContext, ChannelIds.SYNC_STATUS)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(applicationContext.getString(R.string.sync_status_title))
                .setOngoing(true)
                .build()
        return ForegroundInfo(SYNC_NOTIFICATION_ID, notification)
    }

    companion object {
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Encola una corrida (al registrar un evento, al volver la red, en boot). */
        fun enqueue(context: Context) {
            val request =
                androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /** Respaldo periódico cada 15 min (docs/07), por si algo evitó los triggers puntuales. */
        fun enqueuePeriodic(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** `internal` para poder probarlo directamente en `SyncWorkerPayloadTest` (JVM, sin Firestore real). */
internal fun buildPayload(event: OutboxEvent, uid: String): Map<String, Any?> =
    buildMap {
        put("type", event.type.toContractName())
        put("createdBy", uid)
        put("createdAt", FieldValue.serverTimestamp())
        put("clientAt", Timestamp(Date(event.clientAt)))
        event.clockOffsetMs?.let { put("clockOffsetMs", it.toInt()) }
        put("source", event.source)
        event.text?.let { put("text", it) }
        event.replyTo?.let { put("replyTo", it) }
        if (event.type == OutboxEventType.SOS) {
            put("smsSent", event.smsSent)
            if (event.lat != null && event.lng != null) {
                put("location", mapOf("lat" to event.lat, "lng" to event.lng, "accuracyM" to event.accuracyM))
            }
        }
        if (event.type == OutboxEventType.INSULIN_DOSE) {
            event.doseUnits?.let { put("doseUnits", it) }
        }
    }

private fun OutboxEventType.toContractName(): String =
    when (this) {
        OutboxEventType.CHECKIN -> "checkin"
        OutboxEventType.SOS -> "sos"
        OutboxEventType.PARENT_MESSAGE -> "parent_message"
        OutboxEventType.SOS_ACK -> "sos_ack"
        OutboxEventType.INSULIN_DOSE -> "insulin_dose"
    }
