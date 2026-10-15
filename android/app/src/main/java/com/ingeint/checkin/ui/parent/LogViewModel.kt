package com.ingeint.checkin.ui.parent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ingeint.checkin.AppContainer
import com.ingeint.checkin.data.local.OutboxEventType
import com.ingeint.checkin.sync.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class LogUiState(
    /** Dosis habitual de Tresiba del plan (Ajustes), para preseleccionarla. */
    val basalUnits: Int? = null,
    val saved: Boolean = false,
    val error: String? = null,
)

/**
 * Registro de comida (carbohidratos) y de Tresiba por un padre (pedido 2026-10-15). Mismo
 * patrón que el resto: Room primero, `SyncWorker` después (CLAUDE.md regla 4), así que
 * funciona sin internet. La hora por defecto es la del registro; se puede cambiar a una
 * pasada (nunca futura).
 */
class LogViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(LogUiState())
    val state: StateFlow<LogUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val familyId = container.prefs.familyId.first() ?: return@launch
            runCatching {
                val snap = container.firestore.collection("families").document(familyId).get().await()
                @Suppress("UNCHECKED_CAST")
                val plan = snap.get("basalPlan") as? Map<String, Any?>
                _state.value = _state.value.copy(basalUnits = (plan?.get("units") as? Number)?.toInt())
            }
        }
    }

    fun saveMeal(carbsGrams: Int, note: String, atMillis: Long?) {
        if (carbsGrams !in 1..300) return fail("Los carbohidratos deben estar entre 1 y 300 g.")
        record(OutboxEventType.MEAL, atMillis) { carbs = carbsGrams; text = note.trim().take(120).ifEmpty { null } }
    }

    fun saveBasal(units: Int, atMillis: Long?) {
        if (units !in 1..80) return fail("La Tresiba debe estar entre 1 y 80 unidades.")
        record(OutboxEventType.BASAL_DOSE, atMillis) { dose = units.toDouble() }
    }

    fun clearSaved() {
        _state.value = _state.value.copy(saved = false, error = null)
    }

    private class Fields {
        var carbs: Int? = null
        var dose: Double? = null
        var text: String? = null
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(error = message, saved = false)
    }

    private fun record(type: OutboxEventType, atMillis: Long?, fill: Fields.() -> Unit) {
        val now = System.currentTimeMillis()
        if (atMillis != null && atMillis > now + 2 * 60_000L) return fail("La hora no puede ser futura.")
        val fields = Fields().apply(fill)
        viewModelScope.launch {
            val offset = container.prefs.clockOffsetMs.first()
            container.outboxRepository.record(
                type = type,
                clientAtMillis = (atMillis ?: now) + offset,
                clockOffsetMs = offset,
                source = "app",
                text = fields.text,
                doseUnits = fields.dose,
                carbsGrams = fields.carbs,
            )
            SyncWorker.enqueue(container.appContextForChannels)
            _state.value = _state.value.copy(saved = true, error = null)
        }
    }
}
