package com.ingeint.checkin.ui.parent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.ingeint.checkin.AppContainer
import com.ingeint.checkin.data.local.OutboxEventType
import com.ingeint.checkin.sync.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Un SOS de los últimos 60 min sin `sos_ack` (docs/06 "Banner SOS activo"). */
data class ActiveSos(val eventId: String, val atMillis: Long, val lat: Double?, val lng: Double?)

data class TimelineEvent(
    val id: String,
    val type: String,
    val atMillis: Long,
    val text: String?,
    val senderName: String?,
    val syncedLate: Boolean,
    val glucoseValueMgDl: Long?,
    val glucoseTrend: Long?,
    val glucoseLevel: String?,
    val doseUnits: Double?,
)

data class DayCompliance(
    val expected: Int,
    val onTime: Int,
    val late: Int,
    val missed: Int,
    val pending: Int,
    val upcoming: Int,
)

data class ParentUiState(
    val childName: String = "",
    val childPhone: String? = null,
    val remindersEnabled: Boolean = true,
    val activeSos: ActiveSos? = null,
    val dayCompliance: DayCompliance? = null,
    val timeline: List<TimelineEvent> = emptyList(),
)

/**
 * ViewModel de [ParentHomeScreen] (docs/06 "Padres — Inico"). El cumplimiento de hoy
 * (`days/{fecha}`) lo escribe el backend en F6: hasta entonces, `dayCompliance` queda
 * en `null` y la pantalla lo indica sin romperse.
 */
class ParentViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ParentUiState())
    val state: StateFlow<ParentUiState> = _state.asStateFlow()

    private var familyListener: ListenerRegistration? = null
    private var eventsListener: ListenerRegistration? = null
    private var dayListener: ListenerRegistration? = null

    init {
        viewModelScope.launch { attachListeners() }
    }

    private suspend fun attachListeners() {
        val familyId = container.prefs.familyId.first() ?: return
        val familyRef = container.firestore.collection("families").document(familyId)

        familyListener =
            familyRef.addSnapshotListener { snap, _ ->
                if (snap == null) return@addSnapshotListener
                @Suppress("UNCHECKED_CAST")
                val settings = snap.get("settings") as? Map<String, Any?>
                _state.value =
                    _state.value.copy(
                        childName = snap.getString("childName") ?: "",
                        childPhone = settings?.get("childPhone") as? String,
                        remindersEnabled = settings?.get("enabled") as? Boolean ?: true,
                    )
            }

        eventsListener =
            familyRef.collection("events")
                .orderBy("clientAt", Query.Direction.DESCENDING)
                .limit(50)
                .addSnapshotListener { snap, _ ->
                    if (snap == null) return@addSnapshotListener
                    val events =
                        snap.documents.mapNotNull { doc ->
                            val type = doc.getString("type") ?: return@mapNotNull null
                            val realAt = doc.getTimestamp("realAt") ?: doc.getTimestamp("clientAt")
                            @Suppress("UNCHECKED_CAST")
                            val glucose = doc.get("glucose") as? Map<String, Any?>
                            TimelineEvent(
                                id = doc.id,
                                type = type,
                                atMillis = realAt?.toDate()?.time ?: 0L,
                                text = doc.getString("text"),
                                senderName = doc.getString("senderName"),
                                syncedLate = doc.getBoolean("syncedLate") ?: false,
                                glucoseValueMgDl = (glucose?.get("valueMgDl") as? Number)?.toLong(),
                                glucoseTrend = (glucose?.get("trend") as? Number)?.toLong(),
                                glucoseLevel = glucose?.get("level") as? String,
                                doseUnits = (doc.get("doseUnits") as? Number)?.toDouble(),
                            )
                        }
                    _state.value = _state.value.copy(timeline = events, activeSos = findActiveSos(events))
                }

        val today = java.time.LocalDate.now().toString()
        dayListener =
            familyRef.collection("days").document(today).addSnapshotListener { snap, _ ->
                @Suppress("UNCHECKED_CAST")
                val counts = snap?.get("counts") as? Map<String, Any?>
                _state.value =
                    _state.value.copy(
                        dayCompliance =
                            counts?.let {
                                DayCompliance(
                                    expected = (it["expected"] as? Number)?.toInt() ?: 0,
                                    onTime = (it["onTime"] as? Number)?.toInt() ?: 0,
                                    late = (it["late"] as? Number)?.toInt() ?: 0,
                                    missed = (it["missed"] as? Number)?.toInt() ?: 0,
                                    pending = (it["pending"] as? Number)?.toInt() ?: 0,
                                    upcoming = (it["upcoming"] as? Number)?.toInt() ?: 0,
                                )
                            },
                    )
            }
    }

    private fun findActiveSos(events: List<TimelineEvent>): ActiveSos? {
        val cutoff = System.currentTimeMillis() - 60 * 60_000L
        val lastSos = events.filter { it.type == "sos" && it.atMillis >= cutoff }.maxByOrNull { it.atMillis } ?: return null
        val wasAcked = events.any { it.type == "sos_ack" && it.atMillis >= lastSos.atMillis }
        if (wasAcked) return null
        // lat/lng no viajan en el timeline resumido; se leen aparte si hace falta un mapa.
        return ActiveSos(lastSos.id, lastSos.atMillis, lat = null, lng = null)
    }

    /** Mensajes rápidos (docs/06 "Mensajes rápidos"). */
    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val clientAt = container.prefs.correctedNowMillis()
            val offset = container.prefs.clockOffsetMs.first()
            container.outboxRepository.record(
                type = OutboxEventType.PARENT_MESSAGE,
                clientAtMillis = clientAt,
                clockOffsetMs = offset,
                source = "app",
                text = text.take(120),
            )
            SyncWorker.enqueue(container.appContextForChannels)
        }
    }

    /** Botón "Voy en camino" del banner (además de la acción de la notificación). */
    fun ackSos(eventId: String) {
        viewModelScope.launch {
            val clientAt = container.prefs.correctedNowMillis()
            val offset = container.prefs.clockOffsetMs.first()
            container.outboxRepository.record(
                type = OutboxEventType.SOS_ACK,
                clientAtMillis = clientAt,
                clockOffsetMs = offset,
                source = "app",
                text = "Voy en camino",
                replyTo = eventId,
            )
            SyncWorker.enqueue(container.appContextForChannels)
        }
    }

    override fun onCleared() {
        familyListener?.remove()
        eventsListener?.remove()
        dayListener?.remove()
    }
}
