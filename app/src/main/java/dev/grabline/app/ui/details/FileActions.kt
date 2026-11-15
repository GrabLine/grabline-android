package dev.grabline.app.ui.details

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.grabline.app.R

/** Open and Share for a finished phone file (a content:// URI from the engine). */
object FileActions {
    private fun typeOf(context: Context, uri: Uri): String =
        runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "*/*"

    /** ACTION_VIEW with a read grant; false when there is no URI or no app to open it. */
    fun open(context: Context, fileUri: String?): Boolean {
        val uri = fileUri?.let(Uri::parse) ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, typeOf(context, uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** ACTION_SEND through the system chooser. */
    fun share(context: Context, fileUri: String?, name: String): Boolean {
        val uri = fileUri?.let(Uri::parse) ?: return false
        val send = Intent(Intent.ACTION_SEND)
            .setType(typeOf(context, uri))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, context.getString(R.string.file_share_title))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
