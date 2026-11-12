package br.com.medidor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import br.com.medidor.app.ui.AppShell
import br.com.medidor.app.ui.MedidorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MedidorTheme { AppShell() }
        }
    }
}
