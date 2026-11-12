package br.com.medidor.app.domain

import kotlin.math.hypot

/** Diameter in millimeters for Brazil's second family of real coins. */
enum class BrazilianCoin(val label: String, val diameterMm: Double) {
    FIVE_CENTS("5 centavos", 22.0),
    TEN_CENTS("10 centavos", 20.0),
    TWENTY_FIVE_CENTS("25 centavos", 25.0),
    FIFTY_CENTS("50 centavos", 23.0),
    ONE_REAL("1 real", 27.0)
}

data class NormalizedPoint(val x: Float, val y: Float) {
    init {
        require(x in 0f..1f && y in 0f..1f)
    }
}

/**
 * Points are in normalized image coordinates. Only distances that lie in the
 * same physical plane and share perspective can be derived from this scale.
 */
object PhotoCalibration {
    fun distancePixels(a: NormalizedPoint, b: NormalizedPoint, imageWidth: Int, imageHeight: Int): Double {
        require(imageWidth > 0 && imageHeight > 0)
        return hypot((a.x - b.x).toDouble() * imageWidth, (a.y - b.y).toDouble() * imageHeight)
    }

    fun manualReferenceMillimeters(value: String, unit: LengthUnit): Double? =
        value.trim().replace(',', '.').toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { (it * unit.millimeters).takeIf { mm -> mm.isFinite() && mm > 0.0 } }

    fun millimetersFromReference(referenceMm: Double, referencePixels: Double, targetPixels: Double): Double {
        require(referenceMm.isFinite() && referenceMm > 0.0)
        require(referencePixels.isFinite() && referencePixels > 0.0)
        require(targetPixels.isFinite() && targetPixels >= 0.0)
        return referenceMm * targetPixels / referencePixels
    }
}

enum class LengthUnit(val label: String, val millimeters: Double) {
    MILLIMETERS("mm", 1.0),
    CENTIMETERS("cm", 10.0),
    METERS("m", 1000.0)
}
