package com.ingeint.checkin.ui.parent

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max

private val RANGE_BAND = Color(0xFF0CA30C).copy(alpha = 0.12f)
private val LINE = Color(0xFF05213E)
private val DOSE = Color(0xFFF05A3A)
private val MEAL = Color(0xFF5C6E24)
private val BASAL = Color(0xFF378ADD)
private val LOW_DOT = Color(0xFFD03B3B)
private val HIGH_DOT = Color(0xFFEC835A)
private const val GAP_MS = 45 * 60_000L

/**
 * Curva de glicemia del período (pedido 2026-10-16): la banda verde es el rango objetivo,
 * la línea une las lecturas automáticas (se corta si el sensor pasa más de 45 min sin
 * datos) y debajo van las marcas de dosis rápida (naranja), comidas (verde) y Tresiba (azul).
 */
@Composable
fun GlucoseChart(s: StatsResult, modifier: Modifier = Modifier, height: Int = 200) {
    Canvas(modifier = modifier.fillMaxWidth().height(height.dp)) {
        val markerH = 22.dp.toPx()
        val plotH = size.height - markerH
        val maxValue = max(300, ceil((s.series.maxOfOrNull { it.value } ?: 300) / 50.0).toInt() * 50)
        val minValue = 40
        val span = (s.periodEndMillis - s.periodStartMillis).toFloat()
        fun x(t: Long) = ((t - s.periodStartMillis) / span).coerceIn(0f, 1f) * size.width
        fun y(v: Int) = plotH - (v - minValue).toFloat() / (maxValue - minValue) * plotH

        drawRect(RANGE_BAND, Offset(0f, y(s.highThreshold)), Size(size.width, y(s.lowThreshold) - y(s.highThreshold)))
        listOf(s.lowThreshold, s.highThreshold).forEach {
            drawLine(
                Color(0xFF0CA30C).copy(alpha = 0.5f),
                Offset(0f, y(it)),
                Offset(size.width, y(it)),
                strokeWidth = 1.dp.toPx(),
            )
        }
        drawLine(Color.Gray.copy(alpha = 0.4f), Offset(0f, plotH), Offset(size.width, plotH), strokeWidth = 1.dp.toPx())

        val points = s.series.sortedBy { it.atMillis }
        if (s.continuousReadings > 0) {
            val path = Path()
            var last: SeriesPoint? = null
            points.forEach { p ->
                if (last == null || p.atMillis - last!!.atMillis > GAP_MS) path.moveTo(x(p.atMillis), y(p.value)) else path.lineTo(x(p.atMillis), y(p.value))
                last = p
            }
            drawPath(path, LINE, style = Stroke(width = 2.dp.toPx()))
        }
        // Lecturas sueltas (o la última) como puntos; rojos si bajas y naranja si altas.
        val dots = if (s.continuousReadings > 0) points.takeLast(1) else points
        dots.forEach { p ->
            val color = if (p.value < s.lowThreshold) LOW_DOT else if (p.value > s.highThreshold) HIGH_DOT else LINE
            drawCircle(color, 4.dp.toPx(), Offset(x(p.atMillis), y(p.value)))
        }

        val baseY = size.height - 4.dp.toPx()
        s.mealTimes.forEach { drawCircle(MEAL, 4.dp.toPx(), Offset(x(it), baseY - 4.dp.toPx())) }
        s.basalTimes.forEach {
            drawRect(BASAL, Offset(x(it) - 4.dp.toPx(), baseY - 8.dp.toPx()), Size(8.dp.toPx(), 8.dp.toPx()))
        }
        s.doseTimes.forEach {
            drawLine(DOSE, Offset(x(it), baseY), Offset(x(it), baseY - 16.dp.toPx()), strokeWidth = 2.dp.toPx())
        }
    }
}
