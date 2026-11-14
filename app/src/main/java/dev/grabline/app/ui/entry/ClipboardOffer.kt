package dev.grabline.app.ui.entry

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/** Pure decision logic, kept separate so it can be unit tested. */
object ClipboardOfferLogic {
    fun hash(link: String): String =
        MessageDigest.getInstance("SHA-256").digest(link.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** The link to offer, or null when disabled, nothing usable, or already offered or declined. */
    fun decide(clipboardText: String?, enabled: Boolean, lastHash: String?): String? {
        if (!enabled) return null
        val link = IncomingLinks.extract(clipboardText).firstOrNull() ?: return null
        return if (hash(link) == lastHash) null else link
    }
}

/**
 * Offers a copied link once per distinct copy. Call [offerFor] only while the app is in the
 * foreground (Android 10+ blocks background clipboard reads). Only a SHA-256 hash is stored.
 */
class ClipboardOffer(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
    )

    /** Returns the link to offer and remembers it, so the same copy is not offered again. */
    fun offerFor(clipboardText: String?, enabled: Boolean): String? {
        val link = ClipboardOfferLogic.decide(clipboardText, enabled, prefs.getString(KEY, null))
            ?: return null
        prefs.edit().putString(KEY, ClipboardOfferLogic.hash(link)).apply()
        return link
    }

    /** Marks a link as handled (sent or declined) without offering it. */
    fun markHandled(link: String) {
        prefs.edit().putString(KEY, ClipboardOfferLogic.hash(link)).apply()
    }

    private companion object {
        const val PREFS = "clipboard_offer"
        const val KEY = "last_hash"
    }
}
