package br.com.medidor.app.domain

/**
 * A small heuristic for warning about blank/low-detail reference photos.
 * ARCore performs the actual image-quality validation and tracking; this
 * does not guarantee image detection.
 */
object PlanImageFeatures {
    fun edgeFraction(argbPixels: IntArray, width: Int, height: Int): Double {
        require(width >= 2 && height >= 2 && argbPixels.size == width * height)
        fun luma(pixel: Int): Int {
            val r = (pixel ushr 16) and 255
            val g = (pixel ushr 8) and 255
            val b = pixel and 255
            return (r * 299 + g * 587 + b * 114) / 1000
        }
        var edges = 0
        var count = 0
        for (y in 0 until height - 1) {
            for (x in 0 until width - 1) {
                val pos = y * width + x
                val v = luma(argbPixels[pos])
                val horizontal = kotlin.math.abs(v - luma(argbPixels[pos + 1]))
                val vertical = kotlin.math.abs(v - luma(argbPixels[pos + width]))
                if (horizontal > 28 || vertical > 28) edges++
                count++
            }
        }
        return edges.toDouble() / count
    }
}
