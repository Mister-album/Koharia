package koharia.storage

/** Direct child folders of [path], excluding the identity directory and anything outside it. */
internal suspend fun browseStorageDirectories(backend: LibraryStorageBackend, path: String): List<StorageEntry> {
    val normalized = StoragePath.normalize(path)
    if (!backend.stat(normalized).directory) throw StorageFailure(StorageFailure.Reason.NOT_FOUND)
    return backend.list(normalized).filter {
        it.directory && it.path != normalized && it.name != ".koharia" &&
            runCatching { StoragePath.normalize(it.path) == it.path && StoragePath.parent(it.path) == normalized }
                .getOrDefault(false)
    }.distinctBy { it.path }.sortedWith(compareBy<StorageEntry> { it.name.lowercase() }.thenBy { it.name })
}
