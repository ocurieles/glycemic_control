package com.ingeint.checkin.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Paleta de marca (ingeint.com): azul marino, naranja y verde lima. docs/06 pide
 * "colores sobrios en la UI del niño, sin urgencia visual salvo SOS" — por eso el
 * marino (sobrio) queda como `primary` en toda la app, y el naranja/lima quedan como
 * acentos secundarios/terciarios que el rol niño casi no usa.
 */
private val Navy = Color(0xFF05213E)
private val NavyContainer = Color(0xFF1B3A5C)
private val Orange = Color(0xFFF05A3A)
private val Lime = Color(0xFFC2DA65)

private val LightColors =
    lightColorScheme(
        primary = Navy,
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = NavyContainer,
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Orange,
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFDBCF),
        onSecondaryContainer = Color(0xFF5C1D0A),
        tertiary = Color(0xFF5C6E24),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Lime,
        onTertiaryContainer = Color(0xFF283405),
        background = Color(0xFFF9FAFB),
        onBackground = Color(0xFF13212E),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF13212E),
        // Sin esto, los roles neutros (tarjetas, contenedores) quedan en el gris
        // violeta por defecto de Material — no combina con marino/naranja/lima.
        surfaceVariant = Color(0xFFE3E7EB),
        onSurfaceVariant = Color(0xFF44474C),
        outline = Color(0xFF74777C),
        outlineVariant = Color(0xFFC4C7CC),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF3F4F6),
        surfaceContainer = Color(0xFFEDEEF1),
        surfaceContainerHigh = Color(0xFFE7E9EC),
        surfaceContainerHighest = Color(0xFFE2E3E6),
        inverseSurface = Color(0xFF2B3033),
        inverseOnSurface = Color(0xFFF1F0F3),
        inversePrimary = Color(0xFF9DC2EE),
    )
private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF9DC2EE),
        onPrimary = Color(0xFF0A2540),
        primaryContainer = NavyContainer,
        onPrimaryContainer = Color(0xFFD3E4FF),
        secondary = Color(0xFFFFB59D),
        onSecondary = Color(0xFF5C1D0A),
        secondaryContainer = Color(0xFF7A2A10),
        onSecondaryContainer = Color(0xFFFFDBCF),
        tertiary = Lime,
        onTertiary = Color(0xFF2E3A08),
        tertiaryContainer = Color(0xFF44540F),
        onTertiaryContainer = Color(0xFFDDF08F),
        background = Color(0xFF0E1A26),
        onBackground = Color(0xFFE1E2E5),
        surface = Color(0xFF13212E),
        onSurface = Color(0xFFE1E2E5),
        surfaceVariant = Color(0xFF43474E),
        onSurfaceVariant = Color(0xFFC4C7CC),
        outline = Color(0xFF8E9199),
        outlineVariant = Color(0xFF43474E),
        surfaceContainerLowest = Color(0xFF0B1219),
        surfaceContainerLow = Color(0xFF161F27),
        surfaceContainer = Color(0xFF1A232B),
        surfaceContainerHigh = Color(0xFF252E36),
        surfaceContainerHighest = Color(0xFF303941),
        inverseSurface = Color(0xFFE2E3E6),
        inverseOnSurface = Color(0xFF2B3033),
        inversePrimary = Color(0xFF05213E),
    )

/**
 * Botón de acción principal para las pantallas del padre (naranja de ingeint.com):
 * el `Button` de M3 usa `primary` (marino) por defecto, y dejaba toda la app "azul"
 * sin que el naranja de marca se viera en ningún lado visible (pedido 2026-09-27,
 * tras verlo instalado: "sigo viendo todo azul"). No se usa en pantallas del niño:
 * ahí el marino sobrio de `primary` sigue siendo el correcto (docs/06).
 */
@Composable
fun brandButtonColors(): ButtonColors =
    ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary,
    )

@Composable
fun CheckinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
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
