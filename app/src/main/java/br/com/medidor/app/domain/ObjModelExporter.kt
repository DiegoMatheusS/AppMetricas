package br.com.medidor.app.domain

import java.io.Writer
import java.util.Locale

/**
 * Exports the manually constructed architectural mesh to Wavefront OBJ in metres.
 * This is geometry only: no textures, materials, engineering certification or AR data.
 */
object ObjModelExporter {
    fun write(plan: ArchitectPlan, writer: Writer): Int {
        val faces = Architect3dGeometry.model(plan, allFloors = true)
        require(faces.isNotEmpty()) { "Desenhe paredes em pelo menos uma planta calibrada." }
        require(faces.size <= 50_000) { "Modelo grande demais para exportação nesta versão." }
        writer.write("# Medidor - modelo arquitetônico estimado\n")
        writer.write("# Unidades: metros (OBJ nao define unidade formal)\n")
        writer.write("o Maquete_Arquitetonica\n")
        var index = 1
        for (face in faces) {
            require(face.corners.size in 3..4)
            for (p in face.corners) {
                require(p.x.isFinite() && p.y.isFinite() && p.z.isFinite())
                writer.write(String.format(Locale.US, "v %.6f %.6f %.6f\n", p.x, p.y, p.z))
            }
            writer.write("f " + (0 until face.corners.size).joinToString(" ") { (index + it).toString() } + "\n")
            index += face.corners.size
        }
        writer.flush()
        return faces.size
    }
}
