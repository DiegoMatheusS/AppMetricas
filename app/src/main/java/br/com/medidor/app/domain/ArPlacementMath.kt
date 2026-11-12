package br.com.medidor.app.domain

/** Converts a manually calibrated blueprint to a miniature at the real printed-sheet scale. */
object ArPlacementMath {
    fun imageModelScale(paperWidthMeters: Double, floor: PlanFloor): Double? {
        val pixelsPerMeter = FloorPlanMath.pixelsPerMeter(floor) ?: return null
        if (!paperWidthMeters.isFinite() || paperWidthMeters <= 0.0 ||
            floor.imageWidth <= 0) return null
        val blueprintWidthMeters = floor.imageWidth / pixelsPerMeter
        val value = paperWidthMeters / blueprintWidthMeters
        return value.takeIf { it.isFinite() && it > 0.0 }
    }
}
