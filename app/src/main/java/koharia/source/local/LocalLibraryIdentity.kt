package koharia.source.local

/** Shelf membership can change without replacing the underlying library. Root rebinding cannot. */
internal fun LocalLibraryConfig.replacesIdentityOf(previous: LocalLibraryConfig): Boolean {
    if (!previous.setupCompleted) return false
    if (previous.managedBaseTreeUri.isNotBlank() && managedBaseTreeUri != previous.managedBaseTreeUri) return true
    val previousRoots = previous.roots.associateBy { it.id }
    if (roots.any { root ->
            previousRoots[root.id]?.let { old ->
                old.treeUri != root.treeUri || old.relativePath != root.relativePath
            } == true
        }
    ) {
        return true
    }
    return previous.roots.isNotEmpty() && roots.isNotEmpty() &&
        roots.none { root -> previous.roots.any { it.treeUri == root.treeUri && it.relativePath == root.relativePath } }
}
