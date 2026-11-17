package dev.grabline.app.ui.add

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.grabline.app.GrabLineApp
import dev.grabline.app.MainActivity
import dev.grabline.app.R
import dev.grabline.app.data.AppSettings
import dev.grabline.app.ui.entry.IncomingLinks
import dev.grabline.app.ui.settings.AppLocale
import dev.grabline.app.ui.theme.GrabLineTheme
import dev.grabline.app.ui.theme.ThemeMode

/**
 * A small Add sheet over whatever is on screen, filled with the link on the clipboard. The Quick Settings tile and the widget's
 * Paste link open it: Android 10 and later let only an app with focus read the clipboard, so the read waits for the first focus.
 */
class QuickAddActivity : ComponentActivity() {
    private val links = mutableStateOf<List<String>?>(null)
    private var read = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as GrabLineApp).container
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
                colours = colours,
            ) {
                links.value?.let { found ->
                    AddSheetRoute(
                        remote = container.remote,
                        settings = settings,
                        initialLinks = found,
                        sourceTitle = null,
                        phone = container.phone,
                        onDismiss = { finish() },
                        onSent = { message -> Toast.makeText(this@QuickAddActivity, message, Toast.LENGTH_SHORT).show() },
                        onPairClick = {
                            startActivity(Intent(this@QuickAddActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            finish()
                        },
                    )
                }
            }
        }
    }

    /** The clipboard is readable only now that this window has focus. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || read) return
        read = true
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        val found = IncomingLinks.extract(text)
        if (found.isEmpty()) {
            Toast.makeText(this, R.string.quick_add_nothing_copied, Toast.LENGTH_LONG).show()
            finish()
        } else {
            links.value = found
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, QuickAddActivity::class.java)
    }
}
