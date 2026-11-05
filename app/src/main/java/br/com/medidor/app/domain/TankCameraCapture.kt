package br.com.medidor.app.domain

/**
 * Measurements returned from ARCore anchors. Lengths are estimated outer
 * dimensions in meters. They MUST be reviewed by a human before calculating
 * inner tank capacity (wall thickness and geometry are not inferred).
 */
data class TankCameraCapture(val shape: TankShape, val dimensions: List<Double>) {
    fun validated(): TankCameraCapture? {
        val expected = if (shape == TankShape.SPHERE) 1 else 2
        if (dimensions.size != expected) return null
        if (dimensions.any { !it.isFinite() || it !in 0.02..30.0 }) return null
        return this
    }
}
