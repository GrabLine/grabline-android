package dev.grabline.app.ui.entry

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns

/** A .torrent file handed to the app. Bytes are read later. */
data class TorrentFileRef(val uri: String, val displayName: String?)

data class IncomingRequest(
    val links: List<String>,
    val sourceTitle: String? = null,
    val torrentFile: TorrentFileRef? = null,
)

object IntentLinkReader {
    /** Returns null when the intent carries nothing GrabLine can use. */
    fun read(context: Context, intent: Intent?): IncomingRequest? {
        intent ?: return null
        val title = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf { it.isNotEmpty() }
        val result = when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                val links = IncomingLinks.extract(text)
                val stream = streamUri(intent)
                val torrent = if (links.isEmpty() && stream != null) torrentRef(context, stream) else null
                IncomingRequest(links, title, torrent)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val texts = intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.joinToString("\n")
                    ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                IncomingRequest(IncomingLinks.extract(texts), title)
            }
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return null
                if (data.scheme.equals("magnet", ignoreCase = true)) {
                    IncomingRequest(IncomingLinks.extract(data.toString()), title)
                } else {
                    val ref = torrentRef(context, data)
                    if (ref != null) IncomingRequest(emptyList(), title, ref) else return null
                }
            }
            else -> return null
        }
        return result.takeUnless { it.links.isEmpty() && it.torrentFile == null }
    }

    @Suppress("DEPRECATION")
    private fun streamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    private fun torrentRef(context: Context, uri: Uri): TorrentFileRef? {
        val scheme = uri.scheme?.lowercase()
        if (scheme != ContentResolver.SCHEME_CONTENT && scheme != ContentResolver.SCHEME_FILE) return null
        val name = displayName(context, uri)
        val looksTorrent = name?.endsWith(".torrent", true) == true ||
            uri.lastPathSegment?.endsWith(".torrent", true) == true ||
            context.contentResolver.getType(uri) == "application/x-bittorrent"
        return if (looksTorrent) TorrentFileRef(uri.toString(), name) else null
    }

    private fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.lastPathSegment
        val queried = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        return queried ?: uri.lastPathSegment
    }
}
