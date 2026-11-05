package br.com.medidor.app.domain

import kotlin.math.abs
import kotlin.math.max

/** Consecutive metric observations must agree in world coordinates before automatic placement. */
internal class ArMeasurementStability(private val requiredSamples: Int = 3) {
    init { require(requiredSamples >= 2) }

    private val samples = mutableListOf<List<Point3d>>()
    private var lastTimestamp = 0L
    private var lastObservedAt = 0L

    val count: Int get() = samples.size
    val stable: Boolean get() = samples.size >= requiredSamples
    val referencePoints: List<Point3d>? get() = samples.firstOrNull()

    fun reset() {
        samples.clear()
        lastTimestamp = 0L
        lastObservedAt = 0L
    }

    fun observe(points: List<Point3d>, closed: Boolean, timestamp: Long, observedAtMillis: Long): List<Point3d>? {
        if (timestamp <= lastTimestamp || timestamp <= 0) return null
        lastTimestamp = timestamp
        if (points.size !in (if (closed) 3 else 2)..12 || (!closed && points.size != 2) ||
            points.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }) {
            samples.clear()
            return null
        }
        if (observedAtMillis < lastObservedAt || observedAtMillis - lastObservedAt > 1600) samples.clear()
        val reference = samples.firstOrNull()
        val aligned = if (reference?.size == points.size) align(points, reference, closed) else points.toList()
        if (reference == null || reference.size != aligned.size || !consistent(reference, aligned, closed))
            samples.clear()
        if (samples.isNotEmpty() && observedAtMillis - lastObservedAt < 150) return null
        lastObservedAt = observedAtMillis
        samples += aligned
        if (samples.size < requiredSamples) return null
        if (samples.size > requiredSamples) samples.removeAt(0)
        return aligned.indices.map { index ->
            Point3d(samples.sumOf { it[index].x } / samples.size,
                samples.sumOf { it[index].y } / samples.size,
                samples.sumOf { it[index].z } / samples.size)
        }
    }

    private fun align(points: List<Point3d>, reference: List<Point3d>, closed: Boolean): List<Point3d> {
        val candidates = if (closed) {
            listOf(points, points.reversed()).flatMap { order ->
                order.indices.map { shift -> List(order.size) { order[(it + shift) % order.size] } }
            }
        } else listOf(points, points.reversed())
        return candidates.minBy { candidate ->
            reference.indices.sumOf { MeshGeometry.distance(reference[it], candidate[it]) }
        }
    }

    private fun consistent(reference: List<Point3d>, points: List<Point3d>, closed: Boolean): Boolean {
        val span = reference.maxOf { a -> reference.maxOf { b -> MeshGeometry.distance(a, b) } }
        if (span < .001) return false
        val positionTolerance = max(.01, span * .01)
        if (reference.indices.any { MeshGeometry.distance(reference[it], points[it]) > positionTolerance }) return false
        val edges = if (closed) reference.size else 1
        return (0 until edges).all { index ->
            val next = (index + 1) % reference.size
            val before = MeshGeometry.distance(reference[index], reference[next])
            val after = MeshGeometry.distance(points[index], points[next])
            abs(before - after) <= max(.005, before * .025)
        }
    }
}

/** Uses the camera timestamp only for frame ordering and a monotonic clock for age. */
internal class ArFrameValidity(private val maximumAgeMillis: Long = 750) {
    private var timestamp = 0L
    private var freshAt: Long? = null

    @Synchronized fun invalidate() { freshAt = null }

    @Synchronized fun reset() {
        timestamp = 0L
        freshAt = null
    }

    @Synchronized fun update(cameraTimestamp: Long, tracking: Boolean, observedAtMillis: Long): Boolean {
        if (!tracking || cameraTimestamp <= 0 || cameraTimestamp < timestamp) {
            freshAt = null
            return false
        }
        if (cameraTimestamp > timestamp) {
            timestamp = cameraTimestamp
            freshAt = observedAtMillis
        }
        return canConfirm(observedAtMillis)
    }

    @Synchronized fun canConfirm(nowMillis: Long): Boolean =
        freshAt?.let { nowMillis >= it && nowMillis - it <= maximumAgeMillis } == true
}
