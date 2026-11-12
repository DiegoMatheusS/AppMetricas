package br.com.medidor.app.domain

/** Photo positions support overlapping images for offline reconstruction. */
enum class ScanPass(val label: String) {
    HORIZON("Volta horizontal"),
    ELEVATED("Volta elevada")
}

data class ScanPosition(
    val index: Int,
    val pass: ScanPass,
    val angleDegrees: Int,
    val label: String,
    val instruction: String
)

object ScannerCaptureGuide {
    const val MAX_SESSIONS = 8
    const val REQUIRED_SHOTS = 20

    val positions: List<ScanPosition> =
        (0 until 12).map { index ->
            ScanPosition(
                index = index,
                pass = ScanPass.HORIZON,
                angleDegrees = index * 30,
                label = "Horizontal ${index + 1}/12",
                instruction = "Fotografe o objeto em torno de ${index * 30}°, com altura da câmera constante."
            )
        } + (0 until 8).map { index ->
            ScanPosition(
                index = index + 12,
                pass = ScanPass.ELEVATED,
                angleDegrees = index * 45,
                label = "Elevada ${index + 1}/8",
                instruction = "Aponte de cima (aprox. 30°) e circule até ${index * 45}°."
            )
        }

    fun nextMissing(filled: Set<Int>): Int? =
        positions.firstOrNull { it.index !in filled }?.index

    fun validSlots(filled: Collection<Int>): Boolean =
        filled.size <= REQUIRED_SHOTS &&
            filled.toSet().size == filled.size &&
            filled.all { it in 0 until REQUIRED_SHOTS }

    fun complete(filled: Collection<Int>): Boolean =
        validSlots(filled) && filled.size == REQUIRED_SHOTS
}
