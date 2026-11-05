package br.com.medidor.app.vision

import android.graphics.Bitmap
import br.com.medidor.app.domain.*
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

internal object PlanImageRecognition {
    fun detect(bitmap: Bitmap, floor: PlanFloor): PlanRecognitionDraft {
        NativeVision.requireAvailable()
        require(floor.calibrated) { "Calibre uma distância da planta antes de reconhecer as paredes." }
        val ratio = minOf(1.0, 960.0 / maxOf(bitmap.width, bitmap.height))
        val small = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(),
            (bitmap.height * ratio).toInt(), true)
        try {
            return MatScope().use { scope ->
                val rgba = scope.own(Mat())
                val gray = scope.own(Mat())
                val binary = scope.own(Mat())
                Utils.bitmapToMat(small, rgba)
                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.threshold(gray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
                val w = gray.cols()
                val h = gray.rows()
                val horizontal = scope.own(Mat())
                val vertical = scope.own(Mat())
                val hk = scope.own(Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(maxOf(9.0, w * .025), 1.0)))
                val vk = scope.own(Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, maxOf(9.0, h * .025))))
                Imgproc.morphologyEx(binary, horizontal, Imgproc.MORPH_OPEN, hk)
                Imgproc.morphologyEx(binary, vertical, Imgproc.MORPH_OPEN, vk)
                val strokes = mutableListOf<PlanLine>()
                listOf(horizontal, vertical).forEach { lines ->
                    val detected = scope.own(Mat())
                    Imgproc.HoughLinesP(lines, detected, 1.0, Math.PI / 180, 25,
                        minOf(w, h) * .06, 3.0)
                    for (row in 0 until detected.rows()) {
                        val line = detected.get(row, 0)
                        val dx = abs(line[0] - line[2])
                        val dy = abs(line[1] - line[3])
                        when {
                            dy < 3 && dx > 5 -> strokes += PlanLine(true, (line[1] + line[3]) / (2 * h),
                                minOf(line[0], line[2]) / w, maxOf(line[0], line[2]) / w)
                            dx < 3 && dy > 5 -> strokes += PlanLine(false, (line[0] + line[2]) / (2 * w),
                                minOf(line[1], line[3]) / h, maxOf(line[1], line[3]) / h)
                        }
                    }
                }
                val pixelsPerMeter = checkNotNull(FloorPlanMath.pixelsPerMeter(floor)) * w / floor.imageWidth
                val tolerance = (.13 * pixelsPerMeter / maxOf(w, h)).coerceIn(.003, .015)
                val merged = PlanRecognition.mergeStrokes(strokes, tolerance)
                val walls = mutableListOf<PlanWall>()
                val openings = mutableListOf<PlanOpening>()
                val visited = mutableSetOf<Int>()
                for (index in merged.indices) {
                    if (index in visited || walls.size >= 300) continue
                    var segment = merged[index]
                    val gaps = mutableListOf<Pair<Double, Double>>()
                    for (otherIndex in merged.indices) {
                        if (otherIndex == index || otherIndex in visited) continue
                        val other = merged[otherIndex]
                        if (other.horizontal != segment.horizontal || abs(other.offset - segment.offset) > tolerance) continue
                        val axisPixels = if (segment.horizontal) w else h
                        val gap = (other.start - segment.end) * axisPixels / pixelsPerMeter
                        if (gap in .55..1.6) {
                            gaps += segment.end to other.start
                            visited += otherIndex
                            segment = segment.copy(end = other.end)
                        }
                    }
                    visited += index
                    fun point(axis: Double) = if (segment.horizontal)
                        PlanPoint(axis.coerceIn(0.0, 1.0).toFloat(), segment.offset.coerceIn(0.0, 1.0).toFloat())
                    else PlanPoint(segment.offset.coerceIn(0.0, 1.0).toFloat(), axis.coerceIn(0.0, 1.0).toFloat())
                    val wallIndex = walls.size
                    walls += PlanWall(point(segment.start), point(segment.end))
                    for ((start, end) in gaps) {
                        val center = (start + end) / 2
                        val axisPixels = if (segment.horizontal) w else h
                        val widthMeters = (end - start) * axisPixels / pixelsPerMeter
                        val px = if (segment.horizontal) (center * w).toInt() else (segment.offset * w).toInt()
                        val py = if (segment.horizontal) (segment.offset * h).toInt() else (center * h).toInt()
                        var dark = 0
                        var total = 0
                        val band = (.12 * pixelsPerMeter).toInt().coerceIn(2, 12)
                        for (y in maxOf(0, py - band)..minOf(h - 1, py + band))
                            for (x in maxOf(0, px - band)..minOf(w - 1, px + band)) {
                                total++
                                if (binary.get(y, x)[0] > 0) dark++
                            }
                        val window = dark.toDouble() / total > .18
                        openings += PlanOpening(wallIndex, if (window) OpeningType.WINDOW else OpeningType.DOOR,
                            (center - segment.start) / (segment.end - segment.start), widthMeters,
                            if (window) .9 else 0.0, if (window) 1.1 else 2.1)
                    }
                }
                PlanRecognitionDraft(walls, openings)
            }
        } finally { if (small !== bitmap) small.recycle() }
    }
}
