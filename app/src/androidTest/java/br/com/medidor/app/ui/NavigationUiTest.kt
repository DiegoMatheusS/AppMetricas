package br.com.medidor.app.ui

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.medidor.app.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationUiTest {
    @Test fun toolsCameraAndBackUseTheSameNavigationHistory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val permission = instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.CAMERA")
        ParcelFileDescriptor.AutoCloseInputStream(permission).use { it.readBytes() }
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            waitForText("Ajustes")
            clickText("Ajustes")
            waitForText("Ferramentas e ajustes")
            clickText("Régua pela câmera")
            waitForText("Régua e área")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitForText("Ferramentas e ajustes")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitForText("Ajustes")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun node(text: String): AccessibilityNodeInfo? =
        InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByText(text)?.firstOrNull()

    private fun waitForText(text: String) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (node(text) == null && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue("Tela esperada: $text", node(text) != null)
    }

    private fun clickText(text: String) {
        var target = node(text)
        repeat(8) {
            if (target?.isClickable == true) {
                assertTrue("Controle acessível: $text", target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return
            }
            target = target?.parent
        }
        throw AssertionError("Controle sem ação: $text")
    }
}
