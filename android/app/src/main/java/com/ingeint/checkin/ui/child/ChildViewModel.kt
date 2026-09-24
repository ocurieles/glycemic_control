package com.ingeint.checkin.ui.child

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.ListenerRegistration
import com.ingeint.checkin.AppContainer
import com.ingeint.checkin.data.local.LastMessage
import com.ingeint.checkin.data.model.ReminderSettings
import com.ingeint.checkin.reminders.ReminderSchedule
import com.ingeint.checkin.reminders.ReminderScheduler
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

data class ChildUiState(val nextReminderText: String? = null, val lastMessage: LastMessage? = null)

/**
 * ViewModel de [ChildScreen] (docs/06 "Niño (ChildScreen)"). El botón "Ya me revisé"
 * y el de Ayuda todavía no envían nada (docs/08 F3: "sin lógica de envío aún"; el
 * outbox real llega en F4).
 */
class ChildViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ChildUiState())
    val state: StateFlow<ChildUiState> = _state.asStateFlow()

    private var familyListener: ListenerRegistration? = null

    init {
        viewModelScope.launch {
            combine(container.prefs.settings, tick()) { settings, _ -> settings }.collect { settings ->
                _state.update(settings)
            }
        }
        viewModelScope.launch {
            container.prefs.lastMessage.collect { message -> _state.value = _state.value.copy(lastMessage = message) }
        }
        viewModelScope.launch { attachFamilyListener() }
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
                    ReminderScheduler(container.appContextForChannels).scheduleNext(settings)
                }
            }
    }

    private fun tick() =
        kotlinx.coroutines.flow.flow {
            while (true) {
                emit(Unit)
                delay(30_000)
            }
        }

    private fun MutableStateFlow<ChildUiState>.update(settings: ReminderSettings?) {
        val text = settings?.let(::formatNextReminder)
        value = value.copy(nextReminderText = text)
    }

    private fun formatNextReminder(settings: ReminderSettings): String? {
        val slot = ReminderSchedule.nextSlot(System.currentTimeMillis(), settings) ?: return null
        val time =
            Instant.ofEpochMilli(slot.epochMillis)
                .atZone(ZoneId.of(settings.timezone))
                .format(DateTimeFormatter.ofPattern("h:mm a", Locale.Builder().setLanguage("es").setRegion("VE").build()))
        return time
    }

    override fun onCleared() {
        familyListener?.remove()
    }
}
