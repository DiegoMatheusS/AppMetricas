package br.com.medidor.app.domain

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * All dimensions are in meters. Volumes are returned in cubic meters.
 * This module contains ideal geometric calculations, not certified tank calibration.
 */
object Geometry {
    fun rectangularVolume(length: Double, width: Double, height: Double): Double {
        requirePositive(length, width, height)
        return length * width * height
    }

    fun rectangularFloorArea(length: Double, width: Double): Double {
        requirePositive(length, width)
        return length * width
    }

    fun rectangularSurfaceArea(length: Double, width: Double, height: Double): Double {
        requirePositive(length, width, height)
        return 2 * (length * width + length * height + width * height)
    }

    fun cylinderVolume(diameter: Double, height: Double): Double {
        requirePositive(diameter, height)
        return PI * diameter * diameter * height / 4
    }

    fun sphereVolume(diameter: Double): Double {
        requirePositive(diameter)
        return PI * diameter.pow(3) / 6
    }

    fun circumference(diameter: Double): Double {
        requirePositive(diameter)
        return PI * diameter
    }

    /** Liquid height measured from bottom of an upright cylinder. */
    fun verticalCylinderLiquidVolume(diameter: Double, height: Double, liquidLevel: Double): Double {
        requirePositive(diameter, height)
        requireLevel(liquidLevel, height)
        if (liquidLevel == 0.0) return 0.0 // Empty tank, not an invalid cylinder.
        return cylinderVolume(diameter, liquidLevel)
    }

    /** Exact circular segment area multiplied by cylinder length (flat ends, no internals). */
    fun horizontalCylinderLiquidVolume(diameter: Double, length: Double, liquidLevel: Double): Double {
        requirePositive(diameter, length)
        requireLevel(liquidLevel, diameter)
        val radius = diameter / 2
        val h = liquidLevel
        val segmentArea = radius * radius * acos((radius - h) / radius) -
            (radius - h) * sqrt(max(0.0, 2 * radius * h - h * h))
        return segmentArea * length
    }

    /** Spherical segment volume with liquid height measured from bottom. */
    fun sphereLiquidVolume(diameter: Double, liquidLevel: Double): Double {
        requirePositive(diameter)
        requireLevel(liquidLevel, diameter)
        return PI * liquidLevel * liquidLevel * (diameter / 2 - liquidLevel / 3)
    }

    fun liters(cubicMeters: Double): Double = cubicMeters * 1_000

    private fun requirePositive(vararg values: Double) {
        require(values.all { it.isFinite() && it > 0.0 }) { "As medidas devem ser positivas e finitas." }
    }

    private fun requireLevel(level: Double, maximum: Double) {
        require(level.isFinite() && level >= 0.0 && level <= maximum) {
            "O nível precisa estar entre zero e a altura útil."
        }
    }
}

enum class TankShape(val label: String) {
    VERTICAL("Cilíndrico vertical"),
    HORIZONTAL("Cilíndrico horizontal"),
    SPHERE("Esférico")
}

data class TankResult(val capacityM3: Double, val occupiedM3: Double) {
    val freeM3: Double get() = max(0.0, capacityM3 - occupiedM3)
    val fillPercent: Double get() = if (capacityM3 == 0.0) 0.0 else 100 * occupiedM3 / capacityM3
}

object TankCalculator {
    fun calculate(shape: TankShape, diameter: Double, size: Double, level: Double): TankResult {
        val capacity = when (shape) {
            TankShape.VERTICAL, TankShape.HORIZONTAL -> Geometry.cylinderVolume(diameter, size)
            TankShape.SPHERE -> Geometry.sphereVolume(diameter)
        }
        val occupied = when (shape) {
            TankShape.VERTICAL -> Geometry.verticalCylinderLiquidVolume(diameter, size, level)
            TankShape.HORIZONTAL -> Geometry.horizontalCylinderLiquidVolume(diameter, size, level)
            TankShape.SPHERE -> Geometry.sphereLiquidVolume(diameter, level)
        }
        return TankResult(capacity, occupied)
    }
}
