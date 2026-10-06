package koharia.source.local

import android.content.Context
import android.provider.DocumentsContract
import com.hippo.unifile.UniFile
import kotlinx.serialization.Serializable
import java.io.File

internal fun validateLocalName(name: String) {
    require(name.isNotBlank() && name == name.trim() && name !in setOf(".", ".."))
    require(name.none { it == '/' || it == '\\' || it.code < 32 } && !name.startsWith('.'))
}

internal fun resolveLocalChild(root: UniFile, path: String): UniFile {
    var result = root
    for (segment in path.split('/').filter(String::isNotEmpty)) {
        require(segment != "." && segment != "..")
        result = checkNotNull(result.findFile(segment))
        if (root.uri.scheme == "file") {
            val base = File(checkNotNull(root.uri.path)).canonicalFile
            val child = File(checkNotNull(result.uri.path))
            check(child.canonicalFile.toPath().startsWith(base.toPath()))
            check(child.canonicalFile == child.absoluteFile) { "Symbolic links cannot be managed" }
        }
    }
    return result
}

internal fun canMoveLocalFile(context: Context, file: UniFile): Boolean {
    if (file is com.hippo.unifile.RemoteStorageFile) return file.canWrite()
    if (file.uri.scheme == "file") return file.canWrite()
    val column = DocumentsContract.Document.COLUMN_FLAGS
    return context.contentResolver.query(file.uri, arrayOf(column), null, null, null)?.use {
        it.moveToFirst() && it.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_MOVE != 0
    } == true
}

internal fun moveLocalFile(context: Context, file: UniFile, from: UniFile, to: UniFile): UniFile {
    check(to.findFile(checkNotNull(file.name)) == null) { "Destination already exists" }
    if (file is com.hippo.unifile.RemoteStorageFile && to is com.hippo.unifile.RemoteStorageFile) {
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { file.moveTo(to) }
    }
    if (file.uri.scheme == "file") {
        val target = File(checkNotNull(to.uri.path), checkNotNull(file.name))
        check(File(checkNotNull(file.uri.path)).renameTo(target)) { "Unable to move file" }
        return checkNotNull(UniFile.fromFile(target))
    }
    check(canMoveLocalFile(context, file)) { "This provider does not support moving files" }
    val uri = checkNotNull(DocumentsContract.moveDocument(context.contentResolver, file.uri, from.uri, to.uri))
    return checkNotNull(UniFile.fromUri(context, uri))
}

@Serializable
internal data class LocalFolderMutation(
    val rootId: String,
    val rootKey: String,
    val itemKey: String,
    val directory: Boolean,
    val steps: List<LocalFolderMutationStep>,
)

@Serializable
internal data class LocalFolderMutationStep(
    val from: String,
    val to: String,
    val uri: String,
    val size: Long,
    val modifiedAt: Long,
    val completed: Boolean = false,
)

internal fun localMutationStep(root: UniFile, from: String, to: String): LocalFolderMutationStep {
    val source = resolveLocalChild(root, from)
    val parent = resolveLocalChild(root, to.substringBeforeLast('/', ""))
    check(parent.findFile(to.substringAfterLast('/')) == null) { "Destination already exists" }
    return LocalFolderMutationStep(from, to, source.uri.toString(), source.length(), source.lastModified())
}

internal fun executeLocalMutationStep(context: Context, root: UniFile, step: LocalFolderMutationStep): UniFile {
    val file = resolveLocalChild(root, step.from)
    check(file.uri.toString() == step.uri && file.length() == step.size && file.lastModified() == step.modifiedAt) {
        "File changed since operation was prepared"
    }
    val fromPath = step.from.substringBeforeLast('/', "")
    val toPath = step.to.substringBeforeLast('/', "")
    val to = resolveLocalChild(root, toPath)
    check(to.findFile(step.to.substringAfterLast('/')) == null)
    return if (fromPath == toPath) {
        check(file.renameTo(step.to.substringAfterLast('/')))
        checkNotNull(to.findFile(step.to.substringAfterLast('/')))
    } else {
        check(step.from.substringAfterLast('/') == step.to.substringAfterLast('/'))
        moveLocalFile(context, file, resolveLocalChild(root, fromPath), to)
    }
}
