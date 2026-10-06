package koharia.suwayomi.ui

import koharia.suwayomi.SuwayomiSourceInfo
import koharia.suwayomi.SuwayomiSourceMeta
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SuwayomiSourceGroupingTest {

    private val english = SuwayomiSourceInfo(id = 1, name = "Alpha", lang = "en")
    private val chinese = SuwayomiSourceInfo(id = 2, name = "Beta", lang = "zh")
    private val japanese = SuwayomiSourceInfo(id = 3, name = "Gamma", lang = "ja")
    private val local = SuwayomiSourceInfo(id = 4, name = "Local source", lang = LOCAL_SOURCE_LANGUAGE)

    private fun pinned(source: SuwayomiSourceInfo) = source.copy(
        meta = listOf(SuwayomiSourceMeta(SuwayomiSourceInfo.PINNED_META_KEY, "true")),
    )

    private fun group(
        sources: List<SuwayomiSourceInfo>,
        query: String = "",
    ) = groupSuwayomiSources(
        sources = sources,
        query = query,
        pinnedLabel = "Pinned",
        languageLabel = { it.uppercase() },
    )

    @Test
    fun `groups server-pinned sources before language sections`() {
        val groups = group(listOf(chinese, english, japanese).map { if (it.id == 2L) pinned(it) else it })

        assertEquals(listOf("Pinned", "EN", "JA"), groups.map { it.label })
        assertEquals(setOf(2L), groups.first().entries.map { it.id }.toSet())
    }

    @Test
    fun `an unpinned server meta never counts as pinned`() {
        val groups = group(
            listOf(
                english.copy(meta = listOf(SuwayomiSourceMeta(SuwayomiSourceInfo.PINNED_META_KEY, "false"))),
                chinese,
            ),
        )

        assertEquals(listOf("EN", "ZH"), groups.map { it.label })
    }

    @Test
    fun `lists local sources last`() {
        val groups = group(listOf(local, english))

        assertEquals(listOf("EN", "LOCALSOURCELANG"), groups.map { it.label })
    }

    @Test
    fun `filters by name and language without leaving empty headers`() {
        val sources = listOf(english, chinese, japanese)

        assertEquals(listOf("ZH"), group(sources, query = "bet").map { it.label })
        assertEquals(listOf("JA"), group(sources, query = "ja").map { it.label })
        assertEquals(emptyList<String>(), group(sources, query = "nothing").map { it.label })
    }

    @Test
    fun `sorts entries inside a section by name`() {
        val second = SuwayomiSourceInfo(id = 5, name = "Aardvark", lang = "en")

        assertEquals(listOf("Aardvark", "Alpha"), group(listOf(english, second)).single().entries.map { it.name })
    }
}
