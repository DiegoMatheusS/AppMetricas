package br.com.medidor.app.vision

import br.com.medidor.app.domain.AdaptiveEdgeThresholds
import br.com.medidor.app.domain.ContourPixel
import br.com.medidor.app.domain.PlanarContourResult
import br.com.medidor.app.domain.LiveContourTracking
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

internal data class VisualBoundary(val corners: List<ContourPixel>, val region: PlanarContourResult,
    val trace: List<ContourPixel> = corners)

internal object VisualBoundaryDetector {
    /**
     * Multiple controlled edge passes improve detection under uneven
     * illumination; this remains a 2D outline candidate, not object recognition.
     * Reject uniform frames rather than inventing a perimeter.
     */
    fun find(pixels: IntArray, width: Int, height: Int, previous: PlanarContourResult? = null): VisualBoundary? {
        return detect(pixels, width, height, previous, false).firstOrNull()
    }

    fun findAll(pixels: IntArray, width: Int, height: Int): List<VisualBoundary> =
        detect(pixels, width, height, null, true)

    private fun detect(pixels: IntArray, width: Int, height: Int, previous: PlanarContourResult?,
                       allObjects: Boolean): List<VisualBoundary> {
        require(width in 8..512 && height in 8..512 && pixels.size == width * height)
        val adaptive = AdaptiveEdgeThresholds.fromArgb(pixels) ?: return emptyList()
        NativeVision.requireAvailable()
        return MatScope().use { scope ->
            val gray = scope.own(Mat(height, width, CvType.CV_8UC1))
            gray.put(0, 0, ByteArray(pixels.size) { i ->
                val color = pixels[i]
                ((299 * ((color ushr 16) and 255) + 587 * ((color ushr 8) and 255) +
                    114 * (color and 255)) / 1000).toByte()
            })
            val blurred = scope.own(Mat())
            val equalized = scope.own(Mat())
            val contrast = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            try { contrast.apply(gray, equalized) } finally { contrast.collectGarbage(); contrast.clear() }
            val edges = scope.own(Mat())
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            val kernel = scope.own(Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0)
            ))
            val found = mutableListOf<Pair<VisualBoundary, Double>>()
            val passes = listOf(
                adaptive.low to adaptive.high,
                35.0 to 105.0,
                20.0 to 60.0
            ).distinct()
            for ((pass, thresholds) in (passes + (25.0 to 75.0)).withIndex()) {
                val (low, high) = thresholds
                Imgproc.Canny(if (pass == passes.size) equalized else blurred, edges, low, high)
                Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
                val contours = mutableListOf<MatOfPoint>()
                val hierarchy = scope.own(Mat())
                try {
                    Imgproc.findContours(edges, contours, hierarchy,
                        if (allObjects) Imgproc.RETR_LIST else Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                    for (contour in contours.take(1200)) {
                        val area = Imgproc.contourArea(contour)
                        val box = Imgproc.boundingRect(contour)
                        if (box.width < (if (allObjects) 6 else 9) || box.height < (if (allObjects) 6 else 9) ||
                            box.x <= 2 || box.y <= 2 ||
                            box.x + box.width >= width - 2 ||
                            box.y + box.height >= height - 2 ||
                            area / pixels.size !in (if (allObjects) .002.. .85 else .008.. .65)) continue
                        val fill = area / (box.width * box.height).coerceAtLeast(1)
                        if (fill !in .28..1.02) continue
                        val curve = scope.own(MatOfPoint2f(*contour.toArray()))
                        val polygon = scope.own(MatOfPoint2f())
                        Imgproc.approxPolyDP(curve, polygon,
                            Imgproc.arcLength(curve, true) * .025, true)
                        val corners = polygon.toArray().map {
                            ContourPixel(it.x.toInt(), it.y.toInt())
                        }
                        if (corners.size !in 3..12) continue
                        val rawTrace = contour.toArray()
                        val trace = rawTrace.filterIndexed { index, _ -> index % maxOf(1,(rawTrace.size+1599)/1600) == 0 }
                            .map { ContourPixel(it.x.toInt(),it.y.toInt()) }
                        val centerDistance =
                            abs((box.x + box.width / 2.0) / width - .5) +
                            abs((box.y + box.height / 2.0) / height - .5)
                        val region = PlanarContourResult(
                            width, height, box.x, box.y,
                            box.x + box.width - 1, box.y + box.height - 1,
                            area.toInt(), corners
                        )
                        val continuity = if (previous == null) 1.0 else
                            .6 + 1.4 * LiveContourTracking.overlap(previous, region)
                        val score = area * (1 - centerDistance.coerceAtMost(.9) * .55) *
                            (.5 + .5 * fill) * continuity
                        val duplicate = found.indexOfFirst { LiveContourTracking.overlap(it.first.region, region) > .85 }
                        if (duplicate < 0) found += VisualBoundary(corners, region, trace) to score
                        else if (score > found[duplicate].second) found[duplicate] = VisualBoundary(corners, region, trace) to score
                    }
                } finally {
                    contours.forEach(Mat::release)
                }
            }
            if (allObjects) {
                Imgproc.Canny(blurred, edges, adaptive.low, adaptive.high)
                val lines = scope.own(Mat())
                Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180, 30, maxOf(25.0, minOf(width, height) * .16), 4.0)
                for (row in 0 until minOf(lines.rows(), 100)) {
                    val line = lines.get(row, 0)
                    val a = ContourPixel(line[0].toInt(), line[1].toInt())
                    val b = ContourPixel(line[2].toInt(), line[3].toInt())
                    if (found.any { (boundary, _) -> boundary.corners.size >= 3 &&
                        a.x in boundary.region.minX..boundary.region.maxX && b.x in boundary.region.minX..boundary.region.maxX &&
                        a.y in boundary.region.minY..boundary.region.maxY && b.y in boundary.region.minY..boundary.region.maxY }) continue
                    val length = kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
                    if (length < 25) continue
                    val region = PlanarContourResult(width, height, minOf(a.x,b.x), minOf(a.y,b.y),
                        maxOf(a.x,b.x), maxOf(a.y,b.y), maxOf(1,length.toInt()), listOf(a,b))
                    if (found.none { LiveContourTracking.overlap(it.first.region, region) > .8 })
                        found += VisualBoundary(listOf(a,b), region) to (length * 10)
                }
            }
            found.sortedByDescending { it.second }.take(if (allObjects) 32 else 1).map { it.first }
        }
    }
}
