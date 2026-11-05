package br.com.medidor.app.data

import android.content.Context
import android.util.AtomicFile
import br.com.medidor.app.domain.MeshTriangle
import br.com.medidor.app.domain.Point3d
import br.com.medidor.app.domain.ReconstructedMesh
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A model is reusable only while the session still references the same source photos. */
internal class ScannerMeshStore(context: Context) {
    private val folder = File(context.filesDir, "scanner_models").apply { mkdirs() }
    private fun file(id: String): AtomicFile {
        UUID.fromString(id)
        return AtomicFile(File(folder, "model_$id.json"))
    }

    fun read(session: ScannerSession): ReconstructedMesh? = try {
        val target = file(session.id)
        if (target.baseFile.length() !in 1..6_000_000) null else {
            val root = target.openRead().bufferedReader().use { JSONObject(it.readText()) }
            if (root.getString("sources") != fingerprint(session)) null else {
                val vertices = root.getJSONArray("vertices")
                val triangles = root.getJSONArray("triangles")
                require(vertices.length() <= 12_000 && triangles.length() <= 30_000)
                ReconstructedMesh(
                    List(vertices.length()) { i -> vertices.getJSONArray(i).let {
                        Point3d(it.getDouble(0), it.getDouble(1), it.getDouble(2))
                    } },
                    List(triangles.length()) { i -> triangles.getJSONArray(i).let {
                        MeshTriangle(it.getInt(0), it.getInt(1), it.getInt(2))
                    } },
                    root.getInt("registeredPhotos"), root.getInt("suppliedPhotos"), root.optDouble("fieldOfView", 60.0)
                ).also { it.validate() }
            }
        }
    } catch (_: Exception) { null }

    fun save(session: ScannerSession, mesh: ReconstructedMesh) {
        mesh.validate()
        val root = JSONObject().put("sources", fingerprint(session))
            .put("registeredPhotos", mesh.registeredPhotos).put("suppliedPhotos", mesh.suppliedPhotos)
            .put("fieldOfView", mesh.fieldOfViewDegrees)
            .put("vertices", JSONArray().also { array -> mesh.vertices.forEach { point ->
                array.put(JSONArray().put(point.x).put(point.y).put(point.z))
            } })
            .put("triangles", JSONArray().also { array -> mesh.triangles.forEach { face ->
                array.put(JSONArray().put(face.a).put(face.b).put(face.c))
            } })
        val target = file(session.id)
        val stream = target.startWrite()
        try {
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (e: Exception) {
            target.failWrite(stream)
            throw e
        }
    }

    fun delete(id: String) = file(id).delete()
    private fun fingerprint(session: ScannerSession) = session.frames.toSortedMap()
        .entries.joinToString("|") { "${it.key}=${it.value}" }
}
