package br.com.medidor.app.domain

import br.com.medidor.app.data.MeasurementRecord
import java.util.Locale

/** Plain report content, independent of Android PDF APIs, so it can be tested. */
object ReportContent {
    private fun meters(value: Double) = String.format(Locale("pt", "BR"), "%.2f", value)

    fun measurements(records: List<MeasurementRecord>): List<String> {
        val lines = mutableListOf(
            "MEDIDOR - RELATORIO DE MEDICOES",
            "Registros salvos neste dispositivo: ${records.size}",
            ""
        )
        if (records.isEmpty()) lines += "Nenhuma medicao registrada."
        records.forEachIndexed { index, record ->
            lines += "${index + 1}. ${record.title}"
            lines += "Data: ${record.createdAt}"
            lines += "Resultado: ${record.details}"
            lines += ""
        }
        lines += "ATENCAO: Medidas e volumes sao estimativas. Este documento nao e laudo tecnico."
        return lines
    }

    fun architect(title: String, plan: ArchitectPlan): List<String> {
        val lines = mutableListOf(
            "MEDIDOR - RESUMO DO MODO ARQUITETO",
            "Projeto: $title",
            "Pavimentos: ${plan.floors.size}",
            ""
        )
        plan.floors.forEachIndexed { i, floor ->
            lines += "${i + 1}. ${floor.title}"
            lines += "Paredes desenhadas: ${floor.walls.size}; portas/janelas: ${floor.openings.size}"
            lines += if (floor.calibrated) {
                "Extensao das paredes: ${meters(FloorPlanMath.totalWallMeters(floor) ?: 0.0)} m"
            } else "Escala pendente - comprimentos indisponiveis."
            lines += "Pe-direito: ${meters(floor.wallHeightMeters)} m"
            lines += "Posicao X/Y: ${meters(floor.offsetXMeters)} / ${meters(floor.offsetYMeters)} m"
            lines += "Rotacao: ${meters(floor.rotationDegrees)} graus"
            lines += ""
        }
        lines += "ATENCAO: Maquete esquematica e escala manual. Nao e planta executiva, CAD/BIM ou laudo."
        return lines
    }
}

/** Lines for an A4 report with automatic word wrapping and deterministic pages. */
object ReportPagination {
    fun wrap(lines: List<String>, lineLimit: Int = 76): List<String> {
        require(lineLimit >= 12)
        return lines.flatMap { original ->
            val line = original.replace("\r", "").replace("\n", " ")
            if (line.isBlank()) return@flatMap listOf("")
            val output = mutableListOf<String>()
            var current = ""
            for (word in line.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                if (word.length > lineLimit) {
                    if (current.isNotEmpty()) { output += current; current = "" }
                    word.chunked(lineLimit).let { chunks ->
                        output.addAll(chunks.dropLast(1))
                        current = chunks.last()
                    }
                } else if (current.isEmpty()) current = word
                else if (current.length + 1 + word.length <= lineLimit) current += " $word"
                else { output += current; current = word }
            }
            if (current.isNotEmpty()) output += current
            output
        }
    }

    fun pages(lines: List<String>, linesPerPage: Int = 42): List<List<String>> {
        require(linesPerPage > 0)
        val wrapped = wrap(lines)
        return wrapped.chunked(linesPerPage).ifEmpty { listOf(emptyList()) }
    }
}
