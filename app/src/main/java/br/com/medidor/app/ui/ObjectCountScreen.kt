package br.com.medidor.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import kotlin.math.min

/**
 * Offline assisted counting. Each dot represents a user-confirmed visible object.
 * This feature does not detect, infer or estimate occluded objects automatically.
 */
@Composable
fun ObjectCountScreen(onSave: (String, String) -> Unit, initialPhoto: Bitmap? = null, initialMarks: List<Offset> = emptyList()) {
    val context = LocalContext.current
    var photo by remember(initialPhoto) { mutableStateOf(initialPhoto) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    val marks = remember(initialPhoto) { mutableStateListOf<Offset>().apply { addAll(initialMarks) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val newPhoto = SampledPhotos.fromUri(context.contentResolver, uri)
                photo = newPhoto
                marks.clear()
                saved = false
                error = null
            } catch (e: Exception) {
                error = "Não foi possível abrir a fotografia: ${e.localizedMessage}"
            }
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Contagem assistida por foto", style = MaterialTheme.typography.titleLarge)
        Text("Confira os pontos. Toque em uma peça sem ponto para adicionar ou toque em um ponto para removê-lo.")
        OutlinedButton(onClick = { pick.launch("image/*") }) {
            Text(if (photo == null) "Escolher fotografia" else "Trocar fotografia")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val bitmap = photo
        if (bitmap != null) {
            Text("Total marcado: ${marks.size}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Box(Modifier.fillMaxWidth().height(360.dp)) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Foto dos objetos que serão contados",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Área de contagem da foto" }.pointerInput(bitmap) {
                    detectTapGestures { touch ->
                        val scale = min(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height)
                        val width = bitmap.width * scale
                        val height = bitmap.height * scale
                        val left = (size.width - width) / 2f
                        val top = (size.height - height) / 2f
                        if (touch.x >= left && touch.x <= left + width &&
                            touch.y >= top && touch.y <= top + height && marks.size < 500) {
                            val point = Offset((touch.x - left) / width, (touch.y - top) / height)
                            val nearby = marks.indexOfFirst { kotlin.math.hypot((it.x-point.x)*width,(it.y-point.y)*height) < 18.dp.toPx() }
                            if (nearby >= 0) marks.removeAt(nearby) else marks.add(point)
                            saved = false
                        }
                    }
                }) {
                    val scale = min(size.width / bitmap.width.toFloat(), size.height / bitmap.height.toFloat())
                    val width = bitmap.width * scale
                    val height = bitmap.height * scale
                    val left = (size.width - width) / 2f
                    val top = (size.height - height) / 2f
                    marks.forEach { mark ->
                        val center = Offset(left + mark.x * width, top + mark.y * height)
                        drawCircle(Color(0xFF003A8C), radius = 11.dp.toPx(), center = center)
                        drawCircle(Color(0xFFFFD54F), radius = 7.dp.toPx(), center = center)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = marks.isNotEmpty(), onClick = {
                    marks.removeAt(marks.lastIndex)
                    saved = false
                }) { Text("Desfazer") }
                OutlinedButton(enabled = marks.isNotEmpty(), onClick = {
                    marks.clear()
                    saved = false
                }) { Text("Limpar") }
            }
            if (marks.size == 500) Text("Limite de 500 marcações atingido.")
            Text("Confira cada ponto antes de salvar. Objetos ocultos ou sobrepostos podem ficar de fora.",
                style = MaterialTheme.typography.bodySmall)
            Button(enabled = marks.isNotEmpty() && !saved, onClick = {
                onSave("Contagem assistida", "${marks.size} objetos marcados manualmente em fotografia")
                saved = true
            }) { Text(if (saved) "Salvo" else "Salvar contagem no histórico") }
            if (saved) Text("Contagem salva no histórico local (sem anexar a fotografia).")
        }
    }
}
