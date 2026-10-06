package koharia.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.ErrnoException
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Private read-only descriptor bridge for the existing archive/PDF/Readium engines. */
class StorageDocumentsProvider : ContentProvider() {
    private val worker by lazy { HandlerThread("Storage descriptors").apply { start() } }
    override fun onCreate() = true

    /**
     * Resolves a stored document id. A stale, foreign, or reconfigured id describes a file that is no
     * longer reachable, so callers report a missing file instead of a provider crash.
     */
    private fun documentFile(uri: Uri): com.hippo.unifile.RemoteStorageFile? = runCatching {
        NetworkStorageRuntime.fromDocumentId(requireNotNull(context), DocumentsContract.getDocumentId(uri))
    }.getOrNull()

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val documentId = DocumentsContract.getDocumentId(uri)
        val file = documentFile(uri) ?: throw java.io.FileNotFoundException()
        if (!file.exists()) throw java.io.FileNotFoundException()
        val columns =
            projection ?: arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
                Document.COLUMN_LAST_MODIFIED,
                Document.COLUMN_FLAGS,
            )
        return MatrixCursor(columns).apply {
            newRow().also { row ->
                columns.forEach { column ->
                    row.add(
                        column,
                        when (column) {
                            Document.COLUMN_DOCUMENT_ID -> documentId
                            Document.COLUMN_DISPLAY_NAME -> file.name
                            Document.COLUMN_MIME_TYPE -> file.type
                            Document.COLUMN_SIZE -> file.length()
                            Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                            Document.COLUMN_FLAGS -> 0
                            else -> null
                        },
                    )
                }
            }
        }
    }
    override fun getType(uri: Uri): String? = documentFile(uri)?.type
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = openFile(uri, mode, null)
    override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val documentId = DocumentsContract.getDocumentId(uri)
        require(mode == "r")
        signal?.throwIfCanceled()
        val app = requireNotNull(context)
        val file = documentFile(uri) ?: throw java.io.FileNotFoundException()
        val entry = file.runtime.cached(file.storagePath) ?: throw java.io.FileNotFoundException()
        val lease = file.runtime.cache.acquire(entry)
        try {
            return app.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                object : ProxyFileDescriptorCallback() {
                    override fun onGetSize() = entry.size
                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                        signal?.throwIfCanceled()
                        val bytes = runBlocking(Dispatchers.IO) { file.runtime.cache.read(entry, offset, size) }
                        bytes.copyInto(data)
                        bytes.size
                    } catch (error: Exception) {
                        throw ErrnoException(
                            "storageRead",
                            if (error is StorageFailure &&
                                error.reason == StorageFailure.Reason.SPACE
                            ) {
                                OsConstants.ENOSPC
                            } else {
                                OsConstants.EIO
                            },
                            error,
                        )
                    }
                    override fun onRelease() = lease.close()
                },
                Handler(worker.looper),
            )
        } catch (error: Exception) {
            lease.close()
            throw error
        }
    }
}
