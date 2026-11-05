package br.com.medidor.app.domain

import kotlin.math.abs

data class CountRegion(
    val left: Int, val top: Int, val right: Int, val bottom: Int, val area: Int,
    val outline: List<ContourPixel> = emptyList(),
    val meanRed: Int = -1, val meanGreen: Int = -1, val meanBlue: Int = -1,
    val principalAspect: Double? = null,
    val shapeFill: Double? = null
)

/**
 * Offline, experimental foreground segmentation against the border background.
 * Removes isolated noise and traces component boundaries. Joined or similarly
 * colored objects may still count as a single region.
 */
object LiveObjectCounter {
    fun detect(pixels: IntArray, width: Int, height: Int, sensitivity: Int = 45, referenceArea: Double? = null,
               reference: PieceAppearance? = null): List<CountRegion> {
        require(width in 8..512 && height in 8..512 && pixels.size == width * height)
        require(sensitivity in 15..100)
        val rs = IntArray(256); val gs = IntArray(256); val bs = IntArray(256)
        var samples = 0
        for (y in 0 until height) for (x in 0 until width) {
            if (x < 3 || y < 3 || x >= width - 3 || y >= height - 3) {
                val p = pixels[y * width + x]
                rs[(p ushr 16) and 255]++
                gs[(p ushr 8) and 255]++
                bs[p and 255]++
                samples++
            }
        }
        fun median(hist: IntArray): Int {
            var total = 0
            for (i in hist.indices) {
                total += hist[i]
                if (total > samples / 2) return i
            }
            return 255
        }
        val bgR = median(rs); val bgG = median(gs); val bgB = median(bs)
        val raw = BooleanArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            raw[i] = maxOf(
                abs(((p ushr 16) and 255) - bgR),
                abs(((p ushr 8) and 255) - bgG),
                abs((p and 255) - bgB)
            ) >= sensitivity
        }
        // 3x3 majority filter removes pepper noise and reconnects small gaps.
        val mask = BooleanArray(raw.size)
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            var neighbors = 0
            for (dy in -1..1) for (dx in -1..1)
                if (raw[(y + dy) * width + x + dx]) neighbors++
            mask[y * width + x] = neighbors >= 5
        }

        val components = PieceSegmentation.components(mask, width, height)
        val described = components.map { it to describe(it, pixels, width, height) }
        val matchingAreas = if (reference == null) emptyList() else described.mapNotNull { (_, region) ->
            region?.takeIf { SimilarObjectCounter.matches(reference, it) }?.area?.toDouble()
        }.sorted()
        // Learn the current image scale from intact matching pieces before splitting joined regions.
        val area = matchingAreas.getOrNull(matchingAreas.size / 2) ?: referenceArea ?: reference?.area
        val regions = described.flatMap { (component, region) ->
            if (area == null || component.size < area * 1.65) listOfNotNull(region) else
                PieceSegmentation.split(component, width, height, area)
                    .mapNotNull { indices -> describe(indices, pixels, width, height) }
        }

        return regions.sortedByDescending { it.area }.take(100)
    }
    private fun describe(indices: IntArray, pixels: IntArray, width: Int, height: Int): CountRegion? {
        val minArea = maxOf(24, pixels.size / 600)
        if (indices.size < minArea || indices.size > pixels.size / 3) return null
        val left = indices.minOf { it % width }
        val right = indices.maxOf { it % width }
        val top = indices.minOf { it / width }
        val bottom = indices.maxOf { it / width }
        if (left <= 2 || top <= 2 || right >= width - 3 || bottom >= height - 3 ||
            right - left < 3 || bottom - top < 3) return null
        val membership = indices.toHashSet()
        val outline = indices.filter { index ->
            index - 1 !in membership || index + 1 !in membership ||
                index - width !in membership || index + width !in membership
        }.take(1600).map { ContourPixel(it % width, it / width) }
        val meanX = indices.sumOf { (it % width).toDouble() } / indices.size
        val meanY = indices.sumOf { (it / width).toDouble() } / indices.size
        val xx = indices.sumOf { val dx = it % width - meanX; dx * dx } / indices.size
        val yy = indices.sumOf { val dy = it / width - meanY; dy * dy } / indices.size
        val xy = indices.sumOf { (it % width - meanX) * (it / width - meanY) } / indices.size
        val delta = kotlin.math.sqrt((xx - yy) * (xx - yy) + 4 * xy * xy)
        val major = (xx + yy + delta) / 2
        val minor = (xx + yy - delta) / 2
        val aspect = kotlin.math.sqrt(major / minor.coerceAtLeast(.01))
        val fill = indices.size / (12 * kotlin.math.sqrt((xx * yy - xy * xy).coerceAtLeast(.01)))
        return CountRegion(left, top, right, bottom, indices.size, outline,
            (indices.sumOf { (pixels[it] ushr 16) and 255 } / indices.size),
            (indices.sumOf { (pixels[it] ushr 8) and 255 } / indices.size),
            (indices.sumOf { pixels[it] and 255 } / indices.size), aspect, fill.coerceIn(0.0, 1.0))
    }

}

/** Debounces fleeting count changes. Never claims accuracy or tracks identities. */
class LiveCountStabilizer(private val confirmations: Int = 3) {
    init { require(confirmations >= 1) }
    private var candidate: Int? = null
    private var streak = 0
    var stableCount: Int? = null
        private set

    fun update(count: Int): Int? {
        require(count >= 0)
        if (count == stableCount) {
            candidate = null
            streak = 0
            return stableCount
        }
        if (candidate == count) streak++ else { candidate = count; streak = 1 }
        if (streak >= confirmations) {
            stableCount = count
            candidate = null
            streak = 0
        }
        return stableCount
    }
    fun reset() { candidate = null; streak = 0; stableCount = null }
}
