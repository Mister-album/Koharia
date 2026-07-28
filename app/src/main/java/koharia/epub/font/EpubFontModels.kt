package koharia.epub.font

import kotlinx.serialization.Serializable
import java.io.File

@JvmInline
value class EpubFontId(val value: String) {
    val source: EpubFontSource
        get() = when {
            value.startsWith(SYSTEM_PREFIX) -> EpubFontSource.SYSTEM
            value.startsWith(LOCAL_PREFIX) -> EpubFontSource.LOCAL
            else -> EpubFontSource.BUILTIN
        }

    companion object {
        const val BUILTIN_PREFIX = "builtin:"
        const val SYSTEM_PREFIX = "system:"
        const val LOCAL_PREFIX = "local:"

        val ORIGINAL = EpubFontId("${BUILTIN_PREFIX}original")
        val SERIF = EpubFontId("${BUILTIN_PREFIX}serif")
        val SANS_SERIF = EpubFontId("${BUILTIN_PREFIX}sans-serif")
        val MONOSPACE = EpubFontId("${BUILTIN_PREFIX}monospace")
        val CURSIVE = EpubFontId("${BUILTIN_PREFIX}cursive")
        val OPEN_DYSLEXIC = EpubFontId("${BUILTIN_PREFIX}open-dyslexic")

        fun fromPreference(value: String?): EpubFontId {
            return when (value?.trim()) {
                null, "", "ORIGINAL" -> ORIGINAL
                "SERIF" -> SERIF
                "SANS_SERIF" -> SANS_SERIF
                "MONOSPACE" -> MONOSPACE
                "OPEN_DYSLEXIC" -> OPEN_DYSLEXIC
                else -> EpubFontId(value)
            }
        }
    }
}

enum class EpubFontSource {
    BUILTIN,
    SYSTEM,
    LOCAL,
}

data class EpubFontFaceDescriptor(
    val key: String,
    val familyName: String,
    val localizedFamilyName: String? = null,
    val postScriptName: String?,
    val weight: Int,
    val italic: Boolean,
    val minWeight: Int = weight,
    val maxWeight: Int = weight,
    val sourceChecksum: String? = null,
    val sourceFile: File? = null,
    val faceIndex: Int = 0,
    val sfntFlavor: String = SFNT_TRUE_TYPE,
) {
    val isVariableWeight: Boolean
        get() = minWeight != maxWeight

    companion object {
        const val SFNT_TRUE_TYPE = "true-type"
        const val SFNT_CFF = "cff"
    }
}

data class EpubFontFamilyDescriptor(
    val id: EpubFontId,
    val displayName: String,
    val source: EpubFontSource,
    val cssFamilyName: String?,
    val faces: List<EpubFontFaceDescriptor> = emptyList(),
    val fingerprint: String = id.value,
)

data class EpubFontCatalogState(
    val builtInFamilies: List<EpubFontFamilyDescriptor> = emptyList(),
    val systemFamilies: List<EpubFontFamilyDescriptor> = emptyList(),
    val localFamilies: List<EpubFontFamilyDescriptor> = emptyList(),
    val isSystemLoading: Boolean = false,
    val isLocalLoading: Boolean = true,
) {
    val allFamilies: List<EpubFontFamilyDescriptor>
        get() = builtInFamilies + systemFamilies + localFamilies
}

sealed interface EpubFontImportResult {
    data class Success(
        val importedFamilies: Int,
        val duplicateFiles: Int,
    ) : EpubFontImportResult

    data class Failure(val reason: EpubFontImportFailure) : EpubFontImportResult

    data class Conflict(val familyNames: List<String>) : EpubFontImportResult
}

enum class EpubFontImportFailure {
    INVALID_FORMAT,
    FILE_TOO_LARGE,
    LIBRARY_FULL,
    READ_FAILED,
}

internal data class EpubWebFontPayload(
    val key: String,
    val cssFamilyName: String,
    val faces: List<EpubWebFontFace>,
)

internal data class EpubWebFontFace(
    val key: String,
    val postScriptName: String?,
    val file: File?,
    val weight: Int,
    val minWeight: Int,
    val maxWeight: Int,
    val italic: Boolean,
    val mimeType: String,
)

@Serializable
internal data class StoredEpubFontCatalog(
    val version: Int = 1,
    val sources: List<StoredEpubFontSource> = emptyList(),
)

@Serializable
internal data class StoredEpubFontSource(
    val checksum: String,
    val storedFileName: String,
    val originalFileName: String,
    val size: Long,
    val faces: List<StoredEpubFontFace>,
)

@Serializable
internal data class StoredEpubFontFace(
    val faceIndex: Int,
    val familyName: String,
    val localizedFamilyName: String? = null,
    val manufacturer: String? = null,
    val postScriptName: String? = null,
    val weight: Int = 400,
    val italic: Boolean = false,
    val minWeight: Int = weight,
    val maxWeight: Int = weight,
    val sfntFlavor: String = EpubFontFaceDescriptor.SFNT_TRUE_TYPE,
)
