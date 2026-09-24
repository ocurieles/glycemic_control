package com.ingeint.checkin.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ingeint.checkin.data.model.ReminderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "checkin_prefs")

/** Un mensaje de los padres cacheado localmente (docs/03 §5 "Preferencias locales"). */
data class LastMessage(val text: String, val from: String, val atMillis: Long)

/**
 * Preferencias locales (docs/03 §5): rol, familia, settings cacheados, último mensaje.
 * Es la fuente de verdad de "¿ya estoy vinculado?" en el arranque de la app.
 */
class Prefs(private val context: Context) {
    private object Keys {
        val ROLE = stringPreferencesKey("role")
        val FAMILY_ID = stringPreferencesKey("family_id")
        val CHILD_NAME = stringPreferencesKey("child_name")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val SETTINGS_JSON = stringPreferencesKey("settings_json")
        val LAST_MESSAGE_TEXT = stringPreferencesKey("last_message_text")
        val LAST_MESSAGE_FROM = stringPreferencesKey("last_message_from")
        val LAST_MESSAGE_AT = longPreferencesKey("last_message_at")
        val LAST_SOS_ACK_AT = longPreferencesKey("last_sos_ack_at")
        val LAST_NUDGED_SLOT = stringPreferencesKey("last_nudged_slot")
        val CLOCK_OFFSET_MS = longPreferencesKey("clock_offset_ms")
    }

    val role: Flow<String?> = context.dataStore.data.map { it[Keys.ROLE] }
    val familyId: Flow<String?> = context.dataStore.data.map { it[Keys.FAMILY_ID] }
    val childName: Flow<String?> = context.dataStore.data.map { it[Keys.CHILD_NAME] }
    val displayName: Flow<String?> = context.dataStore.data.map { it[Keys.DISPLAY_NAME] }
    val settings: Flow<ReminderSettings?> =
        context.dataStore.data.map { it[Keys.SETTINGS_JSON]?.let(ReminderSettings::fromJson) }
    val lastMessage: Flow<LastMessage?> =
        context.dataStore.data.map { prefs ->
            val text = prefs[Keys.LAST_MESSAGE_TEXT] ?: return@map null
            LastMessage(text, prefs[Keys.LAST_MESSAGE_FROM] ?: "", prefs[Keys.LAST_MESSAGE_AT] ?: 0L)
        }

    suspend fun isLinked(): Boolean = role.first() != null && familyId.first() != null

    suspend fun saveLink(role: String, familyId: String, childName: String, displayName: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ROLE] = role
            prefs[Keys.FAMILY_ID] = familyId
            prefs[Keys.CHILD_NAME] = childName
            prefs[Keys.DISPLAY_NAME] = displayName
        }
    }

    suspend fun saveSettings(settings: ReminderSettings) {
        context.dataStore.edit { it[Keys.SETTINGS_JSON] = settings.toJson() }
    }

    suspend fun saveLastMessage(text: String, from: String, atMillis: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_MESSAGE_TEXT] = text
            prefs[Keys.LAST_MESSAGE_FROM] = from
            prefs[Keys.LAST_MESSAGE_AT] = atMillis
        }
    }

    suspend fun saveLastSosAckAt(atMillis: Long) {
        context.dataStore.edit { it[Keys.LAST_SOS_ACK_AT] = atMillis }
    }

    suspend fun saveClockOffsetMs(offsetMs: Long) {
        context.dataStore.edit { it[Keys.CLOCK_OFFSET_MS] = offsetMs }
    }

    val clockOffsetMs: Flow<Long> = context.dataStore.data.map { it[Keys.CLOCK_OFFSET_MS] ?: 0L }

    /** Último slot ("HH:mm") en el que ya ocurrió el refuerzo local (docs/06, evita doble nudge). */
    suspend fun saveLastNudgedSlot(slot: String) {
        context.dataStore.edit { it[Keys.LAST_NUDGED_SLOT] = slot }
    }

    val lastNudgedSlot: Flow<String?> = context.dataStore.data.map { it[Keys.LAST_NUDGED_SLOT] }

    /** Borra todo (al desvincular el teléfono). */
    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
