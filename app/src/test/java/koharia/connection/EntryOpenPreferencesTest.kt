package koharia.connection

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class EntryOpenPreferencesTest {
    @Test
    fun `local book mode changes independently of comic and Komga modes`() {
        val store = InMemoryPreferenceStore()
        val preferences = EntryOpenPreferences(store)
        preferences.localBookMode() shouldBe EntryOpenMode.DETAILS
        preferences.localSingleBook.set(EntryOpenMode.READER.name)

        preferences.localBookMode() shouldBe EntryOpenMode.READER
        preferences.localMode() shouldBe EntryOpenMode.READER
        preferences.komgaMode() shouldBe EntryOpenMode.DETAILS
        preferences.localSingleComic.set(EntryOpenMode.PAGE_PREVIEW.name)
        preferences.localBookMode() shouldBe EntryOpenMode.READER
    }

    @Test
    fun `unsupported book opening modes fall back to details`() {
        val preferences = EntryOpenPreferences(InMemoryPreferenceStore())
        listOf(EntryOpenMode.PAGE_PREVIEW.name, "unknown").forEach {
            preferences.localSingleBook.set(it)
            preferences.localBookMode() shouldBe EntryOpenMode.DETAILS
        }
    }
}
