package br.com.medidor.app.domain

import kotlin.math.abs

data class CountRegion(
    val left: Int, val top: Int, val right: Int, val bottom: Int, val area: Int,
    val outline: List<ContourPixel> = emptyList(),
    val meanRed: Int = -1, val meanGreen: Int = -1, val meanBlue: Int = -1
)

/**
 * Offline, experimental foreground segmentation against the border background.
 * Removes isolated noise and traces component boundaries. Joined or similarly
 * colored objects may still count as a single region.
 */
object LiveObjectCounter {
    fun detect(pixels: IntArray, width: Int, height: Int, sensitivity: Int = 45): List<CountRegion> {
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

        val seen = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        val regions = ArrayList<CountRegion>()
        val minArea = maxOf(24, pixels.size / 600)
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var head = 0; var tail = 1; queue[0] = start; seen[start] = true
            var minX = width; var minY = height; var maxX = 0; var maxY = 0
            var redSum = 0L; var greenSum = 0L; var blueSum = 0L
            while (head < tail) {
                val index = queue[head++]
                val x = index % width; val y = index / width
                val pixel = pixels[index]
                redSum += (pixel ushr 16) and 255
                greenSum += (pixel ushr 8) and 255
                blueSum += pixel and 255
                minX = minOf(minX, x); maxX = maxOf(maxX, x)
                minY = minOf(minY, y); maxY = maxOf(maxY, y)
                fun visit(i: Int) {
                    if (mask[i] && !seen[i]) { seen[i] = true; queue[tail++] = i }
                }
                if (x > 0) visit(index - 1)
                if (x + 1 < width) visit(index + 1)
                if (y > 0) visit(index - width)
                if (y + 1 < height) visit(index + width)
            }
            val area = tail
            val border = minX <= 2 || minY <= 2 || maxX >= width - 3 || maxY >= height - 3
            if (border || area < minArea || area > pixels.size / 3 ||
                maxX - minX < 3 || maxY - minY < 3) continue

            // Only pixels belonging to this component may form its contour.
            val component = BooleanArray(pixels.size)
            for (i in 0 until tail) component[queue[i]] = true
            val outline = ArrayList<ContourPixel>()
            for (i in 0 until tail) {
                val index = queue[i]
                val x = index % width; val y = index / width
                if (!component[index - 1] || !component[index + 1] ||
                    !component[index - width] || !component[index + width]) {
                    if (outline.size < 1600) outline.add(ContourPixel(x, y))
                }
            }
            regions.add(CountRegion(minX,minY,maxX,maxY,area,outline,
                (redSum / area).toInt(), (greenSum / area).toInt(),
                (blueSum / area).toInt()))
        }
        return regions.sortedByDescending { it.area }.take(100)
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
