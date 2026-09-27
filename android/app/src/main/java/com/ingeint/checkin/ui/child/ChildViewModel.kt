package com.ingeint.checkin.ui.child

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.content.getSystemService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.ListenerRegistration
import com.ingeint.checkin.AppContainer
import com.ingeint.checkin.data.local.LastMessage
import com.ingeint.checkin.data.local.OutboxEvent
import com.ingeint.checkin.data.local.OutboxEventType
import com.ingeint.checkin.data.local.OutboxStatus
import com.ingeint.checkin.data.model.ReminderSettings
import com.ingeint.checkin.notify.ChildNotifier
import com.ingeint.checkin.notify.Haptics
import com.ingeint.checkin.notify.VibrationPattern
import com.ingeint.checkin.reminders.ReminderSchedule
import com.ingeint.checkin.reminders.ReminderScheduler
import com.ingeint.checkin.sync.LocationHelper
import com.ingeint.checkin.sync.SmsFallback
import com.ingeint.checkin.sync.SyncWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ES_VE = Locale.Builder().setLanguage("es").setRegion("VE").build()
private const val TAG = "ChildViewModel"

enum class CheckinStatus { NONE, PENDING, SENT }

data class ChildUiState(
    val nextReminderText: String? = null,
    val lastMessage: LastMessage? = null,
    val checkinStatus: CheckinStatus = CheckinStatus.NONE,
    val checkinStatusTimeText: String? = null,
    val pendingCount: Int = 0,
    val sosStatusText: String? = null,
    /**
     * Valor del último check-in (docs/01, pedido 2026-09-24): solo se llena DESPUÉS de
     * tocar "Ya me revisé", nunca antes — así no queda visible en pantalla para quien
     * mire de reojo antes de que Cesar decida revisarse.
     */
    val lastCheckinGlucoseValueMgDl: Long? = null,
    val lastCheckinGlucoseTrend: Long? = null,
    /** Pedido 2026-09-27: el valor aparece grande un momento (para que Cesar lo note de
     * verdad) y luego se queda chico, como histórico, en [lastCheckinGlucoseValueMgDl]. */
    val showGlucosePopup: Boolean = false,
    val doseLogged: Boolean = false,
)

/**
 * ViewModel de [ChildScreen] (docs/06). El botón "Ya me revisé" y el de Ayuda ya
 * registran en el outbox y encolan [SyncWorker] (docs/07, F4).
 */
class ChildViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ChildUiState())
    val state: StateFlow<ChildUiState> = _state.asStateFlow()

    private var familyListener: ListenerRegistration? = null
    private var lastCheckinListener: ListenerRegistration? = null
    private val context: Context get() = container.appContextForChannels

    init {
        viewModelScope.launch {
            combine(container.prefs.settings, tick()) { settings, _ -> settings }.collect { settings ->
                updateNextReminder(settings)
            }
        }
        viewModelScope.launch {
            container.prefs.lastMessage.collect { message -> _state.value = _state.value.copy(lastMessage = message) }
        }
        viewModelScope.launch {
            container.outboxRepository.observeAll().collect { events -> updateCheckinState(events) }
        }
        viewModelScope.launch { attachFamilyListener() }
    }

    /** Tocar "Ya me revisé" (docs/06): TAP → Room → cancela recordatorio/refuerzo → encola sync. */
    fun onCheckin() {
        Haptics.vibrate(context, VibrationPattern.TAP)
        ChildNotifier.cancelReminder(context)
        ReminderScheduler(context).cancelNudge()
        _state.value = _state.value.copy(lastCheckinGlucoseValueMgDl = null, lastCheckinGlucoseTrend = null)
        viewModelScope.launch {
            val clientAt = container.prefs.correctedNowMillis()
            val offset = container.prefs.clockOffsetMs.first()
            val event =
                container.outboxRepository.record(
                    type = OutboxEventType.CHECKIN,
                    clientAtMillis = clientAt,
                    clockOffsetMs = offset,
                    source = "app",
                )
            SyncWorker.enqueue(context)
            watchCheckinResult(event.id)
        }
    }

    /**
     * Muestra el valor del check-in dentro de la app, solo tras tocar el botón (docs/01):
     * escucha el documento del propio evento hasta que el backend lo procese y traiga
     * `glucose` (o quede sin él, si LibreLinkUp no tiene una lectura reciente).
     */
    private fun watchCheckinResult(eventId: String) {
        lastCheckinListener?.remove()
        viewModelScope.launch {
            val familyId = container.prefs.familyId.first() ?: return@launch
            lastCheckinListener =
                container.firestore
                    .collection("families").document(familyId)
                    .collection("events").document(eventId)
                    .addSnapshotListener { snap, _ ->
                        @Suppress("UNCHECKED_CAST")
                        val glucose = snap?.get("glucose") as? Map<String, Any?>
                        if (glucose != null && _state.value.lastCheckinGlucoseValueMgDl == null) {
                            _state.value =
                                _state.value.copy(
                                    lastCheckinGlucoseValueMgDl = (glucose["valueMgDl"] as? Number)?.toLong(),
                                    lastCheckinGlucoseTrend = (glucose["trend"] as? Number)?.toLong(),
                                    showGlucosePopup = true,
                                )
                            viewModelScope.launch {
                                delay(3_000)
                                _state.value = _state.value.copy(showGlucosePopup = false)
                            }
                        }
                    }
        }
    }

    /** Registro rápido (docs/01, pedido 2026-09-24): un toque, sin pantallas extra. */
    fun logInsulinDose(units: Double) {
        Haptics.vibrate(context, VibrationPattern.TAP)
        viewModelScope.launch {
            val clientAt = container.prefs.correctedNowMillis()
            val offset = container.prefs.clockOffsetMs.first()
            val event =
                container.outboxRepository.record(
                    type = OutboxEventType.INSULIN_DOSE,
                    clientAtMillis = clientAt,
                    clockOffsetMs = offset,
                    source = "app",
                    doseUnits = units,
                )
            Log.i(TAG, "registro rápido guardado en outbox: id=${event.id} units=$units")
            SyncWorker.enqueue(context)
            _state.value = _state.value.copy(doseLogged = true)
            delay(2_000)
            _state.value = _state.value.copy(doseLogged = false)
        }
    }

    /** SOS (docs/06 H5, docs/07 "SOS sin conexión"): nunca se bloquea por ubicación o red. */
    fun onSos() {
        Haptics.vibrate(context, VibrationPattern.TAP)
        viewModelScope.launch {
            val location = LocationHelper.getCurrentLocationOrNull(context) // timeout 5 s, puede ser null
            val clientAt = container.prefs.correctedNowMillis()
            val offset = container.prefs.clockOffsetMs.first()
            val settings = container.prefs.settings.first()
            val childName = container.prefs.childName.first() ?: "El niño"

            var smsSent = false
            if (!isNetworkValidated(context) && settings?.smsFallbackEnabled == true) {
                val timeLabel =
                    Instant.ofEpochMilli(clientAt)
                        .atZone(ZoneId.of(settings.timezone))
                        .format(DateTimeFormatter.ofPattern("h:mm a", ES_VE))
                smsSent =
                    SmsFallback.send(
                        context,
                        childName,
                        timeLabel,
                        settings.smsNumbers,
                        location?.latitude,
                        location?.longitude,
                    )
            }

            container.outboxRepository.record(
                type = OutboxEventType.SOS,
                clientAtMillis = clientAt,
                clockOffsetMs = offset,
                source = "app",
                lat = location?.latitude,
                lng = location?.longitude,
                accuracyM = location?.accuracy,
                smsSent = smsSent,
            )
            Haptics.vibrate(context, VibrationPattern.CONFIRM)
            _state.value = _state.value.copy(sosStatusText = if (smsSent) "sms" else "sent")
            SyncWorker.enqueue(context)
        }
    }

    /** docs/06: "Mientras la app del niño está abierta, un listener de families/{fid} hace lo mismo que sync". */
    private suspend fun attachFamilyListener() {
        val familyId = container.prefs.familyId.first() ?: return
        familyListener =
            container.firestore.collection("families").document(familyId).addSnapshotListener { snap, _ ->
                @Suppress("UNCHECKED_CAST")
                val settingsMap = snap?.get("settings") as? Map<String, Any?> ?: return@addSnapshotListener
                val settings = ReminderSettings.fromMap(settingsMap)
                viewModelScope.launch {
                    container.prefs.saveSettings(settings)
                    ReminderScheduler(context).scheduleNext(settings)
                }
            }
    }

    private fun updateCheckinState(events: List<OutboxEvent>) {
        val checkins = events.filter { it.type == OutboxEventType.CHECKIN }
        val pending = checkins.count { it.status == OutboxStatus.PENDING }
        // `recordedAt` (cuándo se tocó el botón en ESTE dispositivo), no `clientAt` (la
        // hora "corregida" con el desfase de reloj, que puede estar mal si el desfase se
        // calculó mal — ver el bug real de SyncWorker.kt 2026-09-27): así la pantalla
        // siempre refleja el toque más reciente, nunca uno viejo con un valor corrupto.
        val last = checkins.maxByOrNull { it.recordedAt }
        val status =
            when (last?.status) {
                OutboxStatus.SENT -> CheckinStatus.SENT
                OutboxStatus.PENDING, OutboxStatus.REJECTED -> CheckinStatus.PENDING
                null -> CheckinStatus.NONE
            }
        val timeText =
            last?.let {
                DateTimeFormatter.ofPattern("h:mm a", ES_VE)
                    .format(Instant.ofEpochMilli(it.clientAt).atZone(ZoneId.systemDefault()))
            }
        _state.value =
            _state.value.copy(checkinStatus = status, checkinStatusTimeText = timeText, pendingCount = pending)
    }

    private fun tick() =
        kotlinx.coroutines.flow.flow {
            while (true) {
                emit(Unit)
                delay(30_000)
            }
        }

    private fun updateNextReminder(settings: ReminderSettings?) {
        val text = settings?.let(::formatNextReminder)
        _state.value = _state.value.copy(nextReminderText = text)
    }

    private fun formatNextReminder(settings: ReminderSettings): String? {
        val slot = ReminderSchedule.nextSlot(System.currentTimeMillis(), settings) ?: return null
        return Instant.ofEpochMilli(slot.epochMillis)
            .atZone(ZoneId.of(settings.timezone))
            .format(DateTimeFormatter.ofPattern("h:mm a", ES_VE))
    }

    private fun isNetworkValidated(context: Context): Boolean {
        val cm = context.getSystemService<ConnectivityManager>() ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun onCleared() {
        familyListener?.remove()
        lastCheckinListener?.remove()
    }
}
