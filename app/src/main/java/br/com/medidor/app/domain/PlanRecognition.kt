package br.com.medidor.app.domain

import kotlin.math.abs
import kotlin.math.hypot

internal data class PlanLine(val horizontal: Boolean, val offset: Double, val start: Double, val end: Double)
internal data class PlanWallRun(val line: PlanLine, val gaps: List<Pair<Double, Double>>)
internal data class PlanRecognitionDraft(val walls: List<PlanWall>, val openings: List<PlanOpening>)

internal object PlanRecognition {
    /** Merge nearby parallel strokes while preserving door-sized gaps. */
    fun mergeStrokes(input: List<PlanLine>, tolerance: Double): List<PlanLine> {
        require(tolerance.isFinite() && tolerance > 0)
        val output = mutableListOf<PlanLine>()
        for (line in input.sortedWith(compareBy<PlanLine> { it.horizontal }.thenBy { it.offset }.thenBy { it.start })) {
            if (!line.offset.isFinite() || !line.start.isFinite() || !line.end.isFinite() ||
                line.end - line.start < .03) continue
            val index = output.indexOfFirst {
                it.horizontal == line.horizontal && abs(it.offset - line.offset) <= tolerance &&
                    line.start <= it.end + tolerance && it.start <= line.end + tolerance
            }
            if (index < 0) output += line else {
                val previous = output[index]
                output[index] = previous.copy(offset = (previous.offset + line.offset) / 2,
                    start = minOf(previous.start, line.start), end = maxOf(previous.end, line.end))
            }
        }
        return output.take(300)
    }

    /** Hough strokes can arrive in any order, including the right side before the left side of a door. */
    fun wallRuns(input: List<PlanLine>, tolerance: Double, horizontalMeters: Double,
                 verticalMeters: Double): List<PlanWallRun> {
        val groups = mutableListOf<MutableList<PlanLine>>()
        for (line in input) {
            val group = groups.firstOrNull {
                it.first().horizontal == line.horizontal && abs(it.map { segment -> segment.offset }.average() - line.offset) <= tolerance
            }
            if (group == null) groups += mutableListOf(line) else group += line
        }
        val result = mutableListOf<PlanWallRun>()
        for (group in groups) {
            val ordered = group.sortedBy { it.start }
            val offset = group.map { it.offset }.average()
            var current = ordered.first().copy(offset = offset)
            var gaps = mutableListOf<Pair<Double, Double>>()
            val meters = if (current.horizontal) horizontalMeters else verticalMeters
            for (next in ordered.drop(1)) {
                val gap = next.start - current.end
                when {
                    gap <= tolerance -> current = current.copy(end = maxOf(current.end, next.end))
                    gap * meters in .55..1.6 -> {
                        gaps += current.end to next.start
                        current = current.copy(end = next.end)
                    }
                    else -> {
                        result += PlanWallRun(current, gaps.toList())
                        current = next.copy(offset = offset)
                        gaps = mutableListOf()
                    }
                }
            }
            result += PlanWallRun(current, gaps.toList())
        }
        return result.take(300)
    }

    /** Add reviewed suggestions while retaining existing walls and their opening indices. */
    fun apply(floor: PlanFloor, draft: PlanRecognitionDraft, selected: Set<Int>,
              doors: Boolean, windows: Boolean): PlanFloor {
        val walls = floor.walls.toMutableList()
        val mapping = mutableMapOf<Int, Int>()
        fun near(a: PlanPoint, b: PlanPoint) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) < .015
        for (index in selected.sorted()) {
            val candidate = draft.walls.getOrNull(index) ?: continue
            val existing = walls.indexOfFirst {
                near(it.start, candidate.start) && near(it.end, candidate.end) ||
                    near(it.start, candidate.end) && near(it.end, candidate.start)
            }
            if (existing >= 0) mapping[index] = existing else if (walls.size < 300) {
                mapping[index] = walls.size
                walls += candidate
            }
        }
        var updated = floor.copy(walls = walls)
        for (opening in draft.openings) {
            if (opening.type == OpeningType.DOOR && !doors || opening.type == OpeningType.WINDOW && !windows) continue
            val wall = mapping[opening.wallIndex] ?: continue
            val sourceWall = draft.walls[opening.wallIndex]
            val reversed = near(sourceWall.start, updated.walls[wall].end)
            val candidate = opening.copy(wallIndex = wall,
                centerFraction = if (reversed) 1 - opening.centerFraction else opening.centerFraction)
            if (FloorPlanMath.canAddOpening(updated, candidate))
                updated = updated.copy(openings = updated.openings + candidate)
        }
        return updated
    }

    fun nearest(draft: PlanRecognitionDraft, point: PlanPoint): Int? =
        draft.walls.indices.minByOrNull { index ->
            val wall = draft.walls[index]
            val dx = wall.end.x - wall.start.x
            val dy = wall.end.y - wall.start.y
            val fraction = (((point.x - wall.start.x) * dx + (point.y - wall.start.y) * dy) /
                (dx * dx + dy * dy).coerceAtLeast(.000001f)).coerceIn(0f, 1f)
            hypot((point.x - wall.start.x - dx * fraction).toDouble(),
                (point.y - wall.start.y - dy * fraction).toDouble())
        }
}
