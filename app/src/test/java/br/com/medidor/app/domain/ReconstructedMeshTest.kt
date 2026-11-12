package br.com.medidor.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter

class ReconstructedMeshTest {
    @Test fun exportsActualVerticesAndFacesAndRejectsUnsupportedConnections() {
        val points = (0 until 5).flatMap { x -> (0 until 4).map { y -> Point3d(x.toDouble(), y.toDouble(), x * y * .1) } }
        val faces = (0 until 4).flatMap { x -> (0 until 3).map { y ->
            val a = x * 4 + y
            MeshTriangle(a, a + 1, a + 4)
        } }
        val mesh = ReconstructedMesh(points, faces, 3, 4)
        val output = StringWriter()
        mesh.writeObj(output)
        assertEquals(points.size, output.toString().lineSequence().count { it.startsWith("v ") })
        assertEquals(faces.size, output.toString().lineSequence().count { it.startsWith("f ") })
        assertFalse(MeshGeometry.supportedTriangle(Point3d(0.0,0.0,0.0),
            Point3d(1.0,0.0,0.0), Point3d(2.0,0.0,0.0), 3.0))
        assertFalse(MeshGeometry.supportedTriangle(points[0], points[1], points.last(), 1.0))
    }
}
