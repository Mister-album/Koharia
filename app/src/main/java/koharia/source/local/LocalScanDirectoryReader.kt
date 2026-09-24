package koharia.source.local

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.hippo.unifile.UniFile
import java.io.IOException

internal data class LocalScanFile(
    val file: UniFile,
    val name: String,
    val directory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

/** A scan-scoped snapshot; never reused after refresh or used for file mutations. */
internal class LocalScanDirectoryReader(private val context: Context) {
    val attributes = mutableMapOf<Uri, LocalScanFile>()
    private val directories = mutableMapOf<Uri, List<LocalScanFile>>()

    fun metadata(file: UniFile): LocalScanFile = attributes.getOrPut(file.uri) {
        LocalScanFile(file, file.name.orEmpty(), file.isDirectory, file.length(), file.lastModified())
    }

    fun list(directory: UniFile): List<LocalScanFile> = directories.getOrPut(directory.uri) {
        if (!metadata(directory).directory) throw IOException("Local scan target is not a directory")
        val uri = directory.uri
        val files = if (uri.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(uri)) {
            listDocuments(uri)
        } else {
            directory.listFiles()?.map(::metadata) ?: throw IOException("Unable to list local directory")
        }
        files.forEach { attributes[it.file.uri] = it }
        files
    }

    private fun listDocuments(uri: Uri): List<LocalScanFile> {
        val documentId = if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.getDocumentId(uri)
        } else {
            DocumentsContract.getTreeDocumentId(uri)
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return context.contentResolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
            if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
                throw IOException("Local directory listing is still loading")
            }
            val expectedCount = cursor.count
            val files = buildList {
                while (cursor.moveToNext()) {
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0))
                    val file = UniFile.fromUri(context, childUri)
                        ?: throw IOException("Unable to resolve local directory entry")
                    val directory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    add(
                        LocalScanFile(
                            file = file,
                            name = cursor.getString(1) ?: throw IOException("Missing local entry name"),
                            directory = directory,
                            sizeBytes = if (directory) -1L else cursor.getLong(3),
                            modifiedAt = cursor.getLong(4),
                        ),
                    )
                }
            }
            if (files.size != expectedCount) throw IOException("Incomplete local directory listing")
            files
        } ?: throw IOException("Unable to query local directory")
    }
}
