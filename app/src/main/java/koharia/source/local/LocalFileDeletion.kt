package koharia.source.local

import android.provider.DocumentsContract
import com.hippo.unifile.UniFile
import tachiyomi.core.common.util.system.ImageUtil
import java.io.File
import java.io.IOException

/** A snapshot of exactly what the user confirms, resolved again before every mutation. */
internal class LocalFileDeletion private constructor(
    private val root: UniFile,
    private val files: List<Target>,
    private val directories: List<Target>,
    private val imageDirectory: String?,
) {
    private val rootIdentity = localDeletionIdentity(root)
    val fileCount: Int get() = files.size

    fun delete() {
        check(localDeletionIdentity(root) == rootIdentity)
        files.forEach { target ->
            val file = resolve(root, target.path) ?: return@forEach
            check(!file.isDirectory && file.uri.toString() == target.uri)
            check(file.length() == target.size && file.lastModified() == target.modifiedAt)
        }
        files.forEach { target ->
            val file = resolve(root, target.path) ?: return@forEach
            check(!file.isDirectory && file.uri.toString() == target.uri)
            check(file.length() == target.size && file.lastModified() == target.modifiedAt)
            if (!deleteOne(file)) throw IOException("Unable to delete local file")
        }
        directories.forEach { target ->
            val directory = resolve(root, target.path) ?: return@forEach
            check(directory.isDirectory && directory.uri.toString() == target.uri)
            // Never ask a provider to recursively remove files added after confirmation.
            check(children(directory).isEmpty())
            if (!deleteOne(directory)) throw IOException("Unable to delete empty local directory")
        }
        imageDirectory?.let { path ->
            resolve(root, path)?.let { check(children(it).none(::isImage)) }
        }
    }

    private data class Target(val path: String, val uri: String, val size: Long, val modifiedAt: Long)

    companion object {
        fun prepare(
            root: UniFile,
            path: String,
            series: Boolean,
            protectedDirectories: Set<String>,
        ): LocalFileDeletion {
            val relative = path.takeUnless { it == LocalLibraryLocator.ROOT_DIRECTORY_ENTRY }.orEmpty()
            val entry = resolve(root, relative) ?: throw IOException("Local file is unavailable")
            val files = mutableListOf<Target>()
            val directories = mutableListOf<Target>()
            val visited = mutableSetOf<String>()

            fun visit(currentPath: String, file: UniFile) {
                checkSafeFile(root, file)
                check(visited.add(file.uri.toString()))
                val target = Target(currentPath, file.uri.toString(), file.length(), file.lastModified())
                if (file.isDirectory) {
                    check(currentPath.isNotEmpty() && localDeletionIdentity(file) !in protectedDirectories)
                    children(file).forEach { child -> visit(childPath(currentPath, checkNotNull(child.name)), child) }
                    directories += target
                } else {
                    files += target
                }
            }

            val imageDirectory = if (!series && entry.isDirectory) {
                children(entry).filter(::isImage).forEach { child ->
                    visit(childPath(relative, checkNotNull(child.name)), child)
                }
                check(files.isNotEmpty())
                relative
            } else {
                visit(relative, entry)
                null
            }
            return LocalFileDeletion(root, files, directories, imageDirectory)
        }

        private fun childPath(parent: String, name: String): String {
            check(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name)
            return if (parent.isEmpty()) name else "$parent/$name"
        }

        private fun resolve(root: UniFile, path: String): UniFile? {
            check(root.isDirectory)
            checkSafeFile(root, root)
            if (path.isEmpty()) return root
            return path.split('/').fold(root) { parent, segment ->
                childPath("", segment)
                check(parent.canRead())
                val child = parent.findFile(segment)
                    ?: children(parent).firstOrNull { it.name == segment }
                    ?: return null
                checkSafeFile(root, child)
                child
            }
        }

        private fun checkSafeFile(root: UniFile, file: UniFile) {
            if (root.uri.scheme != "file") return
            val base = File(checkNotNull(root.uri.path)).absoluteFile
            val candidate = File(checkNotNull(file.uri.path)).absoluteFile
            check(candidate.toPath().startsWith(base.toPath()))
            check(candidate.canonicalFile == File(base.canonicalFile, candidate.relativeTo(base).path))
        }

        private fun children(directory: UniFile): List<UniFile> {
            check(directory.canRead())
            return directory.listFiles()?.toList() ?: throw IOException("Unable to list local directory")
        }

        private fun deleteOne(file: UniFile): Boolean = if (file.uri.scheme == "file") {
            // UniFile.delete() recurses on raw paths; File.delete() refuses non-empty directories.
            File(checkNotNull(file.uri.path)).delete()
        } else {
            file.delete()
        }

        private fun isImage(file: UniFile): Boolean =
            !file.isDirectory && ImageUtil.isImage(file.name) { file.openInputStream() }
    }
}

internal fun localDeletionIdentity(file: UniFile): String = when (file.uri.scheme) {
    "file" -> File(checkNotNull(file.uri.path)).canonicalPath
    "content" -> "${file.uri.authority}:${DocumentsContract.getDocumentId(file.uri)}"
    else -> error("Unsupported local file URI")
}

internal data class LocalLibraryDeletionEntry(
    val manga: tachiyomi.domain.manga.model.Manga,
    val root: LocalLibraryRootConfig,
    val item: LocalLibraryItem,
    val deletion: LocalFileDeletion,
)

internal data class LocalLibraryDeletionResult(
    val deleted: List<tachiyomi.domain.manga.model.Manga>,
    val failed: List<tachiyomi.domain.manga.model.Manga>,
)

internal data class LocalLibraryDeletionPlan(
    val roots: List<LocalLibraryRootConfig>,
    val entries: List<LocalLibraryDeletionEntry>,
)
