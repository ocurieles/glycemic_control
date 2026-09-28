package com.ingeint.checkin.ui.parent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.ListenerRegistration
import com.ingeint.checkin.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Una dosis dentro del detalle de un día (docs/01, pedido 2026-09-27). */
data class InsulinDoseEntry(
    val atMillis: Long,
    val units: Double,
    val glucoseMgDl: Long? = null,
    val glucoseTrend: Long? = null,
)

data class InsulinUiState(
    val yearMonth: YearMonth = YearMonth.now(),
    /** `dateKey` ("yyyy-MM-dd") → total de unidades ese día. */
    val dailyTotals: Map<String, Double> = emptyMap(),
    val selectedDate: String? = null,
    val selectedDoses: List<InsulinDoseEntry> = emptyList(),
)

/**
 * Calendario de insulina del padre (docs/01, pedido 2026-09-27): un total por día
 * (`families/{fid}/days/{fecha}.insulin`, ver `insulin.ts`) y, al tocar un día, el
 * detalle hora por hora. Vive aparte de `ParentViewModel` porque su ciclo de vida
 * (mes seleccionado, día seleccionado) es propio de esta pantalla.
 */
class InsulinViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(InsulinUiState())
    val state: StateFlow<InsulinUiState> = _state.asStateFlow()

    private var monthListener: ListenerRegistration? = null
    private val dateKeyFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    init {
        loadMonth(YearMonth.now())
    }

    fun loadMonth(yearMonth: YearMonth) {
        _state.value = _state.value.copy(yearMonth = yearMonth, dailyTotals = emptyMap())
        monthListener?.remove()
        viewModelScope.launch {
            val familyId = container.prefs.familyId.first() ?: return@launch
            val start = yearMonth.atDay(1).format(dateKeyFormatter)
            val end = yearMonth.atEndOfMonth().format(dateKeyFormatter)
            monthListener =
                container.firestore
                    .collection("families").document(familyId)
                    .collection("days")
                    .whereGreaterThanOrEqualTo(FieldPath.documentId(), start)
                    .whereLessThanOrEqualTo(FieldPath.documentId(), end)
                    .addSnapshotListener { snap, _ ->
                        if (snap == null) return@addSnapshotListener
                        val totals =
                            snap.documents.mapNotNull { doc ->
                                @Suppress("UNCHECKED_CAST")
                                val insulin = doc.get("insulin") as? Map<String, Any?>
                                val total = (insulin?.get("total") as? Number)?.toDouble() ?: return@mapNotNull null
                                doc.id to total
                            }.toMap()
                        _state.value = _state.value.copy(dailyTotals = totals)
                    }
        }
    }

    fun selectDay(date: LocalDate) {
        val dateKey = date.format(dateKeyFormatter)
        _state.value = _state.value.copy(selectedDate = dateKey, selectedDoses = emptyList())
        viewModelScope.launch {
            val familyId = container.prefs.familyId.first() ?: return@launch
            val snap =
                container.firestore
                    .collection("families").document(familyId)
                    .collection("days").document(dateKey)
                    .get()
                    .await()
            @Suppress("UNCHECKED_CAST")
            val insulin = snap.get("insulin") as? Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val doses = insulin?.get("doses") as? List<Map<String, Any?>> ?: emptyList()
            _state.value =
                _state.value.copy(
                    selectedDoses =
                        doses
                            .mapNotNull { dose ->
                                val atMillis = (dose["atMillis"] as? Number)?.toLong() ?: return@mapNotNull null
                                val units = (dose["units"] as? Number)?.toDouble() ?: return@mapNotNull null
                                InsulinDoseEntry(
                                    atMillis,
                                    units,
                                    (dose["glucoseMgDl"] as? Number)?.toLong(),
                                    (dose["glucoseTrend"] as? Number)?.toLong(),
                                )
                            }
                            .sortedBy { it.atMillis },
                )
        }
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selectedDate = null, selectedDoses = emptyList())
    }

    override fun onCleared() {
        monthListener?.remove()
    }
}
