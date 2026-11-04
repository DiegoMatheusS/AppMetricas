package br.com.medidor.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import br.com.medidor.app.data.LocalHistory

enum class Destination(val label: String) {
    HOME("App Métricas"),
    RULER("Régua pela câmera"),
    AREA("Área pela câmera"),
    TANK_CAMERA("Tanques pela câmera"),
    BOX("Medir e calcular"),
    TANK("Tanques — cálculo manual"),
    PHOTO("Medir por fotografia"),
    HISTORY("Minhas medições"),
    PRO("Pro completo"),
    ARCHITECT("Modo Arquiteto"),
    ARCHITECT_SCAN("Escanear planta pela câmera"),
    SCANNER("Scanner 3D"),
    COUNT("Contador de objetos"),
    MORE("Ferramentas e ajustes")
}

enum class LiveMeasureTool { RULER, AREA, TANK }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell() {
    val context = LocalContext.current
    val store = remember(context) { LocalHistory(context.applicationContext) }
    var destinationName by rememberSaveable { mutableStateOf(Destination.HOME.name) }
    val destination = Destination.entries.firstOrNull { it.name == destinationName }
        ?: Destination.HOME
    var records by remember { mutableStateOf(store.load()) }
    LaunchedEffect(destination) {
        if (destination == Destination.HISTORY || destination == Destination.HOME) {
            records = store.load()
        }
    }
    fun navigate(to: Destination) { destinationName = to.name }
    BackHandler(enabled = destination != Destination.HOME) {
        navigate(Destination.HOME)
    }
    fun addRecord(title: String, details: String) {
        records = store.save(title, details)
    }

    val liveCamera = destination == Destination.RULER ||
        destination == Destination.AREA ||
        destination == Destination.TANK_CAMERA ||
        destination == Destination.ARCHITECT_SCAN ||
        destination == Destination.PHOTO

    Scaffold(
        topBar = {
            if (destination != Destination.HOME && !liveCamera && destination != Destination.MORE) {
                TopAppBar(
                    title = {
                        Text(destination.label, style = MaterialTheme.typography.titleMedium)
                    },
                    navigationIcon = {
                        TextButton(onClick = { navigate(Destination.HOME) }) {
                            Text("‹ Voltar")
                        }
                    }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (destination) {
                Destination.HOME -> HomeScreen(onOpen = ::navigate, records = records)
                Destination.RULER, Destination.AREA, Destination.TANK_CAMERA,
                Destination.PHOTO -> CameraMeasurementScreen(
                    onSave = ::addRecord,
                    liveTool = when (destination) {
                        Destination.AREA -> LiveMeasureTool.AREA
                        Destination.TANK_CAMERA -> LiveMeasureTool.TANK
                        else -> LiveMeasureTool.RULER
                    },
                    onBack = { navigate(Destination.HOME) },
                    onManual = {
                        navigate(if (destination == Destination.TANK_CAMERA)
                            Destination.TANK else Destination.BOX)
                    },
                    onSwitchTool = { tool ->
                        navigate(when (tool) {
                            LiveMeasureTool.RULER -> Destination.RULER
                            LiveMeasureTool.AREA -> Destination.AREA
                            LiveMeasureTool.TANK -> Destination.TANK_CAMERA
                        })
                    }
                )
                Destination.BOX -> BoxCalculatorScreen(
                    onSave = ::addRecord, onCamera = { navigate(Destination.AREA) })
                Destination.TANK -> TankCalculatorScreen(
                    onSave = ::addRecord, onCamera = { navigate(Destination.TANK_CAMERA) })
                Destination.HISTORY -> HistoryScreen(
                    records = records,
                    onDeleteAll = { store.clear(); records = emptyList() },
                    onDeleteOne = { index -> records = store.deleteAt(index) }
                )
                Destination.PRO -> ProScreen()
                Destination.ARCHITECT -> ArchitectScreen()
                Destination.ARCHITECT_SCAN -> ArchitectScanScreen(
                    onBack = { navigate(Destination.HOME) },
                    onCaptured = { navigate(Destination.ARCHITECT) }
                )
                Destination.SCANNER -> ScannerCaptureScreen()
                Destination.COUNT -> ObjectCountHome(onSave = ::addRecord)
                Destination.MORE -> MoreToolsScreen(onOpen = ::navigate)
            }
        }
    }
}
