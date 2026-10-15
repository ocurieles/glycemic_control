package com.ingeint.checkin.ui.parent

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ES_VE = Locale.Builder().setLanguage("es").setRegion("VE").build()
private val DATE_LONG = DateTimeFormatter.ofPattern("dd/MM/yyyy", ES_VE)
private val DATE_SHORT = DateTimeFormatter.ofPattern("dd/MM", ES_VE)

private const val PAGE_W = 595
private const val PAGE_H = 842
private const val MARGIN = 40f
private const val BOTTOM = 790f

private const val NAVY = 0xFF05213E.toInt()
private const val C_LOW = 0xFFD03B3B.toInt()
private const val C_OK = 0xFF0CA30C.toInt()
private const val C_HIGH = 0xFFFAB219.toInt()
private const val C_VERY_HIGH = 0xFFEC835A.toInt()

fun formatOneDecimal(value: Double): String = String.format(ES_VE, "%.1f", value)

/** PDF de resumen para el endocrinólogo (pedido 2026-10-15), dibujado con `PdfDocument`. */
object StatsPdf {
    fun write(context: Context, childName: String, stats: StatsResult): File {
        val doc = PdfDocument()
        val writer = Writer(doc)
        writer.header(childName, stats)
        writer.glucoseSection(stats)
        writer.insulinSection(stats)
        writer.finish()
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "resumen-${stats.lastDay}.pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private class Writer(private val doc: PdfDocument) {
        private var pageNumber = 0
        private lateinit var page: PdfDocument.Page
        private var y = MARGIN

        private val title = paint(20f, bold = true, color = NAVY)
        private val h2 = paint(14f, bold = true, color = NAVY)
        private val body = paint(11f)
        private val small = paint(9f, color = Color.DKGRAY)

        private fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }

        init {
            newPage()
        }

        private fun newPage() {
            if (pageNumber > 0) {
                footer()
                doc.finishPage(page)
            }
            pageNumber++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
            y = MARGIN
        }

        private fun ensure(height: Float) {
            if (y + height > BOTTOM) newPage()
        }

        private fun footer() {
            val c = page.canvas
            c.drawText(
                "Basado en lecturas puntuales tomadas al revisarse y al registrar insulina; no es una curva continua del sensor.",
                MARGIN,
                810f,
                small,
            )
            c.drawText("Registro de apoyo: no sustituye las indicaciones médicas. Página $pageNumber", MARGIN, 822f, small)
        }

        fun finish() {
            footer()
            doc.finishPage(page)
        }

        private fun line(text: String, p: Paint = body, gap: Float = 16f) {
            ensure(gap)
            page.canvas.drawText(text, MARGIN, y + p.textSize, p)
            y += gap
        }

        fun header(childName: String, s: StatsResult) {
            line("Resumen de glicemia e insulina", title, 28f)
            line(
                "$childName · últimos ${s.periodDays} días (${s.firstDay.format(DATE_LONG)} – ${s.lastDay.format(DATE_LONG)})",
                body,
                18f,
            )
            line("Generado el ${java.time.LocalDate.now().format(DATE_LONG)} con Check-in (INGEINT C.A.)", small, 22f)
        }

        fun glucoseSection(s: StatsResult) {
            line("Glicemia", h2, 22f)
            val g = s.glucose
            if (g == null) {
                line("Sin lecturas de glicemia en este período.", body, 24f)
                return
            }
            line("Lecturas: ${g.count} · Promedio: ${g.mean} mg/dL · Mediana: ${g.median} mg/dL", body)
            line("Mínimo: ${g.min} · Máximo: ${g.max} · Desviación típica: ±${g.sd} mg/dL", body)
            line("Rango objetivo: ${s.lowThreshold}–${s.highThreshold} mg/dL", body, 22f)

            ensure(60f)
            val barW = PAGE_W - 2 * MARGIN
            var x = MARGIN
            val segments =
                listOf(
                    g.lowPct to C_LOW,
                    g.inRangePct to C_OK,
                    g.highPct to C_HIGH,
                    g.veryHighPct to C_VERY_HIGH,
                )
            segments.forEach { (pct, color) ->
                val w = (barW * pct / 100.0).toFloat()
                if (w > 0f) {
                    page.canvas.drawRect(RectF(x, y, x + w, y + 18f), Paint().apply { this.color = color })
                    x += w
                }
            }
            y += 28f
            line(
                "Bajo ${pct(g.lowPct)} · En rango ${pct(g.inRangePct)} · Alto ${pct(g.highPct)} · Muy alto (>250) ${pct(g.veryHighPct)}",
                body,
                24f,
            )

            if (s.hourly.isNotEmpty()) {
                ensure(130f)
                line("Promedio por hora del día (mg/dL)", body, 14f)
                val chartH = 80f
                val colW = (PAGE_W - 2 * MARGIN) / 24f
                val byHour = s.hourly.associateBy { it.hour }
                for (h in 0 until 24) {
                    if (h % 3 == 0) page.canvas.drawText("$h h", MARGIN + h * colW, y + chartH + 11f, small)
                    val p = byHour[h] ?: continue
                    val bh = (p.mean / 300f * chartH).coerceIn(2f, chartH)
                    val color = if (p.mean < s.lowThreshold) C_LOW else if (p.mean <= s.highThreshold) C_OK else if (p.mean <= 250) C_HIGH else C_VERY_HIGH
                    val left = MARGIN + h * colW
                    page.canvas.drawRect(RectF(left + 1, y + chartH - bh, left + colW - 1, y + chartH), Paint().apply { this.color = color })
                }
                y += chartH + 24f
            }
        }

        fun insulinSection(s: StatsResult) {
            ensure(80f)
            line("Insulina", h2, 22f)
            line(
                "Total: ${formatOneDecimal(s.totalUnits)} U · Promedio diario: ${formatOneDecimal(s.avgDailyUnits)} U · Dosis por día: ${formatOneDecimal(s.dosesPerDay)}",
                body,
                22f,
            )
            s.response?.let { r ->
                line("Glicemia al aplicar la dosis vs. la revisión siguiente", body, 15f)
                line(
                    "${r.pairs} dosis emparejadas · antes ${r.avgBefore} → después ${r.avgAfter} mg/dL (${signed(r.avgDelta)}) · ${r.avgMinutes} min después, en promedio",
                    body,
                    22f,
                )
            }

            ensure(40f)
            val cols = floatArrayOf(MARGIN, MARGIN + 110f, MARGIN + 210f, MARGIN + 290f)
            val head = arrayOf("Fecha", "Unidades", "Dosis", "Glicemia prom.")
            head.forEachIndexed { i, t -> page.canvas.drawText(t, cols[i], y + 11f, paint(10f, bold = true)) }
            y += 18f
            val p = paint(10f)
            s.days.asReversed().forEach { d ->
                ensure(14f)
                page.canvas.drawText(d.date.format(DATE_SHORT), cols[0], y + 10f, p)
                page.canvas.drawText(if (d.doses == 0) "—" else formatOneDecimal(d.units), cols[1], y + 10f, p)
                page.canvas.drawText(if (d.doses == 0) "—" else d.doses.toString(), cols[2], y + 10f, p)
                page.canvas.drawText(d.avgGlucose?.let { "$it mg/dL" } ?: "—", cols[3], y + 10f, p)
                y += 14f
            }
        }

        private fun pct(v: Double) = String.format(ES_VE, "%.0f %%", v)

        private fun signed(v: Int) = if (v > 0) "+$v" else v.toString()
    }
}
