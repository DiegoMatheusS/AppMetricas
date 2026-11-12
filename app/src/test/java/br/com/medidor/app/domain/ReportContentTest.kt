package br.com.medidor.app.domain

import br.com.medidor.app.data.MeasurementRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportContentTest {
    @Test fun rendersSavedMeasurementsAndDisclaimer() {
        val lines = ReportContent.measurements(listOf(
            MeasurementRecord("Tanque", "1280 litros estimados", "22/10/2026"),
            MeasurementRecord("Caixa", "3,5 m3", "22/10/2026")
        ))
        assertTrue(lines.any { it.contains("1280 litros") })
        assertTrue(lines.any { it.contains("3,5 m3") })
        assertTrue(lines.any { it.contains("nao e laudo") })
    }

    @Test fun distinguishesUncalibratedArchitecture() {
        val lines = ReportContent.architect("Projeto teste", ArchitectPlan())
        assertTrue(lines.any { it.contains("Escala pendente") })
        assertTrue(lines.any { it.contains("Projeto teste") })
    }

    @Test fun wrapsLongValuesWithoutDataLoss() {
        val value = "1234567890".repeat(28)
        val wrapped = ReportPagination.wrap(listOf(value), 40)
        assertTrue(wrapped.all { it.length <= 40 })
        assertEquals(value, wrapped.joinToString(""))
    }

    @Test fun splitsIntoMultiplePagesPreservingOrder() {
        val items = (1..120).map { "Linha $it" }
        val pages = ReportPagination.pages(items, linesPerPage = 35)
        assertEquals(4, pages.size)
        assertEquals(items, pages.flatten())
    }
}
