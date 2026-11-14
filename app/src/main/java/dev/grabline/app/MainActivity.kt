package dev.grabline.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.grabline.app.ui.shell.GrabLineAppShell
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.grabline.app.data.AppSettings
import dev.grabline.app.ui.theme.GrabLineTheme
import dev.grabline.app.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings = remember { AppSettings(applicationContext) }
            val theme by remember { settings.observe(AppSettings.THEME) }.collectAsState(AppSettings.THEME.default)
            val colours by remember { settings.observe(AppSettings.COLOURS) }.collectAsState(AppSettings.COLOURS.default)
            GrabLineTheme(
                themeMode = when (theme) {
                    "light" -> ThemeMode.Light
                    "dark" -> ThemeMode.Dark
                    else -> ThemeMode.System
                },
                useWallpaperColors = colours == "wallpaper",
            ) {
                GrabLineAppShell(
                    onAddClick = { /* Add sheet arrives with the entry task. */ },
                    settings = settings,
                )
            }
        }
    }
}
