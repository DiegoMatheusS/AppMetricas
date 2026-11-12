package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DxfPreviewTest {
    @Test fun parsesLinesFromEntitiesOnlyAndLayers() {
        val src = """
0
SECTION
2
ENTITIES
0
LINE
8
PAREDES
10
0
20
0
11
5
21
0
0
LINE
10
5
20
0
11
5
21
4
0
ENDSEC
0
EOF
""".trimIndent()
        val found=DxfPreview.parse(src)
        assertEquals(2,found.size)
        assertEquals("PAREDES",found[0].layer)
        assertEquals("0",found[1].layer)
        assertEquals(5.0,found[0].x2,1e-6)
        assertEquals(4.0,found[1].y2,1e-6)
    }

    @Test fun expandsClosedPolylineIntoFourSegments() {
        val src = """
0
SECTION
2
ENTITIES
0
LWPOLYLINE
8
WALL
90
4
70
1
10
0
20
0
10
4
20
0
10
4
20
3
10
0
20
3
0
ENDSEC
0
EOF
""".trimIndent()
        val walls=DxfPreview.parse(src)
        assertEquals(4,walls.size)
        assertEquals(3.0,walls[3].y1,1e-6)
        assertEquals(0.0,walls[3].y2,1e-6)
        assertEquals("WALL",walls[3].layer)
    }

    @Test fun rejectsCurvedPolylinesRatherThanSilentlyDroppingArc() {
        val src = """
0
SECTION
2
ENTITIES
0
LWPOLYLINE
10
0
20
0
42
0.4
10
5
20
0
0
ENDSEC
0
EOF
""".trimIndent()
        assertThrows(IllegalArgumentException::class.java) { DxfPreview.parse(src) }
    }

    @Test fun refusesUnknownOrEmptyDrawings() {
        assertThrows(IllegalArgumentException::class.java) {
            DxfPreview.parse("0\nSECTION\n2\nENTITIES\n0\nENDSEC\n0\nEOF")
        }
        assertThrows(IllegalArgumentException::class.java) { DxfPreview.parse("hello") }
    }
}
