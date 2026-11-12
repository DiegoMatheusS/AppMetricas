package br.com.medidor.app.domain

/** Level is always vertical distance from the internal bottom of the tank. */
enum class TankLevelUnit(val label: String) {
    METERS("Metros"), CENTIMETERS("Centímetros"), HEIGHT_PERCENT("Percentual da altura")
}

object TankFillInput {
    fun heightMeters(value: Double, unit: TankLevelUnit, maximumHeight: Double): Double? {
        if (!value.isFinite() || value < 0 || !maximumHeight.isFinite() ||
            maximumHeight <= 0) return null
        val meters = when(unit) {
            TankLevelUnit.METERS -> value
            TankLevelUnit.CENTIMETERS -> value / 100
            TankLevelUnit.HEIGHT_PERCENT -> if (value<=100) maximumHeight*value/100 else return null
        }
        return meters.takeIf { it.isFinite() && it <= maximumHeight }
    }

    fun estimatedMassKg(volumeM3: Double, bulkDensityKgPerM3: Double): Double? =
        if (volumeM3.isFinite() && volumeM3 >= 0 &&
            bulkDensityKgPerM3.isFinite() && bulkDensityKgPerM3 > 0 &&
            bulkDensityKgPerM3 <= 30000)
                volumeM3 * bulkDensityKgPerM3
        else null
}
