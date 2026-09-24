package com.ingeint.checkin.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

/**
 * SOS sin conexión (docs/07 "SOS sin conexión"). El SMS **no** usa palabras médicas
 * (CLAUDE.md regla 3 aplica también aquí, aunque el destinatario sea el padre).
 */
object SmsFallback {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** Devuelve `true` si se intentó enviar a algún número (no garantiza la entrega). */
    fun send(
        context: Context,
        childName: String,
        timeLabel: String,
        numbers: List<String>,
        lat: Double?,
        lng: Double?,
    ): Boolean {
        if (!hasPermission(context) || numbers.isEmpty()) return false

        // Texto exacto de docs/07 "SOS sin conexión".
        val locationPart = if (lat != null && lng != null) " Ubicación: https://maps.google.com/?q=$lat,$lng" else ""
        val text = "$childName necesita ayuda ($timeLabel).$locationPart"

        val smsManager = context.getSystemService(SmsManager::class.java)
        var attempted = false
        for (number in numbers) {
            runCatching {
                val parts = smsManager.divideMessage(text)
                smsManager.sendMultipartTextMessage(number, null, parts, null, null)
                attempted = true
            }
        }
        return attempted
    }
}
