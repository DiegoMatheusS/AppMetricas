package br.com.medidor.app.domain

data class PlanViewport(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f)

/**
 * A zoomable blueprint uses a center-origin transform. Keep the physical
 * content point below the pinch centroid fixed during scaling.
 */
object PlanViewportMath {
    fun change(
        old: PlanViewport,
        viewportWidth: Float,
        viewportHeight: Float,
        centroidX: Float,
        centroidY: Float,
        zoomFactor: Float,
        dragX: Float,
        dragY: Float
    ): PlanViewport {
        require(viewportWidth > 0f && viewportHeight > 0f)
        if (!zoomFactor.isFinite() || zoomFactor <= 0f ||
            !dragX.isFinite() || !dragY.isFinite()) return old
        val newZoom = (old.zoom * zoomFactor).coerceIn(1f, 6f)
        val ratio = newZoom / old.zoom
        val cx = centroidX - viewportWidth / 2f
        val cy = centroidY - viewportHeight / 2f
        val maxX = (newZoom - 1f) * viewportWidth / 2f
        val maxY = (newZoom - 1f) * viewportHeight / 2f
        val panX = (old.panX + (cx - old.panX) * (1f - ratio) + dragX).coerceIn(-maxX, maxX)
        val panY = (old.panY + (cy - old.panY) * (1f - ratio) + dragY).coerceIn(-maxY, maxY)
        return PlanViewport(newZoom, panX, panY)
    }
}
