package br.com.medidor.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import br.com.medidor.app.domain.Geometry
import br.com.medidor.app.domain.TankCalculator
import br.com.medidor.app.domain.TankShape
import br.com.medidor.app.domain.TankLevelUnit
import br.com.medidor.app.domain.TankFillInput
import java.text.NumberFormat
import java.util.Locale

internal fun parseDimension(value: String): Double? =
    value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
internal fun parseLevel(value: String): Double? =
    value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
internal fun decimal(value: Double): String =
    NumberFormat.getNumberInstance(Locale("pt", "BR")).apply { maximumFractionDigits = 3 }.format(value)

@Composable
internal fun DimensionField(label: String, value: String, modifier: Modifier = Modifier.widthIn(max = 200.dp), onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

@Composable
fun BoxCalculatorScreen(onSave: (String, String) -> Unit, onCamera: () -> Unit = {}) {
    var length by rememberSaveable { mutableStateOf("0,50") }
    var width by rememberSaveable { mutableStateOf("0,40") }
    var height by rememberSaveable { mutableStateOf("0,30") }
    var saved by remember { mutableStateOf(false) }
    val l = parseDimension(length)
    val w = parseDimension(width)
    val h = parseDimension(height)
    val valid = l != null && w != null && h != null
    val volume = if (valid) Geometry.rectangularVolume(l!!, w!!, h!!) else null
    val area = if (valid) Geometry.rectangularFloorArea(l!!, w!!) else null
    val surface = if (valid) Geometry.rectangularSurfaceArea(l!!, w!!, h!!) else null

    Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CameraFirstAction(onCamera)
        Text("Caixa ou ambiente retangular", style = MaterialTheme.typography.titleLarge)
        Text("Informe as medidas internas em metros para calcular a capacidade útil.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        DimensionDiagram()
        BoxMeasurementGuide()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DimensionField("Comprimento", length, Modifier.weight(1f)) { length = it; saved = false }
            DimensionField("Largura", width, Modifier.weight(1f)) { width = it; saved = false }
        }
        DimensionField("Altura / profundidade (m)", height) { height = it; saved = false }
        if (!valid) {
            Text("Preencha todas as dimensões com números positivos.", color = MaterialTheme.colorScheme.error)
        } else {
            ResultCard("Volume", "${decimal(volume!!)} m³  •  ${decimal(Geometry.liters(volume))} L")
            ResultCard("Área da base", "${decimal(area!!)} m²")
            ResultCard("Área externa ideal", "${decimal(surface!!)} m²")
        }
        Button(enabled = valid, onClick = {
            onSave("Caixa / ambiente", "Volume: ${decimal(volume!!)} m³ | Base: ${decimal(area!!)} m²")
            saved = true
        }) { Text("Salvar medição") }
        if (saved) Text("Medição salva neste aparelho.", color = MaterialTheme.colorScheme.primary)
        Text("Valores geométricos. Para capacidade interna real, use dimensões internas.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun TankCalculatorScreen(onSave: (String, String) -> Unit, onCamera: () -> Unit = {}) {
    var shape by rememberSaveable { mutableStateOf(TankShape.VERTICAL.name) }
    var illustration by rememberSaveable { mutableStateOf(TankIllustration.VERTICAL.name) }
    var diameter by rememberSaveable { mutableStateOf("8") }
    var size by rememberSaveable { mutableStateOf("12") }
    var level by rememberSaveable { mutableStateOf("5") }
    var levelUnit by rememberSaveable { mutableStateOf(TankLevelUnit.METERS.name) }
    var densityText by rememberSaveable { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    val selected = TankShape.valueOf(shape)
    val d = parseDimension(diameter)
    val s = if (selected == TankShape.SPHERE) d else parseDimension(size)
    val levelValue = parseLevel(level)
    val maxLevel = if (selected == TankShape.VERTICAL) s else d
    val unit = TankLevelUnit.valueOf(levelUnit)
    val lv = if (levelValue != null && maxLevel != null)
        TankFillInput.heightMeters(levelValue, unit, maxLevel) else null
    val valid = d != null && s != null && lv != null
    val result = if (valid) TankCalculator.calculate(selected, d!!, s!!, lv!!) else null
    val density = densityText.trim().replace(',', '.').toDoubleOrNull()
    val densityValid = densityText.isBlank() ||
        (density != null && density.isFinite() && density in 0.01..30000.0)
    val productMass = if (densityValid && result != null && density != null)
        TankFillInput.estimatedMassKg(result.occupiedM3, density) else null

    Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CameraFirstAction(onCamera)
        Text("Quantidade armazenada no tanque", style = MaterialTheme.typography.titleLarge)
        Text("Use dimensões INTERNAS em metros. Tanques ideais, sem acessórios e sem perdas.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Escolha o tipo de tanque", style = MaterialTheme.typography.titleMedium)
        TankIllustrationPicker(
            selected = TankIllustration.valueOf(illustration),
            onSelect = { option ->
                illustration = option.name
                shape = option.geometry.name
                saved = false
            }
        )
        TankOutline(TankIllustration.valueOf(illustration), Modifier.fillMaxWidth().padding(vertical = 8.dp).height(120.dp))
        DimensionDiagram(selected)
        TankMeasurementGuide(selected)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DimensionField("Diâmetro (m)", diameter, Modifier.weight(1f)) { diameter = it; saved = false }
            if (selected != TankShape.SPHERE) {
                DimensionField(if (selected == TankShape.VERTICAL) "Altura (m)" else "Comprimento (m)", size, Modifier.weight(1f)) { size = it; saved = false }
            }
        }
        Text("Altura atual do líquido ou produto, medida a partir do fundo:",
            style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TankLevelUnit.entries.forEach { choice ->
                FilterChip(selected = choice.name == levelUnit, onClick = {
                    levelUnit = choice.name
                    level = when(choice) {
                        TankLevelUnit.METERS -> if (lv != null) decimal(lv) else "0"
                        TankLevelUnit.CENTIMETERS -> if (lv != null) decimal(lv*100) else "0"
                        TankLevelUnit.HEIGHT_PERCENT -> if (lv != null && maxLevel != null)
                            decimal(lv/maxLevel*100) else "0"
                    }
                    saved = false
                }, label = { Text(choice.label) })
            }
        }
        DimensionField("Nível a partir do fundo (${unit.label})", level) {
            level = it; saved = false
        }
        DimensionField("Densidade aparente (kg/m³, opcional)", densityText) {
            densityText = it; saved = false
        }
        Text("Informe a densidade aparente do produto para estimar também a massa. " +
            "Para sólidos a granel, superfície irregular e compactação podem alterar a estimativa.",
            style = MaterialTheme.typography.bodySmall)
        Text("Resultado das medidas", style = MaterialTheme.typography.titleMedium)
        if (!valid) {
            Text("Verifique as medidas. O nível deve ficar entre zero e a altura total " +
                "(${maxLevel?.let(::decimal) ?: "—"} m).",
                color = MaterialTheme.colorScheme.error)
        } else {
            ResultCard("Capacidade geométrica", "${decimal(result!!.capacityM3)} m³ • ${decimal(Geometry.liters(result.capacityM3))} L")
            ResultCard("Volume do produto até ${decimal(lv!!)} m",
                "${decimal(result.occupiedM3)} m³ • ${decimal(Geometry.liters(result.occupiedM3))} L")
            ResultCard("Espaço livre",
                "${decimal(result.freeM3)} m³ • ${decimal(Geometry.liters(result.freeM3))} L")
            ResultCard("Ocupação", "${decimal(result.fillPercent)}% do volume total")
            if (productMass != null) ResultCard("Massa estimada", "${decimal(productMass)} kg")
            if (!densityValid) Text("Informe uma densidade válida de 0,01 a 30.000 kg/m³.",
                color = MaterialTheme.colorScheme.error)
        }
        Button(enabled = valid && densityValid, onClick = {
            onSave(selected.label, "Capacidade: ${decimal(result!!.capacityM3)} m³ | " +
                "Produto: ${decimal(result.occupiedM3)} m³ (${decimal(Geometry.liters(result.occupiedM3))} L) | " +
                "Nível: ${decimal(lv!!)} m" +
                (if (productMass != null) " | Massa estimada: ${decimal(productMass)} kg" else ""))
            saved = true
        }) { Text("Salvar cálculo") }
        if (saved) Text("Cálculo salvo neste aparelho.", color = MaterialTheme.colorScheme.primary)
        Text("Cálculo para geometria interna ideal; não considera fundo irregular, " +
            "teto, tubulações ou volume morto. Percentual da altura não é igual ao " +
            "percentual do volume nos tanques esféricos e horizontais. " +
            "Não substituir arqueação certificada.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ResultCard(label: String, value: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CameraFirstAction(onCamera: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onCamera, modifier = Modifier.fillMaxWidth()) {
                Text("Calcular com a câmera")
            }
            Text("Ou informe manualmente as dimensões abaixo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BoxMeasurementGuide() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Como medir a caixa", style = MaterialTheme.typography.titleSmall)
            Text("Comprimento: de uma extremidade à outra da base.")
            Text("Largura: outra dimensão horizontal da base.")
            Text("Altura: da base até o topo, na vertical.")
            Text("Use medidas internas para calcular a capacidade útil.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TankMeasurementGuide(shape: TankShape) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Entenda as medidas", style = MaterialTheme.typography.titleSmall)
            Text("Diâmetro: distância entre duas bordas opostas, passando pelo centro da seção circular.")
            when (shape) {
                TankShape.VERTICAL -> Text("Altura: distância vertical entre o fundo e o topo interno.")
                TankShape.HORIZONTAL -> Text("Comprimento: distância interna entre as duas extremidades do tanque deitado.")
                TankShape.SPHERE -> Text("Na esfera, o diâmetro é suficiente para calcular a capacidade.")
            }
            Text("Nível: altura do líquido medida a partir do ponto mais baixo do fundo.")
            Text("Os desenhos são ilustrativos; formato longo ou gordão não muda a fórmula do cilindro.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
