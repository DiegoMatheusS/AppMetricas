package br.com.medidor.app.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import br.com.medidor.app.domain.DxfPreview
import br.com.medidor.app.domain.DxfLine
import br.com.medidor.app.domain.DxfDrawingLayout
import br.com.medidor.app.domain.DxfWallImport
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/** Parsed drawing stays in memory until the user confirms layer/unit conversion. */
internal data class ImportedDxf(
    val lines: List<DxfLine>,
    val layout: DxfDrawingLayout
)

/** Safely turns a chosen PDF page or supported DXF lines into a plan bitmap. */
internal object ArchitectDocumentImport {
    fun pageCount(resolver: ContentResolver, uri: Uri): Int {
        val handle = resolver.openFileDescriptor(uri, "r") ?: error("PDF não pode ser aberto.")
        PdfRenderer(handle).use { pdf ->
            return pdf.pageCount.coerceAtMost(1000)
        }
    }

    fun pdfPage(resolver: ContentResolver, uri: Uri, pageIndex: Int): Bitmap {
        val handle = resolver.openFileDescriptor(uri, "r") ?: error("PDF não pode ser aberto.")
        PdfRenderer(handle).use { pdf ->
            require(pageIndex in 0 until pdf.pageCount) { "Página não encontrada no PDF." }
            pdf.openPage(pageIndex).use { page ->
                val ratio = min(2048f / page.width, 2048f / page.height).coerceAtMost(3f)
                val w = (page.width * ratio).toInt().coerceIn(1,2048)
                val h = (page.height * ratio).toInt().coerceIn(1,2048)
                val image = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                try {
                    image.eraseColor(Color.WHITE)
                    page.render(image,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return image
                } catch (error: Exception) {
                    image.recycle()
                    throw error
                }
            }
        }
    }

    fun readDxf(resolver: ContentResolver, uri: Uri): ImportedDxf {
        val input = resolver.openInputStream(uri) ?: error("DXF não pode ser aberto.")
        val text = input.use {
            val data = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = it.read(buffer)
                if (n == -1) break
                require(data.size() + n <= 4_000_000) { "DXF acima de 4 MB." }
                data.write(buffer,0,n)
            }
            data.toString(Charsets.UTF_8.name())
        }
        val lines = DxfPreview.parse(text)
        return ImportedDxf(lines, DxfWallImport.layout(lines))
    }

    fun renderDxf(drawing: ImportedDxf): Bitmap {
        val layout = drawing.layout
        val result = Bitmap.createBitmap(layout.width,layout.height,Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(result)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(31,68,99)
                strokeWidth = 2.5f
                style = Paint.Style.STROKE
            }
            drawing.lines.forEach { line ->
                canvas.drawLine(
                    layout.imageX(line.x1),layout.imageY(line.y1),
                    layout.imageX(line.x2),layout.imageY(line.y2),paint
                )
            }
            return result
        } catch (e: Exception) {
            result.recycle()
            throw e
        }
    }

    /** Compatibility with the original bitmap-only DXF import. */
    fun dxf(resolver: ContentResolver, uri: Uri): Bitmap =
        renderDxf(readDxf(resolver, uri))

}
