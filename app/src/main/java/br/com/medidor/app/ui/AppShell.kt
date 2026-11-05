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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import br.com.medidor.app.data.LocalHistory
import br.com.medidor.app.domain.TankCameraCapture

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
    ARCHITECT_REVIEW("Revisão 2D e modelo 3D"),
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
    var navigation by rememberSaveable { mutableStateOf(listOf(Destination.HOME.name)) }
    val destination = Destination.entries.firstOrNull { it.name == navigation.lastOrNull() }
        ?: Destination.HOME
    val screenState = rememberSaveableStateHolder()
    var records by remember { mutableStateOf(store.load()) }
    var capturedTank by remember { mutableStateOf<TankCameraCapture?>(null) }
    LaunchedEffect(destination) {
        if (destination == Destination.HISTORY || destination == Destination.HOME) {
            records = store.load()
        }
    }
    fun navigate(to: Destination) {
        navigation = AppNavigation.open(navigation, to)
    }
    fun goBack() {
        navigation = AppNavigation.back(navigation)
    }
    BackHandler(enabled = destination != Destination.HOME) { goBack() }
    fun addRecord(title: String, details: String) {
        records = store.save(title, details)
    }

    val liveCamera = destination == Destination.RULER ||
        destination == Destination.AREA ||
        destination == Destination.TANK_CAMERA ||
        destination == Destination.ARCHITECT_SCAN ||
        destination == Destination.ARCHITECT_REVIEW ||
        destination == Destination.COUNT ||
        destination == Destination.PHOTO

    Scaffold(
        topBar = {
            if (destination != Destination.HOME && !liveCamera && destination != Destination.MORE) {
                TopAppBar(
                    title = {
                        Text(destination.label, style = MaterialTheme.typography.titleMedium)
                    },
                    navigationIcon = {
                        TextButton(onClick = ::goBack) {
                            Text("‹ Voltar")
                        }
                    }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            screenState.SaveableStateProvider(destination.name) {
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
                    onBack = ::goBack,
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
                    },
                    onTankArResult = { capture ->
                        capturedTank = capture
                        navigate(Destination.TANK)
                    }
                )
                Destination.BOX -> BoxCalculatorScreen(
                    onSave = ::addRecord, onCamera = { navigate(Destination.AREA) })
                Destination.TANK -> TankCalculatorScreen(
                    onSave = ::addRecord,
                    onCamera = { navigate(Destination.TANK_CAMERA) },
                    incomingAr = capturedTank,
                    onArImported = { capturedTank = null }
                )
                Destination.HISTORY -> HistoryScreen(
                    records = records,
                    onDeleteAll = { store.clear(); records = emptyList() },
                    onDeleteOne = { index -> records = store.deleteAt(index) }
                )
                Destination.PRO -> ProScreen()
                Destination.ARCHITECT -> ArchitectScreen()
                Destination.ARCHITECT_SCAN -> ArchitectScanScreen(
                    onBack = ::goBack,
                    onCaptured = { /* Remain in the live camera after scanning. */ },
                    onReview = { navigate(Destination.ARCHITECT_REVIEW) }
                )
                Destination.SCANNER -> ScannerCaptureScreen()
                Destination.ARCHITECT_REVIEW -> ArchitectReviewScreen(
                    onBack = ::goBack,
                    onEdit = { navigate(Destination.ARCHITECT) },
                    onScan = { navigate(Destination.ARCHITECT_SCAN) }
                )
                Destination.COUNT -> ObjectCountHome(
                    onSave = ::addRecord,
                    onBack = ::goBack
                )
                Destination.MORE -> MoreToolsScreen(onOpen = ::navigate)
            }
            }
        }
    }
}
