package br.com.medidor.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
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

    @Test fun photoTapsCalibrateCalculateAndSaveDistanceAndArea() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        val saved = CopyOnWriteArrayList<String>()
        val photo = Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        try {
            instrumentation.runOnMainSync {
                activity.setContent { MedidorTheme { PointPhotoMeasurementScreen(photo,false,{}, { _, value -> saved.add(value) }) } }
            }
            waitFor("Régua por pontos")
            assertFalse(clickable("Salvar")!!.isEnabled)
            val first = tapPhoto(.1f,.2f)
            SystemClock.sleep(100)
            val screen = instrumentation.uiAutomation.takeScreenshot()!!
            val pixel = screen.getPixel(first.first.toInt(),first.second.toInt())
            assertTrue("Ponto desenhado exatamente no toque",android.graphics.Color.red(pixel)>240 && android.graphics.Color.green(pixel) in 160..195 && android.graphics.Color.blue(pixel)<100)
            screen.recycle()
            tapPhoto(.5f,.2f)
            waitFor("Quanto mede a linha amarela?")
            click("Aplicar escala") // Explicitly confirms the displayed 10 cm reference.
            tapPhoto(.1f,.8f); tapPhoto(.9f,.8f)
            waitFor("Distância: 20 cm")
            click("Salvar")
            instrumentation.waitForIdleSync()
            assertEquals(1,saved.size)
            assertTrue(saved[0].startsWith("Distância: 20 cm"))
            click("Área")
            tapPhoto(.1f,.5f); tapPhoto(.9f,.5f); tapPhoto(.9f,.9f); tapPhoto(.1f,.9f)
            waitFor("Área: 150 cm²")
            click("Salvar")
            instrumentation.waitForIdleSync()
            assertEquals(2,saved.size)
            assertTrue(saved[1].startsWith("Área: 150 cm²"))
            click("Refazer")
            waitFor("Área por pontos")
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
        try {
            assertTrue(instrumentation.uiAutomation.injectInputEvent(down,true))
            assertTrue(instrumentation.uiAutomation.injectInputEvent(up,true))
        } finally { down.recycle(); up.recycle() }
        instrumentation.waitForIdleSync()
        return px to py
    }
}
