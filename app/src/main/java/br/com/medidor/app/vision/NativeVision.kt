package br.com.medidor.app.vision

import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat

internal object NativeVision {
    private var loaded = false

    @Synchronized fun requireAvailable() {
        if (!loaded) {
            check(OpenCVLoader.initLocal()) { "Não foi possível iniciar a análise de imagem." }
            loaded = true
        }
    }
}

/** Release native matrices even when a detection or reconstruction is cancelled. */
internal class MatScope : AutoCloseable {
    private val matrices = mutableListOf<Mat>()
    fun <T : Mat> own(matrix: T): T = matrix.also { matrices += it }
    override fun close() { matrices.asReversed().forEach(Mat::release) }
}
