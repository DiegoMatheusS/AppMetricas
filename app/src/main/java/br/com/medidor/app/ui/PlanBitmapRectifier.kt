package br.com.medidor.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import br.com.medidor.app.domain.PlanPoint
import br.com.medidor.app.domain.PlanRectification

/**
 * Four-point projective warp of a known flat rectangular sheet.
 * This only corrects the SHEET perspective, not lens distortion or
 * perspective in 3D structures drawn on the sheet.
 */
internal object PlanBitmapRectifier {
    fun rectify(
        source: Bitmap,
        corners: List<PlanPoint>,
        sheetWidthCm: Double,
        sheetHeightCm: Double
    ): Bitmap {
        val size = PlanRectification.targetSize(
            corners, source.width, source.height, sheetWidthCm, sheetHeightCm
        ) ?: throw IllegalArgumentException(
            "Marque os quatro cantos da folha na ordem indicada e informe medidas válidas."
        )
        val src = FloatArray(8)
        corners.forEachIndexed { i, p ->
            src[2 * i] = p.x * source.width
            src[2 * i + 1] = p.y * source.height
        }
        val dst = floatArrayOf(
            0f, 0f,
            size.width.toFloat(), 0f,
            size.width.toFloat(), size.height.toFloat(),
            0f, size.height.toFloat()
        )
        val matrix = Matrix()
        require(matrix.setPolyToPoly(src, 0, dst, 0, 4)) {
            "Os cantos selecionados não permitem corrigir a perspectiva."
        }
        val result = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        Canvas(result).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        }
        return result
    }
}
