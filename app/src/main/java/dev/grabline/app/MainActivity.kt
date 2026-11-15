package dev.grabline.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import dev.grabline.app.data.AppSettings
import dev.grabline.app.ui.entry.ClipboardOffer
import dev.grabline.app.ui.entry.IncomingRequest
import dev.grabline.app.ui.entry.IntentLinkReader
import dev.grabline.app.ui.settings.AppLocale
import dev.grabline.app.ui.shell.GrabLineAppShell
import dev.grabline.app.ui.theme.GrabLineTheme
import dev.grabline.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val incoming = MutableStateFlow<IncomingRequest?>(null)
    private val clipboardOffer = MutableStateFlow<String?>(null)
    private val offers by lazy { ClipboardOffer(getSharedPreferences("clipboard_offer", Context.MODE_PRIVATE)) }

    /** Below Android 13 the chosen app language is applied here (see [AppLocale]). */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as GrabLineApp).container
        if (savedInstanceState == null) incoming.value = IntentLinkReader.read(this, intent)
        setContent {
            val settings = container.settings
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
                    remote = container.remote,
                    pairing = container.pairing,
                    settings = settings,
                    incoming = incoming,
                    onIncomingHandled = { incoming.value = null },
                    phone = container.phone,
                    clipboardOffer = clipboardOffer,
                    onClipboardHandled = { _ ->
                        clipboardOffer.value?.let(offers::markHandled)
                        clipboardOffer.value = null
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        IntentLinkReader.read(this, intent)?.let { incoming.value = it }
    }

    /** Android 10+ lets only a focused app read the clipboard, so check on focus. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || incoming.value != null) return
        val settings = (application as GrabLineApp).container.settings
        lifecycleScope.launch {
            val enabled = settings.observe(AppSettings.OFFER_COPIED_LINKS).first()
            val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()
            offers.offerFor(text, enabled)?.let { clipboardOffer.value = it }
        }
    }
}
