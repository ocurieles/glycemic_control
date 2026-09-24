package com.ingeint.checkin.ui.parent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ingeint.checkin.AppContainer
import com.ingeint.checkin.data.model.ReminderSettings
import com.ingeint.checkin.data.remote.FunctionsCallError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class ParentSettingsUiState(
    val settings: ReminderSettings = ReminderSettings(),
    val loading: Boolean = false,
    val errorMessage: String? = null,
    val savedMessage: String? = null,
    val pairingCode: String? = null,
    val libreMessage: String? = null,
    val leftFamily: Boolean = false,
)

/**
 * Ajustes del padre (docs/06 "Padres — Ajustes"). La validación de rangos espeja
 * `validSettings()` de docs/03 §1/§4 — las reglas de Firestore la aplican igual del
 * lado del servidor, esto es solo para no dejar guardar algo que las reglas rechazarían.
 */
class ParentSettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ParentSettingsUiState())
    val state: StateFlow<ParentSettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.prefs.settings.first()?.let { _state.value = _state.value.copy(settings = it) }
            refreshFromServer()
        }
    }

    private suspend fun refreshFromServer() {
        val familyId = container.prefs.familyId.first() ?: return
        runCatching {
            val snap = container.firestore.collection("families").document(familyId).get().await()
            @Suppress("UNCHECKED_CAST")
            val map = snap.get("settings") as? Map<String, Any?> ?: return
            _state.value = _state.value.copy(settings = ReminderSettings.fromMap(map))
        }
    }

    fun save(newSettings: ReminderSettings) {
        val error = validate(newSettings)
        if (error != null) {
            _state.value = _state.value.copy(errorMessage = error)
            return
        }
        viewModelScope.launch {
            val familyId = container.prefs.familyId.first() ?: return@launch
            _state.value = _state.value.copy(loading = true, errorMessage = null, savedMessage = null)
            runCatching {
                container.firestore.collection("families").document(familyId)
                    .update("settings", settingsToMap(newSettings))
                    .await()
            }.onSuccess {
                container.prefs.saveSettings(newSettings)
                _state.value = _state.value.copy(loading = false, savedMessage = "Guardado.", settings = newSettings)
            }.onFailure { e ->
                _state.value = _state.value.copy(loading = false, errorMessage = e.message ?: "No se pudo guardar.")
            }
        }
    }

    fun generatePairingCode() {
        viewModelScope.launch {
            runCatching { container.functionsApi.createPairingCode() }
                .onSuccess { _state.value = _state.value.copy(pairingCode = it.code) }
                .onFailure { _state.value = _state.value.copy(errorMessage = "No se pudo generar el código.") }
        }
    }

    fun connectLibreLinkUp(email: String, password: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, libreMessage = null)
            try {
                container.functionsApi.setLibreLinkUp(email, password)
                _state.value = _state.value.copy(loading = false, libreMessage = "Conectado.")
            } catch (e: FunctionsCallError) {
                _state.value = _state.value.copy(loading = false, libreMessage = friendlyLibreError(e))
            }
        }
    }

    fun testLibreLinkUp() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, libreMessage = null)
            try {
                container.functionsApi.testLibreLinkUp()
                _state.value = _state.value.copy(loading = false, libreMessage = "Lectura obtenida.")
            } catch (e: FunctionsCallError) {
                _state.value = _state.value.copy(loading = false, libreMessage = friendlyLibreError(e))
            }
        }
    }

    private fun friendlyLibreError(e: FunctionsCallError): String = e.message ?: "No se pudo conectar."

    fun leaveFamily() {
        viewModelScope.launch {
            runCatching { container.functionsApi.leaveFamily() }
                .onSuccess {
                    container.prefs.clear()
                    _state.value = _state.value.copy(leftFamily = true)
                }
                .onFailure { _state.value = _state.value.copy(errorMessage = it.message ?: "No se pudo desvincular.") }
        }
    }

    /** Espeja `validSettings()` de docs/03 §1/§4. */
    private fun validate(s: ReminderSettings): String? {
        if (s.intervalMinutes !in 5..240) return "El intervalo debe estar entre 5 y 240 minutos."
        if (s.days.isEmpty()) return "Elige al menos un día."
        if (!isHhmm(s.startTime) || !isHhmm(s.endTime)) return "Hora inválida."
        if (s.startTime >= s.endTime) return "La hora de inicio debe ser antes que la de fin."
        if (s.escalationMinutes !in 1..60) return "El escalamiento debe estar entre 1 y 60 minutos."
        if (s.nudgeMinutes !in 0..30) return "El refuerzo debe estar entre 0 y 30 minutos."
        if (s.lowThreshold !in 40..120) return "El umbral bajo debe estar entre 40 y 120."
        if (s.highThreshold !in 120..400) return "El umbral alto debe estar entre 120 y 400."
        if (s.smsNumbers.size > 3) return "Máximo 3 números para el SOS por SMS."
        return null
    }

    private fun isHhmm(value: String): Boolean = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$").matches(value)

    private fun settingsToMap(s: ReminderSettings): Map<String, Any?> =
        mapOf(
            "enabled" to s.enabled,
            "intervalMinutes" to s.intervalMinutes,
            "days" to s.days,
            "startTime" to s.startTime,
            "endTime" to s.endTime,
            "escalationMinutes" to s.escalationMinutes,
            "nudgeMinutes" to s.nudgeMinutes,
            "timezone" to s.timezone,
            "lowThreshold" to s.lowThreshold,
            "highThreshold" to s.highThreshold,
            "smsFallbackEnabled" to s.smsFallbackEnabled,
            "smsNumbers" to s.smsNumbers,
            "childPhone" to s.childPhone,
        )
}
