package eu.kanade.tachiyomi.source

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.online.HttpSource
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionRegistry
import koharia.connection.ConnectionSource
import koharia.connection.LibraryConnectionProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.ConcurrentHashMap

class AndroidSourceManager(
    @Suppress("UNUSED_PARAMETER")
    context: Context,
    private val sourceRepository: StubSourceRepository,
    private val connectionPreferences: ConnectionPreferences,
    private val connectionRegistry: ConnectionRegistry,
) : SourceManager {

    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val downloadManager: DownloadManager by injectLazy()

    private val scope = CoroutineScope(Job() + Dispatchers.IO)

    private val sourcesMapFlow = MutableStateFlow(ConcurrentHashMap<Long, Source>())

    private val stubSourcesMap = ConcurrentHashMap<Long, StubSource>()

    override val catalogueSources: Flow<List<CatalogueSource>> = sourcesMapFlow.map {
        it.values.filterIsInstance<CatalogueSource>()
    }

    init {
        refreshSources(connectionPreferences.getProfiles())
        _isInitialized.value = true

        scope.launch {
            connectionPreferences.profilesChanges()
                .collectLatest(::refreshSources)
        }

        scope.launch {
            sourceRepository.subscribeAll()
                .collectLatest { sources ->
                    val mutableMap = stubSourcesMap.toMutableMap()
                    sources.forEach {
                        mutableMap[it.id] = it
                    }
                    stubSourcesMap.clear()
                    stubSourcesMap.putAll(mutableMap)
                }
        }
    }

    override fun get(sourceKey: Long): Source? {
        return sourcesMapFlow.value[sourceKey]
    }

    override fun getOrStub(sourceKey: Long): Source {
        return sourcesMapFlow.value[sourceKey] ?: stubSourcesMap.getOrPut(sourceKey) {
            runBlocking { createStubSource(sourceKey) }
        }
    }

    override fun getOnlineSources() = sourcesMapFlow.value.values.filterIsInstance<HttpSource>()

    override fun getCatalogueSources() = sourcesMapFlow.value.values.filterIsInstance<CatalogueSource>()

    override fun getStubSources(): List<StubSource> {
        val onlineSourceIds = getOnlineSources().map { it.id }
        return stubSourcesMap.values.filterNot { it.id in onlineSourceIds }
    }

    private fun registerStubSource(source: StubSource) {
        scope.launch {
            val dbSource = sourceRepository.getStubSource(source.id)
            if (dbSource == source) return@launch
            sourceRepository.upsertStubSource(source.id, source.lang, source.name)
            if (dbSource != null) {
                downloadManager.renameSource(dbSource, source)
            }
        }
    }

    private suspend fun createStubSource(id: Long): StubSource {
        sourceRepository.getStubSource(id)?.let {
            return it
        }
        return StubSource(id = id, lang = "", name = "")
    }

    private fun refreshSources(profiles: List<LibraryConnectionProfile>) {
        val previousSources = sourcesMapFlow.value
        val sources = profiles.mapNotNull { profile ->
            val existing = previousSources[profile.id] as? ConnectionSource
            if (existing?.connectionProfile == profile) {
                existing
            } else {
                runCatching { connectionRegistry.createSource(profile) }
                    .onFailure { error ->
                        logcat(LogPriority.ERROR, error) {
                            "Failed to create connection source provider=${profile.providerId} id=${profile.id}"
                        }
                    }
                    .getOrNull()
            }
        }

        val retainedSources = sources.toSet()
        previousSources.values
            .filterNot(retainedSources::contains)
            .filterIsInstance<AutoCloseable>()
            .forEach { source -> runCatching(source::close) }

        sourcesMapFlow.value = ConcurrentHashMap(sources.associateBy(Source::id))
        sources.forEach { source ->
            registerStubSource(StubSource.from(source))
        }
    }
}
