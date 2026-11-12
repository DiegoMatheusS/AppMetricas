package br.com.medidor.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import br.com.medidor.app.domain.FloorPlanMath
import br.com.medidor.app.domain.OpeningType
import br.com.medidor.app.domain.PlanFloor
import br.com.medidor.app.domain.PlanOpening

/** Manual openings editor: no automatic detection and no unvalidated geometry. */
@Composable
fun OpeningEditor(floor: PlanFloor, onUpdate: (PlanFloor) -> Unit) {
    if (floor.walls.isEmpty()) return
    var selectedWall by remember(floor.title) { mutableStateOf(0) }
    val index = selectedWall.coerceIn(floor.walls.indices)
    var type by remember(floor.title) { mutableStateOf(OpeningType.DOOR) }
    var width by remember(floor.title) { mutableStateOf("0,90") }
    var height by remember(floor.title) { mutableStateOf("2,10") }
    var sill by remember(floor.title) { mutableStateOf("0") }
    var position by remember(floor.title) { mutableStateOf("50") }

    fun number(text: String): Double? =
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    val candidate = run {
        val w = number(width)
        val h = number(height)
        val z = number(sill)
        val p = number(position)
        if (w == null || h == null || z == null || p == null) null
        else PlanOpening(
            wallIndex = index,
            type = type,
            centerFraction = p / 100.0,
            widthMeters = w,
            sillMeters = z,
            heightMeters = h
        )
    }
    val acceptable = candidate != null && FloorPlanMath.canAddOpening(floor, candidate)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Portas e janelas", style = MaterialTheme.typography.titleMedium)
        Text("Escolha a parede e informe onde a abertura ficará. As dimensões são em metros.",
            style = MaterialTheme.typography.bodySmall)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            floor.walls.forEachIndexed { wallIndex, wall ->
                FilterChip(
                    selected = index == wallIndex,
                    onClick = { selectedWall = wallIndex },
                    label = { Text("Parede ${wallIndex + 1}") }
                )
            }
        }
        val length = FloorPlanMath.wallLengthMeters(floor, floor.walls[index])
        Text("Parede ${index + 1}: ${length?.let { String.format("%.2f", it) } ?: "sem escala"} m")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpeningType.entries.forEach { choice ->
                FilterChip(
                    selected = type == choice,
                    onClick = {
                        type = choice
                        width = if (choice == OpeningType.DOOR) "0,90" else "1,20"
                        sill = if (choice == OpeningType.DOOR) "0" else "0,90"
                        height = if (choice == OpeningType.DOOR) "2,10" else "1,20"
                    },
                    label = { Text(choice.label) }
                )
            }
        }
        OutlinedTextField(value = width, onValueChange = { width = it },
            label = { Text("Largura (m)") }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(value = height, onValueChange = { height = it },
            label = { Text("Altura da abertura (m)") }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        if (type == OpeningType.WINDOW) {
            OutlinedTextField(value = sill, onValueChange = { sill = it },
                label = { Text("Altura do peitoril (m)") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        OutlinedTextField(value = position, onValueChange = { position = it },
            label = { Text("Centro da abertura na parede (%)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        if (!acceptable) {
            Text("Verifique largura, altura, posição, limites da parede e sobreposição com outras aberturas.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            enabled = acceptable,
            onClick = { candidate?.let { onUpdate(floor.copy(openings = floor.openings + it)) } }
        ) { Text("Adicionar ${type.label.lowercase()}") }
        floor.openings.forEachIndexed { openingIndex, opening ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${opening.type.label} • parede ${opening.wallIndex + 1} • " +
                        "${opening.widthMeters} × ${opening.heightMeters} m",
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = {
                    onUpdate(floor.copy(openings = floor.openings.filterIndexed { i, _ -> i != openingIndex }))
                }) { Text("Remover") }
            }
        }
    }
}
