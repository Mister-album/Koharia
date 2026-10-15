package koharia.connection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import koharia.domain.kavita.KavitaCacheEntry
import koharia.domain.kavita.KavitaRepository
import koharia.kavita.KavitaAccount
import koharia.kavita.KavitaChapter
import koharia.kavita.KavitaChapterRef
import koharia.kavita.KavitaLibrary
import koharia.kavita.KavitaListItem
import koharia.kavita.KavitaMetadata
import koharia.kavita.KavitaSeries
import koharia.kavita.KavitaVolume
import koharia.kavita.ui.KavitaExploreScreen
import koharia.kavita.ui.KavitaLibraryScreen
import koharia.kavita.ui.KavitaPersonalScreen
import koharia.kavita.ui.KavitaSeriesScreen
import koharia.source.kavita.KavitaPreferences
import koharia.source.kavita.KavitaSource
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class KavitaReadListPresentationDeviceTest {
    @Test
    fun actualListKeepsUnitDownloadsAndStandardSeriesDetails(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val rejection = InstrumentationRegistry.getArguments().getString("seriesActionsRejection").orEmpty()
        check(rejection in setOf("", "permission", "unavailable", "unsupported"))
        val seriesActionsRoute = rejection.isNotEmpty() ||
            InstrumentationRegistry.getArguments().getString("seriesActionsRoute") == "true"
        val startsEmpty = InstrumentationRegistry.getArguments().getString("readlistStartWithoutLocal") == "true"
        val recommendationRoute = InstrumentationRegistry.getArguments().getString("recommendationRoute").orEmpty()
        val ordinaryLibraryDetails =
            InstrumentationRegistry.getArguments().getString("ordinaryLibraryDetails") == "true"
        val recommendationDetails = ordinaryLibraryDetails ||
            InstrumentationRegistry.getArguments().getString("recommendationDetailsRoute") == "true"
        check(
            !recommendationDetails ||
                ((recommendationRoute.isNotEmpty() || ordinaryLibraryDetails) && !seriesActionsRoute),
        )
        check(!ordinaryLibraryDetails || (!startsEmpty && recommendationRoute.isEmpty()))
        check(recommendationRoute in setOf("", "related", "plus"))
        check(recommendationRoute.isEmpty() || !startsEmpty)
        check(!seriesActionsRoute || (!startsEmpty && recommendationRoute.isEmpty()))
        check(context.packageName == "app.koharia.dev.devicefixture")
        InstrumentationRegistry.getArguments().getString("recoverReadListProfile")?.let { id ->
            recoverInterruptedFixture(id.toLong())
            return@runBlocking
        }
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val previous = connections.activeConnectionId.get()
        val hadPrevious = connections.activeConnectionId.isSet()
        val libraryPreferences = Injekt.get<LibraryPreferences>()
        val restores = mutableListOf<() -> Unit>()
        fun <T> replace(preference: Preference<T>, value: T) {
            val present = preference.isSet()
            val old = preference.get()
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("readlist_preference_before", "${preference.key()}|present=$present|value=$old")
                },
            )
            restores += { if (present) preference.set(old) else preference.delete() }
            preference.set(value)
        }
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        val downloads = Injekt.get<DownloadManager>()
        val provider = Injekt.get<DownloadProvider>()
        val cache = Injekt.get<DownloadCache>()
        val profile = profiles.add("kavita", "Download states ${UUID.randomUUID()}")
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("readlist_owned_profile", "${profile.id}|${profile.name}")
                putString("readlist_previous_active", "$previous|present=$hadPrevious")
            },
        )
        val server = UnexpectedRequestServer(archive(), writable = rejection.isNotEmpty())
        var source: KavitaSource? = null
        var stored: Manga? = null
        try {
            replace(libraryPreferences.showLibraryReadProgress, false)
            replace(libraryPreferences.showShelfDownloadCount, true)
            replace(libraryPreferences.displayMode, LibraryDisplayMode.List)
            if (seriesActionsRoute || recommendationDetails) {
                replace(Injekt.get<SharedAppPreferences>().basePreferences().downloadedOnly, ordinaryLibraryDetails)
                if (ordinaryLibraryDetails) {
                    replace(
                        Injekt.get<SourcePreferences>().sourceDisplayMode,
                        LibraryDisplayMode.List,
                    )
                }
                replace(libraryPreferences.chapterCoverDisplayMode, Manga.CHAPTER_COVER_DISPLAY_TEXT)
            }
            KavitaPreferences(profile.id).save(server.address, "fixture-key", server.account)
            val active = withTimeout(10_000) {
                while (Injekt.get<SourceManager>().get(profile.id) !is KavitaSource) delay(25)
                (Injekt.get<SourceManager>().get(profile.id) as KavitaSource).also { it.reload() }
            }
            source = active
            val session = active.session()
            val repository = Injekt.get<KavitaRepository>()
            suspend fun cached(group: String, payload: String) {
                repository.putCache(profile.id, session.accountKey, group, KavitaCacheEntry("data", payload, 1))
            }
            val members = (1..if (startsEmpty) 1 else 2).map {
                KavitaListItem(
                    id = it.toLong(),
                    order = it,
                    chapterId = it.toLong(),
                    seriesId = 10,
                    volumeId = 20,
                    libraryId = 30,
                    title = "Owned book $it",
                    seriesName = "Owned download states",
                )
            }
            cached("resource/ReadingList/items?readingListId=1", Json.encodeToString(members))
            cached("resource/ReadingList?readingListId=1", """{"id":1,"title":"Owned list","ownerUserName":"other"}""")
            cached(
                "series/10",
                Json.encodeToString(KavitaSeries(id = 10, libraryId = 30, name = "Owned download states")),
            )
            cached("metadata/10", Json.encodeToString(KavitaMetadata()))
            cached("libraries", Json.encodeToString(listOf(KavitaLibrary(30, "Owned library"))))
            cached(
                "volumes/10",
                Json.encodeToString(
                    listOf(
                        KavitaVolume(
                            20,
                            chapters = members.map {
                                KavitaChapter(id = it.chapterId, volumeId = 20, title = it.title, pages = 1)
                            },
                        ),
                    ),
                ),
            )
            if (seriesActionsRoute || recommendationRoute.isNotEmpty()) {
                val seriesJson = Json.encodeToString(
                    KavitaSeries(id = 10, libraryId = 30, name = "Owned download states"),
                )
                cached("resource/Series/10", seriesJson)
                cached("resource/want-to-read?seriesId=10", "false")
                cached("resource/Series/all-related?seriesId=10", """{"sequels":[$seriesJson]}""")
                cached("resource/License/valid-license", "true")
                cached(
                    "resource/Metadata/series-detail-plus?seriesId=10&libraryType=0",
                    """{
                      "series":{"name":"Plus summary","summary":"Fixture","genres":[]},
                      "ratings":[{"provider":1,"averageScore":50}],
                      "reviews":[{"username":"Review one","bodyJustText":"Fixture"},
                                 {"username":"Review two","bodyJustText":"Fixture"}],
                      "recommendations":{"ownedSeries":[{"series":$seriesJson}],
                        "externalSeries":[{"name":"External recommendation","url":"https://example.invalid"}]}
                    }
                    """.trimIndent(),
                )
                cached("scrobbling/status", "[]")
                cached("resource/Scrobbling/has-hold?seriesId=10", "false")
                cached("resource/Scrobbling/library-allows-scrobbling?seriesId=10", "false")
                cached("resource/Scrobbling/next-scrobble-time", "\"Fixture\"")
            }
            check(!active.usesSharedDownloadStorage && provider.findOwnedSourceDirs(active).isEmpty())
            val seedManga = Manga.create().copy(
                source = profile.id,
                url = session.identity.series(10),
                initialized = true,
                title = "Owned download states",
            ).let {
                if (ordinaryLibraryDetails) {
                    it.copy(memo = active.toManga(KavitaSeries(id = 10, libraryId = 30, name = it.title)).memo)
                } else {
                    it
                }
            }
            val manga = if (startsEmpty) seedManga else mangas.insertNetworkManga(listOf(seedManga)).single()
            if (!startsEmpty) stored = manga
            val units = if (startsEmpty) {
                emptyList()
            } else {
                chapters.addAll(
                    (1..2).map {
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = session.identity.chapter(KavitaChapterRef(30, 10, 20, it.toLong(), 2)),
                            name = "Unit $it",
                        )
                    },
                )
            }
            if (startsEmpty) check(mangas.getMangaBySourceId(profile.id).isEmpty())
            val seed = archive()
            val complete = context.stringResource(MR.strings.shelf_download_complete, 1L)
            val expectedTitles = members.map { it.title }
            val directory = if (startsEmpty) null else provider.getMangaDir(manga.title, active).getOrThrow()
            suspend fun add(unit: Chapter) {
                val targetDirectory = checkNotNull(directory)
                val name = provider.getChapterDirName(unit.name, unit.scanlator, unit.url) + ".cbz"
                check(targetDirectory.findFile(name) == null)
                val file = checkNotNull(targetDirectory.createFile(name))
                file.openOutputStream().use { it.write(seed) }
                cache.addChapter(name, targetDirectory, manga)
            }
            suspend fun remove(unit: Chapter) {
                val targetDirectory = checkNotNull(directory)
                val name = provider.getChapterDirName(unit.name, unit.scanlator, unit.url) + ".cbz"
                val file = checkNotNull(targetDirectory.findFile(name))
                check(file.openInputStream().use { it.readBytes() }.contentEquals(seed))
                check(file.delete())
                cache.removeChapter(unit, manga)
            }
            var navigator: Navigator? = null
            FixtureActivityLauncher.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme {
                            val initial = if (seriesActionsRoute) {
                                MangaScreen(manga.id, sourceId = profile.id, mangaUrl = manga.url)
                            } else if (ordinaryLibraryDetails) {
                                KavitaLibraryScreen(profile.id, null, false)
                            } else {
                                when (recommendationRoute) {
                                    "related" -> KavitaSeriesScreen(profile.id, 10)
                                    "plus" -> KavitaPersonalScreen(profile.id, "plus", 10)
                                    else -> KavitaExploreScreen(profile.id, "ReadingList/items", 1)
                                }
                            }
                            Navigator(initial) { host ->
                                navigator = host
                                cafe.adriel.voyager.navigator.CurrentScreen()
                            }
                        }
                    }
                }
                if (seriesActionsRoute) {
                    val baseline = mangas.getMangaById(manga.id)
                    add(units[0])
                    verifySeriesActionsNavigation({ navigator }, "Kavita", onDestination = {
                        if (rejection.isNotEmpty()) {
                            suspend fun switch(): AccessibilityNodeInfo = withTimeout(15_000) {
                                fun findSwitch(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                                    if (node == null) return null
                                    if (node.isVisibleToUser && node.isCheckable && node.isEnabled) return node
                                    repeat(node.childCount) { findSwitch(node.getChild(it))?.let { return it } }
                                    return null
                                }
                                var found: AccessibilityNodeInfo? = null
                                while (found == null) {
                                    found = findSwitch(instrumentation.uiAutomation.rootInActiveWindow)
                                    if (found == null) delay(50)
                                }
                                found
                            }
                            val toggle = switch()
                            check(!toggle.isChecked && active.preferences.capabilities.writable)
                            val authenticationBefore = server.authenticationReads.get()
                            when (rejection) {
                                "permission" -> server.account = server.account.copy(roles = listOf("ReadOnly"))
                                "unavailable" -> server.authenticationUnavailable = true
                                "unsupported" -> server.account = server.account.copy(kavitaVersion = "0.7.0")
                            }
                            val rect = Rect().also { toggle.getBoundsInScreen(it) }
                            instrumentation.uiAutomation.executeShellCommand(
                                "input tap ${rect.centerX()} ${rect.centerY()}",
                            ).close()
                            val error = context.stringResource(
                                when (rejection) {
                                    "permission" -> MR.strings.kavita_error_permission
                                    "unsupported" -> MR.strings.kavita_error_version
                                    else -> MR.strings.kavita_error_connection
                                },
                            )
                            withTimeout(15_000) {
                                while (find(instrumentation.uiAutomation.rootInActiveWindow, error) == null) delay(50)
                            }
                            check(server.authenticationReads.get() > authenticationBefore)
                            check(server.writes.get() == 0 && server.requests.get() == 0)
                            val confirm = checkNotNull(
                                find(
                                    instrumentation.uiAutomation.rootInActiveWindow,
                                    context.stringResource(MR.strings.action_ok),
                                ),
                            )
                            val bounds = Rect().also { confirm.getBoundsInScreen(it) }
                            instrumentation.uiAutomation.executeShellCommand(
                                "input tap ${bounds.centerX()} ${bounds.centerY()}",
                            ).close()
                            check(!switch().isChecked)
                            instrumentation.sendStatus(
                                0,
                                Bundle().apply {
                                    putString(
                                        "series_actions_rejection",
                                        "$rejection;fresh-account;error-visible;unchanged;writes=0",
                                    )
                                },
                            )
                        }
                    }) { it is KavitaSeriesScreen }
                    val screen = navigator!!.lastItem as MangaScreen
                    check(screen.mangaId == manga.id && screen.sourceId == profile.id && screen.mangaUrl == manga.url)
                    check(mangas.getMangaById(manga.id) == baseline)
                    check(chapters.getChapterByMangaId(manga.id).associateBy { it.id } == units.associateBy { it.id })
                    check(downloads.getDownloadCount(manga) == 1)
                    if (rejection.isNotEmpty()) {
                        withTimeout(15_000) {
                            while (find(instrumentation.uiAutomation.rootInActiveWindow, units[0].name) ==
                                null
                            ) {
                                delay(50)
                            }
                        }
                        val loader = DownloadPageLoader(ReaderChapter(units[0]), manga, active, downloads, provider)
                        try {
                            val pages = loader.getPages()
                            check(pages.size == 1)
                            checkNotNull(pages.single().stream).invoke().use {
                                val bitmap = checkNotNull(BitmapFactory.decodeStream(it))
                                check(bitmap.width == 32 && bitmap.height == 48)
                                bitmap.recycle()
                            }
                        } finally {
                            loader.recycle()
                        }
                        check(mangas.getMangaById(manga.id) == baseline)
                        check(
                            chapters.getChapterByMangaId(manga.id).associateBy {
                                it.id
                            } == units.associateBy { it.id },
                        )
                        instrumentation.sendStatus(
                            0,
                            Bundle().apply {
                                putString(
                                    "series_actions_local_file",
                                    "$rejection;row-visible;DownloadPageLoader;PNG-decoded;SQL-retained",
                                )
                            },
                        )
                    }
                    remove(units[0])
                    check(server.requests.get() == 0) { "Unhandled fixture routes: ${server.unhandled}" }
                    check(server.writes.get() == 0)
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putString(
                                "series_actions_protocol",
                                "unexpected=0;writes=0;" +
                                    "progress=${server.progressReads.get()};covers=${server.coverReads.get()}",
                            )
                        },
                    )
                    return@use
                }
                if (recommendationRoute.isNotEmpty() || ordinaryLibraryDetails) {
                    suspend fun tap(text: String) {
                        val node = withTimeout(15_000) {
                            var result: AccessibilityNodeInfo? = null
                            while (result == null) {
                                result = find(instrumentation.uiAutomation.rootInActiveWindow, text)
                                if (result == null) delay(50)
                            }
                            result
                        }
                        val rect = Rect().also { node.getBoundsInScreen(it) }
                        instrumentation.uiAutomation.executeShellCommand(
                            "input tap ${rect.centerX()} ${rect.centerY()}",
                        ).close()
                    }
                    if (recommendationRoute == "related") tap(context.stringResource(MR.strings.kavita_related))
                    val title = manga.title
                    if (recommendationDetails) {
                        fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
                            if (node == null) return emptyList()
                            return listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
                        }
                        fun click(node: AccessibilityNodeInfo) {
                            var target = node
                            while (!target.isClickable) target = checkNotNull(target.parent)
                            check(target.isVisibleToUser && target.isEnabled)
                            check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        }
                        suspend fun card(): AccessibilityNodeInfo = withTimeout(15_000) {
                            var result: AccessibilityNodeInfo? = null
                            while (result == null) {
                                val current = nodes(instrumentation.uiAutomation.rootInActiveWindow)
                                result = current.firstOrNull {
                                    it.isVisibleToUser && it.isClickable && it.isEnabled && title in labels(it)
                                }
                                if (result == null) {
                                    current.firstOrNull { it.isScrollable && it.isVisibleToUser }
                                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                                    delay(100)
                                }
                            }
                            result
                        }
                        add(units[0])
                        if (ordinaryLibraryDetails) add(units[1])
                        val target = card()
                        val original = navigator!!.lastItem
                        val baseline = mangas.getMangaById(manga.id)
                        check(
                            original is KavitaSeriesScreen || original is KavitaPersonalScreen ||
                                original is KavitaLibraryScreen,
                        )
                        click(target)
                        val entryName = if (ordinaryLibraryDetails) "library" else recommendationRoute
                        instrumentation.sendStatus(
                            0,
                            Bundle().apply {
                                putString("details_entry_clicked", entryName)
                            },
                        )
                        withTimeout(15_000) {
                            while (navigator?.lastItem !is MangaScreen ||
                                units.any { it.name !in labels(instrumentation.uiAutomation.rootInActiveWindow) }
                            ) {
                                delay(50)
                            }
                        }
                        check((navigator!!.lastItem as MangaScreen).mangaId == manga.id)
                        check(navigator!!.items.size == 2 && navigator!!.items.first() === original)
                        check(mangas.getMangaBySourceId(profile.id).single() == baseline)
                        check(
                            chapters.getChapterByMangaId(manga.id).associateBy {
                                it.id
                            } == units.associateBy { it.id },
                        )
                        check(downloads.getDownloadCount(manga) == if (ordinaryLibraryDetails) 2 else 1)
                        val up = nodes(instrumentation.uiAutomation.rootInActiveWindow).first {
                            it.isVisibleToUser && it.contentDescription?.toString() ==
                                context.stringResource(MR.strings.action_bar_up_description)
                        }
                        click(up)
                        withTimeout(15_000) { while (navigator?.lastItem !== original) delay(50) }
                        card()
                        check(navigator!!.items.size == 1)
                        check(mangas.getMangaBySourceId(profile.id).single() == baseline)
                        check(
                            chapters.getChapterByMangaId(manga.id).associateBy {
                                it.id
                            } == units.associateBy { it.id },
                        )
                        remove(units[0])
                        if (ordinaryLibraryDetails) remove(units[1])
                        check(server.requests.get() == 0) { "Unhandled fixture routes: ${server.unhandled}" }
                        check(server.writes.get() == 0)
                        instrumentation.sendStatus(
                            0,
                            Bundle().apply {
                                putString(
                                    if (ordinaryLibraryDetails) {
                                        "ordinary_library_details"
                                    } else {
                                        "recommendation_details"
                                    },
                                    "$entryName;card;MangaScreen;canonical-owner;" +
                                        "SQL-files-retained;returned",
                                )
                                putString(
                                    "recommendation_details_protocol",
                                    "unexpected=0;writes=0;" +
                                        "progress=${server.progressReads.get()};covers=${server.coverReads.get()}",
                                )
                            },
                        )
                        return@use
                    }
                    repeat(6) {
                        if (find(instrumentation.uiAutomation.rootInActiveWindow, title) == null) {
                            val bounds = Rect().also {
                                instrumentation.uiAutomation.rootInActiveWindow.getBoundsInScreen(it)
                            }
                            instrumentation.uiAutomation.executeShellCommand(
                                "input swipe ${bounds.centerX()} ${bounds.bottom * 3 / 4} " +
                                    "${bounds.centerX()} ${bounds.bottom / 4} 250",
                            ).close()
                            delay(350)
                        }
                    }
                    val partial = context.stringResource(MR.strings.shelf_download_partial, 1L, 2L)
                    val all = context.stringResource(MR.strings.shelf_download_complete, 2L)
                    val increased = context.stringResource(MR.strings.shelf_download_partial, 2L, 3L)
                    val unknown = context.stringResource(MR.strings.shelf_download_unknown, 2L)
                    suspend fun awaitSeries(expected: String?, stage: String) {
                        var actual = emptyList<String>()
                        try {
                            withTimeout(15_000) {
                                while (true) {
                                    actual = labels(instrumentation.uiAutomation.rootInActiveWindow)
                                    if (title in actual && listOf(partial, all, increased, unknown).all {
                                            (it in actual) == (it == expected)
                                        }
                                    ) {
                                        break
                                    }
                                    delay(50)
                                }
                            }
                        } catch (error: Exception) {
                            instrumentation.sendStatus(
                                0,
                                Bundle().apply {
                                    putString("recommendation_failure_labels", actual.toString())
                                },
                            )
                            throw error
                        }
                        instrumentation.sendStatus(
                            0,
                            Bundle().apply {
                                putString("recommendation_stage", "$recommendationRoute;$stage")
                            },
                        )
                    }
                    awaitSeries(null, "none")
                    add(units[0])
                    awaitSeries(partial, "partial")
                    add(units[1])
                    awaitSeries(all, "complete")
                    libraryPreferences.showLibraryReadProgress.set(true)
                    awaitSeries(all, "reading-toggle-independent")
                    suspend fun replaceVolumes(count: Int) {
                        cached(
                            "volumes/10",
                            Json.encodeToString(
                                listOf(
                                    KavitaVolume(
                                        20,
                                        chapters = (1..count).map {
                                            KavitaChapter(id = it.toLong(), volumeId = 20, pages = 1)
                                        },
                                    ),
                                ),
                            ),
                        )
                    }
                    replaceVolumes(3)
                    awaitSeries(increased, "catalogue-increased")
                    replaceVolumes(1)
                    awaitSeries(unknown, "catalogue-decreased")
                    replaceVolumes(0)
                    awaitSeries(unknown, "catalogue-empty")
                    replaceVolumes(2)
                    awaitSeries(all, "catalogue-restored")
                    repository.invalidateGroup(profile.id, session.accountKey, "volumes/10")
                    awaitSeries(unknown, "catalogue-stale")
                    replaceVolumes(2)
                    awaitSeries(all, "catalogue-revalidated")
                    check(chapters.getChapterByMangaId(manga.id).size == 2)
                    remove(units[0])
                    awaitSeries(partial, "deleted-first")
                    remove(units[1])
                    awaitSeries(null, "deleted-all")
                    check(downloads.getDownloadCount(manga) == 0)
                    check(server.requests.get() == 0) { "Unhandled fixture routes: ${server.unhandled}" }
                    check(server.progressReads.get() == 0 && server.coverReads.get() == 0 && server.writes.get() == 0)
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putString("recommendation_protocol", "unexpected=0;writes=0;progress=0;covers=0")
                        },
                    )
                    return@use
                }
                suspend fun awaitCards(
                    count: Int,
                    stage: String,
                    downloadedTitles: Set<String> = expectedTitles.take(count).toSet(),
                ) {
                    var actual = emptyList<String>()
                    try {
                        withTimeout(15_000) {
                            while (true) {
                                actual = labels(instrumentation.uiAutomation.rootInActiveWindow)
                                val root = instrumentation.uiAutomation.rootInActiveWindow
                                val matchingRows = expectedTitles.all { title ->
                                    var node = find(root, title)
                                    var rowLabels = labels(node)
                                    while (node?.parent != null) {
                                        val parent = node.parent
                                        val parentLabels = labels(parent)
                                        if (expectedTitles.count { it in parentLabels } != 1) break
                                        rowLabels = parentLabels
                                        node = parent
                                    }
                                    title in rowLabels && ((complete in rowLabels) == (title in downloadedTitles))
                                }
                                if (matchingRows && actual.count { it == complete } == count) {
                                    break
                                }
                                delay(50)
                            }
                        }
                    } catch (error: Exception) {
                        instrumentation.sendStatus(
                            0,
                            Bundle().apply { putString("readlist_failure_labels", actual.toString()) },
                        )
                        throw error
                    }
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply { putString("readlist_download_stage", "$stage;complete=$count") },
                    )
                }
                awaitCards(0, "none")
                if (!startsEmpty) {
                    add(units[0])
                    awaitCards(1, "series-first-book")
                    remove(units[0])
                    add(units[1])
                    awaitCards(1, "same-count-different-unit", setOf(expectedTitles[1]))
                    add(units[0])
                    awaitCards(2, "series-both-books")

                    libraryPreferences.showLibraryReadProgress.set(true)
                    awaitCards(2, "reading-progress-independent")
                    remove(units[0])
                    awaitCards(1, "deleted-first", setOf(expectedTitles[1]))
                    remove(units[1])
                    awaitCards(0, "deleted-all")
                    check(downloads.getDownloadCount(manga) == 0)
                }
                check(server.requests.get() == 0 && server.progressReads.get() == 0 && server.writes.get() == 0)
                check(server.coverReads.get() == 0)
                val details = context.stringResource(MR.strings.entry_open_details)
                val button = withTimeout(10_000) {
                    var found: AccessibilityNodeInfo? = null
                    while (found == null) {
                        found = find(instrumentation.uiAutomation.rootInActiveWindow, details)
                        if (found == null) delay(50)
                    }
                    found
                }
                val bounds = Rect().also { button.getBoundsInScreen(it) }
                instrumentation.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")
                    .close()
                withTimeout(15_000) {
                    while (navigator?.lastItem !is MangaScreen ||
                        "Owned download states" !in labels(instrumentation.uiAutomation.rootInActiveWindow)
                    ) {
                        delay(50)
                    }
                }
                val screen = navigator!!.lastItem as MangaScreen
                val canonical = checkNotNull(mangas.getMangaByUrlAndSourceId(manga.url, profile.id))
                stored = canonical
                check(screen.mangaId == canonical.id && screen.sourceId == profile.id && screen.mangaUrl == manga.url)
                check(mangas.getMangaBySourceId(profile.id).single().id == canonical.id)
                if (startsEmpty) {
                    withTimeout(15_000) {
                        while (!mangas.getMangaById(canonical.id).initialized ||
                            chapters.getChapterByMangaId(canonical.id).size != 1
                        ) {
                            delay(50)
                        }
                    }
                    val loaded = mangas.getMangaById(canonical.id)
                    check(
                        loaded.author.isNullOrBlank() && loaded.artist.isNullOrBlank() &&
                            loaded.description.isNullOrBlank(),
                    )
                    val unit = chapters.getChapterByMangaId(canonical.id).single()
                    check(session.identity.chapter(unit.url).chapterId == 1L)
                    check(downloads.getDownloadCount(loaded) == 0)
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putString(
                                "readlist_cold_entry",
                                "initialMangas=0;canonical=true;chapters=1;metadataEmpty=true",
                            )
                        },
                    )
                }
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString("readlist_details", "MangaScreen;canonical-owner;single-series;missing-metadata")
                    },
                )
                scenario.onActivity { check(navigator!!.pop()) }
                awaitCards(0, "returned-from-details")
                check(server.requests.get() == 0) { "Unhandled fixture routes: ${server.unhandled}" }
                check(server.writes.get() == 0)
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString(
                            "readlist_protocol",
                            "unexpected=0;writes=0;detailsProgressReads=${server.progressReads.get()};" +
                                "detailsCoverReads=${server.coverReads.get()}",
                        )
                    },
                )
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    source?.let { active -> stored?.let { downloads.deleteManga(it, active, removeQueued = false) } }
                    profiles.remove(profile.id).getOrThrow()
                    mangas.deleteMangaBySourceId(profile.id)
                    check(connections.getProfiles().none { it.id == profile.id })
                    check(mangas.getMangaBySourceId(profile.id).isEmpty())
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putString("readlist_download_cleanup", "complete")
                        },
                    )
                } finally {
                    server.close()
                    restores.asReversed().forEach { it() }
                    if (hadPrevious) {
                        connections.activeConnectionId.set(
                            previous,
                        )
                    } else {
                        connections.activeConnectionId.delete()
                    }
                }
            }
        }
    }

    private suspend fun recoverInterruptedFixture(id: Long) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "app.koharia.dev.devicefixture")
        val name = checkNotNull(InstrumentationRegistry.getArguments().getString("recoverReadListName"))
        check(name.startsWith("Download states "))
        UUID.fromString(name.removePrefix("Download states "))
        val profiles = Injekt.get<ConnectionProfileManager>()
        val before = profiles.profiles()
        val profile = before.single { it.id == id }
        check(profile.providerId == "kavita" && profile.name == name)
        val preferences = KavitaPreferences(id)
        check(java.net.URI(preferences.address).host == "127.0.0.1" && preferences.key == "fixture-key")
        val source = withTimeout(10_000) {
            while (Injekt.get<SourceManager>().get(id) !is KavitaSource) delay(25)
            Injekt.get<SourceManager>().get(id) as KavitaSource
        }
        val mangas = Injekt.get<MangaRepository>()
        val manga = mangas.getMangaBySourceId(id).single()
        val identity = source.session().identity
        check(manga.title == "Owned download states" && manga.url == identity.series(10))
        val units = Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id)
        check(
            units.size == 2 && units.map { it.url }.toSet() == (1L..2L).map {
                identity.chapter(KavitaChapterRef(30, 10, 20, it, 2))
            }.toSet(),
        )
        val provider = Injekt.get<DownloadProvider>()
        val names = units.map { provider.getChapterDirName(it.name, it.scanlator, it.url) + ".cbz" }.toSet()
        val seed = archive()
        val directories = provider.findMangaDirs(manga.title, source)
        check(directories.size == 1)
        val files = checkNotNull(directories.single().listFiles()).toList()
        check(files.isNotEmpty() && files.all { it.name in names })
        files.forEach { check(it.openInputStream().use { input -> input.readBytes() }.contentEquals(seed)) }
        val connections = Injekt.get<ConnectionPreferences>()
        val previous = connections.activeConnectionId.get()
        check(previous != id)
        Injekt.get<DownloadManager>().deleteManga(manga, source, removeQueued = false)
        profiles.remove(id).getOrThrow()
        mangas.deleteMangaBySourceId(id)
        check(profiles.profiles().toSet() == before.filterNot { it.id == id }.toSet())
        check(mangas.getMangaBySourceId(id).isEmpty() && provider.findOwnedSourceDirs(source).isEmpty())
        check(connections.activeConnectionId.get() == previous)
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("readlist_interrupted_cleanup", "$id;exact-profile-dummy-account-SQL-bytes-verified;removed")
            },
        )
    }

    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && node.text?.toString() == text) return node
        repeat(node.childCount) { find(node.getChild(it), text)?.let { return it } }
        return null
    }

    private fun labels(node: AccessibilityNodeInfo?): List<String> {
        if (node == null) return emptyList()
        return buildList {
            if (node.isVisibleToUser) {
                node.text?.toString()?.let(::add)
                node.contentDescription?.toString()?.let(::add)
            }
            repeat(node.childCount) { addAll(labels(node.getChild(it))) }
        }
    }

    private fun archive(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.CYAN)
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        return ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("001.png").apply { time = 1577836800000L })
                zip.write(png)
                zip.closeEntry()
            }
        }.toByteArray()
    }

    private class UnexpectedRequestServer(seed: ByteArray, writable: Boolean = false) : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val requests = AtomicInteger()
        val unhandled = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val progressReads = AtomicInteger()
        val writes = AtomicInteger()
        val coverReads = AtomicInteger()
        private val cover = ZipInputStream(seed.inputStream()).use {
            checkNotNull(it.nextEntry)
            it.readBytes()
        }
        val authenticationReads = AtomicInteger()

        @Volatile var authenticationUnavailable = false

        @Volatile var account = KavitaAccount(
            id = 129,
            username = "fixture",
            token = "fixture-token",
            kavitaVersion = "0.8.8",
            roles = if (writable) listOf("Download") else emptyList(),
        )
        val address get() = "http://127.0.0.1:${listener.localPort}"

        init {
            executor.execute {
                while (!listener.isClosed) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    runCatching {
                        socket.use {
                            it.soTimeout = 5_000
                            val request = it.getInputStream().bufferedReader().readLine().split(' ')
                            val path = request[1].substringBefore('?')
                            if (request[0] != "GET" && path !in setOf(
                                    "/api/Plugin/authenticate",
                                    "/api/Account/refresh-account",
                                    "/hubs/messages/negotiate",
                                )
                            ) {
                                writes.incrementAndGet()
                            }
                            val (status, body) = when (path) {
                                "/api/Plugin/authenticate", "/api/Account/refresh-account" -> {
                                    authenticationReads.incrementAndGet()
                                    if (authenticationUnavailable) {
                                        "503 Fixture" to ""
                                    } else {
                                        "200 OK" to
                                            Json.encodeToString(account)
                                    }
                                }
                                "/hubs/messages/negotiate" -> "404 Not Found" to ""
                                "/api/Image/chapter-cover", "/api/Image/series-cover" -> {
                                    check(request[0] == "GET")
                                    coverReads.incrementAndGet()
                                    "200 OK" to ""
                                }
                                "/api/Reader/get-progress" -> {
                                    check(request[0] == "GET")
                                    progressReads.incrementAndGet()
                                    "503 Fixture" to ""
                                }
                                else -> {
                                    requests.incrementAndGet()
                                    unhandled.add("${request[0]} $path")
                                    "503 Fixture" to ""
                                }
                            }
                            val isCover = path in setOf("/api/Image/chapter-cover", "/api/Image/series-cover")
                            val bytes = if (isCover) cover else body.toByteArray()
                            val media = if (isCover) "image/png" else "application/json"
                            it.getOutputStream().write(
                                (
                                    "HTTP/1.1 $status\r\nContent-Type: $media\r\n" +
                                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                    ).toByteArray() + bytes,
                            )
                        }
                    }
                }
            }
        }

        override fun close() {
            listener.close()
            executor.shutdownNow()
        }
    }
}
