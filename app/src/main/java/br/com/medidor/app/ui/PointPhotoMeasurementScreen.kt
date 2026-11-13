package br.com.medidor.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import br.com.medidor.app.data.MeasurementPreferences
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    initialObjectSeed: NormalizedPoint? = null,
    onAutomatic: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val preferences = remember(context) { MeasurementPreferences(context.applicationContext) }
    var pointSize by remember { mutableStateOf(preferences.pointSize) }
    var pointOptions by remember { mutableStateOf(false) }
    var farHelp by remember { mutableStateOf(false) }
    var referencePicking by remember(photo) { mutableStateOf(false) }
    var resetKey by remember(photo) { mutableStateOf(0) }
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
        scaleMm == null && !referencePicking -> "Foto: toque inativo. Ative Escala ou Objeto; Auto mede no ambiente sem referência."
        scaleMm == null && reference.size < 2 -> "Foto: marque uma referência conhecida. Para calcular sem digitar tamanho, use Auto."
        scaleMm == null -> "2. Informe quanto mede a linha amarela para definir a escala."
        ratio == null -> "Afaste os pontos da referência para calcular a medida."
        points.isEmpty() -> "3. Toque no objeto para selecionar suas arestas ou use Pontos."
        areaMode && points.size < 3 -> "3. Toque em pelo menos três cantos, na ordem, para calcular a área."
        areaMode && area == null -> "As linhas se cruzam. Desfaça o último canto."
        !areaMode && points.size < 2 -> "3. Toque no início e no fim da distância."
        else -> "Medida fixada. Arraste as pontas para ajustar ou salve."
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        InteractiveMeasurementPhoto(
            photo,reference,points,scaleMm != null,areaMode,pointSize,
            title = result ?: if (areaMode) "Foto · Área" else "Foto · Régua",
            message = notice ?: instruction,
            referencePicking = referencePicking,
            resetKey = resetKey,
            onReferenceModeChange = { referencePicking = it; notice = null },
            automaticAvailable = onAutomatic != null,
            leadingActions = listOf(
                MeasureAction("Voltar", "Voltar à câmera", MeasureIcon.BACK, action = onBack),
                MeasureAction("Régua", icon = MeasureIcon.RULER, selected = !areaMode,
                    action = { areaMode = false; points.clear(); automaticArea = null; saved = false; notice = null }),
                MeasureAction("Área", icon = MeasureIcon.AREA, selected = areaMode,
                    action = { areaMode = true; points.clear(); automaticArea = null; saved = false; notice = null }),
                MeasureAction("Auto", "Medir automaticamente no ambiente", MeasureIcon.AUTO,
                    selected = scaleMm == null, enabled = onAutomatic != null, action = { onAutomatic?.invoke() }),
                MeasureAction("Escala", "Definir escala da foto", MeasureIcon.SCALE,
                    action = { if (reference.size == 2) scaleDialog = true else farHelp = true }),
                MeasureAction(if (saved) "Salvo" else "Salvar", icon = MeasureIcon.SAVE,
                    enabled = result != null && !saved, action = { result?.let {
                        onSave(if (areaMode) "Área por pontos" else "Régua por pontos", "$it (estimativa por foto calibrada)")
                        saved = true
                    } })
            ),
            trailingActions = listOf(
                MeasureAction("Desfaz.", "Desfazer", MeasureIcon.UNDO, enabled = reference.isNotEmpty(), action = {
                    if (points.isNotEmpty()) points.removeAt(points.lastIndex)
                    else { scaleMm = null; referencePicking = true; if (reference.isNotEmpty()) reference.removeAt(reference.lastIndex) }
                    automaticArea = null; saved = false; notice = null
                }),
                MeasureAction("Refazer", icon = MeasureIcon.RESET, action = {
                    scaleMm = null; reference.clear(); points.clear(); automaticArea = null
                    resetKey++
                    referencePicking = false; saved = false; notice = null
                })
            ),
            referenceCaption = scaleMm?.let { "REF ${length(it)}" },
            edgeCaption = { pixels -> ratio?.let { length(pixels*it) } },
            areaCaption = if (area != null) result else null,
            initialSeed = initialObjectSeed,
            onReferenceComplete = { scaleDialog = true },
            onChanged = { saved = false; notice = null },
            onAutomaticArea = { automaticArea = it },
            onNotice = { notice = it },
            onPointOptions = { pointOptions = true },
            modifier = Modifier.fillMaxSize()
        )
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
        onDismissRequest = { farHelp = false }, title = { Text("Escala da foto") },
        text = { Text("Na foto, marque uma linha sobre uma referência de tamanho conhecido e informe seu valor. A referência precisa estar no mesmo plano do objeto.\n\nPara medir sem digitar um tamanho, use Auto no ambiente. O zoom e o ponto fino ajudam a ajustar as pontas.") },
        confirmButton = { TextButton(onClick = { farHelp = false; referencePicking = true; reference.clear(); scaleMm = null; notice = null }) { Text("Marcar referência") } },
        dismissButton = { if (onAutomatic != null) TextButton(onClick = { farHelp = false; onAutomatic() }) { Text("Usar Auto") } }
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
            points.clear(); saved = false; notice = null; scaleDialog = false; referencePicking = false
        },enabled = span != null && ReferenceMarkQuality.isReadable(span) && PhotoCalibration.manualReferenceMillimeters(known,unit) != null) { Text("Aplicar escala") } },
        dismissButton = { TextButton(onClick = { scaleDialog = false }) { Text("Voltar à foto") } }
    )
}
