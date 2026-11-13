package br.com.medidor.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.medidor.app.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
class PointMeasurementUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun cameraTapsAreInertUntilThePointButtonIsChosen() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        var automaticCalls = 0
        var pointCalls = 0
        var photoCalls = 0
        try {
            instrumentation.runOnMainSync {
                activity.setContent { MedidorTheme {
                    LiveContourCamera(onPhoto = { photoCalls++ }, onError = {}, onAr = { automaticCalls++ },
                        onArPoints = { pointCalls++ })
                } }
            }
            tapPhoto(.5f,.5f,"Visor livre da câmera")
            assertEquals(0,automaticCalls)
            assertEquals(0,photoCalls)
            assertEquals(0,pointCalls)
            click("Marcar pontos no ambiente sem informar tamanho")
            assertEquals(1,pointCalls)
            assertEquals(0,automaticCalls)
            assertNull(node("Quanto mede a linha amarela?"))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun largerTextKeepsInstructionsAndButtonsBelowThePhoto() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        val photo = Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    val display = androidx.compose.ui.platform.LocalDensity.current
                    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit.Density(display.density,1.5f)) {
                        MedidorTheme { PointPhotoMeasurementScreen(photo,false,{}, { _, _ -> }) }
                    }
                }
            }
            val instruction = "Foto: toque inativo. Ative Escala ou Objeto; Auto mede no ambiente sem referência."
            waitFor(instruction)
            val image = Rect().also { node("Área de marcação da foto")!!.getBoundsInScreen(it) }
            val text = Rect().also { node(instruction)!!.getBoundsInScreen(it) }
            val button = Rect().also { clickable("Definir escala da foto")!!.getBoundsInScreen(it) }
            assertTrue("Foto continua visível com fonte maior",image.height() > 100)
            assertTrue("Texto fica inteiramente no painel",text.top >= image.bottom && text.bottom <= button.top)
            assertTrue("Instrução pode quebrar linhas",text.height() > 20)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun selectedPhotoExplainsScaleAndOpensAutomaticMeasurementWithoutAskingForSize() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        val photo = Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(photo).apply {
            drawColor(android.graphics.Color.WHITE)
            drawRect(400f,240f,720f,480f,android.graphics.Paint().apply { color = android.graphics.Color.DKGRAY })
        }
        var automaticOpened = false
        try {
            instrumentation.runOnMainSync {
                activity.setContent { MedidorTheme {
                    PointPhotoMeasurementScreen(photo,false,{}, { _, _ -> fail("Foto sem escala não deve salvar uma medida") },
                        br.com.medidor.app.domain.NormalizedPoint(.7f,.6f), onAutomatic = { automaticOpened = true })
                } }
            }
            waitFor("Objeto selecionado sem escala. Auto mede no ambiente; Escala calibra a foto.")
            assertFalse(clickable("Salvar")!!.isEnabled)
            assertNull(node("Quanto mede a linha amarela?"))
            val image = Rect().also { node("Área de marcação da foto")!!.getBoundsInScreen(it) }
            val controls = listOf("Voltar à câmera","Régua","Área","Medir automaticamente no ambiente","Definir escala da foto","Salvar")
                .map { text -> Rect().also { clickable(text)!!.getBoundsInScreen(it) } }
            controls.forEach { bounds ->
                assertTrue("Botões abaixo da foto",bounds.top >= image.bottom)
                assertTrue("Mesmo tamanho",kotlin.math.abs(bounds.height()-controls[0].height()) <= 2 &&
                    kotlin.math.abs(bounds.width()-controls[0].width()) <= 2)
            }
            click("Medir automaticamente no ambiente")
            assertTrue("Acesso direto à medição automática",automaticOpened)
            assertNull(node("Quanto mede a linha amarela?"))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun tappingAnotherObjectRecalculatesOnlyItsOwnEdges() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        val saved = CopyOnWriteArrayList<String>()
        val photo = Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(photo).apply {
            drawColor(android.graphics.Color.WHITE)
            val paint = android.graphics.Paint().apply { color = android.graphics.Color.rgb(30,60,90) }
            drawRect(80f,300f,240f,420f,paint)
            drawRect(400f,240f,720f,480f,paint)
        }
        try {
            instrumentation.runOnMainSync {
                activity.setContent { MedidorTheme { PointPhotoMeasurementScreen(photo,false,{}, { _, value -> saved.add(value) }) } }
            }
            waitFor("Área de marcação da foto")
            val deadline = SystemClock.elapsedRealtime()+10_000
            while (node("Escaneando arestas…") != null && SystemClock.elapsedRealtime()<deadline) Thread.sleep(100)
            assertNull("Escaneamento concluído",node("Escaneando arestas…"))
            click("Definir escala da foto"); click("Marcar referência")
            tapPhoto(.1f,.15f); tapPhoto(.5f,.15f)
            click("Aplicar escala")
            click("Selecionar objeto")
            tapPhoto(.2f,.6f)
            waitFor("Objeto selecionado")
            click("Salvar")
            tapPhoto(.7f,.6f)
            click("Salvar")
            assertEquals(2,saved.size)
            fun centimeters(value: String) = Regex("Distância: ([0-9,]+) cm").find(value)!!.groupValues[1].replace(',','.').toDouble()
            assertEquals(5.0,centimeters(saved[0]),.2)
            assertEquals(10.0,centimeters(saved[1]),.2)
            click("Ponto")
            waitFor("Tamanho do ponto")
            click("Fino")
            click("Pontos")
            assertFalse(clickable("Salvar")!!.isEnabled)
            tapPhoto(.1f,.9f); tapPhoto(.5f,.9f)
            waitFor("Distância: 10 cm")
            click("Salvar")
            assertEquals(3,saved.size)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun photoTapsCalibrateCalculateAndSaveDistanceAndArea() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        val saved = CopyOnWriteArrayList<String>()
        val photo = Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        try {
            instrumentation.runOnMainSync {
                activity.setContent { MedidorTheme { PointPhotoMeasurementScreen(photo,false,{}, { _, value -> saved.add(value) }) } }
            }
            waitFor("Foto · Régua")
            assertFalse(clickable("Salvar")!!.isEnabled)
            tapPhoto(.1f,.2f); tapPhoto(.5f,.2f)
            assertNull("Toques não ativam marcação ou calibração sem um botão",node("Quanto mede a linha amarela?"))
            assertFalse(clickable("Salvar")!!.isEnabled)
            click("Definir escala da foto"); click("Marcar referência")
            val first = tapPhoto(.1f,.2f)
            SystemClock.sleep(100)
            val screen = instrumentation.uiAutomation.takeScreenshot()!!
            val pixel = screen.getPixel(first.first.toInt(),first.second.toInt())
            var minX = screen.width; var minY = screen.height; var maxX = -1; var maxY = -1
            for (y in 0 until screen.height) for (x in 0 until screen.width) {
                val color = screen.getPixel(x,y)
                if (android.graphics.Color.red(color)>240 && android.graphics.Color.green(color) in 160..195 && android.graphics.Color.blue(color)<100) {
                    minX=minOf(minX,x); minY=minOf(minY,y); maxX=maxOf(maxX,x); maxY=maxOf(maxY,y)
                }
            }
            assertTrue("Ponto no toque=$first cor=${Integer.toHexString(pixel)} encontrado=[$minX,$minY,$maxX,$maxY] captura=${screen.width}x${screen.height}",android.graphics.Color.red(pixel)>240 && android.graphics.Color.green(pixel) in 160..195 && android.graphics.Color.blue(pixel)<100)
            screen.recycle()
            tapPhoto(.5f,.2f)
            waitFor("Quanto mede a linha amarela?")
            click("Aplicar escala") // Explicitly confirms the displayed 10 cm reference.
            click("Marcar pontos na foto")
            tapPhoto(.1f,.8f); tapPhoto(.9f,.8f)
            waitFor("Distância: 20 cm")
            click("Salvar")
            instrumentation.waitForIdleSync()
            assertEquals(1,saved.size)
            assertTrue(saved[0].startsWith("Distância: 20 cm"))
            click("Marcar pontos na foto") // Turn placement off; existing ends stay draggable.
            tapPhoto(.5f,.4f)
            waitFor("Distância: 20 cm")
            dragPhoto(.9f,.8f,.7f,.8f)
            waitFor("Distância: 15 cm")
            click("Área")
            click("Marcar pontos na foto")
            tapPhoto(.1f,.5f); tapPhoto(.9f,.5f); tapPhoto(.9f,.9f); tapPhoto(.1f,.9f)
            waitFor("Área: 150 cm²")
            click("Salvar")
            instrumentation.waitForIdleSync()
            assertEquals(2,saved.size)
            assertTrue(saved[1].startsWith("Área: 150 cm²"))
            click("Refazer")
            waitFor("Foto · Área")
            assertFalse(clickable("Salvar")!!.isEnabled)
            instrumentation.runOnMainSync {
                activity.setContent { MedidorTheme {
                    ObjectCountScreen({ _, value -> saved.add(value) },photo,listOf(Offset(.25f,.5f),Offset(.75f,.5f)))
                } }
            }
            waitFor("Total marcado: 2")
            tapPhoto(.25f,.5f,"Área de contagem da foto")
            waitFor("Total marcado: 1")
            tapPhoto(.5f,.8f,"Área de contagem da foto")
            waitFor("Total marcado: 2")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun node(text: String): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current.text?.toString() == text || current.contentDescription?.toString() == text) return current
            repeat(current.childCount) { current.getChild(it)?.let(queue::addLast) }
        }
        return null
    }
    private fun waitFor(text: String) {
        val deadline = SystemClock.elapsedRealtime()+10_000
        while (node(text)==null && SystemClock.elapsedRealtime()<deadline) Thread.sleep(100)
        assertNotNull("Controle ou resultado esperado: $text",node(text))
    }
    private fun clickable(text: String): AccessibilityNodeInfo? {
        var current = node(text)
        repeat(8) { if (current?.isClickable == true) return current; current = current?.parent }
        return null
    }
    private fun click(text: String) {
        waitFor(text)
        val control = clickable(text)
        assertNotNull("Botão: $text",control)
        assertTrue("Botão habilitado: $text",control!!.isEnabled)
        assertTrue(control.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun tapPhoto(x: Float,y: Float,description: String = "Área de marcação da foto"): Pair<Float,Float> {
        waitFor(description)
        val bounds = Rect().also { node(description)!!.getBoundsInScreen(it) }
        assertTrue("Foto visível para marcar pontos",bounds.height()>50 && bounds.width()>50)
        val scale = min(bounds.width()/800f,bounds.height()/600f)
        val px = bounds.left+(bounds.width()-800*scale)/2+x*800*scale
        val py = bounds.top+(bounds.height()-600*scale)/2+y*600*scale
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,px,py,0)
        val up = MotionEvent.obtain(now,now+50,MotionEvent.ACTION_UP,px,py,0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            assertTrue(instrumentation.uiAutomation.injectInputEvent(down,true))
            assertTrue(instrumentation.uiAutomation.injectInputEvent(up,true))
        } finally { down.recycle(); up.recycle() }
        instrumentation.waitForIdleSync()
        return px to py
    }

    private fun dragPhoto(x0: Float,y0: Float,x1: Float,y1: Float) {
        val bounds = Rect().also { node("Área de marcação da foto")!!.getBoundsInScreen(it) }
        val scale = min(bounds.width()/800f,bounds.height()/600f)
        fun point(x: Float,y: Float) = (bounds.left+(bounds.width()-800*scale)/2+x*800*scale) to
            (bounds.top+(bounds.height()-600*scale)/2+y*600*scale)
        val a = point(x0,y0); val b = point(x1,y1)
        val start = SystemClock.uptimeMillis()
        for (step in 0..10) {
            val fraction = step/10f
            val action = if (step==0) MotionEvent.ACTION_DOWN else if (step==10) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
            val event = MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,a.first+(b.first-a.first)*fraction,a.second+(b.second-a.second)*fraction,0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event,true)) } finally { event.recycle() }
            SystemClock.sleep(25)
        }
        instrumentation.waitForIdleSync()
    }
}
