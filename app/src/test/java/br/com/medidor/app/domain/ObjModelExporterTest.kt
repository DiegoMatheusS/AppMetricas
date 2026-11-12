package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class ObjModelExporterTest {
    private val floor = PlanFloor(
        title = "Térreo", imageWidth = 1000, imageHeight = 1000,
        knownMeters = 4.0, referenceStart = PlanPoint(.1f,.1f),
        referenceEnd = PlanPoint(.5f,.1f),
        walls = listOf(PlanWall(PlanPoint(.1f,.2f), PlanPoint(.5f,.2f)))
    )

    @Test fun exportsVerticesAndQuadFaces() {
        val writer = StringWriter()
        val count = ObjModelExporter.write(ArchitectPlan(listOf(floor)), writer)
        assertEquals(6, count)
        assertEquals(24, writer.toString().lineSequence().count { it.startsWith("v ") })
        assertEquals(6, writer.toString().lineSequence().count { it.startsWith("f ") })
        assertTrue(writer.toString().contains("f 1 2 3 4"))
    }

    @Test fun doesNotExportImaginaryModelWithoutCalibration() {
        assertThrows(IllegalArgumentException::class.java) {
            ObjModelExporter.write(ArchitectPlan(listOf(floor.copy(referenceEnd = null))), StringWriter())
        }
    }
}
