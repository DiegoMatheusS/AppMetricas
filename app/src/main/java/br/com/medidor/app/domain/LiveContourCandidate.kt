package br.com.medidor.app.domain

import kotlin.math.abs

/**
 * Real-time image-space contour proposal finder.
 *
 * First tries region growing at the point the user touched, then a conservative
 * foreground component near that point. Without a user tap, it proposes the
 * largest reasonably central isolated component.
 *
 * This does not infer physical dimensions, object identity or depth.
 */
object LiveContourCandidate {
    fun find(
        pixels: IntArray,
        width: Int,
        height: Int,
        seed: NormalizedPoint?,
        tolerance: Int = 32,
        bounds: ContourSearchBounds? = null,
        previous: PlanarContourResult? = null
    ): LiveContourState {
        require(width in 8..512 && height in 8..512 && pixels.size == width * height)
        val sx = seed?.let { (it.x * (width - 1)).toInt().coerceIn(0, width - 1) }
        val sy = seed?.let { (it.y * (height - 1)).toInt().coerceIn(0, height - 1) }
        val inBounds = sx != null && sy != null &&
            (bounds == null || bounds.contains(sx, sy))

        if (seed != null && !inBounds) {
            return LiveContourState(null, "Toque dentro da área selecionada.")
        }

        // Keep the seed as a recoverable target when segmentation fails in
        // one transient frame; the UI decides when to reset or retry.
        if (inBounds) {
            val segmented = PlanarContourSegmentation.segment(
                pixels, width, height, sx!!, sy!!, tolerance, bounds
            )
            if (segmented != null) {
                val state = LiveContourTracking.check(previous, segmented)
                if (state.result != null) {
                    val visual = ContourVisualQuality.assess(segmented, previous, bounds)
                    if (visual.rating != ContourVisualRating.LOW) return state
                }
            }
        }

        val regions = LiveObjectCounter.detect(
            pixels, width, height, (tolerance + 12).coerceIn(15, 100)
        ).filter { region ->
            bounds == null || (region.left >= bounds.left &&
                region.right <= bounds.right &&
                region.top >= bounds.top &&
                region.bottom <= bounds.bottom)
        }
        val chosen = if (seed != null) {
            // A manual tap must not jump to an unrelated object elsewhere.
            // Allow a small correction for the user's imprecise touch.
            val marginX = (width * .035).toInt().coerceAtLeast(2)
            val marginY = (height * .035).toInt().coerceAtLeast(2)
            regions.filter { region ->
                sx!! in (region.left - marginX)..(region.right + marginX) &&
                    sy!! in (region.top - marginY)..(region.bottom + marginY)
            }.minByOrNull { region ->
                val cx = (region.left + region.right) / 2
                val cy = (region.top + region.bottom) / 2
                abs(sx!! - cx) + abs(sy!! - cy)
            }
        } else {
            val candidates = regions.filter { region ->
                region.area.toDouble() / (width * height) in .002..0.45 &&
                    ContourVisualQuality.assess(fromRegion(region, width, height), bounds = bounds)
                        .rating != ContourVisualRating.LOW
            }
            if (previous != null) {
                candidates.map { region -> region to fromRegion(region, width, height) }
                    .filter { (_, result) -> LiveContourTracking.check(previous, result).result != null }
                    .map { (region, result) -> region to LiveContourTracking.overlap(previous, result) }
                    .maxByOrNull { (_, overlap) -> overlap }
                    ?.takeIf { (_, overlap) -> overlap >= .15 }?.first
            } else {
                candidates.maxByOrNull { region ->
                    val cx = (region.left + region.right) / 2.0 / width
                    val cy = (region.top + region.bottom) / 2.0 / height
                    region.area.toDouble() *
                        (1.0 - .75 * (abs(cx - .5) + abs(cy - .5)).coerceAtMost(1.0))
                }
            }
        }

        if (chosen != null) {
            val candidate = fromRegion(chosen, width, height)
            if (LiveContourTracking.check(previous, candidate).result != null &&
                ContourVisualQuality.assess(candidate, bounds = bounds).rating != ContourVisualRating.LOW) {
                return LiveContourState(candidate,
                    if (seed == null) "Contorno automático aproximado. Confira as bordas."
                    else "Região selecionada encontrada. Confira as bordas.")
            }
        }

        return LiveContourState(
            null,
            if (seed == null)
                "Nenhum contorno isolado encontrado. Aproxime a câmera, use fundo contrastante " +
                    "ou toque na parte central do objeto."
            else
                "Não encontrei um contorno confiável nesse ponto. Mantenha a câmera firme, " +
                    "melhore a iluminação ou selecione outra região."
        )
    }

    private fun fromRegion(region: CountRegion, width: Int, height: Int) =
        PlanarContourResult(
            width, height, region.left, region.top, region.right, region.bottom,
            region.area, region.outline
        )
}
