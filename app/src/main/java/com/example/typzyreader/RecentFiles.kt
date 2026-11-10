package com.example.typzyreader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A recently opened document: what to call it, where it is, and its cover as a small image. */
data class RecentFile(
    val uri: String,
    /** The document's own title if it sets one, else [fileName]. */
    val title: String,
    /** E.g. "book.typz". */
    val fileName: String,
    /** Where the file is, for people (see [RecentFiles.location]). */
    val location: String,
    /** The cover's image file, or null if the document has none (or it can't be decoded). */
    val cover: File?,
)

/** The documents opened most recently, newest first, kept between sessions. */
class RecentFiles(private val context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val coverDir = File(context.filesDir, COVER_DIR)

    val files: List<RecentFile>
        get() = try {
            val array = JSONArray(preferences.getString(KEY_FILES, null) ?: "[]")
            (0 until array.length()).map { i ->
                val entry = array.getJSONObject(i)
                RecentFile(
                    uri = entry.getString("uri"),
                    title = entry.getString("title"),
                    fileName = entry.getString("fileName"),
                    location = entry.getString("location"),
                    cover = entry.optString("cover").takeIf { it.isNotEmpty() }?.let { File(coverDir, it) },
                )
            }
        } catch (_: Exception) {
            emptyList() // e.g. written by a version that stored them differently
        }

    /**
     * Puts the document at [uri] first, with [cover] as its small cover image (none if null), and
     * forgets the oldest beyond [MAX_RECENT_FILES]. Returns the new list.
     */
    fun record(uri: Uri, title: String, fileName: String, cover: Bitmap?): List<RecentFile> {
        val coverFile = File(coverDir, coverName(uri.toString()))
        if (cover != null) {
            coverDir.mkdirs()
            coverFile.outputStream().use { cover.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } else {
            coverFile.delete()
        }
        val file = RecentFile(uri.toString(), title, fileName, location(context, uri, fileName), coverFile.takeIf { cover != null })
        val (kept, dropped) = withRecent(files, file)
        forget(dropped)
        save(kept)
        return kept
    }

    /** Forgets the documents at [uris]; returns the remaining list. */
    fun remove(uris: Set<String>): List<RecentFile> {
        val (dropped, kept) = files.partition { it.uri in uris }
        forget(dropped)
        save(kept)
        return kept
    }

    private fun save(files: List<RecentFile>) {
        val array = JSONArray()
        for (file in files) {
            array.put(
                JSONObject()
                    .put("uri", file.uri)
                    .put("title", file.title)
                    .put("fileName", file.fileName)
                    .put("location", file.location)
                    .put("cover", file.cover?.name.orEmpty())
            )
        }
        preferences.edit { putString(KEY_FILES, array.toString()) }
    }

    /** Deletes the covers of [files] and gives up the read access kept for them. */
    private fun forget(files: List<RecentFile>) {
        for (file in files) {
            file.cover?.delete()
            try {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(file.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // None was kept, e.g. for a file: URI or one opened from another app.
            }
        }
    }

    companion object {
        const val FILE_NAME = "recent_files"
        const val COVER_DIR = "recent-covers"
        private const val KEY_FILES = "files"

        const val MAX_RECENT_FILES = 3

        /**
         * [files] with [file] first (in place of an older entry for its URI) and at most [max]
         * entries, and the entries that left for good.
         */
        internal fun withRecent(
            files: List<RecentFile>,
            file: RecentFile,
            max: Int = MAX_RECENT_FILES,
        ): Pair<List<RecentFile>, List<RecentFile>> {
            val all = listOf(file) + files.filter { it.uri != file.uri }
            return all.take(max) to all.drop(max)
        }

        /** The cover image's file name for the document at [uri]: the same each time it's opened. */
        private fun coverName(uri: String): String =
            MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
                .joinToString("") { "%02x".format(it) } + ".png"

        /**
         * Where the document at [uri] is: its path when the URI tells it (e.g. a file picked from
         * the phone's storage), else the providing app's name and [fileName], e.g. "Drive: book.typz".
         */
        fun location(context: Context, uri: Uri, fileName: String): String {
            val documentId = try {
                if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.getDocumentId(uri) else null
            } catch (_: Exception) {
                null
            }
            filePath(uri.scheme, uri.authority, documentId, uri.path)?.let { return it }
            val provider = uri.authority?.let { context.packageManager.resolveContentProvider(it, 0) }
            val label = provider?.loadLabel(context.packageManager)?.toString()
            return if (label.isNullOrBlank()) fileName else "$label: $fileName"
        }

        /**
         * The file system path of a document, from its URI's parts, if they show it: a file: URI's
         * path, or a document ID of the phone's storage ("primary:Download/book.typz") or of the
         * Downloads app when it names the file ("raw:/storage/..."). Null otherwise.
         */
        internal fun filePath(scheme: String?, authority: String?, documentId: String?, path: String?): String? = when {
            scheme == "file" -> path
            documentId == null -> null
            authority == EXTERNAL_STORAGE && ':' in documentId -> {
                val (volume, relative) = documentId.split(':', limit = 2)
                val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
                if (relative.isEmpty()) root else "$root/$relative"
            }
            authority == DOWNLOADS && documentId.startsWith("raw:") -> documentId.removePrefix("raw:")
            else -> null
        }

        private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
        private const val DOWNLOADS = "com.android.providers.downloads.documents"
    }
}

/**
 * Whether the document at [uri] is still there (and readable): a missing file, or one whose
 * read access has been lost, can't be opened again.
 */
fun recentFileExists(context: Context, uri: Uri): Boolean = try {
    if (uri.scheme == "file") {
        uri.path?.let { File(it).isFile } == true
    } else {
        // Asks the provider about it, rather than reading it (which could download it).
        context.contentResolver.query(uri, null, null, null, null)?.use { it.moveToFirst() } == true
    }
} catch (_: Exception) {
    false // e.g. the provider reports it gone, or no longer grants access
}
