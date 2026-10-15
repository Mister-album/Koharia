package koharia.source.local

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalLibraryIdentityTest {
    private val root = LocalLibraryRootConfig(id = "root", treeUri = "content://fixture/root")
    private val previous = LocalLibraryConfig(roots = listOf(root), setupCompleted = true)

    @Test
    fun `shelf changes and adding roots preserve library owner`() {
        assertFalse(previous.copy(metadataStorage = LocalMetadataStorage.DATABASE).replacesIdentityOf(previous))
        assertFalse(
            previous.copy(roots = listOf(root, root.copy(id = "extra", treeUri = "content://fixture/extra")))
                .replacesIdentityOf(previous),
        )
        assertFalse(previous.copy(roots = emptyList()).replacesIdentityOf(previous))
    }

    @Test
    fun `rebound root or managed base creates another owner`() {
        assertTrue(
            previous.copy(roots = listOf(root.copy(treeUri = "content://fixture/other")))
                .replacesIdentityOf(previous),
        )
        assertTrue(
            previous.copy(roots = listOf(root.copy(id = "other", treeUri = "content://fixture/other")))
                .replacesIdentityOf(previous),
        )
        assertTrue(
            previous.copy(managedBaseTreeUri = "content://fixture/new")
                .replacesIdentityOf(previous.copy(managedBaseTreeUri = "content://fixture/base")),
        )
    }
}
