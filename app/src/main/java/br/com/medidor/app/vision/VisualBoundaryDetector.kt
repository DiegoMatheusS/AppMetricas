package br.com.medidor.app.vision

import br.com.medidor.app.domain.ContourPixel
import br.com.medidor.app.domain.PlanarContourResult
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

internal data class VisualBoundary(val corners: List<ContourPixel>, val region: PlanarContourResult)

internal object VisualBoundaryDetector {
    fun find(pixels: IntArray, width: Int, height: Int): VisualBoundary? {
        require(width in 8..512 && height in 8..512 && pixels.size == width * height)
        NativeVision.requireAvailable()
        return MatScope().use { scope ->
            val gray = scope.own(Mat(height, width, CvType.CV_8UC1))
            gray.put(0, 0, ByteArray(pixels.size) { i ->
                val color = pixels[i]
                ((299 * ((color ushr 16) and 255) + 587 * ((color ushr 8) and 255) +
                    114 * (color and 255)) / 1000).toByte()
            })
            val blurred = scope.own(Mat())
            val edges = scope.own(Mat())
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 35.0, 105.0)
            val kernel = scope.own(Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0)))
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = scope.own(Mat())
            try {
                Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                contours.mapNotNull { contour ->
                    val area = Imgproc.contourArea(contour)
                    val box = Imgproc.boundingRect(contour)
                    if (area / pixels.size !in .015.. .65 || box.x <= 2 || box.y <= 2 ||
                        box.x + box.width >= width - 2 || box.y + box.height >= height - 2) return@mapNotNull null
                    val curve = scope.own(MatOfPoint2f(*contour.toArray()))
                    val polygon = scope.own(MatOfPoint2f())
                    Imgproc.approxPolyDP(curve, polygon, Imgproc.arcLength(curve, true) * .025, true)
                    val corners = polygon.toArray().map { ContourPixel(it.x.toInt(), it.y.toInt()) }
                    if (corners.size !in 3..12) return@mapNotNull null
                    val centerDistance = abs((box.x + box.width / 2.0) / width - .5) +
                        abs((box.y + box.height / 2.0) / height - .5)
                    val result = PlanarContourResult(width, height, box.x, box.y,
                        box.x + box.width - 1, box.y + box.height - 1, area.toInt(), corners)
                    VisualBoundary(corners, result) to (area * (1 - centerDistance.coerceAtMost(.9)))
                }.maxByOrNull { it.second }?.first
            } finally {
                contours.forEach(Mat::release)
            }
        }
    }
}
