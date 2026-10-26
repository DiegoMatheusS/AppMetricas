package br.com.medidor.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
fun HomeScreen(onOpen: (Destination) -> Unit) {
    var cameraOn by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("AppMétricas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = { cameraOn = !cameraOn }) {
                Text(if (cameraOn) "Desligar câmera" else "Ligar câmera")
            }
        }
        Box(Modifier.fillMaxWidth().height(210.dp).background(Color.Black)) {
            if (cameraOn) HomeCameraPreview()
            else Text("Câmera desligada", color = Color.White, modifier = Modifier.padding(20.dp))
        }
        Button(onClick = { onOpen(Destination.PHOTO) }, modifier = Modifier.fillMaxWidth()) {
            Text("Medir pela câmera")
        }
        Text("Principais ferramentas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        val items = listOf(
            Triple("Medição", "▱", Destination.PHOTO),
            Triple("Medir e calcular", "▣", Destination.BOX),
            Triple("Tanques", "◯", Destination.TANK),
            Triple("Contar objetos", "◎", Destination.COUNT),
            Triple("Modo Arquiteto", "▥", Destination.ARCHITECT),
            Triple("Scanner 3D", "◇", Destination.SCANNER)
        )
        LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items.size) { i ->
                val item = items[i]
                Card(
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clickable { onOpen(item.third) },
                    shape = RoundedCornerShape(22.dp),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(item.second, style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text(item.first, fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun MoreToolsScreen(onOpen: (Destination) -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Mais ferramentas", style = MaterialTheme.typography.titleLarge)
        listOf(
            Destination.HISTORY, Destination.SCANNER, Destination.PRO,
            Destination.ARCHITECT, Destination.TANK
        ).forEach { destination ->
            OutlinedButton(onClick = { onOpen(destination) },modifier=Modifier.fillMaxWidth()) {
                Text(destination.label)
            }
        }
    }
}
