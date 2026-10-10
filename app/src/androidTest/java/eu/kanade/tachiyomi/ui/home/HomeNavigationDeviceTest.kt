package eu.kanade.tachiyomi.ui.home

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.TabNavigator
import eu.kanade.presentation.more.settings.PreferenceScreen
import eu.kanade.presentation.more.settings.screen.SettingsLibraryScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.library.BooksTab
import eu.kanade.tachiyomi.ui.library.ComicsTab
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.more.MoreTab
import koharia.connection.ConnectionOrganizationEntry
import koharia.connection.ConnectionOrganizationNavigation
import koharia.connection.ConnectionOrganizationPage
import koharia.connection.SharedAppPreferenceScope
import koharia.connection.ui.CollectionsTab
import koharia.connection.ui.ConnectionLibraryToolbar
import koharia.connection.ui.ConnectionOrganizationDirectoryState
import koharia.connection.ui.OrganizationContentHost
import koharia.connection.ui.OrganizationsTab
import koharia.connection.ui.ReadListsTab
import koharia.connection.ui.organizationNavigationTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.ScopedPreferenceStore
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.util.collectAsState
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class HomeNavigationDeviceTest {
    @Test
    fun organizationNamesOpenTypedContentsAndAllRestoresCombinedPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val collection = ConnectionOrganizationEntry(ConnectionOrganizationPage.COLLECTIONS, "same", "A")
        val readList = ConnectionOrganizationEntry(ConnectionOrganizationPage.READ_LISTS, "same", "A")
        val collectionTitle = context.stringResource(MR.strings.komga_collections)
        val readListTitle = context.stringResource(MR.strings.komga_filter_read_lists)
        val collectionOnly = collection.copy(id = "collection-only", name = "C")
        val readListOnly = readList.copy(id = "read-list-only", name = "R")
        val all = context.stringResource(MR.strings.all)
        var session by mutableStateOf("account-a")
        var pages by mutableStateOf(ConnectionOrganizationPage.entries.toList())
        var directory by mutableStateOf(
            ConnectionOrganizationDirectoryState(
                listOf(collection, collectionOnly, readList, readListOnly),
                loaded = true,
                loading = false,
            ),
        )
        var retries = 0
        ActivityScenario.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        Column(Modifier.fillMaxSize().statusBarsPadding()) {
                            OrganizationContentHost(session, pages, directory, onReload = { retries++ }) {
                                    page,
                                    id,
                                    pageTabs,
                                ->
                                Column {
                                    ConnectionLibraryToolbar(
                                        title = "Organization toolbar",
                                        searchQuery = null,
                                        onSearchQueryChange = {},
                                        onSearch = {},
                                        onCloseSearch = {},
                                        displayMode = LibraryDisplayMode.CompactGrid,
                                        onDisplayModeChange = {},
                                        connectionProfiles = emptyList(),
                                        activeConnectionId = 0L,
                                        onConnectionSelect = {},
                                        onFilterClick = {},
                                        onRefresh = {},
                                        navigateUp = null,
                                    )
                                    pageTabs()
                                    HorizontalDivider()
                                    Text("$session:$page:${id ?: "all"}")
                                }
                            }
                        }
                    }
                }
            }
            await("All must be the default with type tabs above names of only the selected type") {
                hasText("account-a:COLLECTIONS:all") && hasText(collectionTitle) && hasText(readListTitle) &&
                    hasText("A") && hasText("C") && !hasText("R") &&
                    tabNode(all)?.config?.getOrNull(SemanticsProperties.Selected) == true &&
                    tabNode(collectionTitle)!!.boundsInRoot.bottom <= tabNode(all)!!.boundsInRoot.top
            }
            clickTab("A")
            await("Collection name must open its contents while type tabs stay visible") {
                hasText("account-a:COLLECTIONS:same") && hasText(collectionTitle) && hasText(readListTitle)
            }
            clickTab(readListTitle)
            await("Type switch must reset to All and show only read list names") {
                hasText("account-a:READ_LISTS:all") && hasText("A") && hasText("R") && !hasText("C") &&
                    tabNode(all)?.config?.getOrNull(SemanticsProperties.Selected) == true
            }
            clickTab("A")
            await("Same ID in a read list must open the read list contents") { hasText("account-a:READ_LISTS:same") }
            capture("organization-name-tabs.png")
            clickTab(all)
            await("All must restore the original page with its current type") {
                hasText("account-a:READ_LISTS:all") && hasText(collectionTitle) && hasText(readListTitle)
            }
            clickTab(collectionTitle)
            await("Returning to collections must restore its All view and names") {
                hasText("account-a:COLLECTIONS:all") && hasText("C") && !hasText("R")
            }
            clickTab("A")
            scenario.onActivity { directory = directory.copy(entries = listOf(collectionOnly, readList, readListOnly)) }
            await("Deleting the selected collection must fall back to All") {
                hasText("account-a:COLLECTIONS:all") &&
                    tabNode(all)?.config?.getOrNull(SemanticsProperties.Selected) == true && !hasText("A")
            }
            clickTab(readListTitle)
            await("The other type must retain its same-named entry after deleting a collection") {
                hasText("account-a:READ_LISTS:all") && hasText("A") && hasText("R") && !hasText("C")
            }
            clickTab("A")
            await("Remaining unique name must still open the read list") { hasText("account-a:READ_LISTS:same") }
            scenario.onActivity { session = "account-b" }
            await("Changing the account must reset name and type selection") {
                hasText("account-b:COLLECTIONS:all") && hasText("C") && !hasText("R")
            }
            scenario.onActivity {
                pages = listOf(ConnectionOrganizationPage.READ_LISTS)
                directory = directory.copy(entries = listOf(readList))
            }
            await("Independent mode must show its names without type tabs") {
                hasText("account-b:READ_LISTS:all") && hasText("A") && !hasText(collectionTitle) &&
                    !hasText(readListTitle)
            }
            clickTab("A")
            await("Independent mode must open the selected name") { hasText("account-b:READ_LISTS:same") }
            scenario.onActivity { directory = directory.copy(entries = emptyList()) }
            await("An empty directory must retain All and clear deleted selection") {
                hasText("account-b:READ_LISTS:all") && hasText(all) && !hasText("A")
            }
            scenario.onActivity { directory = directory.copy(error = IllegalStateException("offline")) }
            val retry = context.stringResource(MR.strings.action_retry)
            await("Failed name refresh must offer retry") { hasText(retry) }
            clickText(retry)
            assertEquals(1, retries)
        }
    }

    @Test
    fun organizationSettingsChangeNavigationAndCombinedTopTabs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val preferenceName = "organization-navigation-${UUID.randomUUID()}"
        val store = AndroidPreferenceStore(context, context.getSharedPreferences(preferenceName, 0))
        val preferences = LibraryPreferences(ScopedPreferenceStore(store, SharedAppPreferenceScope))
        var supported by mutableStateOf(ConnectionOrganizationPage.entries.toSet())
        var connectionId by mutableStateOf(42L)
        var navigationTitles = emptyList<String>()
        var settingTitles = emptyList<String>()
        try {
            ActivityScenario.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        MaterialTheme {
                            Navigator(FixtureScreen) {
                                TabNavigator(LibraryTab) { tabNavigator ->
                                    val collections by preferences.showCollections.collectAsState()
                                    val readLists by preferences.showReadLists.collectAsState()
                                    val merge by preferences.mergeOrganizationPages.collectAsState()
                                    val navigation = ConnectionOrganizationNavigation.create(
                                        supported,
                                        collections,
                                        readLists,
                                        merge,
                                    )
                                    val tabs = listOf(LibraryTab) + organizationNavigationTabs(navigation) +
                                        listOf(HistoryTab, MoreTab)
                                    navigationTitles = tabs.map { it.options.title }
                                    val group = SettingsLibraryScreen.getOrganizationGroup(preferences, supported)
                                    settingTitles = group?.preferenceItems?.map { it.title }.orEmpty()
                                    LaunchedEffect(tabs) {
                                        if (tabNavigator.current !in tabs) tabNavigator.current = LibraryTab
                                    }
                                    Scaffold(
                                        bottomBar = { HomeScreen.BottomNavigationBar(tabs) },
                                        contentWindowInsets = WindowInsets(0),
                                    ) { padding ->
                                        Column(Modifier.padding(padding).statusBarsPadding()) {
                                            PreferenceScreen(listOfNotNull(group), Modifier.weight(1f))
                                            HorizontalDivider()
                                            Box(Modifier.weight(1f)) {
                                                if (navigation.combined) {
                                                    OrganizationContentHost(
                                                        connectionId.toString(),
                                                        navigation.pages,
                                                        ConnectionOrganizationDirectoryState(
                                                            loaded = true,
                                                            loading = false,
                                                        ),
                                                        onReload = {},
                                                    ) {
                                                            page,
                                                            _,
                                                            pageTabs,
                                                        ->
                                                        Column {
                                                            Text("Organization toolbar")
                                                            pageTabs()
                                                            Text("$connectionId:$page")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                await("Default navigation must show separate destinations") {
                    navigationTitles.size == 5 && settingTitles.size == 3 && settingTitles.all(::hasText)
                }
                clickText(settingTitles[2])
                await("Combined mode must leave one organization destination") {
                    navigationTitles.size == 4 && hasText("42:COLLECTIONS")
                }
                val readListTitle = context.stringResource(MR.strings.komga_filter_read_lists)
                clickTab(readListTitle)
                await("Top tabs must switch to read lists") { hasText("42:READ_LISTS") }
                capture("combined-organization-tabs.png")
                scenario.onActivity { connectionId = 43 }
                await("Switching connections must reset the selected organization type") {
                    hasText("43:COLLECTIONS")
                }
                clickText(settingTitles[0])
                await("Hiding collections must keep only read lists") {
                    navigationTitles.size == 4 &&
                        !hasText("43:COLLECTIONS")
                }
                assertFalse(preferences.showCollections.get())
                assertTrue(preferences.mergeOrganizationPages.get())
                clickText(settingTitles[1])
                await("Hiding both destinations must leave library history and more") { navigationTitles.size == 3 }
                clickText(settingTitles[0])
                await("Re-enabling only collections must restore its separate destination") {
                    navigationTitles.size == 4
                }
                clickText(settingTitles[1])
                await("Re-enabling both must restore the saved combined mode") { hasText("43:COLLECTIONS") }
                clickText(settingTitles[2])
                await("Disabling combined mode must restore two independent destinations") {
                    navigationTitles.size == 5
                }
                val restored = LibraryPreferences(ScopedPreferenceStore(store, SharedAppPreferenceScope))
                assertTrue(restored.showCollections.get())
                assertTrue(restored.showReadLists.get())
                assertFalse(restored.mergeOrganizationPages.get())
                scenario.onActivity { supported = emptySet() }
                await("Providers without organization support must not show its settings or destinations") {
                    settingTitles.isEmpty() && navigationTitles.size == 3
                }
            }
        } finally {
            context.deleteSharedPreferences(preferenceName)
        }
    }

    private fun hasText(text: String): Boolean = WindowInspector.getGlobalWindowViews().asSequence()
        .flatMap(::views).filterIsInstance<ViewRootForTest>()
        .flatMap { nodes(it.semanticsOwner.rootSemanticsNode) }
        .any { it.config.getOrNull(SemanticsProperties.Text)?.any { textValue -> textValue.text == text } == true }

    private fun clickText(text: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            var node = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::views)
                .filterIsInstance<ViewRootForTest>().flatMap { nodes(it.semanticsOwner.rootSemanticsNode) }
                .first { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true }
            while (node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent!!
            assertTrue(node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
        }
    }

    @Test
    fun navigationRemainsVisibleAcrossTabCountsAndViewportWidths() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val sixTabs = listOf(ComicsTab, BooksTab, CollectionsTab, ReadListsTab, HistoryTab, MoreTab)
        var tabs by mutableStateOf<List<Tab>>(sixTabs)
        var width by mutableStateOf(400.dp)
        var titles = emptyList<String>()
        var navigator: TabNavigator? = null
        ActivityScenario.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        Navigator(FixtureScreen) {
                            TabNavigator(ComicsTab) { tabNavigator ->
                                navigator = tabNavigator
                                titles = tabs.map { it.options.title }
                                Scaffold(
                                    modifier = Modifier.width(width).fillMaxSize(),
                                    bottomBar = { HomeScreen.BottomNavigationBar(tabs) },
                                    contentWindowInsets = WindowInsets(0),
                                ) { padding ->
                                    Box(Modifier.padding(padding).statusBarsPadding()) {
                                        Text(tabNavigator.current.options.title)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            await("Six navigation items must have visible bounds") {
                titles.size == 6 && titles.all { title ->
                    tabNode(title)?.boundsInRoot?.let { it.width > 0 && it.height > 0 } == true
                }
            }
            clickTab(titles[2])
            scenario.onActivity { assertEquals(CollectionsTab, navigator?.current) }
            clickTab(titles[3])
            scenario.onActivity { assertEquals(ReadListsTab, navigator?.current) }
            await("Read list navigation item must be selected") {
                tabNode(titles[3])?.config?.getOrNull(SemanticsProperties.Selected) == true
            }
            capture("six-tabs.png")

            scenario.onActivity {
                width = 320.dp
                navigator?.current = MoreTab
            }
            await("Selected last item must scroll fully into the narrow viewport") {
                tabNode(titles.last())?.let {
                    it.config.getOrNull(SemanticsProperties.Selected) == true &&
                        it.boundsInRoot.width >= 64 * context.resources.displayMetrics.density - 1 &&
                        it.boundsInRoot.right <=
                        320 * context.resources.displayMetrics.density + 1
                } == true
            }
            capture("narrow-six-tabs.png")
            scenario.onActivity { navigator?.current = ComicsTab }
            await("First item must scroll back into view") {
                tabNode(titles.first())?.let {
                    it.config.getOrNull(SemanticsProperties.Selected) == true &&
                        it.boundsInRoot.left >= 0 &&
                        it.boundsInRoot.width >= 64 * context.resources.displayMetrics.density - 1
                } == true
            }

            val configurations = listOf(
                listOf(LibraryTab, CollectionsTab, ReadListsTab, HistoryTab, MoreTab),
                listOf(ComicsTab, BooksTab, OrganizationsTab, HistoryTab, MoreTab),
                listOf(LibraryTab, OrganizationsTab, HistoryTab, MoreTab),
                listOf(ComicsTab, BooksTab, SuwayomiBrowseTab, HistoryTab, MoreTab),
                listOf(LibraryTab, SuwayomiBrowseTab, HistoryTab, MoreTab),
                listOf(ComicsTab, BooksTab, HistoryTab, MoreTab),
                listOf(LibraryTab, HistoryTab, MoreTab),
                sixTabs,
            )
            for (configuration in configurations) {
                scenario.onActivity {
                    width = 400.dp
                    tabs = configuration
                    navigator?.current = configuration.first()
                }
                await("Navigation must resize after switching connection capabilities") {
                    titles.size == configuration.size &&
                        tabNode(titles.first())?.config?.getOrNull(SemanticsProperties.Selected) == true &&
                        titles.all { title ->
                            tabNode(title)?.boundsInRoot?.let { it.width > 0 && it.height > 0 } == true
                        }
                }
                clickTab(titles.last())
                scenario.onActivity { assertEquals(MoreTab, navigator?.current) }
            }
            scenario.onActivity { width = 800.dp }
            await("Requested navigation width must remain bounded by the window") {
                titles.all { title ->
                    tabNode(title)?.boundsInRoot?.let {
                        it.width > 0 && it.right <= context.resources.displayMetrics.widthPixels
                    } == true
                }
            }
        }
    }

    private fun tabNode(title: String): SemanticsNode? {
        return WindowInspector.getGlobalWindowViews().asSequence()
            .flatMap(::views)
            .filterIsInstance<ViewRootForTest>()
            .flatMap { nodes(it.semanticsOwner.rootSemanticsNode) }
            .firstOrNull { node ->
                node.config.getOrNull(SemanticsProperties.Selected) != null &&
                    nodes(node).any { child ->
                        child.config.getOrNull(SemanticsProperties.Text)?.any { it.text == title } == true
                    }
            }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        throw AssertionError(message)
    }

    private fun clickTab(title: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(tabNode(title)?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
        }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Semantics update before the navigation indicator's animation finishes drawing.
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "home-navigation").apply {
            mkdirs()
        }
        File(directory, name).outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun nodes(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        node.children.forEach { yieldAll(nodes(it)) }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
        }
    }

    private object FixtureScreen : Screen() {
        @Composable
        override fun Content() {}
    }
}
