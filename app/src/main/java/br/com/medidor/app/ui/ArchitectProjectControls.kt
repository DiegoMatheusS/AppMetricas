package br.com.medidor.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.medidor.app.data.ArchitectProjectManager

/** Local project picker. Destructive actions are always confirmed. */
@Composable
internal fun ArchitectProjectControls(
    manager: ArchitectProjectManager,
    onChanged: () -> Unit,
    onError: (String) -> Unit
) {
    var serial by remember { mutableIntStateOf(0) }
    val projects = remember(serial) { manager.list() }
    val active = manager.activeId()
    var dialog by remember { mutableStateOf("") }
    var projectName by remember { mutableStateOf("") }
    val chosen = projects.firstOrNull { it.id == active }

    fun apply(action: () -> Unit) {
        try {
            action()
            serial++
            dialog = ""
            onChanged()
        } catch (e: Exception) {
            onError(e.localizedMessage ?: "Não foi possível alterar os projetos.")
        }
    }

    if (dialog.isNotEmpty()) {
        val destructive = dialog == "delete"
        AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text(when (dialog) {
                "new" -> "Novo projeto"
                "duplicate" -> "Duplicar projeto"
                "rename" -> "Renomear projeto"
                else -> "Excluir projeto?"
            }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (destructive) {
                        "Excluir '${chosen?.name}'? Esta ação não pode ser desfeita. " +
                            "Imagens utilizadas por outros projetos serão preservadas."
                    } else "Os projetos são guardados somente neste dispositivo.")
                    if (!destructive) {
                        OutlinedTextField(
                            value = projectName,
                            onValueChange = { projectName = it },
                            singleLine = true,
                            label = { Text("Nome do projeto (até 60 caracteres)") }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = destructive || (projectName.isNotBlank() && projectName.length <= 60),
                    onClick = {
                        when (dialog) {
                            "new" -> apply { manager.create(projectName, copyCurrent = false) }
                            "duplicate" -> apply { manager.create(projectName, copyCurrent = true) }
                            "rename" -> apply { manager.rename(active, projectName) }
                            "delete" -> apply { manager.delete(active) }
                        }
                    }
                ) { Text(if (destructive) "Excluir" else "Salvar") }
            },
            dismissButton = {
                TextButton(onClick = { dialog = "" }) { Text("Cancelar") }
            }
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Meus projetos", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            projects.forEach { item ->
                FilterChip(
                    selected = item.id == active,
                    onClick = { if (item.id != active) apply { manager.select(item.id) } },
                    label = { Text(item.name) }
                )
            }
        }
        Text("Projeto ativo: ${chosen?.name ?: "não encontrado"} • ${projects.size}/20",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = projects.size < 20, onClick = {
                projectName = "Projeto ${projects.size + 1}"
                dialog = "new"
            }) { Text("Novo") }
            OutlinedButton(enabled = projects.size < 20, onClick = {
                projectName = "${chosen?.name ?: "Projeto"} (cópia)"
                dialog = "duplicate"
            }) { Text("Duplicar") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                projectName = chosen?.name.orEmpty()
                dialog = "rename"
            }) { Text("Renomear") }
            OutlinedButton(enabled = projects.size > 1, onClick = {
                dialog = "delete"
            }) { Text("Excluir") }
        }
        Text("Projetos armazenados localmente. Não há sincronização nem backup automático.",
            style = MaterialTheme.typography.bodySmall)
    }
}
