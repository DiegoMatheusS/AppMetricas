package br.com.medidor.app.domain

import kotlin.math.abs

/** Robust border color planes compensate for smooth lighting gradients without flattening objects. */
internal class BorderBackground private constructor(
    private val width: Int, private val height: Int,
    private val coefficients: List<DoubleArray>
) {
    fun difference(pixel: Int, x: Int, y: Int): Int {
        val nx = 2.0 * x / (width - 1) - 1
        val ny = 2.0 * y / (height - 1) - 1
        return coefficients.indices.maxOf { channel ->
            val fit = coefficients[channel]
            val predicted = (fit[0] + fit[1] * nx + fit[2] * ny).coerceIn(0.0, 255.0)
            abs(((pixel ushr (16 - channel * 8)) and 255) - predicted).toInt()
        }
    }

    companion object {
        fun fit(pixels: IntArray, width: Int, height: Int): BorderBackground {
            require(width >= 8 && height >= 8 && pixels.size == width * height)
            val border = pixels.indices.filter { i ->
                i % width < 3 || i % width >= width - 3 || i / width < 3 || i / width >= height - 3
            }
            val channels = (0..2).map { channel ->
                val values = border.map { (pixels[it] ushr (16 - channel * 8)) and 255 }
                var selected = border.indices.toList()
                var coefficients = doubleArrayOf(values.sorted()[values.size / 2].toDouble(), 0.0, 0.0)
                repeat(3) {
                    val matrix = Array(3) { DoubleArray(4) }
                    selected.forEach { index ->
                        val pixel = border[index]
                        val row = doubleArrayOf(1.0, 2.0 * (pixel % width) / (width - 1) - 1,
                            2.0 * (pixel / width) / (height - 1) - 1)
                        for (a in 0..2) {
                            for (b in 0..2) matrix[a][b] += row[a] * row[b]
                            matrix[a][3] += row[a] * values[index]
                        }
                    }
                    solve3(matrix)?.let { coefficients = it }
                    val errors = border.indices.map { index ->
                        val pixel = border[index]
                        abs(values[index] - coefficients[0] - coefficients[1] * (2.0 * (pixel % width) / (width - 1) - 1) -
                            coefficients[2] * (2.0 * (pixel / width) / (height - 1) - 1))
                    }
                    val cutoff = maxOf(8.0, errors.sorted()[errors.size / 2] * 2.5)
                    selected = errors.indices.filter { errors[it] <= cutoff }
                }
                coefficients
            }
            return BorderBackground(width, height, channels)
        }
    }
}

/** Pivoted 3x3 linear solve used by lighting and multiview refinement. */
internal fun solve3(input: Array<DoubleArray>): DoubleArray? {
    val matrix = Array(3) { input[it].copyOf() }
    for (column in 0..2) {
        val pivot = (column..2).maxBy { abs(matrix[it][column]) }
        if (!matrix[pivot][column].isFinite() || abs(matrix[pivot][column]) < 1e-10) return null
        val temporary = matrix[column]; matrix[column] = matrix[pivot]; matrix[pivot] = temporary
        val divisor = matrix[column][column]
        for (index in column..3) matrix[column][index] /= divisor
        for (row in 0..2) if (row != column) {
            val multiplier = matrix[row][column]
            for (index in column..3) matrix[row][index] -= multiplier * matrix[column][index]
        }
    }
    return DoubleArray(3) { matrix[it][3] }.takeIf { values -> values.all { it.isFinite() } }
}
