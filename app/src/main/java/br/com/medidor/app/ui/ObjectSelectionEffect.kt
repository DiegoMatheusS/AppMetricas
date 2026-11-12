package br.com.medidor.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.SweepGradient
import kotlin.math.sin

/** A single moving light sweep, followed by a quiet persistent outline. */
internal object ObjectSelectionEffect {
    fun draw(canvas: Canvas, points: List<PointF>, bounds: RectF, progress: Float, density: Float) {
        if (points.size < 2) return
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
            if (points.size >= 3) close()
        }
        val t = progress.coerceIn(0f, 1f)
        if (points.size >= 3) {
            val dim = Path().apply { fillType = Path.FillType.EVEN_ODD; addRect(bounds, Path.Direction.CW); addPath(path) }
            canvas.drawPath(dim, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb((82 * (t * 5).coerceAtMost(1f)).toInt(),0,0,0) })
        }
        val centerX = points.map { it.x }.average().toFloat()
        val centerY = points.map { it.y }.average().toFloat()
        val sweep = SweepGradient(centerX, centerY,
            intArrayOf(Color.rgb(192,245,101),Color.rgb(84,212,255),Color.rgb(179,120,255),Color.WHITE,Color.rgb(192,245,101)), null)
        sweep.setLocalMatrix(Matrix().apply { setRotate(t * 360, centerX, centerY) })
        val shine = sin(Math.PI * t).toFloat().coerceAtLeast(0f)
        for (width in listOf(16f, 9f, 4f)) canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = width * density; strokeJoin = Paint.Join.ROUND
            shader = sweep; alpha = (shine * if (width > 10) 25 else 55).toInt()
        })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = Color.rgb(192,245,101)
            strokeJoin = Paint.Join.ROUND
        })
        if (t < 1) {
            val measure = PathMeasure(path, points.size >= 3)
            val length = measure.length
            val start = t * length
            val light = Path()
            measure.getSegment(start, minOf(length, start + length * .28f), light, true)
            if (start + length * .28f > length) measure.getSegment(0f, start + length * .28f - length, light, true)
            canvas.drawPath(light, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 4f * density; strokeCap = Paint.Cap.ROUND
                shader = sweep
            })
        }
    }
}
