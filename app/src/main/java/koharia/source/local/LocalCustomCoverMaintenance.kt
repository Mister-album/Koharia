package koharia.source.local

import android.content.Context
import android.provider.DocumentsContract
import com.hippo.unifile.UniFile
import java.io.File
import java.io.IOException

internal fun isRemovedLocalCover(
    sourceId: Long,
    url: String,
    config: LocalLibraryConfig,
    entryExists: (LocalLibraryRootConfig, String) -> Boolean?,
): Boolean {
    if (!config.setupCompleted) return false
    val location = LocalLibraryLocator.location(url, sourceId) ?: return false
    val rootId = location.rootId ?: return false
    val root = config.roots.firstOrNull { it.id == rootId } ?: return true
    return entryExists(root, location.relativePath) == false
}

/** Only a successful listing can establish absence; inaccessible storage remains unknown. */
internal fun localCoverEntryExists(context: Context, root: UniFile, path: String): Boolean? = runCatching {
    if (!root.isDirectory || !root.canRead()) return@runCatching null
    if (path == LocalLibraryLocator.ROOT_DIRECTORY_ENTRY) return@runCatching true
    var parent = root
    for (segment in path.split('/')) {
        require(segment.isNotBlank() && segment != "." && segment != ".." && '\\' !in segment)
        check(parent.isDirectory && parent.canRead())
        val names = when (parent.uri.scheme) {
            "file" -> File(checkNotNull(parent.uri.path)).list()?.toList()
                ?: throw IOException("Unable to list local directory")
            "content" -> {
                val uri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    parent.uri,
                    DocumentsContract.getDocumentId(parent.uri),
                )
                val column = DocumentsContract.Document.COLUMN_DISPLAY_NAME
                val cursor = context.contentResolver.query(uri, arrayOf(column), null, null, null)
                    ?: throw IOException("Unable to list document directory")
                cursor.use {
                    check(!it.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false))
                    check(it.extras.getString(DocumentsContract.EXTRA_ERROR) == null)
                    buildList {
                        val index = it.getColumnIndexOrThrow(column)
                        while (it.moveToNext()) add(it.getString(index))
                    }
                }
            }
            else -> throw IOException("Unsupported local directory")
        }
        check(root.isDirectory && root.canRead() && parent.isDirectory && parent.canRead())
        if (segment !in names) return@runCatching false
        parent = parent.findFile(segment) ?: throw IOException("Directory changed during cover inspection")
    }
    true
}.getOrNull()
