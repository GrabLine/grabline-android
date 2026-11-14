package dev.grabline.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.grabline.app.ui.shell.GrabLineAppShell
import dev.grabline.app.ui.theme.GrabLineTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GrabLineTheme {
                GrabLineAppShell(onAddClick = { /* Add sheet arrives with the entry task. */ })
            }
        }
    }
}
