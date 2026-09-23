package eu.kanade.tachiyomi.data.backup.restore

import android.content.ContentResolver
import android.content.Context
import android.content.UriPermission
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import eu.kanade.presentation.more.settings.screen.data.DirectoryRestorePlan
import eu.kanade.presentation.more.settings.screen.data.RestoreDirectoryRequirement
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RestoreDirectoryAccessTest {
    @Test
    fun `parent tree grant resolves existing child directories without changing their roots`() {
        val providerAuthority = "com.android.externalstorage.documents"
        val first = "content://$providerAuthority/tree/primary%3Akoharia%2Fbooks/" +
            "document/primary%3Akoharia%2Fbooks"
        val second = "content://$providerAuthority/tree/primary%3Akoharia%2Fcomics/" +
            "document/primary%3Akoharia%2Fcomics"
        val parentUri = mockk<Uri> {
            every { scheme } returns "content"
            every { authority } returns providerAuthority
        }
        val firstUri = mockk<Uri> {
            every { scheme } returns "content"
            every { authority } returns providerAuthority
        }
        val secondUri = mockk<Uri> {
            every { scheme } returns "content"
            every { authority } returns providerAuthority
        }
        val firstAccessibleUri = mockk<Uri>()
        val secondAccessibleUri = mockk<Uri>()
        val permission = mockk<UriPermission> {
            every { uri } returns parentUri
            every { isReadPermission } returns true
            every { isWritePermission } returns true
        }
        val cursor = mockk<Cursor>(relaxed = true) {
            every { moveToFirst() } returns true
            every { getString(0) } returns DocumentsContract.Document.MIME_TYPE_DIR
        }
        var secondExists = true
        val resolver = mockk<ContentResolver> {
            every { persistedUriPermissions } returns listOf(permission)
            every { query(any<Uri>(), any<Array<String>>(), null, null, null) } answers {
                when (firstArg<Uri>()) {
                    firstAccessibleUri -> cursor
                    secondAccessibleUri -> cursor.takeIf { secondExists }
                    else -> null
                }
            }
        }
        val context = mockk<Context> {
            every { contentResolver } returns resolver
        }
        mockkStatic(Uri::class, DocumentsContract::class)
        try {
            every { Uri.parse(first) } returns firstUri
            every { Uri.parse(second) } returns secondUri
            every { DocumentsContract.getDocumentId(firstUri) } returns "primary:koharia/books"
            every { DocumentsContract.getDocumentId(secondUri) } returns "primary:koharia/comics"
            every { DocumentsContract.getTreeDocumentId(parentUri) } returns "primary:koharia"
            every {
                DocumentsContract.buildDocumentUriUsingTree(parentUri, "primary:koharia/books")
            } returns firstAccessibleUri
            every {
                DocumentsContract.buildDocumentUriUsingTree(parentUri, "primary:koharia/comics")
            } returns secondAccessibleUri

            assertEquals(firstAccessibleUri.toString(), RestoreDirectoryAccess.resolveGrantedUri(context, first, true))
            assertEquals(
                secondAccessibleUri.toString(),
                RestoreDirectoryAccess.resolveGrantedUri(context, second, true),
            )
            val requirements = listOf(first, second).map { original ->
                RestoreDirectoryRequirement(
                    originalUri = original,
                    displayPath = "",
                    forAppSettings = false,
                    forConnectionSettings = true,
                    writeForAppSettings = false,
                    writeForConnectionSettings = true,
                )
            }
            assertEquals(
                mapOf(first to firstAccessibleUri.toString(), second to secondAccessibleUri.toString()),
                DirectoryRestorePlan.existingBindings(context, requirements, RestoreOptions()),
            )
            assertTrue(RestoreDirectoryAccess.hasPersistedPermission(context, first, true))
            assertEquals("/storage/emulated/0/koharia/books", RestoreDirectoryAccess.displayPath(first))

            secondExists = false
            assertNull(RestoreDirectoryAccess.resolveGrantedUri(context, second, true))

            every { permission.isWritePermission } returns false
            assertFalse(RestoreDirectoryAccess.hasPersistedPermission(context, first, true))
            assertTrue(RestoreDirectoryAccess.hasPersistedPermission(context, first, false))
        } finally {
            unmockkStatic(Uri::class, DocumentsContract::class)
        }
    }
}
