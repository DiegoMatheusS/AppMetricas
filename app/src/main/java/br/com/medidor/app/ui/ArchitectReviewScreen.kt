package br.com.medidor.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import br.com.medidor.app.data.ArchitectPlanStore
import br.com.medidor.app.data.ArchitectProjectManager
import br.com.medidor.app.domain.PlanPoint

/**
 * Lightweight workflow corresponding to review 2D / generated 3D references.
 * It uses the actual saved plan and existing editor/model renderer, never a
 * fictitious recognized floor plan or inferred dimensions.
 */
@Composable
fun ArchitectReviewScreen(onBack: () -> Unit, onEdit: () -> Unit, onScan: () -> Unit) {
    val context = LocalContext.current
    val storage = remember(context) { ArchitectPlanStore(context.applicationContext) }
    val manager = remember(context) { ArchitectProjectManager(context.applicationContext) }
    var project by remember { mutableStateOf(storage.load()) }
    val floor = project.floors[project.selectedFloor]
    val bitmap = remember(floor.imageFile) { storage.readImage(floor.imageFile) }
    val name = remember { manager.list().firstOrNull { it.id == manager.activeId() }?.name
        ?: "Meu projeto" }
    var view3d by rememberSaveable { mutableStateOf(false) }
    var reference by rememberSaveable { mutableStateOf(floor.knownMeters.toString()) }
    var pending by remember { mutableStateOf<PlanPoint?>(null) }
    var height by rememberSaveable { mutableStateOf(floor.wallHeightMeters.toFloat()) }
    var message by remember { mutableStateOf<String?>(null) }
    val lime = Color(0xFFC0F565)
    val ink = Color(0xFF14251F)

    fun updateFloor(updated: br.com.medidor.app.domain.PlanFloor) {
        val next = project.floors.toMutableList()
        next[project.selectedFloor] = updated
        val revised = project.copy(floors = next)
        storage.save(revised)
        project = revised
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFFF7F8F5))
            .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹", color = ink, fontSize = 27.sp) }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = ink)
                Text(if (view3d) "ARQUITETO · MODELO GERADO"
                     else "ARQUITETO · REVISÃO DA PLANTA",
                     fontSize = 10.sp, color = Color.Gray)
            }
            TextButton(onClick = onEdit) { Text("Editar", color = ink) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !view3d,
                onClick = { view3d = false },
                label = { Text("2D") }
            )
            FilterChip(
                selected = view3d,
                onClick = { view3d = true },
                label = { Text("3D") }
            )
            Text(
                if (floor.calibrated) "✓ Escala confirmada" else "Escala pendente",
                fontSize = 11.sp,
                color = if (floor.calibrated) ink else Color(0xFF8A6343),
                modifier = Modifier.padding(top = 12.dp)
            )
        }
        if (!view3d) {
            Text("Planta capturada", fontSize = 12.sp, color = ink,
                fontWeight = FontWeight.Medium)
            if (bitmap != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(17.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        ZoomablePlanCanvas(
                            bitmap = bitmap,
                            floor = floor,
                            pending = pending,
                            perspectiveCorners = emptyList(),
                            showLines = true,
                            showMeasurements = true,
                            onTap = { point ->
                                if (pending == null) pending = point
                                else {
                                    updateFloor(floor.copy(
                                        referenceStart = pending,
                                        referenceEnd = point
                                    ))
                                    pending = null
                                    message = "Referência marcada. Confirme a distância real."
                                }
                            }
                        )
                    }
                }
                Text(
                    "ETAPA 2 DE 3 · CONFIRME A ESCALA",
                    fontSize = 11.sp, color = Color.Gray
                )
                Text("Selecione dois pontos de uma parede na planta e informe " +
                    "a distância real entre eles.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = reference,
                    onValueChange = { reference = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Distância de referência (metros)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Button(
                    onClick = {
                        val length = reference.replace(',', '.').toDoubleOrNull()
                        if (length != null && length.isFinite() && length > 0 &&
                            floor.referenceStart != null && floor.referenceEnd != null
                        ) {
                            updateFloor(floor.copy(knownMeters = length))
                            message = "Escala confirmada para esta planta."
                        } else message = "Marque dois pontos e informe uma distância positiva."
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = lime, contentColor = ink
                    ),
                    shape = RoundedCornerShape(13.dp)
                ) { Text("Confirmar escala") }
            } else {
                Text("Nenhuma planta capturada neste pavimento.")
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                    Text("Escanear planta pela câmera")
                }
            }
            Button(
                onClick = {
                    if (floor.calibrated && project.floors.any { it.walls.isNotEmpty() })
                        view3d = true
                    else {
                        message = "Antes de gerar 3D, marque as paredes no editor."
                        onEdit()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = lime, contentColor = ink)
            ) { Text("Gerar 3D") }
        } else {
            if (floor.calibrated && project.floors.any { it.walls.isNotEmpty() }) {
                OrbitModelPreview(plan = project, allFloors = true, showLines = true)
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Altura das paredes", color = ink)
                            Text("${decimal(height.toDouble())} m", color = ink)
                        }
                        Slider(
                            value = height,
                            onValueChange = { height = it },
                            onValueChangeFinished = {
                                updateFloor(floor.copy(wallHeightMeters = height.toDouble()))
                            },
                            valueRange = 2f..3.5f
                        )
                    }
                }
                Button(
                    onClick = {
                        updateFloor(floor.copy(wallHeightMeters = height.toDouble()))
                        message = "Modelo salvo neste aparelho."
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = lime, contentColor = ink)
                ) { Text("Salvar modelo ✓") }
                Text("${project.floors.sumOf { it.walls.size }} paredes • Visualização 3D esquemática",
                    fontSize = 11.sp, color = Color.Gray)
            } else {
                Text("Ainda não há paredes calibradas para formar a maquete 3D.")
                Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                    Text("Desenhar paredes no editor")
                }
            }
        }
        message?.let { Text(it, color = ink, fontSize = 12.sp) }
        Text(
            "Escala, paredes e maquete são assistidas. A foto por si só não " +
                "reconhece ambientes nem produz medidas reais.",
            color = Color.Gray, fontSize = 11.sp
        )
        Spacer(Modifier.height(18.dp))
    }
}
