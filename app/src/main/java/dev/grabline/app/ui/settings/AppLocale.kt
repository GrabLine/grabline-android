package dev.grabline.app.ui.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The per-app language. Android 13+ keeps it in the system (LocaleManager, called directly; the
 * AppCompat helper needs an AppCompatActivity). Below that, the chosen tag is mirrored into a small SharedPreferences
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
            // AppCompatDelegate.setApplicationLocales does nothing unless an AppCompatActivity is alive, and this
            // app's screens are Compose ComponentActivities, so ask the system's LocaleManager directly.
            val manager = context.getSystemService(LocaleManager::class.java)
            manager?.applicationLocales =
                if (tag == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
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
