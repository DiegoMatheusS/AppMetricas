package br.com.medidor.app.domain

import java.io.Writer
import java.util.Locale
import kotlin.math.sqrt

data class MeshTriangle(val a: Int, val b: Int, val c: Int)
data class ReconstructedMesh(
    val vertices: List<Point3d>,
    val triangles: List<MeshTriangle>,
    val registeredPhotos: Int,
    val suppliedPhotos: Int,
    val fieldOfViewDegrees: Double = 60.0
) {
    fun validate() {
        require(vertices.size in 20..12_000 && triangles.size in 10..30_000) { "Geometria insuficiente para um modelo." }
        require(fieldOfViewDegrees.isFinite() && fieldOfViewDegrees in 25.0..100.0)
        require(registeredPhotos in 2..20 && suppliedPhotos in registeredPhotos..20)
        require(vertices.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
        require(triangles.all { it.a in vertices.indices && it.b in vertices.indices && it.c in vertices.indices &&
            it.a != it.b && it.a != it.c && it.b != it.c })
    }

    fun faces(): List<ModelFace> = triangles.map { triangle ->
        ModelFace(listOf(vertices[triangle.a], vertices[triangle.b], vertices[triangle.c]), 0, 2)
    }

    fun writeObj(writer: Writer) {
        validate()
        writer.write("# App Metricas - superficie reconstruida de fotografias\n")
        writer.write("# Escala proporcional; calibre uma referencia antes de interpretar unidades fisicas\n")
        writer.write("# Campo de visao horizontal estimado: $fieldOfViewDegrees graus\n")
        for (point in vertices) writer.write(String.format(Locale.US, "v %.7f %.7f %.7f\n", point.x, point.y, point.z))
        for (triangle in triangles) writer.write("f ${triangle.a + 1} ${triangle.b + 1} ${triangle.c + 1}\n")
        writer.flush()
    }
}

internal object MeshGeometry {
    fun distance(a: Point3d, b: Point3d): Double =
        sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))

    fun supportedTriangle(a: Point3d, b: Point3d, c: Point3d, maximumEdge: Double): Boolean {
        val ab = distance(a, b)
        val bc = distance(b, c)
        val ca = distance(c, a)
        if (listOf(ab, bc, ca).any { !it.isFinite() || it < 1e-5 || it > maximumEdge }) return false
        val s = (ab + bc + ca) / 2
        val areaSquared = s * (s - ab) * (s - bc) * (s - ca)
        return areaSquared > 1e-12 && maxOf(ab, bc, ca) / minOf(ab, bc, ca) < 8
    }
}
