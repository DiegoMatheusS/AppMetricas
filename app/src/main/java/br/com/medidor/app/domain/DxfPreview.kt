package br.com.medidor.app.domain

import kotlin.math.hypot

/**
 * Safe subset of ASCII DXF: LINE and straight LWPOLYLINE entities.
 * The layer is advisory metadata, never a proof that a line is a wall.
 * No arcs, bulges, block expansion, splines, DWG, IFC or RVT are inferred.
 */
data class DxfLine(
    val x1: Double, val y1: Double, val x2: Double, val y2: Double,
    val layer: String = "0"
)

object DxfPreview {
    fun parse(text: String): List<DxfLine> {
        require(text.length <= 4_000_000) { "DXF excede o limite de 4 MB." }
        val rows = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(rows.size % 2 == 0) { "Estrutura DXF ASCII inválida." }
        val segments = ArrayList<DxfLine>()
        var inEntities = false
        var pendingSection = false
        var entity = ""
        var layer = "0"
        val values = mutableMapOf<Int, String>()
        val vertices = mutableListOf<Pair<Double, Double>>()
        var vertexX: Double? = null
        var polylineFlags = 0

        fun append(x0: Double, y0: Double, x1: Double, y1: Double) {
            require(listOf(x0,y0,x1,y1).all { it.isFinite() && kotlin.math.abs(it) <= 1e9 }) {
                "DXF contém coordenadas inválidas."
            }
            if (hypot(x1 - x0, y1 - y0) <= 1e-7) return
            require(segments.size < 1500) { "DXF excede 1500 segmentos." }
            segments += DxfLine(x0,y0,x1,y1,layer)
        }
        fun flush() {
            if (inEntities && entity == "LINE") {
                val x0 = values[10]?.toDoubleOrNull()
                val y0 = values[20]?.toDoubleOrNull()
                val x1 = values[11]?.toDoubleOrNull()
                val y1 = values[21]?.toDoubleOrNull()
                if (x0 != null && y0 != null && x1 != null && y1 != null)
                    append(x0,y0,x1,y1)
            } else if (inEntities && entity == "LWPOLYLINE") {
                for (j in 1 until vertices.size) {
                    val (x0,y0) = vertices[j-1]
                    val (x1,y1) = vertices[j]
                    append(x0,y0,x1,y1)
                }
                if ((polylineFlags and 1) != 0 && vertices.size > 2) {
                    val (x0,y0) = vertices.last()
                    val (x1,y1) = vertices.first()
                    append(x0,y0,x1,y1)
                }
            }
            values.clear()
            vertices.clear()
            vertexX = null
            polylineFlags = 0
            layer = "0"
        }

        for (i in rows.indices step 2) {
            val code = rows[i].toIntOrNull()
                ?: throw IllegalArgumentException("Grupo DXF inválido.")
            val value = rows[i+1]
            if (code == 0) {
                flush()
                when(value.uppercase()) {
                    "SECTION" -> { pendingSection = true; entity = "" }
                    "ENDSEC" -> { inEntities = false; pendingSection = false; entity = "" }
                    "EOF" -> { entity = ""; inEntities = false }
                    else -> entity = value.uppercase()
                }
            } else if (pendingSection && code == 2) {
                inEntities = value.equals("ENTITIES",ignoreCase = true)
                pendingSection = false
            } else if (inEntities && entity in listOf("LINE","LWPOLYLINE")) {
                when {
                    code == 8 -> layer = value.take(80).ifBlank { "0" }
                    entity == "LINE" && code in setOf(10,20,11,21) -> values[code] = value
                    entity == "LWPOLYLINE" && code == 10 -> {
                        vertexX = value.toDoubleOrNull()
                            ?: throw IllegalArgumentException("Vértice DXF inválido.")
                    }
                    entity == "LWPOLYLINE" && code == 20 -> {
                        val x = vertexX
                            ?: throw IllegalArgumentException("Vértice DXF sem coordenada X.")
                        val y = value.toDoubleOrNull()
                            ?: throw IllegalArgumentException("Vértice DXF inválido.")
                        vertices += x to y
                        require(vertices.size <= 1501) { "Polilinha DXF excessiva." }
                        vertexX = null
                    }
                    entity == "LWPOLYLINE" && code == 70 -> {
                        polylineFlags = value.toIntOrNull() ?: 0
                    }
                    entity == "LWPOLYLINE" && code == 42 &&
                        (value.toDoubleOrNull() ?: 0.0) != 0.0 ->
                        throw IllegalArgumentException(
                            "Polilinhas curvas (bulge) não são suportadas; exporte como segmentos retos."
                        )
                }
            }
        }
        flush()
        require(segments.isNotEmpty()) {
            "Nenhuma LINE/LWPOLYLINE encontrada. Exporte linhas DXF ASCII."
        }
        return segments
    }
}
