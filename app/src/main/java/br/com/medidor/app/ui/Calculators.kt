package br.com.medidor.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
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
    var diameter by rememberSaveable { mutableStateOf("12") }
    var size by rememberSaveable { mutableStateOf("8") }
    var level by rememberSaveable { mutableStateOf("0") }
    var levelUnit by rememberSaveable { mutableStateOf(TankLevelUnit.METERS.name) }
    var densityText by rememberSaveable { mutableStateOf("") }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
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
    val ink = Color(0xFF14251F)
    val lime = Color(0xFFC0F565)

    Column(
        Modifier.background(Color(0xFFF7F8F5)).verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onCamera,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE7EAE4), contentColor = ink)
            ) { Text("Câmera") }
            Button(
                onClick = {},
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White, contentColor = ink)
            ) { Text("Manual") }
        }
        Text("Escolha o formato do tanque",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        TankIllustrationPicker(
            selected = TankIllustration.valueOf(illustration),
            onSelect = { option ->
                illustration = option.name
                shape = option.geometry.name
                saved = false
            }
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(selected.label.uppercase(),
                    color = Color(0xFF747E76), fontSize = 11.sp)
                TankOutline(
                    TankIllustration.valueOf(illustration),
                    Modifier.fillMaxWidth().height(160.dp)
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Unidade de medida", style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF737C73))
            Text("Metros (m)", style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DimensionField("Diâmetro interno (m)", diameter, Modifier.weight(1f)) {
                diameter = it; saved = false
            }
            if (selected != TankShape.SPHERE) {
                DimensionField(
                    if (selected == TankShape.VERTICAL) "Altura útil (m)"
                    else "Comprimento (m)",
                    size, Modifier.weight(1f)
                ) { size = it; saved = false }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = ink),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("CAPACIDADE TOTAL", fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.7f))
                Text(
                    if (result != null) "${decimal(result.capacityM3)} m³"
                    else "— m³",
                    color = lime, fontSize = 30.sp, fontWeight = FontWeight.Medium
                )
                Text(
                    if (result != null) "${decimal(Geometry.liters(result.capacityM3))} litros"
                    else "Informe as dimensões internas",
                    style = MaterialTheme.typography.bodyMedium, color = Color.White
                )
                Text("Capacidade geométrica estimada, não volume atual do líquido.",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
            }
        }
        Text("▾ Nível do produto e cálculos adicionais",
            modifier = Modifier.fillMaxWidth()
                .clickable { detailsOpen = !detailsOpen }
                .padding(vertical = 10.dp),
            style = MaterialTheme.typography.titleSmall)
        if (detailsOpen) {
            Text("Informe o nível atual do produto, medido a partir do fundo.",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
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
            DimensionField("Nível (${unit.label})", level) {
                level = it; saved = false
            }
            DimensionField("Densidade aparente (kg/m³, opcional)", densityText) {
                densityText = it; saved = false
            }
            if (result != null) {
                ResultCard("Volume atual", "${decimal(result.occupiedM3)} m³")
                ResultCard("Espaço livre", "${decimal(result.freeM3)} m³")
                ResultCard("Ocupação", "${decimal(result.fillPercent)}%")
                if (productMass != null)
                    ResultCard("Massa estimada", "${decimal(productMass)} kg")
            }
            if (!densityValid)
                Text("Densidade inválida: use 0,01 a 30.000 kg/m³.",
                    color = MaterialTheme.colorScheme.error)
        }
        if (!valid) {
            Text("Confira as dimensões e o nível: use valores internos válidos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        Button(
            enabled = valid && densityValid,
            onClick = {
                onSave(selected.label, "Capacidade: ${decimal(result!!.capacityM3)} m³ | " +
                    "Produto: ${decimal(result.occupiedM3)} m³ | Nível: ${decimal(lv!!)} m" +
                    (if (productMass != null)
                        " | Massa: ${decimal(productMass)} kg" else ""))
                saved = true
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = lime, contentColor = ink)
        ) {
            Text("Salvar cálculo ✓", fontWeight = FontWeight.Medium)
        }
        if (saved) Text("Cálculo salvo neste aparelho.")
        Text("Os cálculos representam sólidos geométricos ideais. Sem aferição de " +
            "dimensões, não há garantia de capacidade real ou arqueação certificada.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TankMeasurementGuide(selected)
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
