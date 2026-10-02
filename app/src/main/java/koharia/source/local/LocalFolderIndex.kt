package koharia.source.local

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
internal data class LocalFolderMarker(
    val version: Int = 1,
    val id: String = UUID.randomUUID().toString(),
    val imageComic: Boolean = false,
    val imageComicOverride: Boolean? = null,
)

/** Reconcile only evidence of identity, never a file's name, size or sampled contents. */
internal fun reconcileLocalFolders(
    scanned: List<LocalLibraryItem>,
    previous: List<LocalLibraryItem>,
): List<LocalLibraryItem> {
    val priorItems = previous.map { item ->
        val identity = item.documentIdentity
        if (item.isVirtualImageSeries() && identity != null && !identity.endsWith(":image-series")) {
            item.copy(documentIdentity = "$identity:image-series")
        } else {
            item
        }
    }
    val used = mutableSetOf<String>()
    val oldPaths = priorItems.sortedByDescending { it.missing }.associateBy { it.rootId to it.relativePath }
    val documents = priorItems.filter { it.documentIdentity != null }.groupBy { it.rootId to it.documentIdentity }
    val markers = priorItems.filter { it.folderIdentity != null }.groupBy { it.rootId to it.folderIdentity }
    val currentMarkers = scanned.filter { it.folderIdentity != null }.groupBy { it.rootId to it.folderIdentity }
    val currentDocuments = scanned.filter { it.documentIdentity != null }.groupBy { it.rootId to it.documentIdentity }
    val movedDirectories = mutableListOf<Pair<LocalLibraryItem, LocalLibraryItem>>()
    return scanned.sortedBy { it.relativePath.count { c -> c == '/' } }.map { item ->
        val atPath = oldPaths[item.rootId to item.relativePath]?.takeUnless { old ->
            (
                old.documentIdentity != null && item.documentIdentity != null &&
                    old.documentIdentity != item.documentIdentity
                ) ||
                (old.folderIdentity != null && item.folderIdentity != null && old.folderIdentity != item.folderIdentity)
        }
        val marker = item.folderIdentity?.let { value ->
            markers[item.rootId to value]?.singleOrNull()?.takeIf {
                currentMarkers[item.rootId to value]?.size == 1
            }
        }
        val document = item.documentIdentity?.let { value ->
            documents[item.rootId to value]?.singleOrNull()?.takeIf {
                currentDocuments[item.rootId to value]?.size == 1
            }
        }
        val carriedChild = movedDirectories.asReversed().firstNotNullOfOrNull { (old, current) ->
            if (item.rootId == current.rootId && item.relativePath.startsWith("${current.relativePath}/")) {
                oldPaths[item.rootId to (old.relativePath + item.relativePath.removePrefix(current.relativePath))]
            } else {
                null
            }
        }
        val matched = (marker ?: document ?: carriedChild ?: atPath)?.takeIf {
            it.itemKey !in used && it.kind == item.kind && it.isVirtualImageSeries() == item.isVirtualImageSeries()
        }
        val path = matched?.locatorPath ?: ".koharia/nodes/${UUID.randomUUID()}"
        val result = item.copy(
            itemKey = LocalLibraryLocator.itemKey(item.rootId, path),
            locatorPath = path,
            imageComic = matched?.imageComic ?: item.imageComic,
            imageComicOverride = matched?.imageComicOverride ?: item.imageComicOverride,
        )
        if (matched != null) {
            used += matched.itemKey
            if (item.format == "directory" && matched.relativePath != item.relativePath) {
                movedDirectories += matched to result
            }
        }
        result
    }
}

internal fun LocalLibraryItem.isInFolder(rootId: String?, parentPath: String?, recursive: Boolean): Boolean {
    if (rootId != null && this.rootId != rootId) return false
    val parent = parentPath.orEmpty()
    return if (recursive) {
        parent.isEmpty() || relativePath.startsWith("$parent/")
    } else {
        relativePath.substringBeforeLast('/', "") == parent
    }
}

internal fun localImageSeriesPhysicalPath(path: String): String? {
    val parent = path.substringBeforeLast('/', "")
    val name = path.substringAfterLast('/')
    return when {
        name == ".koharia-image-series" -> parent
        name.startsWith(".koharia-image-series-") -> listOf(
            parent,
            name.removePrefix(".koharia-image-series-"),
        ).filter(String::isNotBlank).joinToString("/")
        else -> null
    }
}

internal fun isLocalAuxiliaryFile(name: String): Boolean {
    val lower = name.lowercase()
    return lower.startsWith('.') || lower.endsWith(".opf") || lower.endsWith("comicinfo.xml") ||
        lower in setOf("thumbs.db", "ehthumbs.db", "desktop.ini") ||
        lower.substringBeforeLast('.') in setOf("cover", "folder", "poster", "!cover")
}

internal fun withLocalFolderFingerprints(items: List<LocalLibraryItem>): List<LocalLibraryItem> {
    val children = items.groupBy { it.rootId to it.relativePath.substringBeforeLast('/', "") }
    val updated = items.associateBy { it.itemKey }.toMutableMap()
    items.filter { it.kind == LocalLibraryItem.Kind.FOLDER }
        .sortedByDescending { it.relativePath.count { character -> character == '/' } }
        .forEach { folder ->
            val signature = children[folder.rootId to folder.relativePath].orEmpty()
                .sortedBy { it.relativePath }
                .joinToString("\u0000") { child -> "${child.itemKey}:${updated[child.itemKey]?.fingerprint}" }
            updated[folder.itemKey] = folder.copy(fingerprint = metadataRevision("${folder.fingerprint}:$signature"))
        }
    return items.map { updated.getValue(it.itemKey) }
}

internal fun LocalLibraryIndex.rebindFolderLocations(
    rootIds: Set<String>,
    markMissing: Boolean,
): LocalLibraryIndex = copy(
    items = items.map { item ->
        if (item.rootId in rootIds && item.locatorPath.startsWith(".koharia/nodes/")) {
            item.copy(documentIdentity = null, folderIdentity = null, missing = markMissing || item.missing)
        } else {
            item
        }
    },
)
