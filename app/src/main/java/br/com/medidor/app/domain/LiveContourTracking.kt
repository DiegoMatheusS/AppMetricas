package br.com.medidor.app.domain

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Conservative image-space consistency check for color-segmented live frames.
 * Does NOT identify objects, track their true identity, or measure distance.
 */
data class LiveContourState(
    val result: PlanarContourResult?,
    val label: String
)

object LiveContourTracking {
    /**
     * Move the next frame's seed approximately with the accepted bounding box.
     * Keeps the picked region near the old object, but does not track identity.
     */
    fun followSeed(
        previous: PlanarContourResult, current: PlanarContourResult,
        previousSeed: NormalizedPoint
    ): NormalizedPoint? {
        if (check(previous,current).result == null) return null
        val dx = ((current.minX + current.maxX).toFloat() / (2 * current.width)) -
            ((previous.minX + previous.maxX).toFloat() / (2 * previous.width))
        val dy = ((current.minY + current.maxY).toFloat() / (2 * current.height)) -
            ((previous.minY + previous.maxY).toFloat() / (2 * previous.height))
        val x = (previousSeed.x+dx).coerceIn(0f,1f)
        val y = (previousSeed.y+dy).coerceIn(0f,1f)
        val left = current.minX.toFloat()/current.width
        val right = (current.maxX+1f)/current.width
        val top = current.minY.toFloat()/current.height
        val bottom = (current.maxY+1f)/current.height
        // If the translated seed landed outside the region, choose center.
        return if (x in left..right && y in top..bottom) NormalizedPoint(x,y)
        else NormalizedPoint((left+right)/2,(top+bottom)/2)
    }

    /** Intersection over union of the two normalized bounding boxes. */
    internal fun overlap(previous: PlanarContourResult?, current: PlanarContourResult): Double {
        if (previous == null) return 0.0
        val left = maxOf(previous.minX.toDouble() / previous.width, current.minX.toDouble() / current.width)
        val right = minOf((previous.maxX + 1.0) / previous.width, (current.maxX + 1.0) / current.width)
        val top = maxOf(previous.minY.toDouble() / previous.height, current.minY.toDouble() / current.height)
        val bottom = minOf((previous.maxY + 1.0) / previous.height, (current.maxY + 1.0) / current.height)
        val intersection = (right - left).coerceAtLeast(0.0) * (bottom - top).coerceAtLeast(0.0)
        return intersection / (previous.widthFraction * previous.heightFraction +
            current.widthFraction * current.heightFraction - intersection)
    }

    fun check(previous: PlanarContourResult?, current: PlanarContourResult?): LiveContourState {
        if (current == null) return LiveContourState(null, "Região não encontrada; toque novamente no objeto.")
        val areaFraction = current.pixelsInside.toDouble() / (current.width * current.height)
        if (areaFraction < .002 || areaFraction > .65)
            return LiveContourState(null, "Região muito pequena ou grande para acompanhar.")
        val prev = previous ?: return LiveContourState(current, "Contorno aproximado encontrado.")
        val cx = (current.minX + current.maxX).toDouble() / (2 * current.width)
        val cy = (current.minY + current.maxY).toDouble() / (2 * current.height)
        val px = (prev.minX + prev.maxX).toDouble() / (2 * prev.width)
        val py = (prev.minY + prev.maxY).toDouble() / (2 * prev.height)
        val move = hypot(cx-px,cy-py)
        val ratio = areaFraction / (prev.pixelsInside.toDouble()/(prev.width*prev.height))
        if (move > .18 || ratio !in .45..2.2)
            return LiveContourState(null, "Contorno instável; selecione novamente o objeto.")
        return LiveContourState(current, "Contorno aproximado atualizado.")
    }
}

/** One analyzer thread owns this state. A brief miss keeps the target, never its old overlay. */
internal class LiveContourStabilizer {
    var previous: PlanarContourResult? = null
        private set
    private var pending: PlanarContourResult? = null
    private var misses = 0

    fun update(candidate: LiveContourState, automatic: Boolean): LiveContourState {
        val result = candidate.result
        if (result == null) {
            pending = null
            if (++misses >= 3) previous = null
            return candidate
        }

        misses = 0
        if (automatic && previous == null && LiveContourTracking.overlap(pending, result) < .25) {
            pending = result
            return LiveContourState(null, "Confirmando o contorno. Mantenha a câmera firme.")
        }
        previous = result
        pending = null
        return candidate
    }
}
