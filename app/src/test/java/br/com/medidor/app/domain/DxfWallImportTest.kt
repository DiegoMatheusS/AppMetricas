package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DxfWallImportTest {
    private val lines = listOf(
        DxfLine(0.0,0.0,5.0,0.0,"PAREDES"),
        DxfLine(5.0,0.0,5.0,3.0,"PAREDES"),
        DxfLine(0.0,0.0,5.0,3.0,"COTAS")
    )

    @Test fun wallEndpointsAndPhysicalScaleAgreeWithRaster() {
        val layout = DxfWallImport.layout(lines)
        val converted = DxfWallImport.convert(lines,layout,"PAREDES",DxfDrawingUnit.METER)
        assertEquals(2,converted.walls.size)
        assertEquals(5.0,converted.referenceMeters,1e-6)
        val floor = PlanFloor(
            title="Importado", imageWidth=layout.width,imageHeight=layout.height,
            referenceStart=converted.referenceStart,referenceEnd=converted.referenceEnd,
            knownMeters=converted.referenceMeters,walls=converted.walls
        )
        assertTrue(floor.calibrated)
        assertEquals(5.0,FloorPlanMath.wallLengthMeters(floor,converted.walls[0])!!,1e-4)
        assertEquals(3.0,FloorPlanMath.wallLengthMeters(floor,converted.walls[1])!!,1e-4)
        assertEquals(2,DxfWallImport.layers(lines)["PAREDES"])
        assertEquals(1,DxfWallImport.layers(lines)["COTAS"])
    }

    @Test fun explicitDrawingUnitsAreApplied() {
        val mm = listOf(DxfLine(0.0,0.0,4000.0,0.0,"walls"))
        val drawing=DxfWallImport.convert(mm,DxfWallImport.layout(mm),
            "walls",DxfDrawingUnit.MILLIMETER)
        assertEquals(4.0,drawing.referenceMeters,1e-6)
    }

    @Test fun onlySelectedLayerIsConverted() {
        val selected=DxfWallImport.convert(lines,DxfWallImport.layout(lines),
            "COTAS",DxfDrawingUnit.METER)
        assertEquals(1,selected.walls.size)
    }

    @Test fun invalidLayerAndExcessiveGeometryAreRejected() {
        val layout=DxfWallImport.layout(lines)
        assertThrows(IllegalArgumentException::class.java) {
            DxfWallImport.convert(lines,layout,"INEXISTENTE",DxfDrawingUnit.METER)
        }
        val many=(0..300).map { i ->
            DxfLine(i.toDouble(),0.0,i.toDouble()+0.5,0.0,"PAREDES")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DxfWallImport.convert(many,DxfWallImport.layout(many),
                "PAREDES",DxfDrawingUnit.METER)
        }
    }

    @Test fun degenerateSceneCannotBeImported() {
        assertThrows(IllegalArgumentException::class.java) {
            DxfWallImport.layout(listOf(DxfLine(0.0,0.0,0.0,0.0)))
        }
    }
}
