package br.com.medidor.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ObjectCountHome(
    onSave: (String, String) -> Unit,
    onBack: () -> Unit = {}
) {
    var live by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF14251F))
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) {
                Text("‹ Início", color = Color.White)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = live,
                    onClick = { live = true },
                    label = { Text("Câmera") }
                )
                FilterChip(
                    selected = !live,
                    onClick = { live = false },
                    label = { Text("Por foto") }
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
            if (live) {
                LiveObjectCountScreen(onSave)
            } else {
                ObjectCountScreen(onSave)
            }
        }
    }
}
