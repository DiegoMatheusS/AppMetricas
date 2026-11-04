package br.com.medidor.app.domain

import kotlin.math.abs
import kotlin.math.max

/**
 * Offline exemplar-based visual similarity for already-segmented connected regions.
 * It DOES NOT recognize semantic object categories or recover hidden/stacked pieces.
 * A piece must be selected by the user before counting matches.
 */
data class PieceAppearance(
    val aspectRatio: Double,
    val fillRatio: Double,
    val red: Int,
    val green: Int,
    val blue: Int
)

object SimilarObjectCounter {
    fun appearance(region: CountRegion): PieceAppearance? {
        if (region.area <= 0 || region.meanRed !in 0..255 ||
            region.meanGreen !in 0..255 || region.meanBlue !in 0..255) return null
        val w = region.right - region.left + 1
        val h = region.bottom - region.top + 1
        if (w <= 0 || h <= 0) return null
        return PieceAppearance(
            aspectRatio = w.toDouble() / h,
            fillRatio = region.area.toDouble() / (w * h),
            red = region.meanRed,
            green = region.meanGreen,
            blue = region.meanBlue
        )
    }

    /**
     * Shape and sampled foreground color must be similar. Absolute area is
     * intentionally not used: a piece can look larger when the phone moves.
     * Different pieces can still share this limited appearance descriptor.
     */
    fun matches(reference: PieceAppearance, candidate: CountRegion): Boolean {
        val test = appearance(candidate) ?: return false
        val relativeAspect = test.aspectRatio / reference.aspectRatio
        if (relativeAspect < 0.67 || relativeAspect > 1.5) return false
        if (abs(test.fillRatio - reference.fillRatio) > .20) return false
        val colorDelta = max(
            abs(test.red - reference.red),
            max(abs(test.green - reference.green), abs(test.blue - reference.blue))
        )
        return colorDelta <= 65
    }

    fun matches(reference: PieceAppearance, regions: List<CountRegion>): List<CountRegion> =
        regions.filter { matches(reference, it) }

    fun at(regions: List<CountRegion>, x: Int, y: Int): CountRegion? =
        regions
            .filter { x in it.left..it.right && y in it.top..it.bottom }
            .minByOrNull { (it.right - it.left + 1) * (it.bottom - it.top + 1) }
}
