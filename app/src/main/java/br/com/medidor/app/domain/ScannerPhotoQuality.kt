package br.com.medidor.app.domain

import kotlin.math.abs

/**
 * Fast photographic HEURISTICS; no feature matching, 3D reconstruction,
 * geometry estimation or guarantee of suitability for photogrammetry.
 */
data class ScannerPhotoMetrics(
    val meanLuma: Double,
    val darkFraction: Double,
    val brightFraction: Double,
    val edgeEnergy: Double,
    val differenceHash: Long
) {
    fun warnings(): List<String> = buildList {
        if (darkFraction > .55 || meanLuma < .16) add("Foto muito escura")
        if (brightFraction > .55 || meanLuma > .87) add("Foto muito clara")
        if (edgeEnergy < .014) add("Poucos detalhes/contraste; foco possivelmente insuficiente")
    }
}

data class ScannerPhotoCheck(
    val slot: Int,
    val metrics: ScannerPhotoMetrics?,
    val warnings: List<String>
)

data class ScannerQualityReport(
    val checks: List<ScannerPhotoCheck>,
    val similarPairs: List<Pair<Int, Int>>,
    val missingSlots: List<Int>
) {
    val warningsCount: Int get() = checks.sumOf { it.warnings.size } + similarPairs.size
}

/**
 * Input: single photo already downsampled to 96x96 RGB pixels.
 * Each slot is analyzed independently. dHash can indicate very similar images
 * but CANNOT prove that the camera moved or that photos overlap geometrically.
 */
object ScannerPhotoQuality {
    const val THUMB_SIZE = 96

    fun evaluate(pixels: IntArray, width: Int, height: Int): ScannerPhotoMetrics {
        require(width >= 9 && height >= 8 && pixels.size == width * height) {
            "Imagem reduzida inválida."
        }
        val luma = IntArray(pixels.size) { index ->
            val argb = pixels[index]
            val r = argb ushr 16 and 0xff
            val g = argb ushr 8 and 0xff
            val b = argb and 0xff
            (r * 299 + g * 587 + b * 114) / 1000
        }
        var dark = 0
        var bright = 0
        var brightnessSum = 0L
        for (v in luma) {
            brightnessSum += v
            if (v <= 18) dark++
            if (v >= 238) bright++
        }
        var gradient = 0L
        var gradientPairs = 0
        for (y in 0 until height - 1) {
            for (x in 0 until width - 1) {
                val index = y * width + x
                gradient += abs(luma[index] - luma[index + 1])
                gradient += abs(luma[index] - luma[index + width])
                gradientPairs += 2
            }
        }
        var hash = 0L
        for (y in 0 until 8) {
            val row = y * (height - 1) / 7
            for (x in 0 until 8) {
                val left = x * (width - 1) / 8
                val right = (x + 1) * (width - 1) / 8
                if (luma[row * width + left] > luma[row * width + right]) {
                    hash = hash or (1L shl (y * 8 + x))
                }
            }
        }
        return ScannerPhotoMetrics(
            meanLuma = brightnessSum.toDouble() / (luma.size * 255.0),
            darkFraction = dark.toDouble() / luma.size,
            brightFraction = bright.toDouble() / luma.size,
            edgeEnergy = gradient.toDouble() / (gradientPairs * 255.0),
            differenceHash = hash
        )
    }

    fun similarPairs(metrics: Map<Int, ScannerPhotoMetrics>): List<Pair<Int, Int>> {
        val slots = metrics.keys.sorted()
        return buildList {
            for (i in slots.indices) for (j in i + 1 until slots.size) {
                val left = metrics.getValue(slots[i])
                val right = metrics.getValue(slots[j])
                // A blank/uniform photo can hash to 0 regardless of its content;
                // surface its low detail warning without claiming duplication.
                if (left.edgeEnergy < .014 || right.edgeEnergy < .014) continue
                if (java.lang.Long.bitCount(left.differenceHash xor right.differenceHash) <= 3) {
                    add(slots[i] to slots[j])
                }
            }
        }
    }

    fun summarize(checks: List<ScannerPhotoCheck>): ScannerQualityReport {
        val found = checks.map { it.slot }.toSet()
        return ScannerQualityReport(
            checks = checks.sortedBy { it.slot },
            similarPairs = similarPairs(checks.mapNotNull { result ->
                result.metrics?.let { result.slot to it }
            }.toMap()),
            missingSlots = (0 until ScannerCaptureGuide.REQUIRED_SHOTS).filterNot { it in found }
        )
    }
}
