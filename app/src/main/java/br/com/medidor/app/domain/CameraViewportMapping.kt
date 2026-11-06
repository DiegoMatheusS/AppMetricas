package br.com.medidor.app.domain

/** Window offsets and viewport sizes must be applied in both directions. */
internal object CameraViewportMapping {
    fun cameraPoint(x: Float, y: Float, overlayLeft: Int, overlayTop: Int,
                    cameraLeft: Int, cameraTop: Int, width: Int, height: Int): Pair<Float, Float>? {
        if (width <= 0 || height <= 0 || !x.isFinite() || !y.isFinite()) return null
        val nx = (x + overlayLeft - cameraLeft) / width
        val ny = (y + overlayTop - cameraTop) / height
        return if (nx in 0f..1f && ny in 0f..1f) nx to ny else null
    }

    fun overlayPoint(nx: Float, ny: Float, overlayLeft: Int, overlayTop: Int,
                     cameraLeft: Int, cameraTop: Int, width: Int, height: Int): Pair<Float, Float>? {
        if (width <= 0 || height <= 0 || !nx.isFinite() || !ny.isFinite()) return null
        return (nx * width + cameraLeft - overlayLeft) to (ny * height + cameraTop - overlayTop)
    }
}
