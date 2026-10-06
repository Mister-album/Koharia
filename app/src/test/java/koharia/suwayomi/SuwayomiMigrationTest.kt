package koharia.suwayomi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuwayomiMigrationTest {
    private fun manga(
        id: Int,
        title: String,
        sourceId: Long,
        categories: List<SuwayomiCategory> = emptyList(),
    ) = SuwayomiManga(
        id = id,
        title = title,
        sourceId = sourceId,
        categories = SuwayomiNodes(categories),
    )

    private fun chapter(
        id: Int,
        number: Float,
        read: Boolean = false,
        lastPageRead: Int = 0,
        sourceOrder: Int = id,
    ) = SuwayomiChapter(
        id = id,
        mangaId = 1,
        chapterNumber = number,
        sourceOrder = sourceOrder,
        isRead = read,
        lastPageRead = lastPageRead,
    )

    @Test
    fun `groups library entries by source and marks obsolete sources`() {
        val groups = groupSuwayomiLibraryBySource(
            mangas = listOf(
                manga(1, "A", 10L),
                manga(2, "B", 10L),
                manga(3, "C", 20L),
            ),
            sourceNames = mapOf(10L to "Alpha", 20L to "Beta"),
            obsoleteSourceIds = setOf(20L),
        )

        assertEquals(listOf(10L, 20L), groups.map { it.sourceId })
        assertEquals(listOf(2, 1), groups.map { it.count })
        assertEquals(listOf(false, true), groups.map { it.isObsolete })
    }

    @Test
    fun `sorting keeps obsolete sources first and honours direction`() {
        val groups = listOf(
            SuwayomiLibrarySourceGroup(1L, "Beta", 5, false),
            SuwayomiLibrarySourceGroup(2L, "Alpha", 2, false),
            SuwayomiLibrarySourceGroup(3L, "Dead", 9, true),
        )

        val alphabetical = sortSuwayomiLibrarySources(
            groups,
            query = "",
            obsoleteOnly = false,
            sort = SuwayomiMigrationSort.ALPHABETICAL,
            ascending = true,
        )
        assertEquals(listOf("Dead", "Alpha", "Beta"), alphabetical.map { it.displayName })

        val byTotalDescending = sortSuwayomiLibrarySources(
            groups,
            query = "",
            obsoleteOnly = false,
            sort = SuwayomiMigrationSort.TOTAL,
            ascending = false,
        )
        assertEquals(listOf("Dead", "Beta", "Alpha"), byTotalDescending.map { it.displayName })

        val obsoleteOnly = sortSuwayomiLibrarySources(
            groups,
            query = "",
            obsoleteOnly = true,
            sort = SuwayomiMigrationSort.ALPHABETICAL,
            ascending = true,
        )
        assertEquals(listOf("Dead"), obsoleteOnly.map { it.displayName })

        val queried = sortSuwayomiLibrarySources(
            groups,
            query = "alp",
            obsoleteOnly = false,
            sort = SuwayomiMigrationSort.ALPHABETICAL,
            ascending = true,
        )
        assertEquals(listOf("Alpha"), queried.map { it.displayName })
    }

    @Test
    fun `targets exclude the source being left and follow the query`() {
        val sources = listOf(
            SuwayomiSourceInfo(id = 1L, name = "Leaving", lang = "en"),
            SuwayomiSourceInfo(id = 2L, name = "Alpha", lang = "en"),
            SuwayomiSourceInfo(id = 3L, name = "Beta", lang = "zh"),
        )

        assertEquals(listOf("Alpha", "Beta"), suwayomiMigrationTargets(sources, 1L, "").map { it.name })
        assertEquals(listOf("Beta"), suwayomiMigrationTargets(sources, 1L, "zh").map { it.name })
        assertEquals(listOf("Alpha"), suwayomiMigrationTargets(sources, 1L, "alp").map { it.name })
        // The excluded source is never a target, even when it matches the query.
        assertTrue(suwayomiMigrationTargets(sources, 1L, "leaving").isEmpty())
    }

    @Test
    fun `chapter plan matches by number and carries the furthest progress`() {
        val plan = planSuwayomiChapterMigration(
            source = listOf(
                chapter(1, 1f, read = true),
                chapter(2, 2f, lastPageRead = 7),
                chapter(3, 3f),
            ),
            target = listOf(
                chapter(11, 1f, sourceOrder = 1),
                chapter(12, 2f, lastPageRead = 3, sourceOrder = 2),
                chapter(13, 3f, sourceOrder = 3),
            ),
        )

        assertEquals(0, plan.unmatchedStateCount)
        assertEquals(listOf(11, 12), plan.patches.map { it.targetChapterId })
        assertEquals(true, plan.patches[0].read)
        assertEquals(7, plan.patches[1].lastPageRead)
    }

    @Test
    fun `unmatched read state is reported instead of dropped`() {
        val plan = planSuwayomiChapterMigration(
            source = listOf(
                chapter(1, 1f, read = true),
                chapter(2, 5f, read = true),
                chapter(3, -1f, read = true),
            ),
            target = listOf(chapter(11, 1f, sourceOrder = 1)),
        )

        assertEquals(2, plan.unmatchedStateCount)
        assertEquals(listOf(11), plan.patches.map { it.targetChapterId })
    }

    @Test
    fun `target state is never rolled back by an older source state`() {
        val plan = planSuwayomiChapterMigration(
            source = listOf(chapter(1, 1f, lastPageRead = 2)),
            target = listOf(chapter(11, 1f, read = true, lastPageRead = 9, sourceOrder = 1)),
        )

        assertEquals(true, plan.patches.single().read)
        assertEquals(9, plan.patches.single().lastPageRead)
    }

    @Test
    fun `a failed write never removes the old library entry`() {
        val failure = SuwayomiException(SuwayomiException.Reason.PROTOCOL)

        assertTrue(canRemoveMigratedSource(true, true, null))
        // The reader asked to keep it.
        assertEquals(false, canRemoveMigratedSource(false, true, null))
        // The entry was never in the library.
        assertEquals(false, canRemoveMigratedSource(true, false, null))
        // A step hard-failed.
        assertEquals(false, canRemoveMigratedSource(true, true, failure))
    }

    @Test
    fun `search queries start with the full title and fall back to its parts`() {
        val queries = suwayomiMigrationQueries("Some Title (Group) [DL版]", "vol 2")

        assertEquals("Some Title (Group) [DL版] vol 2", queries.first())
        assertTrue(queries.contains("Some Title (Group) [DL版]"))
        assertTrue(queries.contains("Some Title"))
        assertTrue(queries.contains("Group"))
        // Nothing to simplify away.
        assertEquals(listOf("Plain Title"), suwayomiMigrationQueries("Plain Title", null))
        assertEquals(emptyList<String>(), suwayomiMigrationQueries("   ", null))
    }

    @Test
    fun `a glued bilingual title also offers each script on its own`() {
        val queries = suwayomiMigrationQueries("メイド教育女仆教育。没落貴族瑠璃川椿", null)

        assertEquals("メイド教育女仆教育。没落貴族瑠璃川椿", queries.first())
        // The leading kana run (with the han that follows it) and the trailing han run stand alone.
        assertTrue(queries.contains("メイド"))
        assertTrue(queries.contains("教育女仆教育。没落貴族瑠璃川椿"))
        assertTrue(queries.contains("メイド教育女仆教育。没落貴族瑠璃川椿".substringBefore('。')))
        assertTrue(queries.contains("没落貴族瑠璃川椿"))
    }

    @Test
    fun `japanese titles only keep the leading kana run`() {
        val queries = suwayomiMigrationQueries("ふたなりなぎさちゃんはイきづらいっ？", null)

        assertEquals("ふたなりなぎさちゃんはイきづらいっ？", queries.first())
        assertTrue(queries.contains("ふたなりなぎさちゃんはイきづらいっ"))
    }

    @Test
    fun `similarity prefers exact and containment matches`() {
        assertEquals(1.0, suwayomiTitleSimilarity("Alpha", "alpha"))
        assertTrue(suwayomiTitleSimilarity("Alpha", "Alpha - Part 2") > 0.4)
        assertTrue(suwayomiTitleSimilarity("Alpha", "Beta") < 0.4)
        assertEquals(0.0, suwayomiTitleSimilarity("", "Alpha"))
    }

    @Test
    fun `best match ignores weak candidates`() {
        val exact = SuwayomiManga(id = 1, title = "Alpha")
        val partial = SuwayomiManga(id = 2, title = "Alpha - Part 2")
        val unrelated = SuwayomiManga(id = 3, title = "Beta")

        assertEquals(exact, bestSuwayomiMatch("Alpha", listOf(partial, exact, unrelated)))
        assertEquals(null, bestSuwayomiMatch("Alpha", listOf(unrelated)))
    }

    @Test
    fun `download migration only queues chapters the source already had`() {
        val downloads = planSuwayomiDownloadMigration(
            source = listOf(
                chapter(1, 1f).copy(isDownloaded = true),
                chapter(2, 2f),
                chapter(3, 3f).copy(isDownloaded = true),
            ),
            target = listOf(
                chapter(11, 1f, sourceOrder = 1),
                chapter(12, 2f, sourceOrder = 2),
                chapter(13, 3f, sourceOrder = 3),
            ),
        )

        assertEquals(listOf(11, 13), downloads)
    }
}
