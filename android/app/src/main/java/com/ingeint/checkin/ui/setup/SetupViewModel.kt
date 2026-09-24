package com.ingeint.checkin.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ingeint.checkin.AppContainer
import com.ingeint.checkin.data.remote.FunctionsCallError
import com.ingeint.checkin.notify.Channels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Pasos del asistente de vinculación (docs/06 "Vinculación (SetupScreen)"). */
sealed interface SetupStep {
    data object ChooseRole : SetupStep

    data object ParentChoice : SetupStep

    data object CreateFamilyForm : SetupStep

    data class JoinFamilyForm(val role: String) : SetupStep

    /** Se muestra tras `createFamily`, para que el otro padre se una (docs/04). */
    data class PairingCodeDisplay(val code: String, val expiresAt: Long, val childName: String) : SetupStep

    /** Vinculación completa: MainActivity navega fuera de Setup. */
    data class Linked(val role: String, val childName: String) : SetupStep
}

data class SetupUiState(
    val step: SetupStep = SetupStep.ChooseRole,
    val loading: Boolean = false,
    val errorMessage: String? = null,
)

class SetupViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    fun chooseParent() {
        _state.update { it.copy(step = SetupStep.ParentChoice, errorMessage = null) }
    }

    fun chooseChild() {
        _state.update { it.copy(step = SetupStep.JoinFamilyForm(role = "child"), errorMessage = null) }
    }

    fun chooseCreateFamily() {
        _state.update { it.copy(step = SetupStep.CreateFamilyForm, errorMessage = null) }
    }

    fun chooseJoinFamily() {
        _state.update { it.copy(step = SetupStep.JoinFamilyForm(role = "parent"), errorMessage = null) }
    }

    fun back() {
        _state.update {
            val previous =
                when (it.step) {
                    is SetupStep.CreateFamilyForm, is SetupStep.JoinFamilyForm ->
                        if ((it.step as? SetupStep.JoinFamilyForm)?.role == "child") {
                            SetupStep.ChooseRole
                        } else {
                            SetupStep.ParentChoice
                        }
                    else -> SetupStep.ChooseRole
                }
            it.copy(step = previous, errorMessage = null)
        }
    }

    fun createFamily(childName: String, parentName: String) {
        if (childName.isBlank() || parentName.isBlank()) {
            _state.update { it.copy(errorMessage = "Completa ambos nombres.") }
            return
        }
        runAuthenticated { auth ->
            val result = container.functionsApi.createFamily(childName.trim(), parentName.trim())
            auth.currentUser?.getIdToken(true)?.await() // recibir los custom claims (docs/04)
            container.prefs.saveLink(role = "parent", familyId = result.familyId, childName = childName.trim(), displayName = parentName.trim())
            Channels.createForRole(containerContext(), "parent")
            com.ingeint.checkin.sync.SyncWorker.enqueuePeriodic(containerContext())
            _state.update {
                it.copy(
                    loading = false,
                    step = SetupStep.PairingCodeDisplay(result.code, result.expiresAt, childName.trim()),
                )
            }
        }
    }

    fun joinFamily(role: String, code: String, displayName: String?) {
        if (code.isBlank() || code.length != 6) {
            _state.update { it.copy(errorMessage = "El código debe tener 6 dígitos.") }
            return
        }
        if (role == "parent" && displayName.isNullOrBlank()) {
            _state.update { it.copy(errorMessage = "Escribe cómo te llamas.") }
            return
        }
        runAuthenticated { auth ->
            val result = container.functionsApi.joinFamily(code.trim(), role, displayName?.trim())
            auth.currentUser?.getIdToken(true)?.await()
            container.prefs.saveLink(
                role = role,
                familyId = result.familyId,
                childName = result.childName,
                displayName = if (role == "parent") displayName!!.trim() else result.childName,
            )
            Channels.createForRole(containerContext(), role)
            com.ingeint.checkin.sync.SyncWorker.enqueuePeriodic(containerContext())
            if (role == "child") fetchAndScheduleSettings(result.familyId)
            _state.update { it.copy(loading = false, step = SetupStep.Linked(role, result.childName)) }
        }
    }

    /** Desde la pantalla del código: el padre ya puede seguir (el otro padre se une cuando quiera). */
    fun continueAfterPairingCode() {
        val current = _state.value.step as? SetupStep.PairingCodeDisplay ?: return
        _state.update { it.copy(step = SetupStep.Linked("parent", current.childName)) }
    }

    private fun runAuthenticated(block: suspend (com.google.firebase.auth.FirebaseAuth) -> Unit) {
        _state.update { it.copy(loading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val auth = container.auth
                if (auth.currentUser == null) auth.signInAnonymously().await()
                block(auth)
            } catch (e: FunctionsCallError) {
                android.util.Log.e("SetupViewModel", "callable falló: ${e.code}", e)
                _state.update { it.copy(loading = false, errorMessage = friendlyError(e)) }
            } catch (e: Exception) {
                android.util.Log.e("SetupViewModel", "fallo inesperado en runAuthenticated", e)
                _state.update { it.copy(loading = false, errorMessage = "Algo salió mal. Intenta de nuevo.") }
            }
        }
    }

    private fun friendlyError(e: FunctionsCallError): String {
        val fallback = "Algo salió mal. Intenta de nuevo."
        return when (e.code) {
            "NOT_FOUND" -> "Código inválido o vencido."
            "RESOURCE_EXHAUSTED" -> "Demasiados intentos. Espera un momento."
            "FAILED_PRECONDITION", "INVALID_ARGUMENT" -> e.message ?: fallback
            else -> fallback
        }
    }

    private fun containerContext() = container.appContextForChannels

    /** El niño no recibe settings en `joinFamily`; los busca una vez apenas se vincula. */
    private suspend fun fetchAndScheduleSettings(familyId: String) {
        runCatching {
            val snap = container.firestore.collection("families").document(familyId).get().await()
            @Suppress("UNCHECKED_CAST")
            val settingsMap = snap.get("settings") as? Map<String, Any?> ?: return
            val settings = com.ingeint.checkin.data.model.ReminderSettings.fromMap(settingsMap)
            container.prefs.saveSettings(settings)
            com.ingeint.checkin.reminders.ReminderScheduler(containerContext()).scheduleNext(settings)
        }.onFailure { android.util.Log.w("SetupViewModel", "no se pudieron cargar los settings iniciales", it) }
    }
}
