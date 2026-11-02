package live.hogar.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract

data class Track(val uri: Uri, val title: String, val folder: String)

data class SavedFolder(val uri: String, val name: String)

data class TrackMeta(val title: String?, val artist: String?, val album: String?, val cover: Bitmap?)

/** Recorre una carpeta elegida con el Storage Access Framework (USB, discos de red expuestos por el sistema). */
object LibraryScanner {
    private val extensions = setOf("flac", "m4a", "wav", "wave", "alac")
    private const val MAX_DEPTH = 6

    fun scan(context: Context, tree: Uri, maxTracks: Int = 3000): List<Track> {
        val resolver = context.contentResolver
        val out = ArrayList<Track>()

        fun walk(docId: String, path: String, depth: Int) {
            if (depth > MAX_DEPTH || out.size >= maxTracks) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val dirs = ArrayList<Pair<String, String>>()
            val files = ArrayList<Pair<String, String>>()
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        dirs += id to name
                    } else if (name.substringAfterLast('.', "").lowercase() in extensions) {
                        files += id to name
                    }
                }
            }
            files.sortedWith { a, b -> naturalCompare(a.second, b.second) }.forEach { (id, name) ->
                if (out.size < maxTracks) {
                    out += Track(
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                        title = name.substringBeforeLast('.'),
                        folder = path,
                    )
                }
            }
            dirs.sortedWith { a, b -> naturalCompare(a.second, b.second) }.forEach { (id, name) ->
                walk(id, name, depth + 1)
            }
        }

        val root = DocumentsContract.getTreeDocumentId(tree)
        walk(root, folderLabel(root), 0)
        return out
    }

    fun label(tree: Uri): String =
        runCatching { folderLabel(DocumentsContract.getTreeDocumentId(tree)) }.getOrDefault("Carpeta")
}

object MetaReader {
    fun read(context: Context, track: Track): TrackMeta {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, track.uri)
            TrackMeta(
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() },
                artist = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))?.takeIf { it.isNotBlank() },
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() },
                cover = r.embeddedPicture?.let { decodeScaled(it, 640) },
            )
        } catch (e: Exception) {
            TrackMeta(null, null, null, null)
        } finally {
            runCatching { r.release() }
        }
    }

    private fun decodeScaled(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxPx * 2) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** Carpetas elegidas (el permiso del URI se guarda con takePersistableUriPermission). */
class FolderStore(context: Context) {
    private val prefs = context.getSharedPreferences("hogar_player", Context.MODE_PRIVATE)

    fun load(): List<SavedFolder> =
        (prefs.getString(KEY, "") ?: "").split(SEP).filter { it.isNotBlank() }
            .map { SavedFolder(it, LibraryScanner.label(Uri.parse(it))) }

    fun remember(uri: Uri): List<SavedFolder> {
        val list = (listOf(uri.toString()) + load().map { it.uri }.filter { it != uri.toString() }).take(6)
        prefs.edit().putString(KEY, list.joinToString(SEP.toString())).apply()
        return load()
    }

    private companion object {
        const val KEY = "folders"
        const val SEP = '\u0001'
    }
}
