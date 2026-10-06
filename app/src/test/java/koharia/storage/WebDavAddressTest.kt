package koharia.storage

import koharia.source.local.NetworkStorageDraft
import koharia.source.local.sameNetworkRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebDavAddressTest {
    @Test fun `server address accepts host and port and encodes the chosen folder`() {
        val address = WebDavAddress.parse("https://example.com:5006")
        assertEquals("", address.root)
        assertEquals("https://example.com:5006/", address.endpoint)
        assertEquals("https://example.com:5006/%E4%B8%AD%E6%96%87%20%E7%A9%BA%E6%A0%BC", address.at("中文 空格"))
        assertEquals(
            "https://example.com:5006/dav/books",
            WebDavAddress.parse("https://example.com:5006/dav/books/").let { it.at(it.root) },
        )
        assertEquals("https://example.com/", WebDavAddress.parse("https://example.com:443").endpoint)
        assertEquals("http://[::1]:8080/", WebDavAddress.parse("http://[::1]:8080").endpoint)
        assertNull(WebDavAddress.of("example.com/dav"))
        assertThrows(IllegalArgumentException::class.java) {
            WebDavAddress.parse("https://user:pass@example.com/dav")
        }
        assertThrows(IllegalArgumentException::class.java) { WebDavAddress.parse("ftp://example.com/dav") }
        assertThrows(IllegalArgumentException::class.java) { WebDavAddress.parse("https://example.com/dav?a=1") }
        assertThrows(IllegalArgumentException::class.java) { address.at("../outside") }
    }

    @Test fun `folder selection resolves against both endpoints without changing credentials`() {
        val draft = NetworkStorageDraft(
            NetworkStorageConfiguration(
                mode = LibraryStorageMode.WEBDAV,
                address = "https://example.test/dav",
                internalAddress = "http://192.168.1.1:8080/",
            ),
            "user",
            "password",
        )
        assertTrue(draft.endpointValid)
        val selected = draft.withRoot("dav/中文 空格")
        assertEquals("https://example.test/dav/%E4%B8%AD%E6%96%87%20%E7%A9%BA%E6%A0%BC", selected.configuration.address)
        assertEquals(
            "http://192.168.1.1:8080/dav/%E4%B8%AD%E6%96%87%20%E7%A9%BA%E6%A0%BC",
            selected.configuration.internalAddress,
        )
        assertEquals("password", selected.password)
        assertEquals("https://example.test/dav", draft.configuration.address)
    }

    @Test fun `endpoint only address authenticates before a folder exists`() {
        val draft = NetworkStorageDraft(
            NetworkStorageConfiguration(mode = LibraryStorageMode.WEBDAV, address = "http://192.168.1.10:5006"),
            "user",
            "password",
        )
        assertTrue(draft.endpointValid)
        assertEquals("http://192.168.1.10:5006/", WebDavAddress.parse(draft.configuration.address).endpoint)
        assertFalse(
            NetworkStorageDraft(draft.configuration.copy(address = "192.168.1.10:5006"), "u", "p").endpointValid,
        )
        assertFalse(NetworkStorageDraft(draft.configuration.copy(address = ""), "u", "p").endpointValid)
    }

    @Test fun `LAN endpoint without a folder inherits the selected library root`() {
        val draft = NetworkStorageDraft(
            NetworkStorageConfiguration(
                mode = LibraryStorageMode.WEBDAV,
                address = "https://example.test/dav/",
                internalAddress = "http://192.168.1.1:8080",
            ),
            "user",
            "password",
        )
        val resolved = draft.resolvedConfiguration()
        // The primary address stays byte-identical so an unchanged connection keeps its stored form.
        assertEquals("https://example.test/dav/", resolved.address)
        assertEquals("http://192.168.1.1:8080/dav", resolved.internalAddress)
        assertEquals(
            "",
            draft.copy(configuration = draft.configuration.copy(internalAddress = "")).resolvedConfiguration()
                .internalAddress,
        )
    }

    @Test fun `a trailing slash is not a different library root`() {
        assertTrue(sameNetworkRoot(LibraryStorageMode.WEBDAV, "https://example.test/dav", "https://example.test/dav/"))
        assertTrue(sameNetworkRoot(LibraryStorageMode.SMB, "smb://host/share", "smb://host/share/"))
        assertFalse(
            sameNetworkRoot(LibraryStorageMode.WEBDAV, "https://example.test/dav", "https://example.test/books"),
        )
        assertFalse(sameNetworkRoot(LibraryStorageMode.WEBDAV, "https://example.test/dav", "https://example.test"))
        assertFalse(sameNetworkRoot(LibraryStorageMode.WEBDAV, "not an address", "https://example.test/"))
    }
}
