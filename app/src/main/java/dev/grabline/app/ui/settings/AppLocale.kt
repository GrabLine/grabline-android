package dev.grabline.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * The per-app language. Android 13+ keeps it in the system (LocaleManager, reached through
 * AppCompatDelegate). Below that, the chosen tag is mirrored into a small SharedPreferences
 * file and applied by wrapping the base context of the activity and application.
 */
object AppLocale {
    private const val PREFS = "app_locale"
    private const val KEY = "tag"
    const val SYSTEM = "system"

    fun stored(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    /** Wraps [base] with the stored locale on API 26-32; a no-op on 33+ and for "system". */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = stored(base)
        if (tag == SYSTEM) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale) // also sets the layout direction (Arabic and Urdu mirror)
        return base.createConfigurationContext(config)
    }

    /** Saves and applies [tag] ("system" or a BCP 47 tag). Recreates the activity below API 33. */
    fun apply(context: Context, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AppCompatDelegate.setApplicationLocales(
                if (tag == SYSTEM) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
            )
            return
        }
        val changed = stored(context) != tag
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
        if (!changed) return
        if (tag == SYSTEM) Locale.setDefault(systemLocale())
        context.findActivity()?.recreate()
    }

    private fun systemLocale(): Locale =
        android.content.res.Resources.getSystem().configuration.locales[0]

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
