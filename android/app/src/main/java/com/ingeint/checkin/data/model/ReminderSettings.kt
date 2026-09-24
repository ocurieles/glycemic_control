package com.ingeint.checkin.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings de recordatorios (docs/03 §1), tal como los cachea el niño en DataStore
 * tras un push "sync" (docs/06). La lógica de slots (`ReminderSchedule`) llega en F3;
 * esto es solo el modelo de datos + su (de)serialización a JSON para DataStore.
 */
data class ReminderSettings(
    val enabled: Boolean = true,
    val intervalMinutes: Int = 10,
    val days: List<Int> = listOf(1, 2, 3, 4, 5),
    val startTime: String = "07:00",
    val endTime: String = "13:00",
    val escalationMinutes: Int = 5,
    val nudgeMinutes: Int = 3,
    val timezone: String = "America/Caracas",
    val lowThreshold: Int = 70,
    val highThreshold: Int = 180,
    val smsFallbackEnabled: Boolean = false,
    val smsNumbers: List<String> = emptyList(),
    val childPhone: String? = null,
) {
    fun toJson(): String =
        JSONObject().apply {
            put("enabled", enabled)
            put("intervalMinutes", intervalMinutes)
            put("days", JSONArray(days))
            put("startTime", startTime)
            put("endTime", endTime)
            put("escalationMinutes", escalationMinutes)
            put("nudgeMinutes", nudgeMinutes)
            put("timezone", timezone)
            put("lowThreshold", lowThreshold)
            put("highThreshold", highThreshold)
            put("smsFallbackEnabled", smsFallbackEnabled)
            put("smsNumbers", JSONArray(smsNumbers))
            put("childPhone", childPhone)
        }.toString()

    companion object {
        fun fromJson(json: String): ReminderSettings? =
            runCatching {
                val o = JSONObject(json)
                ReminderSettings(
                    enabled = o.optBoolean("enabled", true),
                    intervalMinutes = o.optInt("intervalMinutes", 10),
                    days = o.optJSONArray("days")?.toIntList() ?: listOf(1, 2, 3, 4, 5),
                    startTime = o.optString("startTime", "07:00"),
                    endTime = o.optString("endTime", "13:00"),
                    escalationMinutes = o.optInt("escalationMinutes", 5),
                    nudgeMinutes = o.optInt("nudgeMinutes", 3),
                    timezone = o.optString("timezone", "America/Caracas"),
                    lowThreshold = o.optInt("lowThreshold", 70),
                    highThreshold = o.optInt("highThreshold", 180),
                    smsFallbackEnabled = o.optBoolean("smsFallbackEnabled", false),
                    smsNumbers = o.optJSONArray("smsNumbers")?.toStringList() ?: emptyList(),
                    childPhone = o.optString("childPhone").takeIf { it.isNotEmpty() },
                )
            }.getOrNull()

        /** Construye settings desde el mapa crudo que trae `families/{fid}.settings` de Firestore. */
        fun fromMap(map: Map<String, Any?>): ReminderSettings =
            ReminderSettings(
                enabled = map["enabled"] as? Boolean ?: true,
                intervalMinutes = (map["intervalMinutes"] as? Number)?.toInt() ?: 10,
                days = (map["days"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: listOf(1, 2, 3, 4, 5),
                startTime = map["startTime"] as? String ?: "07:00",
                endTime = map["endTime"] as? String ?: "13:00",
                escalationMinutes = (map["escalationMinutes"] as? Number)?.toInt() ?: 5,
                nudgeMinutes = (map["nudgeMinutes"] as? Number)?.toInt() ?: 3,
                timezone = map["timezone"] as? String ?: "America/Caracas",
                lowThreshold = (map["lowThreshold"] as? Number)?.toInt() ?: 70,
                highThreshold = (map["highThreshold"] as? Number)?.toInt() ?: 180,
                smsFallbackEnabled = map["smsFallbackEnabled"] as? Boolean ?: false,
                smsNumbers = (map["smsNumbers"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                childPhone = map["childPhone"] as? String,
            )
    }
}

private fun JSONArray.toIntList(): List<Int> = List(length()) { getInt(it) }

private fun JSONArray.toStringList(): List<String> = List(length()) { getString(it) }
