package koharia.source.komga

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KomgaProgressAccountTest {
    @Test
    fun `binding covers source root alternate endpoint and credentials without ambiguous separators`() {
        val owner = account()
        assertEquals(owner.key, account().key)
        assertNotEquals(owner.key, account(id = 2).key)
        assertNotEquals(owner.key, account(address = "https://other.test/library").key)
        assertNotEquals(owner.key, account(internal = "http://other.test/library").key)
        assertNotEquals(owner.key, account(user = "another").key)
        assertNotEquals(owner.key, account(password = "changed").key)
        assertNotEquals(account(user = "a|b", password = "c").key, account(user = "a", password = "b|c").key)
        assertEquals(Credentials.basic("reader", "fixture"), owner.headers["Authorization"])
    }

    @Test
    fun `network guard rejects configuration changes after request construction`() {
        val account = account()
        val request = Request.Builder().url("https://komga.test/library/api/v1/books/one")
            .headers(account.headers).tag(KomgaProgressAccount::class.java, account).build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        assertThrows(KomgaProgressOwnerChanged::class.java) {
            KomgaProgressAccountInterceptor { account(user = "replacement").key }.intercept(chain)
        }
        verify(exactly = 0) { chain.proceed(any()) }
    }

    @Test
    fun `network guard permits owned roots but refuses foreign redirects and sibling paths`() {
        val account = account()
        account.requireDestination(Request.Builder().url("https://komga.test/library/api/v1/books/one").build())
        account.requireDestination(Request.Builder().url("http://internal.test/library/api/v1/books/one").build())
        for (url in listOf(
            "https://foreign.test/library/api/v1/books/one",
            "https://komga.test/library-other/api/v1/books/one",
            "http://komga.test/library/api/v1/books/one",
        )) {
            assertThrows(KomgaProgressOwnerChanged::class.java) {
                account.requireDestination(Request.Builder().url(url).build())
            }
        }
    }

    private fun account(
        id: Long = 1,
        address: String = "https://komga.test/library",
        internal: String = "http://internal.test/library",
        user: String = "reader",
        password: String = "fixture",
    ) = KomgaProgressAccount(id, address, internal, "Credentials", user, password, "")
}
