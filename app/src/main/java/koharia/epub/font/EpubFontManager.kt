package koharia.epub.font

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import android.util.Base64
import android.util.LruCache
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class EpubFontManager(
    context: Context,
    private val json: Json,
) {
    private val appContext = context.applicationContext
    private val sourcesDirectory = File(appContext.filesDir, "epub-fonts/sources")
    private val extractedDirectory = File(appContext.cacheDir, "epub-fonts")
    private val catalogFile = AtomicFile(File(appContext.filesDir, "epub-fonts/catalog.json"))
    private val parser = OpenTypeFontParser()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val catalogMutex = Mutex()
    private val materializationLock = Any()
    private val typefaceCache = object : LruCache<String, Typeface>(24) {}
    private val materializedFaceFiles = ConcurrentHashMap<String, File>()
    private var storedCatalog = StoredEpubFontCatalog()

    private val _catalogState = MutableStateFlow(
        EpubFontCatalogState(
            builtInFamilies = builtInFamilies(),
            systemFamilies = if (Build.VERSION.SDK_INT <
                Build.VERSION_CODES.Q
            ) {
                genericSystemFamilies()
            } else {
                emptyList()
            },
            isSystemLoading = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
            isLocalLoading = true,
        ),
    )
    val catalogState: StateFlow<EpubFontCatalogState> = _catalogState.asStateFlow()

    init {
        scope.launch {
            loadLocalCatalog()
            scanSystemFonts()
        }
    }

    suspend fun importFonts(
        uris: List<Uri>,
        replaceConflicts: Boolean = false,
    ): EpubFontImportResult = withContext(Dispatchers.IO) {
        catalogMutex.withLock {
            if (uris.isEmpty()) return@withLock EpubFontImportResult.Success(0, 0)
            sourcesDirectory.mkdirs()
            val staged = mutableListOf<StagedFont>()
            var duplicateFiles = 0
            try {
                for (uri in uris) {
                    val stagedFile = File.createTempFile("font-import-", ".tmp", appContext.cacheDir)
                    val result = runCatching {
                        appContext.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(stagedFile).use { output ->
                                val buffer = ByteArray(COPY_BUFFER_SIZE)
                                var total = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    total += read
                                    require(total <= MAX_SOURCE_BYTES) { "Font source is too large" }
                                    output.write(buffer, 0, read)
                                }
                            }
                        } ?: error("Unable to open font")
                        val checksum = stagedFile.sha256()
                        if (staged.any { it.checksum == checksum }) {
                            duplicateFiles++
                            stagedFile.delete()
                            null
                        } else {
                            val parsed = parseFont(stagedFile)
                            require(parsed.faces.isNotEmpty())
                            val retainedSource = storedCatalog.sources.firstOrNull { it.checksum == checksum }
                            val missingFaces = parsed.missingFrom(retainedSource)
                            if (missingFaces.isEmpty()) {
                                duplicateFiles++
                                stagedFile.delete()
                                null
                            } else {
                                StagedFont(
                                    temporaryFile = stagedFile,
                                    checksum = checksum,
                                    originalFileName = queryDisplayName(uri) ?: "font",
                                    parsed = parsed.copy(faces = missingFaces),
                                )
                            }
                        }
                    }
                    val value = result.getOrElse { error ->
                        stagedFile.delete()
                        staged.forEach { it.temporaryFile.delete() }
                        val reason = if (
                            error is IllegalArgumentException && error.message == "Font source is too large"
                        ) {
                            EpubFontImportFailure.FILE_TOO_LARGE
                        } else if (error is IllegalArgumentException) {
                            EpubFontImportFailure.INVALID_FORMAT
                        } else {
                            EpubFontImportFailure.READ_FAILED
                        }
                        return@withLock EpubFontImportResult.Failure(reason)
                    }
                    value?.let(staged::add)
                }

                val conflicts = findConflicts(staged)
                if (conflicts.isNotEmpty() && !replaceConflicts) {
                    return@withLock EpubFontImportResult.Conflict(conflicts)
                }
                val originalCatalog = storedCatalog
                if (replaceConflicts) storedCatalog = catalogWithoutConflictingFaces(staged)
                val acceptedFaces = acceptedStagedFaceIndices(staged, replaceConflicts)
                val acceptedSources = staged.filter { acceptedFaces[it.checksum].orEmpty().isNotEmpty() }
                val existingChecksums = originalCatalog.sources.mapTo(mutableSetOf(), StoredEpubFontSource::checksum)
                val newBytes = acceptedSources
                    .filterNot { it.checksum in existingChecksums }
                    .sumOf { it.temporaryFile.length() }
                if (storedCatalog.sources.sumOf { it.size } + newBytes > MAX_LIBRARY_BYTES) {
                    storedCatalog = originalCatalog
                    return@withLock EpubFontImportResult.Failure(EpubFontImportFailure.LIBRARY_FULL)
                }

                val newlyCreatedFiles = mutableListOf<File>()
                val additions = runCatching {
                    acceptedSources.map { font ->
                        val hasCff = font.parsed.faces.any { it.sfntFlavor == EpubFontFaceDescriptor.SFNT_CFF }
                        val extension = when {
                            font.parsed.isCollection && hasCff -> "otc"
                            font.parsed.isCollection -> "ttc"
                            hasCff -> "otf"
                            else -> "ttf"
                        }
                        val retainedSource = storedCatalog.sources.firstOrNull { it.checksum == font.checksum }
                            ?: originalCatalog.sources.firstOrNull { it.checksum == font.checksum }
                        val destination = retainedSource?.let { File(sourcesDirectory, it.storedFileName) }
                            ?: File(sourcesDirectory, "${font.checksum}.$extension")
                        if (!destination.exists()) {
                            if (retainedSource == null) newlyCreatedFiles += destination
                            font.temporaryFile.copyTo(destination, overwrite = false)
                        }
                        StoredEpubFontSource(
                            checksum = font.checksum,
                            storedFileName = destination.name,
                            originalFileName = retainedSource?.originalFileName ?: font.originalFileName,
                            size = destination.length(),
                            faces = font.parsed.faces.filter {
                                it.index in acceptedFaces.getValue(font.checksum)
                            }.map { face ->
                                StoredEpubFontFace(
                                    faceIndex = face.index,
                                    familyName = face.familyName,
                                    localizedFamilyName = face.localizedFamilyName,
                                    manufacturer = face.manufacturer,
                                    postScriptName = face.postScriptName,
                                    weight = face.weight,
                                    italic = face.italic,
                                    minWeight = face.minWeight,
                                    maxWeight = face.maxWeight,
                                    sfntFlavor = face.sfntFlavor,
                                )
                            },
                        )
                    }
                }.getOrElse {
                    storedCatalog = originalCatalog
                    newlyCreatedFiles.forEach(File::delete)
                    return@withLock EpubFontImportResult.Failure(EpubFontImportFailure.READ_FAILED)
                }
                storedCatalog = storedCatalog.copy(
                    sources = mergeStoredFontSources(storedCatalog.sources, additions),
                )
                if (runCatching(::saveCatalog).isFailure) {
                    storedCatalog = originalCatalog
                    newlyCreatedFiles.forEach(File::delete)
                    return@withLock EpubFontImportResult.Failure(EpubFontImportFailure.READ_FAILED)
                }
                val retainedFiles = storedCatalog.sources.mapTo(mutableSetOf()) { it.storedFileName }
                originalCatalog.sources.filter { it.storedFileName !in retainedFiles }.forEach { source ->
                    File(sourcesDirectory, source.storedFileName).delete()
                }
                publishLocalCatalog()
                EpubFontImportResult.Success(
                    importedFamilies = additions.flatMap {
                        it.faces
                    }.map { it.familyName.normalizedFamily() }.distinct().size,
                    duplicateFiles = duplicateFiles,
                )
            } finally {
                staged.forEach { it.temporaryFile.delete() }
            }
        }
    }

    suspend fun deleteFamily(id: EpubFontId): Boolean = withContext(Dispatchers.IO) {
        catalogMutex.withLock {
            if (id.source != EpubFontSource.LOCAL) return@withLock false
            val familyKey = id.value.removePrefix(EpubFontId.LOCAL_PREFIX)
            val removedFaces = storedCatalog.sources.flatMap { source ->
                source.faces.filter { localFamilyKey(it.familyName) == familyKey }
                    .map { face -> source.checksum to face.faceIndex }
            }
            var changed = false
            val filesToDelete = mutableListOf<File>()
            val updated = storedCatalog.sources.mapNotNull { source ->
                val remainingFaces = source.faces.filterNot { localFamilyKey(it.familyName) == familyKey }
                if (remainingFaces.size == source.faces.size) return@mapNotNull source
                changed = true
                if (remainingFaces.isEmpty()) {
                    filesToDelete += File(sourcesDirectory, source.storedFileName)
                    null
                } else {
                    source.copy(faces = remainingFaces)
                }
            }
            if (!changed) return@withLock false
            val originalCatalog = storedCatalog
            storedCatalog = storedCatalog.copy(sources = updated)
            removedFaces.forEach { (checksum, faceIndex) ->
                File(extractedDirectory, "$checksum-$faceIndex.sfnt").delete()
            }
            val removedFaceKeys = removedFaces.mapTo(mutableSetOf()) { (checksum, faceIndex) ->
                "local:$checksum:$faceIndex"
            }
            materializedFaceFiles.keys.removeAll(removedFaceKeys)
            if (runCatching(::saveCatalog).isFailure) {
                storedCatalog = originalCatalog
                return@withLock false
            }
            filesToDelete.forEach(File::delete)
            publishLocalCatalog()
            true
        }
    }

    fun resolve(id: EpubFontId): EpubFontFamilyDescriptor {
        return catalogState.value.allFamilies.firstOrNull { it.id == id }
            ?: catalogState.value.builtInFamilies.first { it.id == EpubFontId.ORIGINAL }
    }

    fun fingerprint(id: EpubFontId): String = resolve(id).fingerprint

    internal fun webPayload(id: EpubFontId): EpubWebFontPayload? {
        val family = resolve(id)
        if (family.id == EpubFontId.ORIGINAL || family.source == EpubFontSource.BUILTIN || family.faces.isEmpty()) {
            return null
        }
        val faces = family.faces.webLoadOrder().map { face ->
            EpubWebFontFace(
                key = face.key,
                postScriptName = face.postScriptName,
                file = if (family.source == EpubFontSource.LOCAL) face.sourceFile else null,
                weight = face.weight,
                minWeight = face.minWeight,
                maxWeight = face.maxWeight,
                italic = face.italic,
                mimeType = if (face.sfntFlavor == EpubFontFaceDescriptor.SFNT_CFF) "font/otf" else "font/ttf",
            )
        }
        return EpubWebFontPayload(
            key = "${family.id.value}:${family.fingerprint}",
            cssFamilyName = requireNotNull(family.cssFamilyName),
            faces = faces,
        )
    }

    fun fontLength(faceKey: String): Long {
        val currentFace = localFace(faceKey) ?: return -1L
        return materializeFace(currentFace)?.length()?.takeIf { it > 0L } ?: -1L
    }

    fun fontChunk(faceKey: String, chunkIndex: Int): String? {
        if (chunkIndex < 0) return null
        val currentFace = localFace(faceKey) ?: return null
        val file = materializeFace(currentFace) ?: return null
        val offset = chunkIndex.toLong() * WEB_CHUNK_BYTES
        if (offset >= file.length()) return ""
        RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            val bytes = ByteArray(minOf(WEB_CHUNK_BYTES.toLong(), file.length() - offset).toInt())
            val read = input.read(bytes)
            if (read <= 0) return ""
            return Base64.encodeToString(bytes, 0, read, Base64.NO_WRAP)
        }
    }

    private fun localFace(faceKey: String): EpubFontFaceDescriptor? {
        return catalogState.value.localFamilies.asSequence()
            .flatMap { it.faces.asSequence() }
            .firstOrNull { it.key == faceKey }
    }

    private fun List<EpubFontFaceDescriptor>.webLoadOrder(): List<EpubFontFaceDescriptor> {
        val remaining = toMutableList()
        return buildList {
            fun takeClosest(italic: Boolean, targetWeight: Int) {
                val face = remaining.filter { it.italic == italic }
                    .minByOrNull { kotlin.math.abs(it.weight - targetWeight) }
                    ?: return
                remaining.remove(face)
                add(face)
            }
            takeClosest(italic = false, targetWeight = 400)
            takeClosest(italic = true, targetWeight = 400)
            takeClosest(italic = false, targetWeight = 700)
            takeClosest(italic = true, targetWeight = 700)
            addAll(remaining.sortedWith(compareBy({ it.italic }, { kotlin.math.abs(it.weight - 400) })))
        }
    }

    fun previewTypeface(id: EpubFontId): Typeface? {
        val family = resolve(id)
        val cacheKey = "${id.value}:${family.fingerprint}"
        typefaceCache.get(cacheKey)?.let { return it }
        val face = family.faces.minWithOrNull(
            compareBy<EpubFontFaceDescriptor> { it.italic }.thenBy { kotlin.math.abs(it.weight - 400) },
        ) ?: return when (id.value) {
            EpubFontId.SERIF.value, "${EpubFontId.SYSTEM_PREFIX}generic-serif" -> Typeface.SERIF
            EpubFontId.SANS_SERIF.value, "${EpubFontId.SYSTEM_PREFIX}generic-sans-serif" -> Typeface.SANS_SERIF
            EpubFontId.MONOSPACE.value, "${EpubFontId.SYSTEM_PREFIX}generic-monospace" -> Typeface.MONOSPACE
            else -> null
        }
        val file = if (family.source == EpubFontSource.LOCAL) materializeFace(face) else face.sourceFile
        val typeface = file?.let {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Typeface.Builder(it).setTtcIndex(
                        if (family.source ==
                            EpubFontSource.SYSTEM
                        ) {
                            face.faceIndex
                        } else {
                            0
                        },
                    ).build()
                } else {
                    Typeface.createFromFile(it)
                }
            }.getOrNull()
        }
        typeface?.let { typefaceCache.put(cacheKey, it) }
        return typeface
    }

    private fun parseFont(file: File): ParsedOpenTypeFile {
        val locales = appContext.resources.configuration.locales
        val locale = if (locales.isEmpty) Locale.getDefault() else locales[0]
        return parser.parse(file, locale)
    }

    private suspend fun loadLocalCatalog() = catalogMutex.withLock {
        sourcesDirectory.mkdirs()
        extractedDirectory.mkdirs()
        val restored = runCatching {
            catalogFile.openRead().bufferedReader().use { json.decodeFromString<StoredEpubFontCatalog>(it.readText()) }
        }.getOrNull()
        storedCatalog = restored ?: rebuildCatalogFromSources()
        var catalogChanged = restored == null
        val existingSources = storedCatalog.sources.filter { File(sourcesDirectory, it.storedFileName).isFile }
        if (existingSources.size != storedCatalog.sources.size) {
            storedCatalog = storedCatalog.copy(sources = existingSources)
            catalogChanged = true
        }
        val refreshedCatalog = refreshCatalogNames(storedCatalog)
        if (refreshedCatalog != storedCatalog) {
            storedCatalog = refreshedCatalog
            catalogChanged = true
        }
        if (catalogChanged && storedCatalog.sources.isNotEmpty()) {
            runCatching(::saveCatalog).onFailure {
                logcat(logcat.LogPriority.WARN, it) { "Failed to persist recovered EPUB font catalog" }
            }
        }
        publishLocalCatalog(isLoading = false)
    }

    private fun refreshCatalogNames(catalog: StoredEpubFontCatalog): StoredEpubFontCatalog {
        return catalog.copy(
            sources = catalog.sources.map sourceMap@{ source ->
                val file = File(sourcesDirectory, source.storedFileName)
                val parsedFaces = runCatching { parseFont(file).faces.associateBy { it.index } }
                    .getOrNull()
                    ?: return@sourceMap source
                source.copy(
                    faces = source.faces.map faceMap@{ storedFace ->
                        val parsedFace = parsedFaces[storedFace.faceIndex] ?: return@faceMap storedFace
                        storedFace.copy(
                            familyName = parsedFace.familyName,
                            localizedFamilyName = parsedFace.localizedFamilyName,
                        )
                    },
                )
            },
        )
    }

    private fun rebuildCatalogFromSources(): StoredEpubFontCatalog {
        val sources = sourcesDirectory.listFiles().orEmpty().mapNotNull { file ->
            if (!file.isFile || file.length() !in 1..MAX_SOURCE_BYTES) return@mapNotNull null
            val parsed = runCatching { parseFont(file) }.getOrNull() ?: return@mapNotNull null
            val checksum = runCatching { file.sha256() }.getOrNull() ?: return@mapNotNull null
            StoredEpubFontSource(
                checksum = checksum,
                storedFileName = file.name,
                originalFileName = file.name,
                size = file.length(),
                faces = parsed.faces.map { face ->
                    StoredEpubFontFace(
                        faceIndex = face.index,
                        familyName = face.familyName,
                        localizedFamilyName = face.localizedFamilyName,
                        manufacturer = face.manufacturer,
                        postScriptName = face.postScriptName,
                        weight = face.weight,
                        italic = face.italic,
                        minWeight = face.minWeight,
                        maxWeight = face.maxWeight,
                        sfntFlavor = face.sfntFlavor,
                    )
                },
            )
        }
        return StoredEpubFontCatalog(sources = sources)
    }

    private suspend fun scanSystemFonts() {
        val families = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { systemFamiliesApi29() }
                .onFailure { logcat(logcat.LogPriority.WARN, it) { "Failed to scan system EPUB fonts" } }
                .getOrDefault(emptyList())
                .ifEmpty(::genericSystemFamilies)
        } else {
            genericSystemFamilies()
        }
        _catalogState.value = _catalogState.value.copy(systemFamilies = families, isSystemLoading = false)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun systemFamiliesApi29(): List<EpubFontFamilyDescriptor> {
        val parsedFiles = mutableMapOf<File, ParsedOpenTypeFile?>()
        return android.graphics.fonts.SystemFonts.getAvailableFonts()
            .mapNotNull { font ->
                val file = font.file?.takeIf { it.isFile } ?: return@mapNotNull null
                val parsed = if (file in parsedFiles) {
                    parsedFiles[file]
                } else {
                    runCatching { parseFont(file) }.getOrNull().also { parsedFiles[file] = it }
                }
                val parsedFace = parsed?.faces?.getOrNull(font.ttcIndex) ?: return@mapNotNull null
                val family = parsedFace.familyName.trim()
                family to EpubFontFaceDescriptor(
                    key = "system:${file.absolutePath}:${font.ttcIndex}",
                    familyName = family,
                    localizedFamilyName = parsedFace.localizedFamilyName,
                    postScriptName = parsedFace.postScriptName ?: family,
                    weight = font.style.weight,
                    italic = font.style.slant == android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC,
                    minWeight = parsedFace.minWeight,
                    maxWeight = parsedFace.maxWeight,
                    sourceFile = file,
                    faceIndex = font.ttcIndex,
                    sfntFlavor = parsedFace.sfntFlavor,
                )
            }
            .groupBy({ it.first.normalizedFamily() }, { it.second })
            .map { (_, faces) ->
                val name = faces.first().familyName
                val displayName = faces.firstNotNullOfOrNull { it.localizedFamilyName } ?: name
                val id = EpubFontId("${EpubFontId.SYSTEM_PREFIX}${stableKey(name)}")
                val stableFaces = faces.distinctBy { Triple(it.postScriptName, it.weight, it.italic) }
                    .sortedWith(
                        compareBy<EpubFontFaceDescriptor> { it.postScriptName.orEmpty() }
                            .thenBy { it.weight }
                            .thenBy { it.italic }
                            .thenBy { it.sourceFile?.absolutePath.orEmpty() }
                            .thenBy { it.faceIndex },
                    )
                EpubFontFamilyDescriptor(
                    id = id,
                    displayName = displayName,
                    source = EpubFontSource.SYSTEM,
                    cssFamilyName = cssFamilyName(id),
                    faces = stableFaces,
                    fingerprint = "${Build.FINGERPRINT}:${stableFaces.joinToString {
                        "${it.postScriptName}:${it.weight}:${it.italic}"
                    }}".sha256Text(),
                )
            }
            .sortedBy { it.displayName.lowercase() }
    }

    private fun publishLocalCatalog(isLoading: Boolean = false) {
        val faces = storedCatalog.sources.flatMap { source ->
            val file = File(sourcesDirectory, source.storedFileName)
            source.faces.map { face ->
                face.familyName.normalizedFamily() to EpubFontFaceDescriptor(
                    key = "local:${source.checksum}:${face.faceIndex}",
                    familyName = face.familyName,
                    localizedFamilyName = face.localizedFamilyName,
                    postScriptName = face.postScriptName,
                    weight = face.weight,
                    italic = face.italic,
                    minWeight = face.minWeight,
                    maxWeight = face.maxWeight,
                    sourceChecksum = source.checksum,
                    sourceFile = file,
                    faceIndex = face.faceIndex,
                    sfntFlavor = face.sfntFlavor,
                )
            }
        }
        val families = faces.groupBy({ it.first }, { it.second }).map { (familyKey, familyFaces) ->
            val id = EpubFontId("${EpubFontId.LOCAL_PREFIX}${stableKey(familyKey)}")
            val stableFaces = familyFaces.sortedWith(
                compareBy<EpubFontFaceDescriptor> { it.italic }
                    .thenBy { it.weight }
                    .thenBy { it.sourceChecksum.orEmpty() }
                    .thenBy { it.faceIndex },
            )
            EpubFontFamilyDescriptor(
                id = id,
                displayName = familyFaces.firstNotNullOfOrNull { it.localizedFamilyName }
                    ?: familyFaces.first().familyName,
                source = EpubFontSource.LOCAL,
                cssFamilyName = cssFamilyName(id),
                faces = stableFaces,
                fingerprint = stableFaces.joinToString("|") {
                    "${it.sourceChecksum}:${it.faceIndex}:${it.weight}:${it.italic}:${it.minWeight}:${it.maxWeight}"
                }.sha256Text(),
            )
        }.sortedBy { it.displayName.lowercase() }
        _catalogState.value = _catalogState.value.copy(localFamilies = families, isLocalLoading = isLoading)
    }

    private fun materializeFace(face: EpubFontFaceDescriptor): File? = synchronized(materializationLock) {
        materializedFaceFiles[face.key]?.takeIf(File::isFile)?.let { return@synchronized it }
        val source = face.sourceFile?.takeIf(File::isFile) ?: return@synchronized null
        val parsed = runCatching { parseFont(source) }.getOrNull() ?: return@synchronized null
        if (!parsed.isCollection) {
            materializedFaceFiles[face.key] = source
            return@synchronized source
        }
        extractedDirectory.mkdirs()
        val destination = File(extractedDirectory, "${face.sourceChecksum}-${face.faceIndex}.sfnt")
        if (!destination.isFile || destination.length() == 0L) {
            runCatching { parser.extractFace(source, face.faceIndex, destination) }
                .onFailure { destination.delete() }
                .getOrNull() ?: return@synchronized null
        }
        destination.setLastModified(System.currentTimeMillis())
        materializedFaceFiles[face.key] = destination
        trimExtractedCache(destination)
        destination
    }

    private fun trimExtractedCache(protectedFile: File) {
        var total = extractedDirectory.listFiles()?.sumOf(File::length) ?: return
        if (total <= MAX_EXTRACTED_CACHE_BYTES) return
        extractedDirectory.listFiles()
            ?.filter { it != protectedFile }
            ?.sortedBy(File::lastModified)
            ?.forEach { file ->
                if (total <= MAX_EXTRACTED_CACHE_BYTES) return
                val length = file.length()
                if (file.delete()) total -= length
            }
    }

    private fun findConflicts(staged: List<StagedFont>): List<String> {
        val existing = storedCatalog.sources.flatMap { it.faces }
        val incoming = staged.flatMap { it.parsed.faces }
        val existingConflicts = incoming.filter { face ->
            existing.any {
                it.familyName.normalizedFamily() == face.familyName.normalizedFamily() &&
                    it.weight == face.weight && it.italic == face.italic
            }
        }.map { it.familyName }
        val incomingConflicts = incoming.groupBy { it.identity() }
            .filterValues { it.size > 1 }
            .values
            .map { it.first().familyName }
        return (existingConflicts + incomingConflicts).distinct()
    }

    private fun acceptedStagedFaceIndices(
        staged: List<StagedFont>,
        replaceConflicts: Boolean,
    ): Map<String, Set<Int>> {
        if (!replaceConflicts) {
            return staged.associate { font -> font.checksum to font.parsed.faces.mapTo(mutableSetOf()) { it.index } }
        }
        val winners = mutableSetOf<Triple<String, Int, Boolean>>()
        val accepted = mutableMapOf<String, MutableSet<Int>>()
        staged.asReversed().forEach { font ->
            font.parsed.faces.asReversed().forEach { face ->
                if (winners.add(face.identity())) accepted.getOrPut(font.checksum, ::mutableSetOf).add(face.index)
            }
        }
        return accepted
    }

    private fun ParsedOpenTypeFace.identity(): Triple<String, Int, Boolean> =
        Triple(familyName.normalizedFamily(), weight, italic)

    private fun catalogWithoutConflictingFaces(staged: List<StagedFont>): StoredEpubFontCatalog {
        val replacements = staged.flatMap { it.parsed.faces }
            .map { Triple(it.familyName.normalizedFamily(), it.weight, it.italic) }
            .toSet()
        val updated = storedCatalog.sources.mapNotNull { source ->
            val remaining = source.faces.filterNot {
                Triple(it.familyName.normalizedFamily(), it.weight, it.italic) in replacements
            }
            source.copy(faces = remaining).takeIf { remaining.isNotEmpty() }
        }
        return storedCatalog.copy(sources = updated)
    }

    private fun saveCatalog() {
        catalogFile.baseFile.parentFile?.mkdirs()
        val stream = catalogFile.startWrite()
        try {
            stream.bufferedWriter().apply {
                write(json.encodeToString(storedCatalog))
                flush()
            }
            catalogFile.finishWrite(stream)
        } catch (error: Exception) {
            catalogFile.failWrite(stream)
            throw error
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return appContext.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }

    private data class StagedFont(
        val temporaryFile: File,
        val checksum: String,
        val originalFileName: String,
        val parsed: ParsedOpenTypeFile,
    )

    companion object {
        const val MAX_SOURCE_BYTES = 256L * 1024 * 1024
        const val MAX_LIBRARY_BYTES = 1024L * 1024 * 1024
        const val MAX_EXTRACTED_CACHE_BYTES = 256L * 1024 * 1024
        const val MAX_WEB_FONT_FAMILY_BYTES = 48L * 1024 * 1024
        const val WEB_CHUNK_BYTES = 256 * 1024
        private const val COPY_BUFFER_SIZE = 64 * 1024

        private fun builtInFamilies(): List<EpubFontFamilyDescriptor> = listOf(
            builtIn(EpubFontId.ORIGINAL, "Original", null),
            builtIn(EpubFontId.SERIF, "Serif", "serif"),
            builtIn(EpubFontId.SANS_SERIF, "Sans serif", "sans-serif"),
            builtIn(EpubFontId.MONOSPACE, "Monospace", "monospace"),
            builtIn(EpubFontId.CURSIVE, "Cursive", "cursive"),
            builtIn(EpubFontId.OPEN_DYSLEXIC, "OpenDyslexic", "OpenDyslexic"),
        )

        private fun genericSystemFamilies(): List<EpubFontFamilyDescriptor> = listOf(
            EpubFontFamilyDescriptor(
                id = EpubFontId("${EpubFontId.SYSTEM_PREFIX}generic-serif"),
                displayName = "System serif",
                source = EpubFontSource.SYSTEM,
                cssFamilyName = "serif",
            ),
            EpubFontFamilyDescriptor(
                id = EpubFontId("${EpubFontId.SYSTEM_PREFIX}generic-sans-serif"),
                displayName = "System sans serif",
                source = EpubFontSource.SYSTEM,
                cssFamilyName = "sans-serif",
            ),
            EpubFontFamilyDescriptor(
                id = EpubFontId("${EpubFontId.SYSTEM_PREFIX}generic-monospace"),
                displayName = "System monospace",
                source = EpubFontSource.SYSTEM,
                cssFamilyName = "monospace",
            ),
        )

        private fun builtIn(id: EpubFontId, name: String, css: String?) = EpubFontFamilyDescriptor(
            id = id,
            displayName = name,
            source = EpubFontSource.BUILTIN,
            cssFamilyName = css,
        )

        private fun cssFamilyName(id: EpubFontId): String = "KohariaEpub_${stableKey(id.value)}"
        private fun localFamilyKey(name: String): String = stableKey(name.normalizedFamily())
        private fun String.normalizedFamily(): String = trim().lowercase().replace(Regex("\\s+"), " ")
        private fun stableKey(value: String): String = value.sha256Text().take(24)
        private fun String.sha256Text(): String = MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        private fun File.sha256(): String = inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

internal fun ParsedOpenTypeFile.missingFrom(source: StoredEpubFontSource?): List<ParsedOpenTypeFace> {
    val retainedFaceIndices = source?.faces
        ?.mapTo(mutableSetOf(), StoredEpubFontFace::faceIndex)
        .orEmpty()
    return faces.filterNot { it.index in retainedFaceIndices }
}

internal fun mergeStoredFontSources(
    retained: List<StoredEpubFontSource>,
    additions: List<StoredEpubFontSource>,
): List<StoredEpubFontSource> {
    val additionsByChecksum = additions.associateBy(StoredEpubFontSource::checksum)
    val merged = retained.map { source ->
        val addition = additionsByChecksum[source.checksum] ?: return@map source
        source.copy(
            size = addition.size,
            faces = (source.faces + addition.faces)
                .distinctBy(StoredEpubFontFace::faceIndex)
                .sortedBy(StoredEpubFontFace::faceIndex),
        )
    }
    val retainedChecksums = retained.mapTo(mutableSetOf(), StoredEpubFontSource::checksum)
    return merged + additions.filterNot { it.checksum in retainedChecksums }
}
