package eu.kanade.tachiyomi.data.backup.restore

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

internal object RestoreDirectoryAccess {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    fun hasPersistedPermission(context: Context, uri: String, requireWrite: Boolean): Boolean =
        resolveGrantedUri(context, uri, requireWrite) != null

    fun resolveGrantedUri(context: Context, uri: String, requireWrite: Boolean): String? {
        val target = Uri.parse(uri)
        if (target.scheme == ContentResolver.SCHEME_FILE) {
            val directory = target.path?.let(::File) ?: return null
            return uri.takeIf {
                directory.isDirectory && directory.canRead() && (!requireWrite || directory.canWrite())
            }
        }
        if (target.scheme != ContentResolver.SCHEME_CONTENT) return null
        val documentId = directoryId(target) ?: return null
        val resolver = context.contentResolver
        return resolver.persistedUriPermissions.asSequence().mapNotNull { permission ->
            val grant = permission.uri
            if (!permission.isReadPermission || (requireWrite && !permission.isWritePermission) ||
                grant.scheme != target.scheme || grant.authority != target.authority
            ) {
                return@mapNotNull null
            }
            val candidate = runCatching {
                DocumentsContract.getTreeDocumentId(grant)
                DocumentsContract.buildDocumentUriUsingTree(grant, documentId)
            }.getOrNull() ?: grant.takeIf { it == target }
            candidate?.takeIf { isDirectory(context, it) }?.toString()
        }.firstOrNull()
    }

    private fun isDirectory(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
            null,
            null,
            null,
        )?.use { cursor ->
            cursor.moveToFirst() && cursor.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR
        } == true
    }.getOrDefault(false)

    fun displayPath(uri: String, savedPath: String = ""): String {
        if (savedPath.isNotBlank() && !savedPath.startsWith("content://")) return savedPath
        val parsed = Uri.parse(uri)
        if (parsed.scheme == ContentResolver.SCHEME_FILE) return parsed.path ?: uri
        val documentId = directoryId(parsed)
            ?: return uri
        return when {
            documentId.startsWith("raw:", ignoreCase = true) -> documentId.substringAfter(':')
            parsed.authority == EXTERNAL_STORAGE_AUTHORITY -> {
                val volume = documentId.substringBefore(':')
                val relativePath = documentId.substringAfter(':', missingDelimiterValue = "").trim('/')
                val root = if (volume.equals("primary", ignoreCase = true)) {
                    "/storage/emulated/0"
                } else {
                    "/storage/$volume"
                }
                if (relativePath.isBlank()) root else "$root/$relativePath"
            }
            else -> documentId
        }
    }

    fun sameDirectory(first: String, second: String): Boolean {
        if (first == second) return true
        val firstUri = Uri.parse(first)
        val secondUri = Uri.parse(second)
        return firstUri.scheme == ContentResolver.SCHEME_CONTENT && firstUri.scheme == secondUri.scheme &&
            firstUri.authority == secondUri.authority &&
            directoryId(firstUri)?.let { it == directoryId(secondUri) } == true
    }

    private fun directoryId(uri: Uri): String? =
        runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
}
