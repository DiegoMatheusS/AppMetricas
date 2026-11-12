package br.com.medidor.app.domain

import org.junit.Assert.*
import org.junit.Test

class PlanRecognitionTest {
    @Test fun mergesWallStrokesAndPreservesDoorSizedGaps() {
        val lines = listOf(PlanLine(true, .2, .1, .4), PlanLine(true, .205, .12, .4),
            PlanLine(true, .2, .5, .9))
        val merged = PlanRecognition.mergeStrokes(lines, .01)
        assertEquals(2, merged.size)
        assertEquals(.1, merged.first().start, .0001)
        assertEquals(.4, merged.first().end, .0001)
    }

    @Test fun joinsTheDoorGapWhenRightStrokeWasDetectedBeforeTheLeftOne() {
        val input = listOf(PlanLine(true, .84, .55, .9), PlanLine(true, .835, .1, .45))
        val runs = PlanRecognition.wallRuns(input, .015, 6.0, 5.0)
        assertEquals(1, runs.size)
        assertEquals(.1, runs.single().line.start, .0001)
        assertEquals(.9, runs.single().line.end, .0001)
        assertEquals(listOf(.45 to .55), runs.single().gaps)
    }

    @Test fun applyingSuggestionsKeepsExistingOpeningsAndHandlesReversedWalls() {
        val first = PlanPoint(.1f, .2f)
        val last = PlanPoint(.9f, .2f)
        val floor = PlanFloor("Térreo", imageWidth = 1000, imageHeight = 1000, knownMeters = 8.0,
            referenceStart = first, referenceEnd = last, walls = listOf(PlanWall(first, last)))
        val draft = PlanRecognitionDraft(listOf(PlanWall(last, first)), listOf(
            PlanOpening(0, OpeningType.DOOR, .2, .8, 0.0, 2.1)
        ))
        val applied = PlanRecognition.apply(floor, draft, setOf(0), doors = true, windows = true)
        assertEquals(1, applied.walls.size)
        assertEquals(.8, applied.openings.single().centerFraction, .0001)
        assertEquals(applied, PlanRecognition.apply(applied, draft, setOf(0), doors = true, windows = true))
        assertEquals(floor, PlanRecognition.apply(floor, draft, emptySet(), true, true))
    }
}
