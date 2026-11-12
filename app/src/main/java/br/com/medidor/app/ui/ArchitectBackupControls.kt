package br.com.medidor.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import br.com.medidor.app.data.ArchitectBackupManager
import br.com.medidor.app.data.ArchitectPlanStore
import br.com.medidor.app.domain.ObjModelExporter
import br.com.medidor.app.domain.ReportContent
import br.com.medidor.app.data.PdfReportExporter
import br.com.medidor.app.data.ArchitectProjectManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.util.Locale

/**
 * Document provider integration (SAF); no storage permission or cloud service.
 * User explicitly chooses destination/source, and confirms destructive restore.
 */
@Composable
internal fun ArchitectBackupControls(onRestored: () -> Unit) {
    val context = LocalContext.current
    val backups = remember(context) { ArchitectBackupManager(context.applicationContext) }
    val plans = remember(context) { ArchitectPlanStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null && !busy) scope.launch {
            busy = true
            try {
                val count = withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: error("Não foi possível escrever no documento.")
                    backups.export(output)
                }
                message = "Backup criado: $count projeto(s), plantas e histórico incluídos."
            } catch (e: Exception) {
                message = "Falha no backup: ${e.localizedMessage}"
            } finally {
                busy = false
            }
        }
    }
    val selectBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && !busy) importing = uri
    }
    val saveProjectPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null && !busy) scope.launch {
            busy = true
            try {
                val pageCount = withContext(Dispatchers.IO) {
                    val project = plans.load()
                    val manager = ArchitectProjectManager(context.applicationContext)
                    val name = manager.list().first { it.id == manager.activeId() }.name
                    val out = context.contentResolver.openOutputStream(uri)
                        ?: error("Não foi possível escrever o relatório PDF.")
                    PdfReportExporter.write(ReportContent.architect(name, project), out)
                }
                message = "Relatório do projeto salvo em PDF ($pageCount página(s))."
            } catch (e: Exception) {
                message = "Falha na exportação PDF: ${e.localizedMessage}"
            } finally {
                busy = false
            }
        }
    }
    val saveObj = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null && !busy) scope.launch {
            busy = true
            try {
                val faces = withContext(Dispatchers.IO) {
                    val project = plans.load()
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: error("Não foi possível criar o arquivo OBJ.")
                    output.use { stream ->
                        OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                            ObjModelExporter.write(project, writer)
                        }
                    }
                }
                message = "Maquete exportada: $faces faces geométricas, dimensões em metros."
            } catch (e: Exception) {
                message = "Falha ao exportar maquete: ${e.localizedMessage}"
            } finally {
                busy = false
            }
        }
    }

    if (importing != null) {
        AlertDialog(
            onDismissRequest = { importing = null },
            title = { Text("Substituir projetos e medições?") },
            text = {
                Text("Esta restauração substitui TODOS os projetos locais e o histórico " +
                    "de medições atuais. Exporte um backup antes de prosseguir. " +
                    "O arquivo será verificado antes de qualquer substituição.")
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    val file = importing ?: return@TextButton
                    importing = null
                    scope.launch {
                        busy = true
                        try {
                            val count = withContext(Dispatchers.IO) {
                                val input = context.contentResolver.openInputStream(file)
                                    ?: error("Não foi possível abrir o backup.")
                                backups.restore(input)
                            }
                            message = "Restauração concluída: $count projeto(s) e histórico recuperados."
                            onRestored()
                        } catch (e: Exception) {
                            message = "Não foi possível restaurar: ${e.localizedMessage}"
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Substituir e restaurar") }
            },
            dismissButton = {
                TextButton(onClick = { importing = null }) { Text("Cancelar") }
            }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Backup e exportação", style = MaterialTheme.typography.titleMedium)
        Text("Faça uma cópia dos projetos, plantas e medições em um ZIP no local escolhido. " +
            "O arquivo não é criptografado; guarde-o com segurança.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                saveBackup.launch("medidor-backup.zip")
            }) { Text("Criar backup") }
            OutlinedButton(enabled = !busy, onClick = {
                selectBackup.launch(arrayOf("application/zip", "application/octet-stream"))
            }) { Text("Restaurar ZIP") }
        }
        OutlinedButton(enabled = !busy, onClick = {
            saveObj.launch("maquete-medidor.obj")
        }) { Text("Exportar maquete 3D (.obj)") }
        OutlinedButton(enabled = !busy, onClick = {
            saveProjectPdf.launch("medidor-relatorio-arquiteto.pdf")
        }) { Text("Exportar relatório do projeto (PDF)") }
        Text("A exportação OBJ contém somente a geometria dos pavimentos do projeto atual. " +
            "Ela não é um scanner 3D nem um modelo CAD certificado.",
            style = MaterialTheme.typography.bodySmall)
        if (busy) Text("Processando arquivo local…", color = MaterialTheme.colorScheme.primary)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
