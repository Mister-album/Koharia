package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import koharia.connection.ConnectionBackupRestoreAdapter
import koharia.connection.ConnectionRestoreState
import koharia.connection.ConnectionSourceRestorer
import koharia.cover.CustomCoverStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupRestorer(
    private val context: Context,
    private val notifier: BackupNotifier,
    private val isSync: Boolean,

    private val categoriesRestorer: CategoriesRestorer = CategoriesRestorer(),
    private val preferenceRestorer: PreferenceRestorer = PreferenceRestorer(context),
    private val mangaRestorer: MangaRestorer = MangaRestorer(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val connectionProfileManager: koharia.connection.ConnectionProfileManager = Injekt.get(),
) {

    private var restoreAmount = 0
    private var restoreProgress = 0
    private val errors = mutableListOf<Pair<Date, String>>()

    /**
     * Mapping of source ID to source name from backup data
     */
    private var sourceMapping: Map<Long, String> = emptyMap()

    suspend fun restore(
        uri: Uri,
        options: RestoreOptions,
        password: CharArray? = null,
        directoryBindings: Map<String, String> = emptyMap(),
    ) {
        val startTime = System.currentTimeMillis()

        restoreFromFile(uri, options, password, directoryBindings)

        val time = System.currentTimeMillis() - startTime

        val logFile = writeErrorLog()

        notifier.showRestoreComplete(
            time,
            errors.size,
            logFile.parent,
            logFile.name,
            isSync,
        )
    }

    private suspend fun restoreFromFile(
        uri: Uri,
        options: RestoreOptions,
        password: CharArray?,
        directoryBindings: Map<String, String>,
    ) {
        val backup = BackupDecoder(context).decode(uri, password)
        var replacementEntries = eu.kanade.tachiyomi.data.backup.models.TextReplacementBackup.decode(
            backup.textReplacement,
        )
            .filter {
                if (it.entry.scope ==
                    koharia.domain.textreplacement.ReplacementScope.GLOBAL
                ) {
                    options.appSettings
                } else {
                    options.libraryEntries
                }
            }
        val inventory = BackupConnectionInventory.read(backup)
        val recoveryKey = BackupRestoreKey.create(backup, options, directoryBindings)
        val libraryOwners = if (options.libraryEntries) {
            backup.backupManga.mapTo(mutableSetOf()) {
                it.source
            } + backup.backupKomgaReadStatus.map { it.connectionId } +
                backup.backupKomgaPages.map { it.connectionId } +
                backup.backupKomgaPageAttempts.map { it.connectionId } +
                backup.backupKomgaReadStatusAttempts.map { it.connectionId }
        } else {
            emptySet()
        }
        val importedOwners = inventory.filter { options.connectionSettings || it.id in libraryOwners }
        val ownerIds = importedOwners.mapTo(mutableSetOf()) { it.id }
        var appPreferences = BackupAppConnectionReferences.remap(backup.backupPreferences, emptyMap())
        var heldReadStatus = emptyList<koharia.domain.komga.KomgaReadStatusIntent>()
        var heldJournal = BackupKomgaProgressJournal.Held()
        require(ownerIds.containsAll(libraryOwners)) { "Backup is missing connection ownership information" }
        if (options.connectionSettings) {
            require(
                backup.backupSourcePreferences.all {
                    it.sourceKey.startsWith("source_") &&
                        it.sourceKey.removePrefix("source_").toLongOrNull() in ownerIds
                },
            ) { "Backup settings are missing connection ownership information" }
            require(
                backup.backupSourcePreferences.map { it.sourceKey }.distinct().size ==
                    backup.backupSourcePreferences.size,
            ) { "Duplicate backup source settings" }
        }
        // Resolve every directory before changing preferences or library data.
        var sourcePreferences = if (options.connectionSettings) {
            BackupDirectoryRemapper.remap(context, backup.backupSourcePreferences, directoryBindings)
        } else {
            emptyList()
        }
        val storageDirectory = backup.backupStorageDirectory.takeIf { options.appSettings && it.isNotBlank() }
            ?.let {
                val selected = checkNotNull(directoryBindings[it]) { "Storage access must be granted before restore" }
                check(BackupDirectoryRemapper.hasTreePermission(context, selected, true)) {
                    "Storage access was revoked before restore"
                }
                selected
            }

        // Store source mapping for error messages
        val backupMaps = backup.backupSources
        sourceMapping = backupMaps.associate { it.sourceId to it.name }

        if (options.libraryEntries) {
            restoreAmount += backup.backupManga.size
        }
        if (options.categories) {
            restoreAmount += 1
        }
        if (options.appSettings) {
            restoreAmount += 1
        }
        if (options.connectionSettings) {
            restoreAmount += 1
        }

        val restoreData: suspend () -> Unit = {
            ConnectionRestoreState.duringRestore {
                if (options.libraryEntries) {
                    val chaptersBySource = backup.backupManga.groupBy { it.source }
                    chaptersBySource.forEach { (sourceId, mangas) ->
                        val adapter = sourceManager.get(sourceId) as? ConnectionBackupRestoreAdapter
                        adapter?.prepareReadingStateRestore(mangas.flatMap { it.chapters }.map { it.url })
                    }
                }
                coroutineScope {
                    if (options.categories) {
                        restoreCategories(backup.backupCategories).join()
                    }
                    if (options.appSettings) {
                        restoreAppPreferences(
                            appPreferences,
                            backup.backupCategories.takeIf { options.categories },
                        ).join()
                        storageDirectory?.let { Injekt.get<StoragePreferences>().baseStorageDirectory.set(it) }
                    }
                    if (options.connectionSettings) {
                        restoreSourcePreferences(sourcePreferences).join()
                    }
                    if (options.libraryEntries) {
                        restoreManga(
                            backup.backupManga,
                            if (options.categories) backup.backupCategories else emptyList(),
                        ).join()
                        Injekt.get<tachiyomi.data.Database>().transaction {
                            val intents = Injekt.get<koharia.domain.komga.KomgaReadStatusRepository>()
                            intents.restoreHeld(heldReadStatus)
                            Injekt.get<koharia.domain.komga.KomgaPageEventRepository>().restoreHeld(heldJournal.pages)
                            intents.restoreHeldAttempts(heldJournal.readAttempts)
                        }
                    }
                    // TODO: optionally trigger online library + tracker update
                }
                if (options.appSettings) preferenceRestorer.finishRestore()
                Injekt.get<CustomCoverStore>().invalidate()
            }
        }
        if (options.appSettings || options.connectionSettings || importedOwners.isNotEmpty()) {
            val sourceRestorer = checkNotNull(sourceManager as? ConnectionSourceRestorer) {
                "Source manager must coordinate connection settings restore"
            }
            // Registration starts provider observers; run it after the restore write pause ends.
            sourceRestorer.withSourceUpdatesDeferred {
                if (importedOwners.isEmpty()) {
                    restoreData()
                } else {
                    connectionProfileManager.importConnections(
                        importedOwners.map {
                            koharia.connection.LibraryConnectionProfile(it.id, it.providerId, it.name)
                        },
                        recoveryKey = recoveryKey,
                    ) { targets ->
                        replacementEntries = replacementEntries.map { saved ->
                            val oldId = saved.entry.context.connectionId
                            val target = targets[oldId]
                            if (saved.entry.scope ==
                                koharia.domain.textreplacement.ReplacementScope.GLOBAL
                            ) {
                                saved
                            } else if (target == null) {
                                saved.copy(
                                    entry = saved.entry.copy(context = saved.entry.context.copy(connectionId = 0)),
                                )
                            } else {
                                val reference = BackupConnectionReferences(oldId, target.id)
                                saved.copy(
                                    entry = saved.entry.copy(
                                        context = saved.entry.context.copy(connectionId = target.id),
                                    ),
                                    seriesUrl = saved.seriesUrl?.let(reference::url),
                                    bookUrl = saved.bookUrl?.let(reference::url),
                                )
                            }
                        }
                        appPreferences = BackupAppConnectionReferences.remap(backup.backupPreferences, targets)
                        val references = targets.mapValues { (old, profile) ->
                            BackupConnectionReferences(old, profile.id)
                        }
                        sourcePreferences = sourcePreferences.map { source ->
                            val oldId = source.sourceKey.removePrefix("source_").toLong()
                            references.getValue(oldId).preferences(source).let { mapped ->
                                mapped.copy(
                                    prefs = mapped.prefs.map { preference ->
                                        if (preference.key == "Koharia server profile name") {
                                            preference.copy(
                                                value = eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue(
                                                    targets.getValue(oldId).name,
                                                ),
                                            )
                                        } else {
                                            preference
                                        }
                                    },
                                )
                            }
                        }
                        if (options.libraryEntries) {
                            heldReadStatus = BackupKomgaReadStatusIntents.remap(
                                backup.backupKomgaReadStatus,
                                targets,
                                recoveryKey,
                            )
                            heldJournal = BackupKomgaProgressJournal.remap(backup, targets, recoveryKey)
                            backup.backupManga.forEach { references.getValue(it.source).manga(it) }
                        }
                        sourceMapping = sourceMapping.mapKeys { (old, _) -> targets[old]?.id ?: old }
                        restoreData()
                    }
                }
            }
        } else {
            restoreData()
        }
        val replacementConflicts = eu.kanade.tachiyomi.data.backup.models.TextReplacementBackup.restore(
            replacementEntries,
        )
        if (replacementConflicts >
            0
        ) {
            errors +=
                Date() to context.stringResource(MR.strings.replacement_restore_conflicts, replacementConflicts)
        }
    }

    private fun CoroutineScope.restoreCategories(backupCategories: List<BackupCategory>) = launch {
        ensureActive()
        categoriesRestorer(backupCategories)

        restoreProgress += 1
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.categories),
            restoreProgress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreManga(
        backupMangas: List<BackupManga>,
        backupCategories: List<BackupCategory>,
    ) = launch {
        mangaRestorer.sortByNew(backupMangas)
            .forEach {
                ensureActive()

                try {
                    mangaRestorer.restore(it, backupCategories)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    val sourceName = sourceMapping[it.source] ?: it.source.toString()
                    errors.add(Date() to "${it.title} [$sourceName]: ${e.message}")
                }

                restoreProgress += 1
                notifier.showRestoreProgress(it.title, restoreProgress, restoreAmount, isSync)
            }
    }

    private fun CoroutineScope.restoreAppPreferences(
        preferences: List<BackupPreference>,
        categories: List<BackupCategory>?,
    ) = launch {
        ensureActive()
        preferenceRestorer.restoreApp(
            preferences,
            categories,
            preserveConnectionInventory = true,
        )

        restoreProgress += 1
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.app_settings),
            restoreProgress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreSourcePreferences(preferences: List<BackupSourcePreferences>) = launch {
        ensureActive()
        preferenceRestorer.restoreSource(preferences, initializeLegacyInventory = false)

        restoreProgress += 1
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.source_settings),
            restoreProgress,
            restoreAmount,
            isSync,
        )
    }

    private fun writeErrorLog(): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("koharia_restore_error.txt")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

                file.bufferedWriter().use { out ->
                    errors.forEach { (date, message) ->
                        out.write("[${sdf.format(date)}] $message\n")
                    }
                }
                return file
            }
        } catch (e: Exception) {
            // Empty
        }
        return File("")
    }
}
