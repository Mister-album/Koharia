package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.IOException

/** Keeps the atomic rename when supported, copying without consuming the source otherwise. */
internal fun finalizeDownloadedFile(parent: UniFile, file: UniFile, targetName: String): UniFile {
    if (tryRenameDownloadedEntry(parent, file, targetName)) {
        return parent.findFile(targetName) ?: file
    }
    val target = copyDownloadedEntry(parent, file, targetName)
    // A cleanup failure must not remove the completed copy.
    if (!file.delete()) throw IOException("Failed to remove temporary download: ${file.name}")
    return target
}

internal fun finalizeDownloadedDirectory(parent: UniFile, directory: UniFile, targetName: String): UniFile {
    if (tryRenameDownloadedEntry(parent, directory, targetName)) {
        return parent.findFile(targetName) ?: directory
    }
    val target = copyDownloadedEntry(parent, directory, targetName)
    if (!directory.delete()) throw IOException("Failed to remove temporary download: ${directory.name}")
    return target
}

private fun copyDownloadedEntry(parent: UniFile, source: UniFile, targetName: String): UniFile {
    val target = if (source.isDirectory) parent.createDirectory(targetName) else parent.createFile(targetName)
    target ?: throw IOException("Failed to create downloaded entry: $targetName")
    try {
        if (source.isDirectory) {
            val children = source.listFiles()
                ?: throw IOException("Failed to list downloaded directory: ${source.name}")
            children.forEach { child ->
                val name = child.name ?: throw IOException("Downloaded file has no name")
                copyDownloadedEntry(target, child, name)
            }
        } else {
            source.openInputStream().use { input ->
                target.openOutputStream().use { output -> input.copyTo(output) }
            }
        }
        return target
    } catch (error: Throwable) {
        try {
            deleteDownloadedTree(target)
        } catch (cleanupError: Throwable) {
            error.addSuppressed(cleanupError)
        }
        throw error
    }
}

internal fun deleteDownloadedTree(file: UniFile): Boolean {
    if (file.isDirectory) file.listFiles().orEmpty().forEach(::deleteDownloadedTree)
    return file.delete()
}

private fun tryRenameDownloadedEntry(parent: UniFile, file: UniFile, targetName: String): Boolean {
    // UniFile.renameTo changes the name within its current parent; it cannot move an entry.
    if (file.parentFile?.uri != parent.uri) return false
    return try {
        file.renameTo(targetName)
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        file.logcat(LogPriority.WARN, error) {
            "Downloader: SAF rename failed for ${file.name}, using copy fallback"
        }
        false
    }
}
