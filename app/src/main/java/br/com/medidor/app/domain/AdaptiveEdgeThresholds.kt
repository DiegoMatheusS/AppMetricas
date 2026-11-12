package br.com.medidor.app.domain

/** Canny thresholds adapted to frame brightness range, without an ML model. */
data class EdgeThresholds(val low: Double, val high: Double, val contrast: Int)

object AdaptiveEdgeThresholds {
    fun fromArgb(pixels: IntArray): EdgeThresholds? {
        if (pixels.isEmpty()) return null
        val histogram = IntArray(256)
        for (pixel in pixels) {
            val gray = (299 * ((pixel ushr 16) and 255) +
                587 * ((pixel ushr 8) and 255) +
                114 * (pixel and 255)) / 1000
            histogram[gray]++
        }
        fun quantile(fraction: Double): Int {
            val count = (pixels.size * fraction).toInt()
            var cumulative = 0
            for (i in histogram.indices) {
                cumulative += histogram[i]
                if (cumulative > count) return i
            }
            return 255
        }
        val spread = quantile(.90) - quantile(.10)
        if (spread < 10) return null
        return EdgeThresholds(
            low = (spread * .21).coerceIn(12.0, 70.0),
            high = (spread * .62).coerceIn(32.0, 175.0),
            contrast = spread
        )
    }
}
