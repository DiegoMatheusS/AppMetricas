package br.com.medidor.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.medidor.app.domain.*
import br.com.medidor.app.vision.PlanImageRecognition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Recognition is reviewed on the actual floor image before it changes the saved project. */
@Composable
internal fun PlanRecognitionControls(bitmap: Bitmap, floor: PlanFloor, onApply: (PlanFloor) -> Unit) {
    var draft by remember(floor.imageFile) { mutableStateOf<PlanRecognitionDraft?>(null) }
    var selected by remember(floor.imageFile) { mutableStateOf<Set<Int>>(emptySet()) }
    var doors by remember { mutableStateOf(true) }
    var windows by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val currentFloor by rememberUpdatedState(floor)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = floor.calibrated && !busy, onClick = {
            val source = floor
            busy = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.Default) { PlanImageRecognition.detect(bitmap, source) }
                    if (currentFloor == source) {
                        draft = result
                        selected = result.walls.indices.toSet()
                        message = if (result.walls.isEmpty()) "Não encontrei paredes. Confira a escala e o contraste da planta."
                            else "Revise as sugestões sobre a planta. Toque numa parede para incluir ou retirar."
                    } else message = "A planta mudou durante a análise. Execute o reconhecimento novamente."
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = e.localizedMessage ?: "Falha ao analisar a planta." }
                finally { busy = false }
            }
        }) { Text(if (busy) "Analisando planta…" else "Reconhecer paredes, portas e janelas") }
        if (!floor.calibrated) Text("Calibre uma distância conhecida para reconhecer as dimensões.",
            style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        val suggestions = draft
        if (suggestions != null && suggestions.walls.isNotEmpty()) {
            val preview = PlanRecognition.apply(floor, suggestions, selected, doors, windows)
            Text("${selected.size} paredes selecionadas • ${suggestions.openings.count { it.type == OpeningType.DOOR }} portas sugeridas • " +
                "${suggestions.openings.count { it.type == OpeningType.WINDOW }} janelas sugeridas",
                style = MaterialTheme.typography.bodySmall)
            ZoomablePlanCanvas(bitmap, preview, pending = null, perspectiveCorners = emptyList(),
                showLines = true, showMeasurements = false, onTap = { point ->
                    PlanRecognition.nearest(suggestions, point)?.let { index ->
                        selected = if (index in selected) selected - index else selected + index
                    }
                })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = doors, onClick = { doors = !doors }, label = { Text("Portas") })
                FilterChip(selected = windows, onClick = { windows = !windows }, label = { Text("Janelas") })
                OutlinedButton(onClick = {
                    selected = if (selected.isEmpty()) suggestions.walls.indices.toSet() else emptySet()
                }) { Text(if (selected.isEmpty()) "Selecionar todas" else "Limpar seleção") }
            }
            Text("As sugestões consideram linhas retas e intervalos na planta. Confira cada abertura.",
                style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = selected.isNotEmpty(), onClick = {
                    onApply(PlanRecognition.apply(floor, suggestions, selected, doors, windows))
                    draft = null
                    message = "Sugestões aplicadas. Ajuste paredes e aberturas no editor."
                }) { Text("Aplicar seleção") }
                OutlinedButton(onClick = { draft = null }) { Text("Descartar sugestões") }
            }
        }
    }
}
