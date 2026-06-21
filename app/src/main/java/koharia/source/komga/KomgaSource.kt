package koharia.source.komga

import android.content.SharedPreferences
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.sourcePreferences
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import koharia.komga.api.dto.LibraryDto
import koharia.komga.api.KomgaApiClient
import koharia.komga.domain.repository.KomgaRepository
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import java.security.MessageDigest
import java.util.Locale

class KomgaSource :
    HttpSource(),
    ConfigurableSource,
    UnmeteredSource {

    private val preferences: SharedPreferences by lazy { sourcePreferences() }
    private val json: Json by injectLazy()

    override val name: String = SOURCE_NAME
    override val lang: String = SOURCE_LANG
    override val id: Long = ID
    override val supportsLatest: Boolean = true
    override val versionId: Int = SOURCE_VERSION

    override val baseUrl: String
        get() = preferences.getString(PREF_ADDRESS, "")!!.removeSuffix("/")

    private val username: String
        get() = preferences.getString(PREF_USERNAME, "")!!

    private val password: String
        get() = preferences.getString(PREF_PASSWORD, "")!!

    private val apiKey: String
        get() = preferences.getString(PREF_API_KEY, "")!!

    private val defaultLibraries: Set<String>
        get() = preferences.getStringSet(PREF_DEFAULT_LIBRARIES, emptySet()) ?: emptySet()

    private val chapterNameTemplate: String
        get() = preferences.getString(PREF_CHAPTER_NAME_TEMPLATE, PREF_CHAPTER_NAME_TEMPLATE_DEFAULT)!!

    private val apiClient: KomgaApiClient
        get() = KomgaApiClient(baseUrl, headers, client, json)

    private val repository: KomgaRepository
        get() = KomgaRepository(baseUrl, apiClient)

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", "KohariaKomga/${AppInfo.getVersionName()}")
        .also { builder ->
            if (apiKey.isNotBlank()) {
                builder.set("X-API-Key", apiKey)
            }
        }

    override val client: OkHttpClient
        get() = network.client.newBuilder()
            .authenticator { _, response ->
                if (apiKey.isNotBlank() || response.request.header("Authorization") != null) {
                    null
                } else {
                    response.request.newBuilder()
                        .addHeader("Authorization", Credentials.basic(username, password))
                        .build()
                }
            }
            .dns(Dns.SYSTEM)
            .build()

    override fun popularMangaRequest(page: Int): Request = repository.popularMangaRequest(page, defaultLibraries)

    override fun popularMangaParse(response: Response) = repository.parseMangasPage(response)

    override fun latestUpdatesRequest(page: Int): Request = repository.latestUpdatesRequest(page, defaultLibraries)

    override fun latestUpdatesParse(response: Response) = repository.parseMangasPage(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        repository.searchMangaRequest(page, query, filters, defaultLibraries)

    override fun searchMangaParse(response: Response) = repository.parseMangasPage(response)

    override fun getMangaUrl(manga: eu.kanade.tachiyomi.source.model.SManga): String = manga.url.replace("/api/v1", "")

    override fun mangaDetailsRequest(manga: eu.kanade.tachiyomi.source.model.SManga): Request = repository.mangaDetailsRequest(manga)

    override fun mangaDetailsParse(response: Response) = repository.mangaDetailsParse(response)

    override fun chapterListRequest(manga: eu.kanade.tachiyomi.source.model.SManga): Request = repository.chapterListRequest(manga)

    override fun chapterListParse(response: Response) = repository.chapterListParse(response, chapterNameTemplate)

    override fun pageListRequest(chapter: eu.kanade.tachiyomi.source.model.SChapter): Request = repository.pageListRequest(chapter)

    override fun pageListParse(response: Response) = repository.pageListParse(response)

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, headersBuilder().add("Accept", "image/*,*/*;q=0.8").build())

    fun rawFileRequest(chapterUrl: String, rangeStart: Long? = null): Request = apiClient.bookFileRequest(chapterUrl, rangeStart)

    fun hasValidBaseUrl(): Boolean = baseUrl.startsWith("http://") || baseUrl.startsWith("https://")

    override fun getFilterList(): FilterList {
        fetchFilterOptions()

        val filters = mutableListOf<Filter<*>>(
            UnreadFilter(),
            InProgressFilter(),
            ReadFilter(),
            TypeSelect(),
            CollectionSelect(buildList {
                add(CollectionFilterEntry("None"))
                collections.forEach { add(CollectionFilterEntry(it.name, it.id)) }
            }),
            LibraryFilter(libraries, defaultLibraries),
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
                    "Failed to fetch filtering options from the server"
                } else {
                    "Press \"Reset\" to show filtering options"
                }

                add(0, Filter.Header(message))
                add(1, Filter.Separator())
            }

            addAll(authors.map { (role, items) -> AuthorGroup(role, items.map { AuthorFilter(it) }) })
            add(SeriesSort())
        }

        return FilterList(filters)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        fetchFilterOptions()

        screen.addEditTextPreference(
            title = screen.context.stringResource(MR.strings.komga_pref_address_title),
            default = "",
            summary = baseUrl.ifBlank { screen.context.stringResource(MR.strings.komga_pref_address_summary) },
            dialogMessage = screen.context.stringResource(MR.strings.komga_pref_address_dialog),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            validate = { it.startsWith("http://") || it.startsWith("https://") },
            validationMessage = screen.context.stringResource(MR.strings.komga_pref_address_validation),
            key = PREF_ADDRESS,
            restartRequired = true,
        )
        screen.addEditTextPreference(
            title = screen.context.stringResource(MR.strings.komga_pref_api_key_title),
            default = "",
            summary = if (apiKey.isBlank()) {
                screen.context.stringResource(MR.strings.komga_pref_api_key_summary)
            } else {
                "*".repeat(apiKey.length)
            },
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            key = PREF_API_KEY,
            restartRequired = true,
        )
        if (apiKey.isBlank()) {
            screen.addEditTextPreference(
                title = screen.context.stringResource(MR.strings.komga_pref_username_title),
                default = "",
                summary = username.ifBlank { screen.context.stringResource(MR.strings.komga_pref_username_summary) },
                key = PREF_USERNAME,
                restartRequired = true,
            )
            screen.addEditTextPreference(
                title = screen.context.stringResource(MR.strings.komga_pref_password_title),
                default = "",
                summary = if (password.isBlank()) {
                    screen.context.stringResource(MR.strings.komga_pref_password_summary)
                } else {
                    "*".repeat(password.length)
                },
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                key = PREF_PASSWORD,
                restartRequired = true,
            )
        }

        MultiSelectListPreference(screen.context).apply {
            key = PREF_DEFAULT_LIBRARIES
            title = screen.context.stringResource(MR.strings.komga_pref_default_libraries_title)
            summary = buildString {
                append(screen.context.stringResource(MR.strings.komga_pref_default_libraries_summary))
                if (libraries.isEmpty()) {
                    append(' ')
                    append(screen.context.stringResource(MR.strings.komga_pref_default_libraries_reload_hint))
                }
            }
            entries = libraries.map { it.name }.toTypedArray()
            entryValues = libraries.map { it.id }.toTypedArray()
            setDefaultValue(emptySet<String>())
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

    suspend fun getBrowseLibraries(): List<LibraryDto> {
        if (!hasValidBaseUrl()) {
            fetchFilterStatus = FetchFilterStatus.NOT_FETCHED
            return emptyList()
        }

        return try {
            val options = repository.fetchFilterOptions()
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
            emptyList()
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

    fun buildFilterListForLibrary(libraryId: String?): FilterList {
        val filters = getFilterList()
        filters.filterIsInstance<LibraryFilter>().firstOrNull()?.state?.forEach { option ->
            option.state = if (libraryId == null) {
                true
            } else {
                option.id == libraryId
            }
        }
        filters.filterIsInstance<TypeSelect>().firstOrNull()?.state = 0
        filters.filterIsInstance<SeriesSort>().firstOrNull()?.state = Filter.Sort.Selection(1, true)
        return filters
    }

    private var libraries = emptyList<koharia.komga.api.dto.LibraryDto>()
    private var collections = emptyList<koharia.komga.api.dto.CollectionDto>()
    private var genres = emptySet<String>()
    private var tags = emptySet<String>()
    private var publishers = emptySet<String>()
    private var authors = emptyMap<String, List<koharia.komga.api.dto.AuthorDto>>()

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

    override fun chapterPageParse(response: Response) = throw UnsupportedOperationException()

    companion object {
        const val SOURCE_NAME = "Komga"
        const val SOURCE_LANG = "all"
        const val SOURCE_VERSION = 1
        const val TYPE_SERIES = "Series"
        const val TYPE_READ_LISTS = "Read lists"
        const val TYPE_BOOKS = "Books"

        val ID: Long by lazy {
            val key = "${SOURCE_NAME.lowercase()}/$SOURCE_LANG/$SOURCE_VERSION"
            val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
            (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
        }
    }
}

private enum class FetchFilterStatus {
    NOT_FETCHED,
    FETCHING,
    FETCHED,
}

private const val PREF_ADDRESS = "Address"
private const val PREF_USERNAME = "Username"
private const val PREF_PASSWORD = "Password"
private const val PREF_API_KEY = "API key"
private const val PREF_DEFAULT_LIBRARIES = "Default libraries"
private const val PREF_CHAPTER_NAME_TEMPLATE = "Chapter name template"
private const val PREF_CHAPTER_NAME_TEMPLATE_DEFAULT = "{number} - {title} ({size})"

private fun PreferenceScreen.addEditTextPreference(
    title: String,
    default: String,
    summary: String,
    dialogMessage: String? = null,
    inputType: Int? = null,
    validate: ((String) -> Boolean)? = null,
    validationMessage: String? = null,
    key: String = title,
    restartRequired: Boolean = false,
) {
    EditTextPreference(context).apply {
        this.key = key
        this.title = title
        this.summary = summary
        setDefaultValue(default)
        dialogTitle = title
        this.dialogMessage = dialogMessage

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
                            val isValid = text.isBlank() || validate(text)
                            editText.error = if (!isValid) validationMessage else null
                            editText.rootView.findViewById<Button>(android.R.id.button1)?.isEnabled = editText.error == null
                        }
                    },
                )
            }
        }

        setOnPreferenceChangeListener { _, newValue ->
            val text = newValue as String
            val isValid = text.isBlank() || validate?.invoke(text) ?: true
            if (restartRequired && isValid) {
                Toast.makeText(context, context.stringResource(MR.strings.requires_app_restart), Toast.LENGTH_LONG).show()
            }
            isValid
        }
    }.also(::addPreference)
}
