package dev.grabline.app.engine

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scratch files in app-private storage; finished files go to Downloads/GrabLine through MediaStore (API 29+),
 * the public Downloads folder (API 26-28, needs WRITE_EXTERNAL_STORAGE maxSdk 28), or a SAF tree.
 * The copy happens only after the engine verified the scratch file's size (rule 9).
 */
class AndroidPhoneStorage(context: Context) : PhoneStorage {
    private val app = context.applicationContext
    private val scratchDir = File(app.noBackupFilesDir, "phone-downloads").apply { mkdirs() }

    override fun scratchFile(jobId: Long) = File(scratchDir, "job-$jobId.part")

    override fun deleteScratch(jobId: Long) {
        scratchFile(jobId).delete()
    }

    override fun freeBytes(folderUri: String?): Long =
        runCatching { StatFs(scratchDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    override suspend fun existingNames(folderUri: String?): Set<String> = withContext(Dispatchers.IO) {
        runCatching {
            when {
                folderUri != null -> safNames(Uri.parse(folderUri))
                Build.VERSION.SDK_INT >= 29 -> mediaStoreNames()
                else -> legacyDir().list()?.toSet().orEmpty()
            }
        }.getOrDefault(emptySet())
    }

    override suspend fun publish(folderUri: String?, name: String, scratch: File): String = withContext(Dispatchers.IO) {
        try {
            when {
                folderUri != null -> publishSaf(Uri.parse(folderUri), name, scratch)
                Build.VERSION.SDK_INT >= 29 -> publishMediaStore(name, scratch)
                else -> publishLegacy(name, scratch)
            }
        } catch (e: SecurityException) {
            throw FolderUnavailableException(e)
        } catch (e: FileNotFoundException) {
            throw FolderUnavailableException(e)
        }
    }

    private fun mime(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    private fun copy(scratch: File, out: OutputStream) {
        out.use { o -> scratch.inputStream().use { it.copyTo(o, 64 * 1024) } }
    }

    private fun checkSize(expected: Long, input: InputStream?): Unit {
        val actual = input?.use { s -> var n = 0L; val b = ByteArray(64 * 1024); while (true) { val r = s.read(b); if (r < 0) break; n += r }; n }
        if (actual != null && actual != expected) throw IOException("Copied $actual of $expected bytes")
    }

    private fun mediaStoreNames(): Set<String> {
        val out = HashSet<String>()
        app.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf("$RELATIVE/"), null,
        )?.use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    private fun publishMediaStore(name: String, scratch: File): String {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw FolderUnavailableException()
        try {
            copy(scratch, resolver.openOutputStream(uri) ?: throw FolderUnavailableException())
            checkSize(scratch.length(), resolver.openInputStream(uri))
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri.toString()
    }

    @Suppress("DEPRECATION")
    private fun legacyDir() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GrabLine")

    private fun publishLegacy(name: String, scratch: File): String {
        val dir = legacyDir()
        if (!dir.isDirectory && !dir.mkdirs()) throw FolderUnavailableException()
        val target = File(dir, uniqueName(name, dir.list()?.toSet().orEmpty()))
        val part = File(dir, "${target.name}.part")
        try {
            copy(scratch, part.outputStream())
            if (part.length() != scratch.length() || !part.renameTo(target)) throw IOException("Could not finish the file")
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
        MediaScannerConnection.scanFile(app, arrayOf(target.path), null, null)
        return Uri.fromFile(target).toString()
    }

    private fun safParent(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun safNames(tree: Uri): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val out = HashSet<String>()
        app.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    private fun publishSaf(tree: Uri, name: String, scratch: File): String {
        val resolver = app.contentResolver
        val doc = DocumentsContract.createDocument(resolver, safParent(tree), mime(name), name)
            ?: throw FolderUnavailableException()
        try {
            copy(scratch, resolver.openOutputStream(doc) ?: throw FolderUnavailableException())
            checkSize(scratch.length(), resolver.openInputStream(doc))
        } catch (e: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw e
        }
        return doc.toString()
    }

    private companion object {
        const val RELATIVE = "Download/GrabLine"
    }
}
