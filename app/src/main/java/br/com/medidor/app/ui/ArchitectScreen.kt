package br.com.medidor.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import br.com.medidor.app.data.ArchitectPlanStore
import br.com.medidor.app.data.ArchitectProjectManager
import br.com.medidor.app.domain.ArchitectPlan
import br.com.medidor.app.domain.FloorPlanMath
import br.com.medidor.app.domain.PlanFloor
import br.com.medidor.app.domain.PlanPoint
import br.com.medidor.app.domain.PlanWall
import br.com.medidor.app.domain.PlanRectification
import br.com.medidor.app.domain.PlanLandmarkAlignment
import br.com.medidor.app.domain.DxfWallImport
import br.com.medidor.app.domain.DxfDrawingUnit
import android.graphics.Bitmap
import android.graphics.Paint
import java.util.Locale
import kotlin.math.min

/**
 * Non-AI floor editor. No automatic floorplan interpretation/AR tracking here:
 * walls and reference line are traced by the user on a perspective-correct plan.
 */
@Composable
fun ArchitectScreen() {
    val context = LocalContext.current
    val repository = remember(context) { ArchitectPlanStore(context.applicationContext) }
    val manager = remember(context) { ArchitectProjectManager(context.applicationContext) }
    var activeProjectId by remember { mutableStateOf(manager.activeId()) }
    var project by remember { mutableStateOf(repository.load()) }
    var editingMode by remember { mutableStateOf("reference") }
    var pending by remember { mutableStateOf<PlanPoint?>(null) }
    var showLines by rememberSaveable { mutableStateOf(true) }
    var showMeasurements by rememberSaveable { mutableStateOf(true) }
    var showAllFloors by rememberSaveable { mutableStateOf(true) }
    var preview3D by rememberSaveable { mutableStateOf(false) }
    var paperWidthCm by rememberSaveable { mutableStateOf("21") }
    var confirmErase by remember { mutableStateOf(false) }
    var confirmRectify by remember { mutableStateOf(false) }
    var sheetWidth by rememberSaveable { mutableStateOf("21") }
    var sheetHeight by rememberSaveable { mutableStateOf("29,7") }
    val perspectiveCorners = remember { mutableStateListOf<PlanPoint>() }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedPdf by remember { mutableStateOf<Uri?>(null) }
    var pdfPages by remember { mutableStateOf(0) }
    var pdfPageText by remember { mutableStateOf("1") }
    var dxfDraft by remember { mutableStateOf<ImportedDxf?>(null) }
    var selectedDxfLayer by remember { mutableStateOf<String?>(null) }
    var selectedDxfUnit by remember { mutableStateOf<DxfDrawingUnit?>(null) }

    val selected = project.selectedFloor
    val current = project.floors[selected]
    var referenceText by remember(activeProjectId, selected) { mutableStateOf(current.knownMeters.toString()) }
    var wallHeightText by remember(activeProjectId, selected) { mutableStateOf(current.wallHeightMeters.toString()) }
    var offsetXText by remember(activeProjectId, selected) { mutableStateOf(current.offsetXMeters.toString()) }
    var offsetYText by remember(activeProjectId, selected) { mutableStateOf(current.offsetYMeters.toString()) }
    var rotationText by remember(activeProjectId, selected) { mutableStateOf(current.rotationDegrees.toString()) }

    fun persist(next: ArchitectPlan) {
        try {
            repository.save(next)
            project = next
            error = null
        } catch (e: Exception) {
            error = "Não foi possível salvar o projeto: ${e.localizedMessage}"
        }
    }
    fun editFloor(next: PlanFloor) {
        val updated = project.floors.toMutableList().apply { set(selected, next) }
        persist(project.copy(floors = updated))
    }
    fun chooseFloor(index: Int) {
        if (index == selected) return
        pending = null
        perspectiveCorners.clear()
        editingMode = "reference"
        persist(project.copy(selectedFloor = index))
    }

    val openImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val photo = SampledPhotos.fromUri(context.contentResolver, uri)
                val name = repository.saveImage(photo)
                editFloor(current.copy(
                    imageFile = name,
                    imageWidth = photo.width,
                    imageHeight = photo.height,
                    referenceStart = null,
                    referenceEnd = null,
                    walls = emptyList(),
                    openings = emptyList(),
                    alignmentPoint = null,
                    secondAlignmentPoint = null
                ))
                editingMode = "reference"
                perspectiveCorners.clear()
                pending = null
            } catch (e: Exception) {
                error = "Falha ao importar planta: ${e.localizedMessage}"
            }
        }
    }

    fun saveImportedDrawing(image: Bitmap) {
        try {
            val name = repository.saveImage(image)
            editFloor(current.copy(
                imageFile = name, imageWidth = image.width, imageHeight = image.height,
                referenceStart = null, referenceEnd = null,
                walls = emptyList(), openings = emptyList(),
                alignmentPoint = null, secondAlignmentPoint = null
            ))
            editingMode = "reference"
            pending = null
            perspectiveCorners.clear()
        } finally {
            image.recycle()
        }
    }

    val openPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) try {
            pdfPages = ArchitectDocumentImport.pageCount(context.contentResolver, uri)
            require(pdfPages > 0) { "PDF sem páginas." }
            selectedPdf = uri
            pdfPageText = "1"
        } catch (e: Exception) {
            error = "Não foi possível abrir PDF: ${e.localizedMessage}"
        }
    }
    val openDxf = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) try {
            dxfDraft = ArchitectDocumentImport.readDxf(context.contentResolver, uri)
            selectedDxfLayer = null
            selectedDxfUnit = null
        } catch (e: Exception) {
            error = "Não foi possível importar DXF: ${e.localizedMessage}"
        }
    }

    if (dxfDraft != null) {
        val drawing = dxfDraft!!
        val layers = DxfWallImport.layers(drawing.lines)
        val layerCount = selectedDxfLayer?.let { layers[it] } ?: 0
        AlertDialog(
            onDismissRequest = { dxfDraft = null },
            title = { Text("Importar desenho DXF") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${drawing.lines.size} segmentos de desenho. Selecione somente " +
                        "uma camada que realmente contenha paredes.")
                    Text("A importação substitui a imagem e as marcações do andar atual. " +
                        "Portas e janelas anteriores serão removidas.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedDxfLayer == null,
                            onClick = { selectedDxfLayer = null },
                            label = { Text("Só desenho (sem paredes)") }
                        )
                        layers.forEach { (layer, count) ->
                            FilterChip(
                                selected = selectedDxfLayer == layer,
                                onClick = { selectedDxfLayer = layer },
                                label = { Text("$layer ($count)") }
                            )
                        }
                    }
                    if (selectedDxfLayer != null) {
                        Text("Selecione a UNIDADE REAL do arquivo DXF. " +
                            "Não é possível inferi-la com segurança apenas pelas linhas.",
                            style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DxfDrawingUnit.entries.forEach { unit ->
                                FilterChip(
                                    selected = selectedDxfUnit == unit,
                                    onClick = { selectedDxfUnit = unit },
                                    label = { Text(unit.label) }
                                )
                            }
                        }
                        Text("A camada selecionada possui $layerCount segmentos. " +
                            "O máximo é ${DxfWallImport.MAX_WALLS} paredes por importação. " +
                            "Todas essas linhas serão tratadas como paredes até você revisá-las.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Suporta LINE e polilinhas retas. Cotas, móveis e portas em uma " +
                        "camada escolhida incorretamente também virariam paredes. " +
                        "Confira a prévia 3D depois.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedDxfLayer == null ||
                        (selectedDxfUnit != null && layerCount in 1..DxfWallImport.MAX_WALLS),
                    onClick = {
                        try {
                            val converted = selectedDxfLayer?.let { layer ->
                                DxfWallImport.convert(
                                    drawing.lines,drawing.layout,layer,
                                    requireNotNull(selectedDxfUnit)
                                )
                            }
                            val bitmap = ArchitectDocumentImport.renderDxf(drawing)
                            try {
                                val filename = repository.saveImage(bitmap)
                                editFloor(current.copy(
                                    imageFile = filename,
                                    imageWidth = bitmap.width,
                                    imageHeight = bitmap.height,
                                    referenceStart = converted?.referenceStart,
                                    referenceEnd = converted?.referenceEnd,
                                    knownMeters = converted?.referenceMeters ?: current.knownMeters,
                                    walls = converted?.walls ?: emptyList(),
                                    openings = emptyList(),
                                    alignmentPoint = null,
                                    secondAlignmentPoint = null
                                ))
                                if (converted != null) {
                                    referenceText = converted.referenceMeters.toString()
                                    editingMode = "walls"
                                } else {
                                    editingMode = "reference"
                                }
                                pending = null
                                perspectiveCorners.clear()
                                dxfDraft = null
                            } finally {
                                bitmap.recycle()
                            }
                        } catch (e: Exception) {
                            error = "Importação DXF não concluída: ${e.localizedMessage}"
                        }
                    }
                ) { Text("Importar desenho") }
            },
            dismissButton = {
                TextButton(onClick = { dxfDraft = null }) { Text("Cancelar") }
            }
        )
    }

    if (selectedPdf != null) {
        AlertDialog(
            onDismissRequest = { selectedPdf = null },
            title = { Text("Escolher página da planta") },
            text = {
                Column {
                    Text("O PDF possui $pdfPages páginas. Escolha a página que contém a planta.")
                    OutlinedTextField(
                        value = pdfPageText, onValueChange = { pdfPageText = it },
                        label = { Text("Página (1 a $pdfPages)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    Text("A página será convertida em imagem. Marque as paredes e calibre a escala " +
                        "para criar a maquete 3D.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pdfPageText.toIntOrNull()?.let { it in 1..pdfPages } == true,
                    onClick = {
                        val uri = selectedPdf
                        selectedPdf = null
                        if (uri != null) try {
                            saveImportedDrawing(ArchitectDocumentImport.pdfPage(
                                context.contentResolver, uri, pdfPageText.toInt() - 1
                            ))
                        } catch (e: Exception) {
                            error = "Falha ao converter página: ${e.localizedMessage}"
                        }
                    }
                ) { Text("Importar página") }
            },
            dismissButton = {
                TextButton(onClick = { selectedPdf = null }) { Text("Cancelar") }
            }
        )
    }

    fun rectifySelectedPlan() {
        try {
            val original = repository.readImage(current.imageFile)
                ?: error("Imagem original não encontrada.")
            val w = sheetWidth.trim().replace(',', '.').toDoubleOrNull()
            val h = sheetHeight.trim().replace(',', '.').toDoubleOrNull()
            if (w == null || h == null) throw IllegalArgumentException("Informe largura e altura reais da folha.")
            try {
                val corrected = PlanBitmapRectifier.rectify(
                    original, perspectiveCorners.toList(), w, h
                )
                try {
                    val name = repository.saveImage(corrected)
                    editFloor(current.copy(
                        imageFile = name,
                        imageWidth = corrected.width,
                        imageHeight = corrected.height,
                        referenceStart = null,
                        referenceEnd = null,
                        alignmentPoint = null,
                        secondAlignmentPoint = null,
                        walls = emptyList(),
                        openings = emptyList()
                    ))
                    pending = null
                    perspectiveCorners.clear()
                    editingMode = "reference"
                } finally {
                    corrected.recycle()
                }
            } finally {
                original.recycle()
            }
        } catch (e: Exception) {
            error = "Não foi possível corrigir a perspectiva: ${e.localizedMessage}"
        }
    }

    if (confirmRectify) {
        AlertDialog(
            onDismissRequest = { confirmRectify = false },
            title = { Text("Substituir desenho da planta?") },
            text = { Text("A nova imagem será salva, mas a escala, as paredes, as portas, " +
                "as janelas e o ponto de alinhamento deste andar serão apagados. " +
                "Será necessário recalibrar e marcar novamente.") },
            confirmButton = { TextButton(onClick = {
                confirmRectify = false
                rectifySelectedPlan()
            }) { Text("Corrigir e limpar marcações") } },
            dismissButton = { TextButton(onClick = { confirmRectify = false }) {
                Text("Cancelar")
            } }
        )
    }
    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text("Apagar paredes deste andar?") },
            text = { Text("A planta e a referência de escala serão mantidas.") },
            confirmButton = {
                TextButton(onClick = {
                    editFloor(current.copy(walls = emptyList(), openings = emptyList()))
                    pending = null
                    confirmErase = false
                }) { Text("Apagar paredes") }
            },
            dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("Cancelar") } }
        )
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Editor de plantas — prévia", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)
        Text("Importe uma planta vista de cima, marque uma referência real e desenhe as paredes tocando nos extremos. " +
            "O projeto é salvo offline. AR é experimental. PDF e DXF podem ser usados como referência para modelagem manual.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        ArchitectProjectControls(
            manager = manager,
            onChanged = {
                activeProjectId = manager.activeId()
                project = repository.load()
                pending = null
                perspectiveCorners.clear()
                editingMode = "reference"
            },
            onError = { error = it }
        )
        ArchitectBackupControls(onRestored = {
            activeProjectId = manager.activeId()
            project = repository.load()
            pending = null
            perspectiveCorners.clear()
            editingMode = "reference"
            error = null
        })
        Text("Pavimentos", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            project.floors.forEachIndexed { index, f ->
                FilterChip(
                    selected = index == selected,
                    onClick = { chooseFloor(index) },
                    label = { Text(f.title) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = project.floors.size < 6, onClick = {
                val count = project.floors.size
                persist(project.copy(
                    floors = project.floors + PlanFloor("Andar ${count + 1}"),
                    selectedFloor = count
                ))
                pending = null
                editingMode = "reference"
            }) { Text("+ Andar") }
            OutlinedButton(enabled = project.floors.size < 6, onClick = {
                val count = project.floors.size
                persist(project.copy(
                    floors = project.floors + current.copy(title = "Andar ${count + 1}"),
                    selectedFloor = count
                ))
                pending = null
                editingMode = "walls"
            }) { Text("Duplicar") }
        }
        Text("Alinhamento dos pavimentos: ajuste o deslocamento em metros. " +
            "Use o térreo como referência (0,0); as posições seguem o desenho da planta.",
            style = MaterialTheme.typography.bodySmall)
        if (selected > 0) {
            Text("Posição horizontal do pavimento", style = MaterialTheme.typography.titleMedium)
            val firstAlignment = PlanLandmarkAlignment.suggestedOffset(project.floors[0], current)
            val twoPointAlignment = PlanLandmarkAlignment.suggestedPose(project.floors[0], current)
            Text("Marque dois pontos correspondentes no térreo e neste pavimento " +
                "(por exemplo, dois pilares). Dois pontos permitem corrigir também a rotação. " +
                "Se tiver apenas um, use o alinhamento por deslocamento.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                enabled = twoPointAlignment != null &&
                    twoPointAlignment.offsetXMeters in -20.0..20.0 &&
                    twoPointAlignment.offsetYMeters in -20.0..20.0,
                onClick = {
                    twoPointAlignment?.let { pose ->
                        offsetXText = formatMeters(pose.offsetXMeters)
                        offsetYText = formatMeters(pose.offsetYMeters)
                        rotationText = formatMeters(pose.rotationDegrees)
                        editFloor(current.copy(
                            offsetXMeters = pose.offsetXMeters,
                            offsetYMeters = pose.offsetYMeters,
                            rotationDegrees = pose.rotationDegrees
                        ))
                    }
                }
            ) { Text("Alinhar posição e rotação (2 pontos)") }
            OutlinedButton(
                enabled = firstAlignment != null &&
                    firstAlignment.first in -20.0..20.0 &&
                    firstAlignment.second in -20.0..20.0,
                onClick = {
                    firstAlignment?.let { (x, y) ->
                        offsetXText = formatMeters(x)
                        offsetYText = formatMeters(y)
                        editFloor(current.copy(offsetXMeters = x, offsetYMeters = y))
                    }
                }
            ) { Text("Alinhar só posição (1 ponto)") }
            if (twoPointAlignment == null) {
                Text("Rotação automática indisponível: confira os dois pontos, " +
                    "as escalas e a distância entre as referências (mínimo 0,5 m). " +
                    "Diferenças de escala maiores que 8% são rejeitadas.",
                    style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = offsetXText,
                onValueChange = { offsetXText = it },
                label = { Text("Deslocamento X (m): positivo à direita") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            OutlinedTextField(
                value = offsetYText,
                onValueChange = { offsetYText = it },
                label = { Text("Deslocamento Y (m): positivo para baixo na planta") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            OutlinedTextField(
                value = rotationText,
                onValueChange = { rotationText = it },
                label = { Text("Rotação do pavimento (graus: -180 a 180)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val x = offsetXText.trim().replace(',', '.').toDoubleOrNull()
                    val y = offsetYText.trim().replace(',', '.').toDoubleOrNull()
                    val degrees = rotationText.trim().replace(',', '.').toDoubleOrNull()
                    if (x != null && y != null && degrees != null &&
                        x.isFinite() && y.isFinite() && degrees.isFinite() &&
                        x in -20.0..20.0 && y in -20.0..20.0 &&
                        degrees in -180.0..180.0) {
                        editFloor(current.copy(
                            offsetXMeters = x, offsetYMeters = y, rotationDegrees = degrees
                        ))
                    } else {
                        error = "Informe X/Y entre -20 e 20 m e rotação entre -180° e 180°."
                    }
                }) { Text("Aplicar posição e rotação") }
                OutlinedButton(onClick = {
                    offsetXText = "0"
                    offsetYText = "0"
                    rotationText = "0"
                    editFloor(current.copy(offsetXMeters = 0.0, offsetYMeters = 0.0, rotationDegrees = 0.0))
                }) { Text("Centralizar") }
            }
        }

        OutlinedButton(onClick = { openImage.launch("image/*") }) {
            Text(if (current.imageFile == null) "Importar planta (imagem)" else "Trocar planta deste andar")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openPdf.launch(arrayOf("application/pdf")) }) {
                Text("Importar PDF")
            }
            OutlinedButton(onClick = {
                openDxf.launch(arrayOf("application/dxf", "image/vnd.dxf", "application/octet-stream", "text/plain"))
            }) { Text("Importar DXF") }
        }
        Text("PDF: escolha uma página e desenhe paredes manualmente. DXF: importe uma " +
            "camada de linhas como paredes editáveis, após escolher a unidade do desenho, " +
            "ou use apenas como referência. DWG, IFC e RVT ainda não são aceitos.",
            style = MaterialTheme.typography.bodySmall)
        val bitmap = remember(current.imageFile) { repository.readImage(current.imageFile) }
        if (bitmap != null) {
            PlanRecognitionControls(bitmap, current, onApply = ::editFloor)
            Text("Escala: informe uma medida conhecida na planta (metros).")
            OutlinedTextField(
                value = referenceText,
                onValueChange = { referenceText = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Distância da linha de referência (m)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            OutlinedButton(onClick = {
                val meters = referenceText.trim().replace(',', '.').toDoubleOrNull()
                if (meters != null && meters.isFinite() && meters > 0) {
                    editFloor(current.copy(knownMeters = meters))
                } else {
                    error = "Informe uma medida positiva em metros."
                }
            }) { Text("Aplicar medida de referência") }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = editingMode == "perspective", onClick = {
                    editingMode = "perspective"; pending = null; perspectiveCorners.clear()
                }, label = { Text("Corrigir perspectiva") })
                FilterChip(selected = editingMode == "landmark", onClick = {
                    editingMode = "landmark"; pending = null; perspectiveCorners.clear()
                }, label = { Text("Ponto 1") })
                FilterChip(selected = editingMode == "landmark2", onClick = {
                    editingMode = "landmark2"; pending = null; perspectiveCorners.clear()
                }, label = { Text("Ponto 2") })
                FilterChip(selected = editingMode == "reference", onClick = {
                    editingMode = "reference"; pending = null
                }, label = { Text("Marcar escala") })
                FilterChip(
                    selected = editingMode == "walls",
                    onClick = { editingMode = "walls"; pending = null },
                    enabled = current.calibrated,
                    label = { Text("Desenhar paredes") }
                )
            }
            Text(
                when {
                    editingMode == "perspective" ->
                        "Marque os cantos da folha nesta ordem: superior esquerdo, superior direito, " +
                            "inferior direito, inferior esquerdo (${perspectiveCorners.size}/4)."
                    editingMode == "landmark" ->
                        "Toque no PRIMEIRO ponto físico correspondente em ambos os pavimentos."
                    editingMode == "landmark2" ->
                        "Toque no SEGUNDO ponto correspondente (afastado ao menos 0,5 m do primeiro)."
                    pending == null && editingMode == "reference" ->
                        "Toque nos dois extremos da distância conhecida."
                    pending != null && editingMode == "reference" ->
                        "Agora toque no segundo extremo da referência."
                    !current.calibrated -> "Calibre a escala antes de desenhar paredes."
                    pending == null -> "Toque no início de uma parede."
                    else -> "Toque no fim da parede. Repita para as demais."
                },
                color = MaterialTheme.colorScheme.primary
            )
            ZoomablePlanCanvas(
                bitmap = bitmap,
                floor = current,
                pending = pending,
                perspectiveCorners = perspectiveCorners,
                showLines = showLines,
                showMeasurements = showMeasurements,
                onTap = { point ->
                    when (editingMode) {
                        "perspective" -> {
                            if (perspectiveCorners.size < 4) perspectiveCorners.add(point)
                        }
                        "landmark" -> {
                            editFloor(current.copy(alignmentPoint = point))
                            editingMode = if (current.calibrated) "walls" else "reference"
                        }
                        "landmark2" -> {
                            editFloor(current.copy(secondAlignmentPoint = point))
                            editingMode = if (current.calibrated) "walls" else "reference"
                        }
                        else -> {
                            val first = pending
                            if (first == null) {
                                pending = point
                            } else if (first != point) {
                                if (editingMode == "reference") {
                                    editFloor(current.copy(referenceStart = first, referenceEnd = point))
                                    editingMode = "walls"
                                } else if (editingMode == "walls" && current.calibrated) {
                                    val wall = PlanWall(first, point)
                                    if ((FloorPlanMath.wallLengthMeters(current, wall) ?: 0.0) > 0.01) {
                                        editFloor(current.copy(walls = current.walls + wall))
                                    }
                                }
                                pending = null
                            }
                        }
                    }
                }
            )
            if (editingMode == "perspective") {
                OutlinedTextField(
                    value = sheetWidth,
                    onValueChange = { sheetWidth = it },
                    label = { Text("Largura real da folha (cm)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = sheetHeight,
                    onValueChange = { sheetHeight = it },
                    label = { Text("Altura real da folha (cm)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                val width = sheetWidth.replace(',', '.').toDoubleOrNull() ?: 0.0
                val height = sheetHeight.replace(',', '.').toDoubleOrNull() ?: 0.0
                val valid = PlanRectification.targetSize(
                    perspectiveCorners.toList(), bitmap.width, bitmap.height, width, height
                ) != null
                Button(enabled = valid, onClick = {
                    if (current.walls.isNotEmpty() || current.referenceStart != null ||
                        current.referenceEnd != null || current.openings.isNotEmpty() ||
                        current.alignmentPoint != null || current.secondAlignmentPoint != null) confirmRectify = true
                    else rectifySelectedPlan()
                }) { Text("Aplicar correção de perspectiva") }
                OutlinedButton(enabled = perspectiveCorners.isNotEmpty(), onClick = {
                    perspectiveCorners.removeAt(perspectiveCorners.lastIndex)
                }) { Text("Desfazer canto") }
                Text("A correção funciona somente com folha plana e retangular; " +
                    "não remove distorção da lente. Após aplicar, calibre novamente a escala.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (pending != null) {
                OutlinedButton(onClick = { pending = null }) { Text("Cancelar ponto atual") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = current.walls.isNotEmpty(), onClick = {
                    editFloor(current.copy(
                        walls = current.walls.dropLast(1),
                        openings = current.openings.filter { it.wallIndex < current.walls.lastIndex }
                    ))
                    pending = null
                }) { Text("Desfazer parede") }
                OutlinedButton(enabled = current.walls.isNotEmpty(), onClick = {
                    confirmErase = true
                }) { Text("Limpar paredes") }
            }
            OutlinedTextField(
                value = wallHeightText,
                onValueChange = { wallHeightText = it },
                label = { Text("Pé-direito / altura das paredes (m)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            OutlinedButton(onClick = {
                val height = wallHeightText.trim().replace(',', '.').toDoubleOrNull()
                if (height != null && height.isFinite() && height > 0 && height <= 20) {
                    editFloor(current.copy(wallHeightMeters = height))
                } else error = "A altura deve estar entre 0 e 20 m."
            }) { Text("Aplicar altura") }
            OpeningEditor(floor = current, onUpdate = { updated -> editFloor(updated) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = showLines, onClick = { showLines = !showLines },
                    label = { Text("Linhas") })
                FilterChip(selected = showMeasurements, onClick = { showMeasurements = !showMeasurements },
                    label = { Text("Cotas") })
            }
            val total = FloorPlanMath.totalWallMeters(current)
            Text("Paredes: ${current.walls.size} • Total desenhado: " +
                (if (total == null) "escala pendente" else "${formatMeters(total)} m"))
            Text("Medidas estimadas, válidas somente para planta plana e sem distorção de perspectiva.",
                style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Importe uma imagem da planta neste andar para iniciar a marcação.")
        }

        Text("Visualização", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !preview3D, onClick = { preview3D = false }, label = { Text("Planta 2D") })
            FilterChip(selected = preview3D, onClick = { preview3D = true }, label = { Text("Prévia 3D") })
        }
        if (preview3D) {
            FilterChip(selected = showAllFloors, onClick = { showAllFloors = !showAllFloors },
                label = { Text(if (showAllFloors) "Exibindo todos os andares" else "Somente este andar") })
            OrbitModelPreview(
                plan = project,
                allFloors = showAllFloors,
                showLines = showLines
            )
            Text("Prévia tridimensional interativa das paredes marcadas com portas e janelas. " +
                "Ainda não é um modelo CAD/BIM, scanner 3D ou realidade aumentada.",
                style = MaterialTheme.typography.bodySmall)
        }
        if (current.calibrated && project.floors.any { it.walls.isNotEmpty() }) {
            Text("Realidade aumentada (experimental)", style = MaterialTheme.typography.titleMedium)
            Text("Disponível apenas nos aparelhos compatíveis com ARCore. " +
                "Na mesa, toque numa superfície detectada. No papel, use a planta impressa " +
                "igual à imagem importada.")
            OutlinedTextField(
                value = paperWidthCm,
                onValueChange = { paperWidthCm = it },
                label = { Text("Largura da folha impressa (cm)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    context.startActivity(Intent(context, ArchitectArActivity::class.java).apply {
                        putExtra(ArchitectArActivity.EXTRA_MODE, ArchitectArActivity.MODE_TABLE)
                    })
                }) { Text("AR na mesa") }
                val paperCm = paperWidthCm.replace(',', '.').toFloatOrNull()
                Button(
                    enabled = current.imageFile != null && paperCm != null &&
                        paperCm.isFinite() && paperCm > 0f,
                    onClick = {
                        context.startActivity(Intent(context, ArchitectArActivity::class.java).apply {
                            putExtra(ArchitectArActivity.EXTRA_MODE, ArchitectArActivity.MODE_PAPER)
                            putExtra(ArchitectArActivity.EXTRA_PAPER_WIDTH_CM, paperCm!!)
                        })
                    }
                ) { Text("AR sobre papel") }
            }
            Text("AR sobre o papel depende de reconhecimento visual; plantas sem detalhes " +
                "suficientes podem não ser detectadas. A maquete é miniaturizada conforme a folha.",
                style = MaterialTheme.typography.bodySmall)
        }
        if (showMeasurements && current.walls.isNotEmpty()) {
            Text("Comprimento de cada parede", style = MaterialTheme.typography.titleMedium)
            current.walls.forEachIndexed { index, wall ->
                Text("Parede ${index + 1}: ${FloorPlanMath.wallLengthMeters(current, wall)?.let {
                    "${formatMeters(it)} m"
                } ?: "sem escala"}")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

private fun formatMeters(meters: Double): String =
    String.format(Locale("pt", "BR"), "%.2f", meters)

private data class PlanRect(val left: Float, val top: Float, val width: Float, val height: Float)

private fun fittedRect(view: IntSize, bitmap: Bitmap): PlanRect {
    val scale = min(view.width.toFloat() / bitmap.width, view.height.toFloat() / bitmap.height)
    val w = bitmap.width * scale
    val h = bitmap.height * scale
    return PlanRect((view.width - w) / 2, (view.height - h) / 2, w, h)
}

private fun planTouch(touch: Offset, size: IntSize, bitmap: Bitmap): PlanPoint? {
    val r = fittedRect(size, bitmap)
    if (r.width <= 0f || r.height <= 0f) return null
    val x = (touch.x - r.left) / r.width
    val y = (touch.y - r.top) / r.height
    return if (x in 0f..1f && y in 0f..1f) PlanPoint(x, y) else null
}

@Composable
internal fun FloorPlanCanvas(
    bitmap: Bitmap,
    floor: PlanFloor,
    pending: PlanPoint?,
    perspectiveCorners: List<PlanPoint>,
    showLines: Boolean,
    showMeasurements: Boolean,
    onTap: (PlanPoint) -> Unit
) {
    Box(Modifier.fillMaxWidth().height(350.dp).background(Color(0xFF26333A))) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Planta original importada",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        Canvas(
            Modifier.fillMaxSize().pointerInput(bitmap, floor, pending, onTap) {
                detectTapGestures { position ->
                    planTouch(position, size, bitmap)?.let(onTap)
                }
            }
        ) {
            val r = fittedRect(IntSize(size.width.toInt(), size.height.toInt()), bitmap)
            fun center(point: PlanPoint) =
                Offset(r.left + point.x * r.width, r.top + point.y * r.height)
            val refA = floor.referenceStart
            val refB = floor.referenceEnd
            if (refA != null && refB != null) {
                drawLine(Color(0xFFFF9800), center(refA), center(refB), 3.dp.toPx())
            }
            if (showLines) {
                floor.walls.forEach { wall ->
                    drawLine(Color(0xFF14AD9C), center(wall.start), center(wall.end), 4.dp.toPx())
                }
            }
            if (showLines) {
                floor.openings.forEach { opening ->
                    val wall = floor.walls.getOrNull(opening.wallIndex) ?: return@forEach
                    if (FloorPlanMath.openingRange(floor, opening) == null) return@forEach
                    val x = wall.start.x + (wall.end.x - wall.start.x) * opening.centerFraction.toFloat()
                    val y = wall.start.y + (wall.end.y - wall.start.y) * opening.centerFraction.toFloat()
                    drawCircle(
                        if (opening.type == br.com.medidor.app.domain.OpeningType.DOOR)
                            Color(0xFFFF9800) else Color(0xFF7C4DFF),
                        radius = 6.dp.toPx(),
                        center = center(PlanPoint(x, y))
                    )
                }
            }
            if (showMeasurements) {
                drawIntoCanvas { surface ->
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.rgb(2, 80, 79)
                        textSize = 12.dp.toPx()
                        textAlign = Paint.Align.CENTER
                        setShadowLayer(3f, 0f, 1f, android.graphics.Color.WHITE)
                    }
                    floor.walls.forEach { wall ->
                        val value = FloorPlanMath.wallLengthMeters(floor, wall) ?: return@forEach
                        val a = center(wall.start)
                        val b = center(wall.end)
                        surface.nativeCanvas.drawText(
                            "${formatMeters(value)} m",
                            (a.x + b.x) / 2,
                            (a.y + b.y) / 2 - 8.dp.toPx(),
                            paint
                        )
                    }
                }
            }
            if (perspectiveCorners.isNotEmpty()) {
                perspectiveCorners.forEachIndexed { index, point ->
                    drawCircle(Color(0xFFFFC107), 7.dp.toPx(), center(point))
                    if (index > 0) {
                        drawLine(Color(0xFFFFC107), center(perspectiveCorners[index - 1]),
                            center(point), 2.dp.toPx())
                    }
                }
                if (perspectiveCorners.size == 4) {
                    drawLine(Color(0xFFFFC107), center(perspectiveCorners[3]),
                        center(perspectiveCorners[0]), 2.dp.toPx())
                }
            }
            floor.alignmentPoint?.let {
                drawCircle(Color(0xFFE91E63), 7.dp.toPx(), center(it))
            }
            floor.secondAlignmentPoint?.let {
                drawCircle(Color(0xFF00BCD4), 7.dp.toPx(), center(it))
                floor.alignmentPoint?.let { first ->
                    drawLine(Color(0xFF00BCD4), center(first), center(it), 2.dp.toPx())
                }
            }
            pending?.let { drawCircle(Color(0xFFF44336), 7.dp.toPx(), center(it)) }
        }
    }
}

