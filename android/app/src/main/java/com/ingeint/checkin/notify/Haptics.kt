package com.ingeint.checkin.notify

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Patrones de vibración (docs/01 "Patrones de vibración (contrato)").
 * CLAUDE.md regla 2: en rutas del rol niño SOLO se usa esto — nunca `setSound`
 * con un URI, `RingtoneManager`, `MediaPlayer` ni `ToneGenerator`.
 */
object VibrationPattern {
    /** Recordatorio / refuerzo. */
    val REMINDER = longArrayOf(0, 250, 150, 250)

    /** Mensaje de los padres. */
    val MESSAGE = longArrayOf(0, 200, 120, 200, 120, 200)

    /** Revisión o SOS recibido por el servidor. */
    val CONFIRM = longArrayOf(0, 700)

    /** Un padre respondió al SOS. */
    val SEEN = longArrayOf(0, 700, 250, 700)

    /** Feedback inmediato al tocar. */
    val TAP = longArrayOf(0, 40)
}

/**
 * Vibra con `USAGE_ALARM` (docs/06 "Vibración"), lo que hace que vibre aunque el
 * teléfono esté en silencio (a validar por modelo/fabricante, ver docs/09).
 */
object Haptics {
    fun vibrate(context: Context, pattern: LongArray) {
        val vibrator = vibratorOf(context) ?: return
        val effect = VibrationEffect.createWaveform(pattern, -1)
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
    }

    private fun vibratorOf(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
