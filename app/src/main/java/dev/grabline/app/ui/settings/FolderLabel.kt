package dev.grabline.app.ui.settings

import android.net.Uri
import android.provider.DocumentsContract

/** A readable label for the save-folder setting: "Downloads/GrabLine" or a picked tree URI. */
fun folderLabel(value: String): String {
    if (!value.startsWith("content:")) return value
    return try {
        val id = DocumentsContract.getTreeDocumentId(Uri.parse(value))
        id.substringAfter(':', id).ifEmpty { id }
    } catch (e: Exception) {
        value
    }
}
