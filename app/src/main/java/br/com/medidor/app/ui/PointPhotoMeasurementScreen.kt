package br.com.medidor.app.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import br.com.medidor.app.data.MeasurementPreferences
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.medidor.app.domain.*
import java.text.NumberFormat
import java.util.Locale

/** A frozen photograph keeps taps aligned even on devices without ARCore. */
@Composable
internal fun PointPhotoMeasurementScreen(
    photo: Bitmap,
    initialArea: Boolean,
    onBack: () -> Unit,
    onSave: (String, String) -> Unit,
    initialObjectSeed: NormalizedPoint? = null
) {
    val context = LocalContext.current
    val preferences = remember(context) { MeasurementPreferences(context.applicationContext) }
    var pointSize by remember { mutableStateOf(preferences.pointSize) }
    var pointOptions by remember { mutableStateOf(false) }
    var farHelp by remember { mutableStateOf(false) }
    var automaticArea by remember(photo) { mutableStateOf<Double?>(null) }
    var areaMode by remember(photo) { mutableStateOf(initialArea) }
    val reference = remember(photo) { mutableStateListOf<NormalizedPoint>() }
    val points = remember(photo) { mutableStateListOf<NormalizedPoint>() }
    var scaleMm by remember(photo) { mutableStateOf<Double?>(null) }
    var scaleDialog by remember { mutableStateOf(false) }
    var known by remember { mutableStateOf("10") }
    var unit by remember { mutableStateOf(LengthUnit.CENTIMETERS) }
    var saved by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val number = remember { NumberFormat.getNumberInstance(Locale.forLanguageTag("pt-BR")).apply { maximumFractionDigits = 2 } }
    fun length(mm: Double) = if (mm >= 1000) "${number.format(mm / 1000)} m" else "${number.format(mm / 10)} cm"
    val span = if (reference.size == 2) PhotoCalibration.distancePixels(reference[0], reference[1], photo.width, photo.height) else null
    val ratio = if (scaleMm != null && span != null && ReferenceMarkQuality.isReadable(span)) scaleMm!! / span else null
    val distance = if (!areaMode && points.size == 2 && ratio != null)
        PhotoCalibration.distancePixels(points[0], points[1], photo.width, photo.height) * ratio else null
    val pixelsArea = if (areaMode) automaticArea ?: PointMeasurementGeometry.polygonAreaPixels2(points, photo.width, photo.height) else null
    val area = if (pixelsArea != null && ratio != null) pixelsArea * ratio * ratio else null
    val result = distance?.let { "Distância: ${length(it)}" } ?: area?.let {
        "Área: " + if (it >= 1_000_000) "${number.format(it / 1_000_000)} m²" else "${number.format(it / 100)} cm²"
    }
    val instruction = when {
        scaleMm == null && reference.size < 2 -> "1. Toque nos dois extremos de uma referência conhecida na foto."
        scaleMm == null -> "2. Informe quanto mede a linha amarela para definir a escala."
        ratio == null -> "Afaste os pontos da referência para calcular a medida."
        points.isEmpty() -> "3. Toque no objeto para selecionar suas arestas ou use Pontos."
        areaMode && points.size < 3 -> "3. Toque em pelo menos três cantos, na ordem, para calcular a área."
        areaMode && area == null -> "As linhas se cruzam. Desfaça o último canto."
        !areaMode && points.size < 2 -> "3. Toque no início e no fim da distância."
        else -> "Medida fixada. Arraste as pontas para ajustar ou salve."
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("‹ Câmera") }
            Row {
                FilterChip(selected = !areaMode, onClick = { areaMode = false; points.clear(); saved = false }, label = { Text("Régua") })
                FilterChip(selected = areaMode, onClick = { areaMode = true; points.clear(); saved = false }, label = { Text("Área") })
            }
        }
        Text(result ?: if (areaMode) "Área por pontos" else "Régua por pontos",
            modifier = Modifier.padding(horizontal = 12.dp), fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(notice ?: instruction, modifier = Modifier.padding(12.dp), fontSize = 14.sp)
        InteractiveMeasurementPhoto(
            photo,reference,points,scaleMm != null,areaMode,pointSize,
            referenceCaption = scaleMm?.let { "REF ${length(it)}" },
            edgeCaption = { pixels -> ratio?.let { length(pixels*it) } },
            areaCaption = if (area != null) result else null,
            initialSeed = initialObjectSeed,
            onReferenceComplete = { scaleDialog = true },
            onChanged = { saved = false; notice = null },
            onAutomaticArea = { automaticArea = it },
            onNotice = { notice = it },
            onPointOptions = { pointOptions = true },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (scaleMm == null) Button(onClick = { scaleDialog = true }, enabled = reference.size == 2, modifier = Modifier.fillMaxWidth()) { Text("Definir escala") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = {
                    if (points.isNotEmpty()) points.removeAt(points.lastIndex)
                    else { scaleMm = null; if (reference.isNotEmpty()) reference.removeAt(reference.lastIndex) }
                    automaticArea = null; saved = false; notice = null
                }, modifier = Modifier.weight(1f), enabled = reference.isNotEmpty()) { Text("Desfazer") }
                OutlinedButton(onClick = { scaleMm = null; reference.clear(); points.clear(); automaticArea = null; saved = false; notice = null }, modifier = Modifier.weight(1f)) { Text("Refazer") }
                Button(onClick = { result?.let { onSave(if (areaMode) "Área por pontos" else "Régua por pontos", "$it (estimativa por foto calibrada)"); saved = true } },
                    enabled = result != null && !saved, modifier = Modifier.weight(1f)) { Text(if (saved) "Salvo" else "Salvar") }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Referência e objeto no mesmo plano.", fontSize = 11.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { farHelp = true }) { Text("Medir de longe") }
            }
        }
    }
    if (pointOptions) AlertDialog(
        onDismissRequest = { pointOptions = false },
        title = { Text("Tamanho do ponto") },
        text = { Column {
            Text("Use Fino para fios, quinas e detalhes pequenos. O tamanho do marcador não muda a escala da medida.")
            MeasurementPointSize.entries.forEach { choice ->
                FilterChip(selected = pointSize == choice, onClick = {
                    pointSize = choice
                    runCatching { preferences.pointSize = choice }
                    pointOptions = false
                }, label = { Text(choice.label) })
            }
            Text("Aproxime com dois dedos. Arraste uma ponta para abrir a lupa.", fontSize = 12.sp)
        } },
        confirmButton = { TextButton(onClick = { pointOptions = false }) { Text("Fechar") } }
    )
    if (farHelp) AlertDialog(
        onDismissRequest = { farHelp = false }, title = { Text("Medir uma cerca de longe") },
        text = { Text("Fotografe a cerca de frente, incluindo uma referência conhecida, como a altura de um mourão no mesmo plano. Marque essa referência e informe o tamanho real. Depois toque na cerca para selecionar suas arestas. Use Ampliar seleção se precisar incluir mais partes, ou Pontos para escolher os extremos.\n\nUse o zoom e o ponto fino para ajustar detalhes. Esta medição é o comprimento ou a área projetada do objeto na foto; a distância entre você e a cerca exige uma leitura real de profundidade.") },
        confirmButton = { TextButton(onClick = { farHelp = false }) { Text("Entendi") } }
    )
    if (scaleDialog) AlertDialog(
        onDismissRequest = { scaleDialog = false },
        title = { Text("Quanto mede a linha amarela?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Informe o tamanho real da referência. A câmera precisa dessa escala para calcular centímetros e área.")
                OutlinedTextField(known,{ known = it },label = { Text("Medida da referência") },singleLine = true,keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Row { LengthUnit.entries.forEach { choice -> FilterChip(selected = unit == choice,onClick = { unit = choice },label = { Text(choice.label) }) } }
                Text("Ou use o diâmetro de uma moeda:", fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(BrazilianCoin.entries) { coin -> OutlinedButton(onClick = { known = coin.diameterMm.toString(); unit = LengthUnit.MILLIMETERS }) { Text(coin.label) } }
                }
                if (span != null && !ReferenceMarkQuality.isReadable(span)) Text("Referência curta demais. Refaça os pontos mais afastados.",color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = {
            scaleMm = PhotoCalibration.manualReferenceMillimeters(known,unit)
            points.clear(); saved = false; notice = null; scaleDialog = false
        },enabled = span != null && ReferenceMarkQuality.isReadable(span) && PhotoCalibration.manualReferenceMillimeters(known,unit) != null) { Text("Aplicar escala") } },
        dismissButton = { TextButton(onClick = { scaleDialog = false }) { Text("Voltar à foto") } }
    )
}
