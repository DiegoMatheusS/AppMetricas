package br.com.medidor.app.data

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import br.com.medidor.app.domain.ReportPagination
import java.io.OutputStream

/**
 * Offline A4 PDF, with page numbers and clear estimated-measurement disclaimers.
 * The PDF only contains the supplied text; never silently reads other projects.
 */
object PdfReportExporter {
    fun write(lines: List<String>, target: OutputStream): Int {
        val pages = ReportPagination.pages(lines, linesPerPage = 41)
        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, rows ->
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(595, 842, index + 1).create()
                )
                val canvas = page.canvas
                val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(25, 78, 93)
                    textSize = 15f
                    isFakeBoldText = true
                }
                val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.DKGRAY
                    textSize = 10.5f
                }
                val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.GRAY
                    textSize = 9f
                }
                canvas.drawText("MEDIDOR | Relatorio local", 42f, 42f, heading)
                canvas.drawLine(42f, 54f, 553f, 54f, heading)
                rows.forEachIndexed { row, text ->
                    canvas.drawText(text, 43f, 83f + row * 17.3f, body)
                }
                canvas.drawText(
                    "Estimativas sem certificacao | Pagina ${index + 1} / ${pages.size}",
                    43f, 817f, footer
                )
                document.finishPage(page)
            }
            document.writeTo(target)
            target.flush()
            return pages.size
        } finally {
            document.close()
            target.close()
        }
    }
}
