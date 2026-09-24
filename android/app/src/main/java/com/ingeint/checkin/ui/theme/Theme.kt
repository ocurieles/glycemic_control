package com.ingeint.checkin.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// docs/06: "colores sobrios en la UI del niño". Paleta neutra, sin urgencia visual salvo SOS.
private val Primary = Color(0xFF1565C0)
private val PrimaryDark = Color(0xFF90CAF9)

private val LightColors =
    lightColorScheme(primary = Primary, secondary = Color(0xFF546E7A))
private val DarkColors =
    darkColorScheme(primary = PrimaryDark, secondary = Color(0xFFB0BEC5))

@Composable
fun CheckinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= 31 -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColors
            else -> LightColors
        }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
