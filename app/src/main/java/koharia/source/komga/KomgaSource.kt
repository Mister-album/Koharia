package koharia.source.komga

import android.content.SharedPreferences
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.widget.Button
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.komga.api.KomgaApiClient
import koharia.komga.api.dto.BookDto
import koharia.komga.api.dto.LibraryDto
import koharia.komga.domain.repository.KomgaRepository
import koharia.komga.download.KomgaChapterMemo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class KomgaSource(
    override val id: Long = ID,
    private val customName: String = SOURCE_NAME,
) :
    HttpSource(),
    ConfigurableSource,
    UnmeteredSource {

    private val preferences: SharedPreferences by lazy { sourcePreferences() }
    private val json: Json by injectLazy()
    private val application: android.app.Application by lazy { Injekt.get() }

    override val name: String = customName
    override val lang: String = SOURCE_LANG
    override val supportsLatest: Boolean = true
    override val versionId: Int = SOURCE_VERSION

    override val baseUrl: String
        get() = preferences.getString(PREF_ADDRESS, "")!!.removeSuffix("/")

    private val username: String
        get() = preferences.getString(PREF_USERNAME, "")!!

    private val password: String
        get() = preferences.getString(PREF_PASSWORD, "")!!

    private val authMode: String
        get() = preferences.getString(PREF_AUTH_MODE, null) ?: defaultAuthMode()

    private val apiKey: String
        get() = preferences.getString(PREF_API_KEY, null)
            ?: preferences.getString(PREF_API_KEY_WRONG_CASE, "")!!

    private val defaultLibraries: Set<String>
        get() = preferences.getStringSet(PREF_DEFAULT_LIBRARIES, emptySet()) ?: emptySet()

    private val chapterNameTemplate: String
        get() = preferences.getString(PREF_CHAPTER_NAME_TEMPLATE, PREF_CHAPTER_NAME_TEMPLATE_DEFAULT)!!

    private val apiClient: KomgaApiClient
        get() = KomgaApiClient(baseUrl, currentHeaders(), client, json)

    private val repository: KomgaRepository
        get() = KomgaRepository(baseUrl, apiClient)
    private val forceBrowseRequestsUntil = AtomicLong(0L)

    fun currentHeaders(): Headers = headersBuilder().build()

    fun currentReadiumHeaders(): Headers = currentHeaders().newBuilder()
        .also { builder ->
            builder.removeAll("X-API-Key")
            builder.removeAll("Authorization")
            when (authMode) {
                AUTH_MODE_API_KEY -> if (apiKey.isNotBlank()) {
                    builder.set("X-API-Key", apiKey)
                }
                AUTH_MODE_CREDENTIALS -> if (username.isNotBlank() && password.isNotBlank()) {
                    builder.set("Authorization", Credentials.basic(username, password))
                }
            }
        }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", "KohariaKomga/${AppInfo.getVersionName()}")
        .also { builder ->
            if (apiKey.isNotBlank()) {
                builder.set("X-API-Key", apiKey)
            }
        }

    override val client = super.client.newBuilder()
        .addInterceptor(KomgaOfflineInterceptor(application))
        .addNetworkInterceptor(KomgaCacheControlInterceptor(application))
        .addInterceptor { chain ->
            val original = chain.request()
            val newBuilder = original.newBuilder()

            if (authMode == AUTH_MODE_API_KEY && apiKey.isNotBlank()) {
                newBuilder.addHeader("X-Komga-Api-Key", apiKey)
            } else if (authMode == AUTH_MODE_CREDENTIALS && username.isNotBlank() && password.isNotBlank()) {
                newBuilder.addHeader("Authorization", Credentials.basic(username, password))
            }
            chain.proceed(newBuilder.build())
        }
        .dns(Dns.SYSTEM)
        .build()

    override fun popularMangaRequest(page: Int): Request =
        repository.popularMangaRequest(page, defaultLibraries, consumeBrowseCachePolicy())

    override fun popularMangaParse(response: Response) = repository.parseMangasPage(response)

    override fun latestUpdatesRequest(page: Int): Request =
        repository.latestUpdatesRequest(page, defaultLibraries, consumeBrowseCachePolicy())

    override fun latestUpdatesParse(response: Response) = repository.parseMangasPage(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        repository.searchMangaRequest(page, query, filters, defaultLibraries, consumeBrowseCachePolicy())

    override fun searchMangaParse(response: Response) = repository.parseMangasPage(response)

    override fun getMangaUrl(manga: eu.kanade.tachiyomi.source.model.SManga): String = manga.url.replace("/api/v1", "")

    override fun mangaDetailsRequest(manga: eu.kanade.tachiyomi.source.model.SManga): Request =
        repository.mangaDetailsRequest(manga, KomgaCachePolicy.NetworkFirst)

    override fun mangaDetailsParse(response: Response) = repository.mangaDetailsParse(response)

    override fun chapterListRequest(manga: eu.kanade.tachiyomi.source.model.SManga): Request =
        repository.chapterListRequest(manga, KomgaCachePolicy.NetworkFirst)

    override fun chapterListParse(response: Response) = repository.chapterListParse(response, chapterNameTemplate)

    override fun pageListRequest(chapter: eu.kanade.tachiyomi.source.model.SChapter): Request =
        repository.pageListRequest(chapter, KomgaCachePolicy.Default)

    override fun pageListParse(response: Response) = repository.pageListParse(response)

    suspend fun getPageList(
        chapter: eu.kanade.tachiyomi.source.model.SChapter,
        cachePolicy: KomgaCachePolicy,
    ): List<Page> {
        return client.newCall(repository.pageListRequest(chapter, cachePolicy))
            .awaitSuccess()
            .let(repository::pageListParse)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request =
        GET(
            KomgaChapterMemo.networkPageImageUrl(page.imageUrl!!),
            headersBuilder().add("Accept", "image/*,*/*;q=0.8").build(),
        )

    fun rawFileRequest(chapterUrl: String, rangeStart: Long? = null): Request = apiClient.bookFileRequest(
        chapterUrl,
        rangeStart,
    )

    fun hasValidBaseUrl(): Boolean = baseUrl.startsWith("http://") || baseUrl.startsWith("https://")

    suspend fun getMe(): koharia.komga.api.dto.UserDto? {
        if (!hasValidBaseUrl()) return null
        return try {
            client.newCall(apiClient.meRequest())
                .awaitSuccess()
                .let { apiClient.parse<koharia.komga.api.dto.UserDto>(it) }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getBookDetails(bookUrl: String): BookDto? {
        if (!hasValidBaseUrl()) return null
        return try {
            client.newCall(apiClient.detailsRequest(bookUrl, KomgaCachePolicy.NetworkFirst))
                .awaitSuccess()
                .let { apiClient.parse<BookDto>(it) }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun updateMangaViewerFlags(mangaId: String, viewerFlags: Long) {
        if (!hasValidBaseUrl()) return
        try {
            apiClient.updateClientSettings(
                mapOf(
                    "koharia.manga.$mangaId.viewerFlags" to
                        koharia.komga.api.dto.ClientSettingUpdateDto(value = viewerFlags.toString()),
                ),
            )
        } catch (e: Exception) {
            // Ignore for now
        }
    }

    suspend fun getMangaViewerFlags(mangaId: String): Long? {
        if (!hasValidBaseUrl()) return null
        return try {
            val settings = client.newCall(
                eu.kanade.tachiyomi.network.GET("$baseUrl/api/v1/client-settings/user/list", headers),
            )
                .awaitSuccess()
                .let { apiClient.parse<Map<String, koharia.komga.api.dto.ClientSettingDto>>(it) }
            settings["koharia.manga.$mangaId.viewerFlags"]?.value?.toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }

    override fun getFilterList(): FilterList {
        fetchFilterOptions()

        val filters = mutableListOf<Filter<*>>(
            TypeSelect(),
            CollectionSelect(
                buildList {
                    add(CollectionFilterEntry("None"))
                    collections.forEach { add(CollectionFilterEntry(it.name, it.id)) }
                },
            ),
            LibraryFilter(libraries, defaultLibraries),
            ReadingStateGroup(),
            UriMultiSelectFilter(
                "Status",
                listOf("Ongoing", "Ended", "Abandoned", "Hiatus").map {
                    UriMultiSelectOption(it, it.uppercase(Locale.ROOT))
                },
            ),
            UriMultiSelectFilter("Genres", genres.map { UriMultiSelectOption(it) }),
            UriMultiSelectFilter("Tags", tags.map { UriMultiSelectOption(it) }),
            UriMultiSelectFilter("Publishers", publishers.map { UriMultiSelectOption(it) }),
        ).apply {
            if (fetchFilterStatus != FetchFilterStatus.FETCHED) {
                val message = if (fetchFilterStatus == FetchFilterStatus.NOT_FETCHED && fetchFiltersAttempts >= 3) {
                    application.stringResource(MR.strings.komga_filter_fetch_failed)
                } else {
                    application.stringResource(MR.strings.komga_filter_fetch_hint)
                }

                add(0, Filter.Header(message))
                add(1, Filter.Separator())
            }

            if (authors.isNotEmpty()) {
                add(Filter.Header(application.stringResource(MR.strings.author)))
                addAll(authors.map { (role, items) -> AuthorGroup(role, items.map { AuthorFilter(it) }) })
            }
            add(SeriesSort())
        }

        return FilterList(filters).also {
            if (isPersistentFilteringEnabled()) {
                applyPersistentFilterState(it)
            }
        }
    }

    fun isPersistentFilteringEnabled(libraryScope: KomgaLibraryScope = KomgaLibraryScope.ALL): Boolean {
        val scopedKey = persistentFilteringEnabledKey(libraryScope)
        if (libraryScope != KomgaLibraryScope.ALL && !preferences.contains(scopedKey)) {
            return preferences.getBoolean(PREF_PERSISTENT_FILTERS_ENABLED, false)
        }
        return preferences.getBoolean(scopedKey, false)
    }

    fun setPersistentFilteringEnabled(
        enabled: Boolean,
        filters: FilterList,
        libraryScope: KomgaLibraryScope = KomgaLibraryScope.ALL,
    ) {
        preferences.edit()
            .putBoolean(persistentFilteringEnabledKey(libraryScope), enabled)
            .apply()

        if (enabled) {
            savePersistentFilterState(filters, libraryScope)
        } else {
            preferences.edit()
                .remove(persistentFilterStateKey(libraryScope))
                .apply()
        }
    }

    fun resetPersistentFilters(libraryScope: KomgaLibraryScope = KomgaLibraryScope.ALL) {
        val editor = preferences.edit()
        if (libraryScope == KomgaLibraryScope.ALL) {
            editor.remove(PREF_PERSISTENT_FILTERS_STATE)
        } else {
            // A scoped default prevents the legacy unscoped state from being restored again.
            editor.putString(
                persistentFilterStateKey(libraryScope),
                json.encodeToString(defaultLibraryPersistentFilterState()),
            )
        }
        editor.apply()
    }

    fun savePersistentFilterState(
        filters: FilterList,
        libraryScope: KomgaLibraryScope = KomgaLibraryScope.ALL,
    ) {
        if (!isPersistentFilteringEnabled(libraryScope)) return

        preferences.edit()
            .putString(
                persistentFilterStateKey(libraryScope),
                json.encodeToString(filters.toPersistentFilterState()),
            )
            .apply()
    }

    fun saveSessionFilterState(
        filters: FilterList,
        libraryScope: KomgaLibraryScope = KomgaLibraryScope.ALL,
    ) {
        synchronized(sessionFilterStates) {
            sessionFilterStates[libraryScope] = filters.toPersistentFilterState()
        }
    }

    fun resetSessionFilterState(libraryScope: KomgaLibraryScope = KomgaLibraryScope.ALL) {
        synchronized(sessionFilterStates) {
            sessionFilterStates.remove(libraryScope)
        }
    }

    private fun applyPersistentFilterState(
        filters: FilterList,
        libraryScope: KomgaLibraryScope = KomgaLibraryScope.ALL,
    ): Boolean {
        val scopedState = preferences.getString(persistentFilterStateKey(libraryScope), null)
        val legacyState = if (libraryScope != KomgaLibraryScope.ALL) {
            preferences.getString(PREF_PERSISTENT_FILTERS_STATE, null)
        } else {
            null
        }
        val saved = (scopedState ?: legacyState)
            ?.let { runCatching { json.decodeFromString<PersistentFilterState>(it) }.getOrNull() }
            ?: return false

        if (scopedState != null && libraryScope != KomgaLibraryScope.ALL) {
            filters.resetFilterState()
        }
        filters.applyPersistentFilterState(saved)
        return true
    }

    private fun FilterList.applyPersistentFilterState(saved: PersistentFilterState) {
        forEach { filter ->
            when (filter) {
                is Filter.CheckBox -> saved.checkBoxes[filter.name]?.let { filter.state = it }
                is Filter.Select<*> -> saved.selects[filter.name]?.let { index ->
                    if (index in filter.values.indices) {
                        filter.state = index
                    }
                }
                is Filter.Sort -> saved.sorts[filter.name]?.let { sort ->
                    if (sort.index in filter.values.indices) {
                        filter.state = Filter.Sort.Selection(sort.index, sort.ascending)
                    }
                }
                is Filter.Group<*> -> {
                    val selected = saved.groups[filter.name]
                    filter.state
                        .filterIsInstance<Filter.CheckBox>()
                        .forEach { option ->
                            option.state = if (selected != null) {
                                option.persistentOptionKey() in selected
                            } else {
                                // Preserve filters saved before standalone checkboxes were grouped in the UI.
                                saved.checkBoxes[option.name] ?: option.state
                            }
                        }
                }
                else -> {}
            }
        }
    }

    private fun persistentFilterStateKey(libraryScope: KomgaLibraryScope): String {
        return when (libraryScope) {
            KomgaLibraryScope.ALL -> PREF_PERSISTENT_FILTERS_STATE
            KomgaLibraryScope.COMIC -> PREF_PERSISTENT_FILTERS_STATE_COMIC
            KomgaLibraryScope.BOOK -> PREF_PERSISTENT_FILTERS_STATE_BOOK
        }
    }

    private fun persistentFilteringEnabledKey(libraryScope: KomgaLibraryScope): String {
        return when (libraryScope) {
            KomgaLibraryScope.ALL -> PREF_PERSISTENT_FILTERS_ENABLED
            KomgaLibraryScope.COMIC -> PREF_PERSISTENT_FILTERS_ENABLED_COMIC
            KomgaLibraryScope.BOOK -> PREF_PERSISTENT_FILTERS_ENABLED_BOOK
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        fetchFilterOptions()

        val serverProfileManager = Injekt.get<KomgaServerProfileManager>()
        val serverName = Injekt.get<KomgaServerPreferences>()
            .getProfiles()
            .find { it.id == id }
            ?.name
            ?: name
        screen.addEditTextPreference(
            title = screen.context.stringResource(MR.strings.komga_server_name_title),
            default = serverName,
            summary = serverName,
            dialogMessage = screen.context.stringResource(MR.strings.komga_server_name_help),
            validate = { value ->
                value.trim().isNotEmpty() &&
                    serverProfileManager.isDirectoryNameAvailable(value, id)
            },
            validationMessage = screen.context.stringResource(MR.strings.komga_server_name_validation),
            key = PREF_SERVER_PROFILE_NAME,
            allowBlank = false,
            showValueAsSummary = true,
        )

        screen.addEditTextPreference(
            title = screen.context.stringResource(MR.strings.komga_pref_address_title),
            default = "",
            summary = baseUrl.ifBlank { screen.context.stringResource(MR.strings.komga_pref_address_summary) },
            dialogMessage = screen.context.stringResource(MR.strings.komga_pref_address_dialog),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            validate = { it.startsWith("http://") || it.startsWith("https://") },
            validationMessage = screen.context.stringResource(MR.strings.komga_pref_address_validation),
            key = PREF_ADDRESS,
        )
        val authModePref = androidx.preference.ListPreference(screen.context).apply {
            key = PREF_AUTH_MODE
            title = screen.context.stringResource(MR.strings.komga_pref_auth_mode_title)
            entries = arrayOf(
                screen.context.stringResource(MR.strings.komga_pref_auth_mode_credentials),
                screen.context.stringResource(MR.strings.komga_pref_auth_mode_api_key),
            )
            entryValues = arrayOf(AUTH_MODE_CREDENTIALS, AUTH_MODE_API_KEY)
            setDefaultValue(defaultAuthMode())
            summary = "%s"
        }.also(screen::addPreference)

        val usernamePref = screen.addEditTextPreference(
            title = screen.context.stringResource(MR.strings.komga_pref_username_title),
            default = "",
            summary = username.ifBlank { screen.context.stringResource(MR.strings.komga_pref_username_summary) },
            key = PREF_USERNAME,
        )
        val passwordPref = screen.addEditTextPreference(
            title = screen.context.stringResource(MR.strings.komga_pref_password_title),
            default = "",
            summary = if (password.isBlank()) {
                screen.context.stringResource(MR.strings.komga_pref_password_summary)
            } else {
                "*".repeat(password.length)
            },
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            key = PREF_PASSWORD,
        )
        val apiKeyPref = screen.addEditTextPreference(
            title = screen.context.stringResource(MR.strings.komga_pref_api_key_title),
            default = "",
            summary = if (apiKey.isBlank()) {
                screen.context.stringResource(MR.strings.komga_pref_api_key_summary)
            } else {
                "*".repeat(apiKey.length)
            },
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            key = PREF_API_KEY,
        )

        fun updateAuthFieldsVisibility(mode: String) {
            val isCredentials = mode == AUTH_MODE_CREDENTIALS
            usernamePref.isVisible = isCredentials
            passwordPref.isVisible = isCredentials
            apiKeyPref.isVisible = !isCredentials
        }

        authModePref.setOnPreferenceChangeListener { _, newValue ->
            updateAuthFieldsVisibility(newValue as String)
            true
        }

        val initialAuthMode = screen.preferenceManager.preferenceDataStore
            ?.getString(PREF_AUTH_MODE, null)
            ?: preferences.getString(PREF_AUTH_MODE, null)
            ?: defaultAuthMode()
        updateAuthFieldsVisibility(initialAuthMode)

        androidx.preference.Preference(screen.context).apply {
            key = PREF_DEFAULT_LIBRARIES
            title = screen.context.stringResource(MR.strings.komga_pref_default_libraries_title)
            summary = screen.context.stringResource(MR.strings.komga_pref_default_libraries_summary)
            setDefaultValue(emptySet<String>())

            var isFetching = false

            setOnPreferenceClickListener { pref ->
                if (isFetching) return@setOnPreferenceClickListener true
                isFetching = true

                val dataStore = pref.preferenceManager.preferenceDataStore
                val currentAddress = (dataStore?.getString(PREF_ADDRESS, "") ?: "").removeSuffix("/")
                val currentAuthMode =
                    dataStore?.getString(PREF_AUTH_MODE, null) ?: defaultAuthMode()
                val currentUsername = dataStore?.getString(PREF_USERNAME, "") ?: ""
                val currentPassword = dataStore?.getString(PREF_PASSWORD, "") ?: ""
                val currentApiKey = dataStore?.getString(PREF_API_KEY, null)
                    ?: dataStore?.getString(PREF_API_KEY_WRONG_CASE, null)
                    ?: ""

                val currentSelection = dataStore?.getStringSet(PREF_DEFAULT_LIBRARIES, emptySet()) ?: emptySet()

                scope.launch(Dispatchers.Main) {
                    val fetchedLibraries = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        try {
                            if (currentAddress.isBlank()) return@withContext emptyList<LibraryDto>()

                            val tempHeaders = Headers.Builder().apply {
                                if (currentAuthMode == AUTH_MODE_API_KEY && currentApiKey.isNotBlank()) {
                                    add("X-Komga-Api-Key", currentApiKey)
                                } else if (currentAuthMode == AUTH_MODE_CREDENTIALS && currentUsername.isNotBlank() &&
                                    currentPassword.isNotBlank()
                                ) {
                                    add("Authorization", Credentials.basic(currentUsername, currentPassword))
                                }
                            }.build()

                            val cleanClient = Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>().client
                            val tempApiClient = KomgaApiClient(currentAddress, tempHeaders, cleanClient, json)
                            val tempRepo = KomgaRepository(currentAddress, tempApiClient)
                            tempRepo.fetchFilterOptions(forceRefresh = true).libraries
                        } catch (e: Exception) {
                            emptyList<LibraryDto>()
                        }
                    }

                    if (fetchedLibraries.isEmpty()) {
                        isFetching = false
                        return@launch
                    }

                    val names = fetchedLibraries.map { it.name }.toTypedArray<CharSequence>()
                    val checkedItems = fetchedLibraries.map { it.id in currentSelection }.toBooleanArray()
                    val newSelection = currentSelection.toMutableSet()

                    com.google.android.material.dialog.MaterialAlertDialogBuilder(screen.context)
                        .setTitle(screen.context.stringResource(MR.strings.komga_pref_default_libraries_title))
                        .setMultiChoiceItems(names, checkedItems) { _, which, isChecked ->
                            val id = fetchedLibraries[which].id
                            if (isChecked) newSelection.add(id) else newSelection.remove(id)
                        }
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            if (newSelection != currentSelection) {
                                if (callChangeListener(newSelection)) {
                                    dataStore?.putStringSet(PREF_DEFAULT_LIBRARIES, newSelection)
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .setOnDismissListener { isFetching = false }
                        .show()
                }
                true
            }
        }.also(screen::addPreference)

        screen.addEditTextPreference(
            key = PREF_CHAPTER_NAME_TEMPLATE,
            title = screen.context.stringResource(MR.strings.komga_pref_chapter_name_template_title),
            summary = screen.context.stringResource(MR.strings.komga_pref_chapter_name_template_summary),
            inputType = InputType.TYPE_CLASS_TEXT,
            default = PREF_CHAPTER_NAME_TEMPLATE_DEFAULT,
            dialogMessage = screen.context.stringResource(MR.strings.komga_pref_chapter_name_template_dialog),
        )
    }

    suspend fun getBrowseLibraries(forceRefresh: Boolean = false): List<LibraryDto> {
        if (!hasValidBaseUrl()) {
            fetchFilterStatus = FetchFilterStatus.NOT_FETCHED
            return emptyList()
        }

        return try {
            val options = repository.fetchFilterOptions(forceRefresh)
            libraries = options.libraries
            collections = options.collections
            genres = options.genres
            tags = options.tags
            publishers = options.publishers
            authors = options.authors
            fetchFilterStatus = FetchFilterStatus.FETCHED
            libraries
        } catch (e: Exception) {
            fetchFilterStatus = FetchFilterStatus.NOT_FETCHED
            Log.e("KomgaSource", "Failed to load Komga libraries", e)
            throw e
        }
    }

    fun invalidateBrowseCache() {
        libraries = emptyList()
        collections = emptyList()
        genres = emptySet()
        tags = emptySet()
        publishers = emptySet()
        authors = emptyMap()
        fetchFilterStatus = FetchFilterStatus.NOT_FETCHED
        fetchFiltersAttempts = 0
    }

    fun refreshBrowseRequests() {
        forceBrowseRequestsUntil.set(System.currentTimeMillis() + BROWSE_REFRESH_WINDOW_MILLIS)
    }

    fun registerServerSettingsChangeListener(
        onChanged: () -> Unit,
    ): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in SERVER_SETTING_KEYS) {
                invalidateBrowseCache()
                onChanged()
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun unregisterServerSettingsChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun buildFilterListForLibrary(
        libraryId: String?,
        preservePersistentFilters: Boolean = false,
        allowedLibraryIds: Set<String>? = null,
        libraryScope: KomgaLibraryScope = KomgaLibraryScope.ALL,
        currentFilters: FilterList? = null,
        preserveSessionFilters: Boolean = false,
        fallbackLibraries: List<LibraryDto>? = null,
        resetLibrarySelection: Boolean = false,
    ): FilterList {
        val filters = getFilterList().withFallbackLibraries(fallbackLibraries)
        val currentState = currentFilters
            ?.takeIf { it.isNotEmpty() }
            ?.toPersistentFilterState()
        val sessionState = if (preserveSessionFilters) {
            synchronized(sessionFilterStates) { sessionFilterStates[libraryScope] }
        } else {
            null
        }
        val hasPreservedState = when {
            currentState != null -> {
                filters.applyPersistentFilterState(currentState)
                true
            }
            sessionState != null -> {
                filters.applyPersistentFilterState(sessionState)
                true
            }
            preservePersistentFilters && isPersistentFilteringEnabled(libraryScope) -> {
                applyPersistentFilterState(filters, libraryScope)
            }
            else -> false
        }
        val scopedFilters = filters.restrictLibraries(allowedLibraryIds)

        if (!hasPreservedState) {
            scopedFilters.resetFilterState()
            scopedFilters.filterIsInstance<TypeSelect>().firstOrNull()?.state = 0
            scopedFilters.filterIsInstance<SeriesSort>().firstOrNull()?.state = Filter.Sort.Selection(1, true)
        }
        scopedFilters.filterIsInstance<LibraryFilter>().firstOrNull()?.state?.let { options ->
            when {
                libraryId != null -> options.forEach { option -> option.state = option.id == libraryId }
                resetLibrarySelection -> options.forEach { option ->
                    option.state = allowedLibraryIds != null || option.id in defaultLibraries
                }
                allowedLibraryIds != null && options.none { it.state } -> options.forEach { it.state = true }
            }
        }
        return scopedFilters
    }

    fun buildFilterListForTagSearch(
        tag: String,
        allowedLibraryIds: Set<String>? = null,
        libraryScope: KomgaLibraryScope = KomgaLibraryScope.ALL,
    ): FilterList {
        val targetGroup = when {
            tags.any { it.equals(tag, true) } -> "Tags"
            genres.any { it.equals(tag, true) } -> "Genres"
            else -> "Tags"
        }
        Log.d("KomgaSource", "buildFilterListForTagSearch: tag=$tag targetGroup=$targetGroup")

        val filters = buildFilterListForLibrary(
            libraryId = null,
            preservePersistentFilters = true,
            allowedLibraryIds = allowedLibraryIds,
            libraryScope = libraryScope,
            preserveSessionFilters = true,
        )
            .withSelectedMultiOption(targetGroup, tag)
        filters.filterIsInstance<TypeSelect>().firstOrNull()?.state = 0
        return filters
    }

    private var libraries = emptyList<koharia.komga.api.dto.LibraryDto>()
    private var collections = emptyList<koharia.komga.api.dto.CollectionDto>()
    private var genres = emptySet<String>()
    private var tags = emptySet<String>()
    private var publishers = emptySet<String>()
    private var authors = emptyMap<String, List<koharia.komga.api.dto.AuthorDto>>()
    private val sessionFilterStates = mutableMapOf<KomgaLibraryScope, PersistentFilterState>()

    private var fetchFilterStatus = FetchFilterStatus.NOT_FETCHED
    private var fetchFiltersAttempts = 0
    private val scope = CoroutineScope(Dispatchers.IO)

    private fun fetchFilterOptions() {
        if (!hasValidBaseUrl() || fetchFilterStatus != FetchFilterStatus.NOT_FETCHED || fetchFiltersAttempts >= 3) {
            return
        }

        fetchFilterStatus = FetchFilterStatus.FETCHING
        fetchFiltersAttempts++

        scope.launch {
            try {
                repository.fetchFilterOptions().let {
                    libraries = it.libraries
                    collections = it.collections
                    genres = it.genres
                    tags = it.tags
                    publishers = it.publishers
                    authors = it.authors
                }
                fetchFilterStatus = FetchFilterStatus.FETCHED
            } catch (e: Exception) {
                fetchFilterStatus = FetchFilterStatus.NOT_FETCHED
                Log.e("KomgaSource", "Failed to fetch filtering options", e)
            }
        }
    }

    private fun consumeBrowseCachePolicy(): KomgaCachePolicy {
        return if (System.currentTimeMillis() <= forceBrowseRequestsUntil.get()) {
            KomgaCachePolicy.NetworkFirst
        } else {
            KomgaCachePolicy.Default
        }
    }

    override fun chapterPageParse(response: Response) = throw UnsupportedOperationException()

    companion object {
        const val SOURCE_NAME = "Komga"
        const val SOURCE_LANG = "all"
        internal const val PREF_SERVER_PROFILE_NAME = "Koharia server profile name"
        const val SOURCE_VERSION = 1
        const val TYPE_SERIES = "Series"
        const val TYPE_READ_LISTS = "Read lists"
        const val TYPE_BOOKS = "Books"
        private const val BROWSE_REFRESH_WINDOW_MILLIS = 30_000L

        private val SERVER_SETTING_KEYS = setOf(
            PREF_SERVER_PROFILE_NAME,
            PREF_ADDRESS,
            PREF_USERNAME,
            PREF_PASSWORD,
            PREF_API_KEY,
            PREF_API_KEY_WRONG_CASE,
            PREF_AUTH_MODE,
            PREF_DEFAULT_LIBRARIES,
            PREF_CHAPTER_NAME_TEMPLATE,
        )

        val ID: Long by lazy {
            val key = "${SOURCE_NAME.lowercase()}/$SOURCE_LANG/$SOURCE_VERSION"
            val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
            (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
        }
    }

    private fun defaultAuthMode(): String {
        return if (apiKey.isNotBlank()) AUTH_MODE_API_KEY else AUTH_MODE_CREDENTIALS
    }
}

@Serializable
private data class PersistentFilterState(
    val checkBoxes: Map<String, Boolean> = emptyMap(),
    val selects: Map<String, Int> = emptyMap(),
    val sorts: Map<String, PersistentSortState> = emptyMap(),
    val groups: Map<String, Set<String>> = emptyMap(),
)

@Serializable
private data class PersistentSortState(
    val index: Int,
    val ascending: Boolean,
)

private fun defaultLibraryPersistentFilterState(): PersistentFilterState {
    return PersistentFilterState(
        selects = mapOf("Search for" to 0),
        sorts = mapOf("Sort" to PersistentSortState(index = 1, ascending = true)),
    )
}

private enum class FetchFilterStatus {
    NOT_FETCHED,
    FETCHING,
    FETCHED,
}

private fun FilterList.withSelectedMultiOption(groupName: String, optionId: String): FilterList {
    var groupFound = false
    val updatedFilters = list.map { filter ->
        if (filter !is UriMultiSelectFilter || filter.name != groupName) {
            return@map filter
        }

        groupFound = true
        var optionFound = false
        val options = filter.state.map { option ->
            option.apply {
                if (id.equals(optionId, true) || name.equals(optionId, true)) {
                    state = true
                    optionFound = true
                }
            }
        }.let { options ->
            if (optionFound) {
                options
            } else {
                options + UriMultiSelectOption(optionId).apply { state = true }
            }
        }
        UriMultiSelectFilter(groupName, options)
    }

    return FilterList(
        if (groupFound) {
            updatedFilters
        } else {
            list + UriMultiSelectFilter(
                groupName,
                listOf(UriMultiSelectOption(optionId).apply { state = true }),
            )
        },
    )
}

private fun FilterList.restrictLibraries(allowedLibraryIds: Set<String>?): FilterList {
    if (allowedLibraryIds == null) return this
    return FilterList(
        map { filter ->
            if (filter is LibraryFilter) {
                val selectedLibraryIds = filter.state
                    .filter { it.state && it.id in allowedLibraryIds }
                    .mapTo(linkedSetOf(), UriMultiSelectOption::id)
                LibraryFilter(
                    libraries = filter.state
                        .filter { it.id in allowedLibraryIds }
                        .map { LibraryDto(id = it.id, name = it.name) },
                    defaultLibraries = selectedLibraryIds,
                )
            } else {
                filter
            }
        },
    )
}

private fun FilterList.withFallbackLibraries(fallbackLibraries: List<LibraryDto>?): FilterList {
    if (fallbackLibraries.isNullOrEmpty()) return this
    return FilterList(
        map { filter ->
            if (filter is LibraryFilter && filter.state.isEmpty()) {
                LibraryFilter(fallbackLibraries, emptySet())
            } else {
                filter
            }
        },
    )
}

private fun FilterList.toPersistentFilterState(): PersistentFilterState {
    val checkBoxes = mutableMapOf<String, Boolean>()
    val selects = mutableMapOf<String, Int>()
    val sorts = mutableMapOf<String, PersistentSortState>()
    val groups = mutableMapOf<String, Set<String>>()

    forEach { filter ->
        when (filter) {
            is Filter.CheckBox -> checkBoxes[filter.name] = filter.state
            is Filter.Select<*> -> selects[filter.name] = filter.state
            is Filter.Sort -> {
                filter.state?.let { sorts[filter.name] = PersistentSortState(it.index, it.ascending) }
            }
            is Filter.Group<*> -> {
                groups[filter.name] = filter.state
                    .filterIsInstance<Filter.CheckBox>()
                    .filter { it.state }
                    .map { it.persistentOptionKey() }
                    .toSet()
            }
            else -> {}
        }
    }

    return PersistentFilterState(
        checkBoxes = checkBoxes,
        selects = selects,
        sorts = sorts,
        groups = groups,
    )
}

private fun FilterList.resetFilterState() {
    forEach { filter ->
        when (filter) {
            is Filter.CheckBox -> filter.state = false
            is Filter.Select<*> -> filter.state = 0
            is Filter.Sort -> filter.state = null
            is Filter.Group<*> ->
                filter.state
                    .filterIsInstance<Filter.CheckBox>()
                    .forEach { it.state = false }
            else -> {}
        }
    }
}

private fun Filter.CheckBox.persistentOptionKey(): String {
    return when (this) {
        is UriMultiSelectOption -> id
        is AuthorFilter -> "${author.name}\u001F${author.role}"
        else -> name
    }
}

private const val PREF_ADDRESS = "Address"
private const val PREF_USERNAME = "Username"
private const val PREF_PASSWORD = "Password"
private const val PREF_API_KEY = "API key"
private const val PREF_API_KEY_WRONG_CASE = "Api key"

private const val PREF_AUTH_MODE = "AuthMode"
private const val AUTH_MODE_CREDENTIALS = "Credentials"
private const val AUTH_MODE_API_KEY = "ApiKey"
private const val PREF_DEFAULT_LIBRARIES = "Default libraries"
private const val PREF_CHAPTER_NAME_TEMPLATE = "Chapter name template"
private const val PREF_CHAPTER_NAME_TEMPLATE_DEFAULT = "{number} - {title} ({size})"
private const val PREF_PERSISTENT_FILTERS_ENABLED = "Persistent filters enabled"
private const val PREF_PERSISTENT_FILTERS_ENABLED_COMIC = "Persistent filters enabled comic"
private const val PREF_PERSISTENT_FILTERS_ENABLED_BOOK = "Persistent filters enabled book"
private const val PREF_PERSISTENT_FILTERS_STATE = "Persistent filters state"
private const val PREF_PERSISTENT_FILTERS_STATE_COMIC = "Persistent filters state comic"
private const val PREF_PERSISTENT_FILTERS_STATE_BOOK = "Persistent filters state book"

private fun PreferenceScreen.addEditTextPreference(
    title: String,
    default: String,
    summary: String,
    dialogMessage: String? = null,
    inputType: Int? = null,
    validate: ((String) -> Boolean)? = null,
    validationMessage: String? = null,
    key: String = title,
    allowBlank: Boolean = true,
    showValueAsSummary: Boolean = false,
): EditTextPreference {
    return EditTextPreference(context).apply {
        this.key = key
        this.title = title
        this.summary = summary
        if (showValueAsSummary) {
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        }
        setDefaultValue(default)
        dialogTitle = title
        this.dialogMessage = dialogMessage

        fun isValidValue(text: String): Boolean {
            return if (text.isBlank()) allowBlank else validate?.invoke(text) ?: true
        }

        setOnBindEditTextListener { editText ->
            if (inputType != null) {
                editText.inputType = inputType
            }
            if (validate != null) {
                editText.addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                        override fun afterTextChanged(editable: Editable?) {
                            val text = editable?.toString().orEmpty()
                            val isValid = isValidValue(text)
                            editText.error = if (!isValid) validationMessage else null
                            editText.rootView.findViewById<Button>(android.R.id.button1)?.isEnabled =
                                editText.error == null
                        }
                    },
                )
            }
        }

        setOnPreferenceChangeListener { _, newValue ->
            val text = newValue as String
            isValidValue(text)
        }
    }.also(::addPreference)
}
