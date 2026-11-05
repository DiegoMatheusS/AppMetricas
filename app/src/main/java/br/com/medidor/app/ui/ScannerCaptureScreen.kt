package br.com.medidor.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import br.com.medidor.app.data.ScannerMeshStore
import br.com.medidor.app.domain.ReconstructedMesh
import br.com.medidor.app.vision.PhotoReconstruction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import br.com.medidor.app.data.ScannerSessionStore
import br.com.medidor.app.domain.ScanPass
import br.com.medidor.app.domain.ScannerCaptureGuide
import br.com.medidor.app.domain.ScannerQualityReport
import br.com.medidor.app.domain.ScanCorrespondenceReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Guided acquisition, bounded offline reconstruction, preview and OBJ export.
 */
@Composable
fun ScannerCaptureScreen() {
    val context = LocalContext.current
    val store = remember(context) { ScannerSessionStore(context.applicationContext) }
    var sessions by remember { mutableStateOf(store.list()) }
    var activeId by remember { mutableStateOf(sessions.firstOrNull()?.id) }
    val active = sessions.firstOrNull { it.id == activeId }
    var position by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED)
    }
    var dialog by remember { mutableStateOf("") }
    var nameText by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }
    var qualityReport by remember { mutableStateOf<ScannerQualityReport?>(null) }
    var reportSessionId by remember { mutableStateOf<String?>(null) }
    var correspondenceReport by remember { mutableStateOf<ScanCorrespondenceReport?>(null) }
    var correspondenceSessionId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val meshStore = remember(context) { ScannerMeshStore(context.applicationContext) }
    var mesh by remember { mutableStateOf<ReconstructedMesh?>(null) }
    var fieldOfView by remember { mutableStateOf("60") }
    var reconstructionProgress by remember { mutableStateOf<String?>(null) }
    var reconstructionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val meshFaces = remember(mesh) { mesh?.faces().orEmpty() }
    LaunchedEffect(activeId, refresh) {
        val selected = active
        mesh = if (selected == null) null else withContext(Dispatchers.IO) { meshStore.read(selected) }
        mesh?.let { fieldOfView = it.fieldOfViewDegrees.toString() }
    }
    val exportObj = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val model = mesh
        if (uri != null && model != null && !busy) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri) ?: error("Não foi possível criar o OBJ.")
                    output.bufferedWriter().use { model.writeObj(it) }
                }
                feedback = "Modelo OBJ exportado."
            } catch (e: Exception) {
                feedback = "Falha ao exportar o modelo: ${e.localizedMessage}"
            } finally { busy = false }
        }
    }

    fun reload(id: String?) {
        sessions = store.list()
        activeId = id?.takeIf { requested -> sessions.any { it.id == requested } }
            ?: sessions.firstOrNull()?.id
        refresh++
        mesh = null
        qualityReport = null
        reportSessionId = null
        correspondenceReport = null
        correspondenceSessionId = null
    }

    fun saveShot(bitmap: Bitmap, sessionId: String, slot: Int) {
        showCamera = false
        scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) {
                    try { store.saveShot(sessionId, slot, bitmap) }
                    finally { bitmap.recycle() }
                }
                reload(result.id)
                position = ScannerCaptureGuide.nextMissing(result.frames.keys) ?: slot
                feedback = "Foto salva no armazenamento local."
            } catch (e: Exception) {
                if (!bitmap.isRecycled) bitmap.recycle()
                feedback = "Erro ao salvar foto: ${e.localizedMessage}"
            } finally {
                busy = false
            }
        }
    }

    val askCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraGranted = granted
        if (granted) showCamera = true
        else feedback = "Câmera não autorizada. Você pode importar fotos pela galeria."
    }
    var exportingId by remember { mutableStateOf<String?>(null) }
    val exportZip = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val id = exportingId
        exportingId = null
        if (uri != null && id != null && !busy) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: error("Não foi possível criar o ZIP.")
                    store.exportSession(id, output)
                }
                feedback = "Cópia ZIP criada. Guarde o arquivo em local seguro."
            } catch (e: Exception) {
                feedback = "Falha ao exportar sessão: ${e.localizedMessage}"
            } finally {
                busy = false
            }
        }
    }
    val importZip = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && !busy) scope.launch {
            busy = true
            try {
                val restored = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("Não foi possível abrir o ZIP.")
                    store.importSession(input)
                }
                reload(restored.id)
                position = ScannerCaptureGuide.nextMissing(restored.frames.keys) ?: 0
                showCamera = false
                feedback = "Sessão recuperada em uma cópia separada, sem apagar as anteriores."
            } catch (e: Exception) {
                feedback = "ZIP não restaurado: ${e.localizedMessage}"
            } finally {
                busy = false
            }
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val sessionId = activeId
        val slot = position
        if (uri != null && sessionId != null && !busy) {
            scope.launch {
                busy = true
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        SampledPhotos.fromUri(context.contentResolver, uri)
                    }
                    val saved = withContext(Dispatchers.IO) {
                        try { store.saveShot(sessionId, slot, bitmap) }
                        finally { bitmap.recycle() }
                    }
                    reload(saved.id)
                    position = ScannerCaptureGuide.nextMissing(saved.frames.keys) ?: slot
                    feedback = "Imagem importada e salva localmente."
                } catch (e: Exception) {
                    feedback = "Não foi possível importar: ${e.localizedMessage}"
                } finally {
                    busy = false
                }
            }
        }
    }

    if (dialog.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text(when (dialog) {
                "create" -> "Nova sessão"
                "rename" -> "Renomear sessão"
                "deleteShot" -> "Excluir fotografia?"
                else -> "Excluir toda a sessão?"
            }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (dialog == "delete" || dialog == "deleteShot")
                        "A ação é permanente. Use Exportar sessão ZIP para preservar estas fotos " +
                            "antes de excluir."
                    else "Dê um nome ao objeto que será fotografado.")
                    if (dialog == "create" || dialog == "rename") {
                        OutlinedTextField(
                            value = nameText, onValueChange = { nameText = it },
                            label = { Text("Nome (até 60 caracteres)") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && (dialog == "delete" || dialog == "deleteShot" ||
                        nameText.trim().length in 1..60),
                    onClick = {
                        try {
                            when (dialog) {
                                "create" -> {
                                    val session = store.create(nameText)
                                    reload(session.id)
                                    position = 0
                                    showCamera = false
                                }
                                "rename" -> active?.let {
                                    store.rename(it.id, nameText)
                                    reload(it.id)
                                }
                                "delete" -> active?.let {
                                    store.deleteSession(it.id)
                                    reload(null)
                                    position = 0
                                    showCamera = false
                                }
                                "deleteShot" -> active?.let {
                                    store.deleteShot(it.id, position)
                                    reload(it.id)
                                }
                            }
                            feedback = null
                        } catch (e: Exception) {
                            feedback = "Operação não concluída: ${e.localizedMessage}"
                        }
                        dialog = ""
                    }
                ) { Text(if (dialog.startsWith("delete")) "Excluir" else "Salvar") }
            },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancelar") } }
        )
    }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Scanner 3D", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)
        Text("Fotografe o objeto imóvel de vários ângulos para reconstruir sua superfície. " +
            "O modelo é proporcional e pode ficar incompleto quando faltam detalhes nas fotos.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Deixe o objeto imóvel e caminhe ao redor, mantendo boa iluminação. " +
            "Evite objetos transparentes, brilhantes ou em movimento. " +
            "Mantenha sobreposição entre fotos e não use zoom digital.",
            style = MaterialTheme.typography.bodySmall)

        Text("Sessões locais (${sessions.size}/${ScannerCaptureGuide.MAX_SESSIONS})",
            style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sessions.forEach { session ->
                FilterChip(
                    selected = activeId == session.id,
                    enabled = !busy,
                    onClick = {
                        activeId = session.id
                        mesh = null
                        position = ScannerCaptureGuide.nextMissing(session.frames.keys) ?: 0
                        showCamera = false
                        feedback = null
                        qualityReport = null
                        reportSessionId = null
                        correspondenceReport = null
                        correspondenceSessionId = null
                    },
                    label = { Text("${session.name} (${session.capturedCount}/20)") }
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !busy && sessions.size < ScannerCaptureGuide.MAX_SESSIONS,
                onClick = {
                    nameText = "Objeto ${sessions.size + 1}"
                    dialog = "create"
                }
            ) { Text("Nova sessão") }
            if (active != null) {
                OutlinedButton(enabled = !busy, onClick = {
                    nameText = active.name
                    dialog = "rename"
                }) { Text("Renomear") }
                OutlinedButton(enabled = !busy, onClick = {
                    dialog = "delete"
                }) { Text("Excluir sessão") }
            }
        }

        Text("Backup separado das sessões do Scanner:",
            style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy && active != null, onClick = {
                exportingId = active?.id
                exportZip.launch("scanner-medidor.zip")
            }) { Text("Exportar sessão ZIP") }
            OutlinedButton(
                enabled = !busy && sessions.size < ScannerCaptureGuide.MAX_SESSIONS,
                onClick = {
                    importZip.launch(arrayOf("application/zip", "application/octet-stream"))
                }
            ) { Text("Importar sessão ZIP") }
        }
        Text("Cada ZIP guarda uma sessão com suas fotos. A importação cria uma nova sessão, " +
            "verificando tamanho, arquivos e hashes SHA-256 antes de salvar. " +
            "Guarde os ZIPs em local seguro; não são criptografados.",
            style = MaterialTheme.typography.bodySmall)

        if (active == null) {
            Text("Crie uma sessão para começar a fotografar.")
        } else {
            val captured = active.frames.keys
            val step = ScannerCaptureGuide.positions[position]
            Text("Fotografias registradas: ${active.capturedCount}/20",
                style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { active.capturedCount / 20f },
                modifier = Modifier.fillMaxWidth()
            )
            if (active.isComplete) {
                Text("As 20 posições foram fotografadas. Você pode gerar o modelo 3D.",
                    color = MaterialTheme.colorScheme.primary)
            }
            OutlinedTextField(
                value = fieldOfView, onValueChange = { fieldOfView = it },
                enabled = !busy, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Campo de visão horizontal da câmera (graus)") },
                supportingText = { Text("Use o valor da câmera, quando conhecido. A estimativa inicial é 60°.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            Button(enabled = !busy && !showCamera && active.frames.size >= 3, onClick = {
                val selected = active
                val fov = fieldOfView.replace(',', '.').toDoubleOrNull()
                if (fov == null || !fov.isFinite() || fov !in 25.0..100.0) {
                    feedback = "Informe um campo de visão entre 25° e 100°."
                } else {
                    busy = true
                    reconstructionJob = scope.launch {
                        try {
                            val jobContext = coroutineContext
                            val model = withContext(Dispatchers.Default) {
                                PhotoReconstruction.reconstruct(selected.frames.keys.sorted(), fov,
                                    load = { slot -> store.thumbnail(selected.frames[slot]) },
                                    progress = { message ->
                                        jobContext.ensureActive()
                                        scope.launch {
                                            if (reconstructionJob?.isActive == true) reconstructionProgress = message
                                        }
                                    })
                            }
                            jobContext.ensureActive()
                            val latest = store.list().firstOrNull { it.id == selected.id }
                            check(latest?.frames == selected.frames) { "As fotos da sessão mudaram. Gere o modelo novamente." }
                            withContext(Dispatchers.IO) { meshStore.save(selected, model) }
                            if (activeId == selected.id) mesh = model
                            feedback = "Superfície reconstruída com ${model.registeredPhotos}/${model.suppliedPhotos} fotos."
                        } catch (e: CancellationException) {
                            feedback = "Reconstrução cancelada."
                            throw e
                        } catch (e: Exception) {
                            feedback = "Não foi possível reconstruir: ${e.localizedMessage}"
                        } finally {
                            busy = false
                            reconstructionProgress = null
                            reconstructionJob = null
                        }
                    }
                }
            }) { Text(if (mesh == null) "Gerar modelo 3D" else "Reconstruir modelo") }
            reconstructionProgress?.let { message ->
                Text(message)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(onClick = { reconstructionJob?.cancel() }) { Text("Cancelar reconstrução") }
            }
            mesh?.let { model ->
                Text("Modelo 3D • ${model.vertices.size} pontos • ${model.triangles.size} faces",
                    style = MaterialTheme.typography.titleMedium)
                MeshModelPreview(meshFaces)
                Text("Superfície parcial em escala proporcional. " +
                    "Fotos com fundo simples, textura e sobreposição melhoram o resultado.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = !busy, onClick = {
                    exportObj.launch("modelo-scanner.obj")
                }) { Text("Exportar modelo OBJ") }
            }
            OutlinedButton(enabled = !busy && !showCamera && active.frames.isNotEmpty(),
                onClick = {
                    val requestedId = active.id
                    scope.launch {
                        busy = true
                        try {
                            val result = withContext(Dispatchers.Default) {
                                store.analyzeSession(requestedId)
                            }
                            if (activeId == requestedId) {
                                qualityReport = result
                                reportSessionId = requestedId
                                feedback = "Análise local finalizada. Revise os avisos antes de fotografar novamente."
                            }
                        } catch (e: Exception) {
                            feedback = "Não foi possível analisar as fotos: ${e.localizedMessage}"
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text("Analisar qualidade das fotos (offline)") }
            if (qualityReport != null && reportSessionId == active.id) {
                val report = qualityReport!!
                Text("Pré-análise das imagens", style = MaterialTheme.typography.titleMedium)
                Text("Fotos: ${report.checks.size}/20 • alertas: ${report.warningsCount} • " +
                    "posições ausentes: ${report.missingSlots.size}")
                if (report.warningsCount == 0) {
                    Text("Nenhum alerta heurístico nesta amostra. Isso NÃO comprova " +
                        "qualidade para reconstrução ou cobertura de todos os ângulos.",
                        style = MaterialTheme.typography.bodySmall)
                }
                report.checks.filter { it.warnings.isNotEmpty() }.forEach { check ->
                    Text("Foto ${check.slot + 1}: ${check.warnings.joinToString("; ")}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (report.similarPairs.isNotEmpty()) {
                    Text("Possíveis imagens muito parecidas (sem comprovação de repetição): " +
                        report.similarPairs.take(8).joinToString("; ") {
                            "${it.first + 1} e ${it.second + 1}"
                        } + if (report.similarPairs.size > 8) "…" else "",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (report.missingSlots.isNotEmpty()) {
                    Text("Posições pendentes: " + report.missingSlots.joinToString(", ") { "${it + 1}" },
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("O algoritmo verifica exposição, detalhes visuais e semelhança aproximada " +
                    "entre fotos reduzidas. Não identifica poses, pontos 3D, sobreposição " +
                    "geométrica nem garante fotogrametria.",
                    style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                enabled = !busy && !showCamera && active.frames.size >= 2,
                onClick = {
                    val requestedId = active.id
                    scope.launch {
                        busy = true
                        try {
                            val result = withContext(Dispatchers.Default) {
                                store.compareSessionPhotos(requestedId)
                            }
                            if (activeId == requestedId) {
                                correspondenceReport = result
                                correspondenceSessionId = requestedId
                                feedback = "Análise de correspondências 2D concluída."
                            }
                        } catch (e: Exception) {
                            feedback = "Falha na comparação das fotos: ${e.localizedMessage}"
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text("Comparar pontos visuais entre fotos") }
            if (correspondenceReport != null && correspondenceSessionId == active.id) {
                val report = correspondenceReport!!
                Text("Correspondências 2D (experimental)",
                    style = MaterialTheme.typography.titleMedium)
                Text("Pares de ângulos vizinhos examinados: ${report.pairs.size}/28; " +
                    "pares com consenso geométrico aproximado: ${report.supportedPairs}.")
                if (report.pairs.isEmpty()) {
                    Text("Sem pares vizinhos de fotografias disponíveis. " +
                        "Capture as posições consecutivas antes de comparar.",
                        style = MaterialTheme.typography.bodySmall)
                }
                report.pairs.forEach { pair ->
                    Text(
                        "Fotos ${pair.firstSlot + 1} → ${pair.secondSlot + 1}: " +
                            "${pair.inliers}/${pair.tentative} correspondências 2D" +
                            (if (pair.supported) " (indício positivo)" else " (inconclusivo)") +
                            (pair.epipolar?.let { ep ->
                                " | geometria epipolar: ${ep.inliers}/${ep.matches}" +
                                    (if (ep.plausible) " (compatível)" else " (inconclusiva)")
                            } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (pair.supported) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.error
                    )
                }
                if (report.unavailableSlots.isNotEmpty()) {
                    Text("Não foi possível ler as fotos: " +
                        report.unavailableSlots.joinToString(", ") { "${it + 1}" },
                        style = MaterialTheme.typography.bodySmall)
                }
                if (report.missingSlots.isNotEmpty()) {
                    Text("Fotos ainda não capturadas: ${report.missingSlots.size}/20.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("Além da comparação 2D, uma verificação epipolar experimental " +
                    "testa se os pontos são compatíveis com duas vistas. Uma matriz " +
                    "fundamental ajuda a selecionar fotos compatíveis. Use Gerar modelo 3D " +
                    "para reconstruir e conferir a superfície.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("Selecione a posição da fotografia:", fontWeight = FontWeight.SemiBold)
            for (pass in ScanPass.entries) {
                Text(pass.label, style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ScannerCaptureGuide.positions.filter { it.pass == pass }.forEach { item ->
                        FilterChip(
                            selected = position == item.index,
                            enabled = !busy,
                            onClick = { position = item.index; showCamera = false; feedback = null },
                            label = { Text("${item.index + 1}${if (item.index in captured) " ✓" else ""}") }
                        )
                    }
                }
            }
            Text("Posição ${step.index + 1}/20 — ${step.label}",
                style = MaterialTheme.typography.titleMedium)
            Text(step.instruction)
            Text("Foto ${if (position in captured) "salva; pode ser refeita" else "pendente"}")
            val thumb = remember(active.id, position, refresh) {
                store.thumbnail(active.frames[position])
            }
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = "Fotografia armazenada para a posição ${position + 1}",
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    contentScale = ContentScale.Fit
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    gallery.launch("image/*")
                }) { Text(if (position in captured) "Trocar pela galeria" else "Importar da galeria") }
                Button(enabled = !busy, onClick = {
                    if (cameraGranted) showCamera = !showCamera
                    else askCamera.launch(Manifest.permission.CAMERA)
                }) { Text(if (showCamera) "Fechar câmera" else "Abrir câmera") }
            }
            if (showCamera && cameraGranted && !busy) {
                CameraPreviewAndCapture(
                    onPhoto = { bitmap ->
                        val id = activeId
                        if (id != null) saveShot(bitmap, id, position)
                        else bitmap.recycle()
                    },
                    onError = { feedback = it }
                )
            }
            if (position in captured) {
                OutlinedButton(enabled = !busy, onClick = { dialog = "deleteShot" }) {
                    Text("Excluir foto desta posição")
                }
            }
            if (!active.isComplete) {
                OutlinedButton(enabled = !busy, onClick = {
                    position = ScannerCaptureGuide.nextMissing(captured) ?: 0
                    showCamera = false
                }) { Text("Ir para a próxima foto pendente") }
            }
            Text("O backup geral do Modo Arquiteto não inclui estas fotos. " +
                "Exporte cada sessão do Scanner em ZIP antes de desinstalar o aplicativo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        if (busy) Text("Processando fotografias no aparelho…")
        feedback?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}
