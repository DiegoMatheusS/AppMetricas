package br.com.medidor.app.domain

import kotlin.math.abs
import kotlin.math.hypot

/** Merges duplicate/fragmented angled strokes in pixel space, preserving image aspect ratio. */
internal object AngledPlanWalls {
    fun merge(input: List<PlanWall>, width: Int, height: Int, tolerancePixels: Double): List<PlanWall> {
        require(width > 0 && height > 0 && tolerancePixels.isFinite() && tolerancePixels > 0)
        data class Line(val ax: Double, val ay: Double, val bx: Double, val by: Double) {
            val length get() = hypot(bx - ax, by - ay)
        }
        val result = mutableListOf<Line>()
        val lines = input.map { Line(it.start.x * width.toDouble(), it.start.y * height.toDouble(),
            it.end.x * width.toDouble(), it.end.y * height.toDouble()) }
            .filter { it.length > 5 && listOf(it.ax, it.ay, it.bx, it.by).all(Double::isFinite) }
            .sortedByDescending { it.length }
        for (line in lines) {
            var merged = false
            for (index in result.indices) {
                val before = result[index]
                val ux = (before.bx - before.ax) / before.length
                val uy = (before.by - before.ay) / before.length
                val vx = (line.bx - line.ax) / line.length
                val vy = (line.by - line.ay) / line.length
                if (abs(ux * vy - uy * vx) > .10) continue
                fun normal(x: Double, y: Double) = -uy * x + ux * y
                fun along(x: Double, y: Double) = ux * x + uy * y
                val base = normal(before.ax, before.ay)
                if (abs(normal(line.ax, line.ay) - base) > tolerancePixels ||
                    abs(normal(line.bx, line.by) - base) > tolerancePixels) continue
                val old = listOf(along(before.ax, before.ay), along(before.bx, before.by)).sorted()
                val next = listOf(along(line.ax, line.ay), along(line.bx, line.by)).sorted()
                if (next[0] > old[1] + tolerancePixels * 2 || old[0] > next[1] + tolerancePixels * 2) continue
                val center = (base * before.length + (normal(line.ax, line.ay) + normal(line.bx, line.by)) / 2 * line.length) /
                    (before.length + line.length)
                val start = minOf(old[0], next[0]); val end = maxOf(old[1], next[1])
                result[index] = Line(ux * start - uy * center, uy * start + ux * center,
                    ux * end - uy * center, uy * end + ux * center)
                merged = true
                break
            }
            if (!merged && result.size < 300) result += line
        }
        return result.map { line -> PlanWall(
            PlanPoint((line.ax / width).coerceIn(0.0, 1.0).toFloat(), (line.ay / height).coerceIn(0.0, 1.0).toFloat()),
            PlanPoint((line.bx / width).coerceIn(0.0, 1.0).toFloat(), (line.by / height).coerceIn(0.0, 1.0).toFloat())) }
    }
}
