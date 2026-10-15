package koharia.importing

import android.content.ClipData
import android.content.Intent
import android.content.pm.PathPermission
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.PatternMatcher
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Policy checks only: these do not establish the platform's cross-UID grant boundary. */
class IncomingUriPermissionDeviceTest {
    private val uri = Uri.parse("content://fixture.example/books/sample.epub")

    @Before
    fun requireFixture() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "app.koharia.dev.devicefixture")
    }

    @Test
    fun publiclyReadableProviderNeedsNoGrantFlag() {
        val provider = ProviderInfo().apply { exported = true }
        assertTrue(hasLegacyIncomingReadAccess(Intent(Intent.ACTION_VIEW, uri), uri, provider))
        provider.readPermission = "fixture.READ"
        assertFalse(hasLegacyIncomingReadAccess(Intent(Intent.ACTION_VIEW, uri), uri, provider))
    }

    @Test
    fun pathReadPermissionAppliesOnlyToMatchingPaths() {
        val provider = ProviderInfo().apply {
            exported = true
            pathPermissions = arrayOf(PathPermission("/books", PatternMatcher.PATTERN_PREFIX, "fixture.READ", null))
        }
        assertFalse(hasLegacyIncomingReadAccess(Intent(Intent.ACTION_VIEW, uri), uri, provider))
        val publicUri = Uri.parse("content://fixture.example/public/sample.epub")
        assertTrue(hasLegacyIncomingReadAccess(Intent(Intent.ACTION_VIEW, publicUri), publicUri, provider))
    }

    @Test
    fun privateProviderRequiresReadFlagAndMatchingDataOrClip() {
        val provider = ProviderInfo().apply { exported = false }
        val intent = Intent(Intent.ACTION_VIEW, uri)
        assertFalse(hasLegacyIncomingReadAccess(intent, uri, provider))
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(hasLegacyIncomingReadAccess(intent, uri, provider))
        intent.data = null
        intent.clipData = ClipData.newRawUri("Fixture", uri)
        assertTrue(hasLegacyIncomingReadAccess(intent, uri, provider))
        intent.flags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        assertFalse(hasLegacyIncomingReadAccess(intent, uri, provider))
    }

    @Test
    fun missingOrUnrelatedUriCarrierDoesNotAuthorizeStream() {
        val provider = ProviderInfo().apply { exported = false }
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        assertFalse(hasLegacyIncomingReadAccess(intent, uri, provider))
        intent.clipData = ClipData.newRawUri("Other fixture", Uri.parse("content://fixture.example/other.epub"))
        assertFalse(hasLegacyIncomingReadAccess(intent, uri, provider))
    }
}
