package br.com.medidor.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.activity.compose.BackHandler
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import br.com.medidor.app.data.LocalHistory
import br.com.medidor.app.data.MeasurementRecord

enum class Destination(val label: String) {
    HOME("Medidor"),
    BOX("Medir e calcular"),
    TANK("Tanques e reservatórios"),
    PHOTO("Medir por fotografia"),
    HISTORY("Minhas medições"),
    PRO("Pro completo"),
    ARCHITECT("Modo Arquiteto"),
    SCANNER("Scanner 3D"),
    COUNT("Contador de objetos"),
    MORE("Mais ferramentas")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell() {
    val context = LocalContext.current
    val store = remember(context) { LocalHistory(context.applicationContext) }
    var destinationName by rememberSaveable { mutableStateOf(Destination.HOME.name) }
    val destination = Destination.entries.firstOrNull { it.name == destinationName } ?: Destination.HOME
    var records by remember { mutableStateOf(store.load()) }
    var showMore by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(destination) {
        if (destination == Destination.HISTORY) records = store.load()
    }
    fun navigate(to: Destination) { destinationName = to.name }
    BackHandler(enabled = showMore || destination != Destination.HOME) {
        if (showMore) showMore = false else navigate(Destination.HOME)
    }
    fun addRecord(title: String, details: String) { records = store.save(title, details) }

    if (showMore) {
        ModalBottomSheet(onDismissRequest = { showMore = false }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mais ferramentas", style = MaterialTheme.typography.titleLarge)
                listOf(Destination.SCANNER, Destination.COUNT, Destination.HISTORY, Destination.PHOTO, Destination.PRO).forEach { tool ->
                    OutlinedButton(onClick = { showMore = false; navigate(tool) }, modifier = Modifier.fillMaxWidth()) { Text(tool.label) }
                }
            }
        }
    }
    Scaffold(bottomBar = {
        if (destination != Destination.PHOTO) Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            tonalElevation = 3.dp,
            shadowElevation = 4.dp
        ) {
            Column {
                Text("Ferramentas", style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 18.dp, top = 8.dp))
                NavigationBar {
                    listOf(Destination.BOX, Destination.ARCHITECT, Destination.TANK, Destination.MORE).forEach { target ->
                        NavigationBarItem(
                            selected = if (target == Destination.MORE) showMore else destination == target,
                            onClick = { if (target == Destination.MORE) showMore = true else navigate(target) },
                            icon = { Text(when (target) {
                                Destination.BOX -> "▱"
                                Destination.ARCHITECT -> "▥"
                                Destination.TANK -> "◯"
                                else -> "⋯"
                            }) },
                            label = { Text(when (target) {
                                Destination.BOX -> "Medição"
                                Destination.ARCHITECT -> "Arquiteto"
                                Destination.TANK -> "Tanques"
                                else -> "Mais"
                            }) }
                        )
                    }
                }
            }
        }
    }, topBar = {
        if (destination != Destination.PHOTO) TopAppBar(
            title = { Text(destination.label, style = MaterialTheme.typography.titleLarge) },
            navigationIcon = {
                if (destination != Destination.HOME) {
                    TextButton(onClick = { navigate(Destination.HOME) }) { Text("Voltar") }
                }
            }
        )
    }) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (destination) {
                Destination.HOME -> HomeScreen(onOpen = ::navigate)
                Destination.BOX -> BoxCalculatorScreen(onSave = ::addRecord, onCamera = { navigate(Destination.PHOTO) })
                Destination.TANK -> TankCalculatorScreen(onSave = ::addRecord, onCamera = { navigate(Destination.PHOTO) })
                Destination.PHOTO -> CameraMeasurementScreen(onSave = ::addRecord)
                Destination.HISTORY -> HistoryScreen(records = records, onDeleteAll = {
                    store.clear()
                    records = emptyList()
                }, onDeleteOne = { index ->
                    records = store.deleteAt(index)
                })
                Destination.PRO -> ProScreen()
                Destination.ARCHITECT -> ArchitectScreen()
                Destination.SCANNER -> ScannerCaptureScreen()
                Destination.COUNT -> ObjectCountHome(onSave = ::addRecord)
                Destination.MORE -> MoreToolsScreen(onOpen = ::navigate)
            }
        }
    }
}
