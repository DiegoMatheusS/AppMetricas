package br.com.medidor.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import br.com.medidor.app.domain.ReportContent
import br.com.medidor.app.data.PdfReportExporter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.medidor.app.data.MeasurementRecord
import br.com.medidor.app.data.ProBilling
import android.app.Activity

@Composable
fun HistoryScreen(
    records: List<MeasurementRecord>,
    onDeleteAll: () -> Unit,
    onDeleteOne: (Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportingPdf by remember { mutableStateOf(false) }
    var pdfFeedback by remember { mutableStateOf<String?>(null) }
    val savePdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null && !exportingPdf) scope.launch {
            exportingPdf = true
            try {
                val pageCount = withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: error("Não foi possível criar o PDF.")
                    PdfReportExporter.write(ReportContent.measurements(records), output)
                }
                pdfFeedback = "PDF exportado com sucesso ($pageCount página(s))."
            } catch (e: Exception) {
                pdfFeedback = "Falha ao exportar PDF: ${e.localizedMessage}"
            } finally {
                exportingPdf = false
            }
        }
    }
    var deleteIndex by remember { mutableStateOf<Int?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    if (deleteIndex != null || confirmAll) {
        AlertDialog(
            onDismissRequest = { deleteIndex = null; confirmAll = false },
            title = { Text("Confirmar exclusão") },
            text = { Text(if (confirmAll) "Apagar todas as medições? A ação não pode ser desfeita."
                else "Excluir esta medição?") },
            confirmButton = {
                TextButton(onClick = {
                    if (confirmAll) onDeleteAll() else deleteIndex?.let(onDeleteOne)
                    confirmAll = false
                    deleteIndex = null
                }) { Text("Excluir") }
            },
            dismissButton = {
                TextButton(onClick = { deleteIndex = null; confirmAll = false }) { Text("Cancelar") }
            }
        )
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Histórico local", style = MaterialTheme.typography.titleLarge)
        Text("Seus cálculos ficam salvos neste dispositivo. Faça backup manual em Modo Arquiteto.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (records.isEmpty()) {
            ResultCard("Sem medições", "Faça um cálculo e toque em Salvar.")
        } else {
            OutlinedButton(onClick = {
                val report = buildString {
                    appendLine("Relatório — Medidor")
                    records.forEach { record ->
                        appendLine("${record.createdAt} | ${record.title} | ${record.details}")
                    }
                    appendLine("Estimativas geométricas, não constituem laudo.")
                }
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                context.startActivity(Intent.createChooser(share, "Compartilhar medições"))
            }) { Text("Compartilhar relatório (texto)") }
            OutlinedButton(enabled = !exportingPdf, onClick = {
                savePdf.launch("medidor-relatorio-medicoes.pdf")
            }) { Text("Exportar relatório PDF") }
            records.forEachIndexed { index, record ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(record.title, fontWeight = FontWeight.SemiBold)
                        Text(record.details)
                        Text(record.createdAt, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { deleteIndex = index }) { Text("Excluir esta medição") }
                    }
                }
            }
            OutlinedButton(onClick = { confirmAll = true }) { Text("Apagar histórico deste aparelho") }
        }
        if (exportingPdf) Text("Criando relatório PDF…")
        pdfFeedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun ProScreen(billing: ProBilling, onBack: (() -> Unit)? = null) {
    val state = billing.state
    val activity = LocalContext.current as? Activity
    Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        onBack?.let { TextButton(onClick = it) { Text("‹ Voltar") } }
        Text("Pro Completo", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        ResultCard(if (state.owned) "Pro ativado" else "Compra única, sem mensalidade",
            if (state.owned) "Disponível nesta conta da Google Play" else state.price ?: "Preço disponível pela Google Play")
        Text("Ferramentas incluídas:", style = MaterialTheme.typography.titleMedium)
        listOf(
            "Scanner 3D com exportação OBJ",
            "Modo Arquiteto com AR e vários andares",
            "Contagem assistida de peças pela câmera",
            "Tanques pela câmera"
        ).forEach { Text("• $it") }
        HorizontalDivider()
        if (!state.owned) Button(enabled = state.configured && state.canBuy && !state.busy && activity != null,
            onClick = { activity?.let(billing::buy) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.pending) "Aguardando pagamento" else state.price?.let { "Comprar por $it" } ?: "Comprar pela Google Play")
        }
        OutlinedButton(enabled = state.configured && !state.busy, onClick = { billing.refresh(true) },
            modifier = Modifier.fillMaxWidth()) { Text("Restaurar compra") }
        if (state.busy) Text("Conferindo a Google Play…")
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text("Use a mesma conta da Google Play para restaurar. Pagamentos pendentes só liberam o Pro após confirmação.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun PlannedFeatureScreen(title: String, description: String, stages: List<String>) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Planejado para próxima fase", color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge)
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(description)
        Text("Etapas previstas:", style = MaterialTheme.typography.titleMedium)
        stages.forEachIndexed { index, stage -> Text("${index + 1}. $stage") }
        Text("Essa ferramenta ainda não realiza medições nesta versão.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
