package koharia.lanraragi

import koharia.domain.lanraragi.LanraragiEntry
import koharia.domain.lanraragi.LanraragiReadState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LanraragiCatalogTest {
    @Test
    fun `downloaded candidates still respect category reading status and grouping`() {
        val a = LanraragiEntry("a", title = "A", pageCount = 10, progress = 10, tags = "tag:a")
        val b = LanraragiEntry("b", title = "B", pageCount = 10, tags = "tag:b")
        val tank = LanraragiEntry("tank", LanraragiEntry.Kind.TANK, "Collection", members = listOf("a"))
        val category = LanraragiEntry("category", LanraragiEntry.Kind.CATEGORY, "Category", members = listOf("a"))
        val entries = listOf(a, b, tank, category)
        val downloads = setOf("a", "b", "tank")
        assertEquals(
            listOf(tank),
            filterLanraragiCatalog(
                entries,
                emptyList(),
                LanraragiFilter(category = "category"),
                availableEntryIds = downloads,
            ),
        )
        assertTrue(
            filterLanraragiCatalog(
                entries,
                emptyList(),
                LanraragiFilter(category = "category", readStatus = 1),
                availableEntryIds = downloads,
            ).isEmpty(),
        )
        assertEquals(
            listOf(b),
            filterLanraragiCatalog(
                entries,
                emptyList(),
                LanraragiFilter(tag = "tag:b"),
                availableEntryIds = downloads,
            ),
        )
        assertEquals(
            listOf(a),
            filterLanraragiCatalog(
                entries,
                emptyList(),
                LanraragiFilter(category = "category"),
                availableEntryIds = setOf("a"),
            ),
        )
    }

    private val a = LanraragiEntry("a", title = "Alpha", tags = " artist:One, date_added:20", pageCount = 10)
    private val b =
        LanraragiEntry("b", title = "Beta", tags = "artist:Two", pageCount = 5, progress = 5, lastRead = 100)
    private val tank = LanraragiEntry("tank", LanraragiEntry.Kind.TANK, "Collection", members = listOf("b", "a"))

    @Test
    fun `nested tanks preserve order deduplicate archives and terminate cycles`() {
        val nested = tank.copy(id = "nested", members = listOf("a", "tank", "missing"))
        val root = tank.copy(members = listOf("b", "nested", "a"))
        val result = flattenLanraragiTank(root, listOf(a, b, root, nested).associateBy { it.id })
        assertEquals(listOf("b", "a", "missing"), result.map { it.id })
    }

    @Test
    fun `group switch keeps identities and category matches tank members`() {
        val category =
            LanraragiEntry("category", LanraragiEntry.Kind.CATEGORY, "Dynamic snapshot", members = listOf("a"))
        val entries = listOf(a, b, tank, category)
        assertEquals(
            listOf("tank"),
            filterLanraragiCatalog(entries, emptyList(), LanraragiFilter(category = "category")).map {
                it.id
            },
        )
        assertEquals(
            listOf("a"),
            filterLanraragiCatalog(entries, emptyList(), LanraragiFilter(category = "category", grouped = false)).map {
                it.id
            },
        )
    }

    @Test
    fun `local unread override wins over remote completed filtering`() {
        val unread = LanraragiReadState("b", 0, 5, 200, localUnread = true, pending = false)
        assertEquals(
            listOf("a", "b"),
            filterLanraragiCatalog(listOf(a, b), listOf(unread), LanraragiFilter(grouped = false, readStatus = 1)).map {
                it.id
            },
        )
        assertFalse(localProgressWins(unread, b))
    }

    @Test
    fun `pending reading is compared using time not highest page`() {
        val reread = LanraragiReadState("b", 0, 5, 200)
        assertTrue(localProgressWins(reread, b))
        assertFalse(localProgressWins(reread.copy(readAt = 50), b))
    }

    @Test
    fun `offline search matches title and normalized tags`() {
        val results =
            filterLanraragiCatalog(
                listOf(a, b),
                emptyList(),
                LanraragiFilter(query = "alpha artist:one", grouped = false),
            )
        assertEquals(listOf(a), results)
        assertEquals(20, a.addedAt)
    }

    @Test
    fun `random order is finite stable per session and never changes ids`() {
        val entries = (1..100).map { a.copy(id = it.toString()) }
        val filter = LanraragiFilter(grouped = false, sort = 3, randomSeed = 17)
        val first = filterLanraragiCatalog(entries, emptyList(), filter)
        assertEquals(first, filterLanraragiCatalog(entries.reversed(), emptyList(), filter))
        assertEquals(entries.map { it.id }.toSet(), first.map { it.id }.toSet())
    }
}
