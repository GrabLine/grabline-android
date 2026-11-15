package dev.grabline.app.ui.details

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.grabline.app.R
import java.io.File

/** Open and Share for a finished phone file (a content:// URI from the engine, or file:// on Android 8 and 9). */
object FileActions {
    /** content:// as is; file:// (API 26-28 saves to Downloads directly) goes through the app's FileProvider. */
    private fun shareable(context: Context, fileUri: String?): Uri? {
        val uri = fileUri?.let(Uri::parse) ?: return null
        if (uri.scheme != "file") return uri
        val path = uri.path ?: return null
        return runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", File(path)) }.getOrNull()
    }

    private fun typeOf(context: Context, uri: Uri): String =
        runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "*/*"

    /** ACTION_VIEW with a read grant, or null when there is no usable URI. */
    fun openIntent(context: Context, fileUri: String?): Intent? {
        val uri = shareable(context, fileUri) ?: return null
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, typeOf(context, uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** ACTION_SEND through the system chooser, or null when there is no usable URI. */
    fun shareIntent(context: Context, fileUri: String?, name: String): Intent? {
        val uri = shareable(context, fileUri) ?: return null
        val send = Intent(Intent.ACTION_SEND)
            .setType(typeOf(context, uri))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, context.getString(R.string.file_share_title))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** false when there is no URI or no app to open it. */
    fun open(context: Context, fileUri: String?): Boolean = start(context, openIntent(context, fileUri))

    fun share(context: Context, fileUri: String?, name: String): Boolean = start(context, shareIntent(context, fileUri, name))

    private fun start(context: Context, intent: Intent?): Boolean {
        intent ?: return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
