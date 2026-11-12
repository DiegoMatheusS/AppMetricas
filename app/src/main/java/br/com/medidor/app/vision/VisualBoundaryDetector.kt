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

internal data class VisualBoundary(val corners: List<ContourPixel>, val region: PlanarContourResult)

internal object VisualBoundaryDetector {
    /**
     * Multiple controlled edge passes improve detection under uneven
     * illumination; this remains a 2D outline candidate, not object recognition.
     * Reject uniform frames rather than inventing a perimeter.
     */
    fun find(pixels: IntArray, width: Int, height: Int, previous: PlanarContourResult? = null): VisualBoundary? {
        require(width in 8..512 && height in 8..512 && pixels.size == width * height)
        val adaptive = AdaptiveEdgeThresholds.fromArgb(pixels) ?: return null
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
            var best: Pair<VisualBoundary, Double>? = null
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
                        Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                    for (contour in contours) {
                        val area = Imgproc.contourArea(contour)
                        val box = Imgproc.boundingRect(contour)
                        if (box.width < 9 || box.height < 9 ||
                            box.x <= 2 || box.y <= 2 ||
                            box.x + box.width >= width - 2 ||
                            box.y + box.height >= height - 2 ||
                            area / pixels.size !in .008.. .65) continue
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
                        if (best == null || score > best!!.second) {
                            best = VisualBoundary(corners, region) to score
                        }
                    }
                } finally {
                    contours.forEach(Mat::release)
                }
            }
            best?.first
        }
    }
}
