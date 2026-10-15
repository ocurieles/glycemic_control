package com.ingeint.checkin.ui.parent

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.firestore.Query
import com.ingeint.checkin.AppContainer
import com.ingeint.checkin.data.model.ReminderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

private const val TAG = "StatsViewModel"
private const val MAX_DAYS = 30

data class StatsUiState(
    val periodDays: Int = 14,
    val loading: Boolean = true,
    val error: Boolean = false,
    val childName: String = "",
    val stats: StatsResult? = null,
)

/** Estadísticas de glicemia e insulina (pedido 2026-10-15): solo lecturas de Firestore. */
class StatsViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    private var events: List<StatEvent> = emptyList()
    private var settings: ReminderSettings = ReminderSettings()

    init {
        load()
    }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = false)
        viewModelScope.launch {
            try {
                val familyId = container.prefs.familyId.first() ?: return@launch
                settings = container.prefs.settings.first() ?: ReminderSettings()
                val name = container.prefs.childName.first() ?: ""
                val since = System.currentTimeMillis() - (MAX_DAYS + 1) * 24L * 3600 * 1000
                val snap =
                    container.firestore.collection("families").document(familyId).collection("events")
                        .whereGreaterThanOrEqualTo("clientAt", Timestamp(java.util.Date(since)))
                        .orderBy("clientAt", Query.Direction.ASCENDING)
                        .get()
                        .await()
                events =
                    snap.documents.mapNotNull { doc ->
                        val type = doc.getString("type")
                        if (type != "checkin" && type != "insulin_dose") return@mapNotNull null
                        val at = (doc.getTimestamp("realAt") ?: doc.getTimestamp("clientAt"))?.toDate()?.time
                            ?: return@mapNotNull null
                        @Suppress("UNCHECKED_CAST")
                        val glucose = doc.get("glucose") as? Map<String, Any?>
                        StatEvent(
                            type = type,
                            atMillis = at,
                            glucose = (glucose?.get("valueMgDl") as? Number)?.toInt(),
                            trend = (glucose?.get("trend") as? Number)?.toInt(),
                            units = (doc.get("doseUnits") as? Number)?.toDouble(),
                        )
                    }
                _state.value = _state.value.copy(childName = name)
                recompute()
            } catch (e: Exception) {
                Log.w(TAG, "no se pudieron cargar las estadísticas", e)
                _state.value = _state.value.copy(loading = false, error = true)
            }
        }
    }

    fun setPeriod(days: Int) {
        _state.value = _state.value.copy(periodDays = days)
        recompute()
    }

    private fun recompute() {
        val s = settings
        val stats =
            StatsCalculator.compute(
                events,
                _state.value.periodDays,
                runCatching { ZoneId.of(s.timezone) }.getOrDefault(ZoneId.of("America/Caracas")),
                s.lowThreshold,
                s.highThreshold,
                System.currentTimeMillis(),
            )
        _state.value = _state.value.copy(stats = stats, loading = false)
    }

    suspend fun createPdf(): File? {
        val stats = _state.value.stats ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { StatsPdf.write(container.appContextForChannels, _state.value.childName, stats) }
                .onFailure { Log.w(TAG, "no se pudo generar el PDF", it) }
                .getOrNull()
        }
    }
}
