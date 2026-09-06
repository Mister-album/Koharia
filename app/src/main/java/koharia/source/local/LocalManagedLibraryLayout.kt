package koharia.source.local

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.UUID

internal fun createManagedLayout(
    context: Context,
    uri: Uri,
    displayPath: String,
    libraryId: String,
    contentTypes: Set<LocalLibraryContentType>,
    json: Json,
): LocalManagedLibraryLayout? {
    if (contentTypes.none { it == LocalLibraryContentType.COMICS || it == LocalLibraryContentType.BOOKS }) {
        return null
    }
    val root = UniFile.fromUri(context, uri)?.takeIf { it.isDirectory && it.canWrite() } ?: return null
    return runCatching {
        val folderNames = buildList {
            add(".koharia")
            if (LocalLibraryContentType.COMICS in contentTypes) add("Comics")
            if (LocalLibraryContentType.BOOKS in contentTypes) add("Books")
        }
        folderNames.forEach { name ->
            require(root.findFile(name)?.isDirectory != false) { "A file occupies the required directory" }
        }
        val metadataRoot = root.findFile(".koharia") ?: checkNotNull(root.createDirectory(".koharia"))
        require(metadataRoot.findFile("metadata")?.isDirectory != false)
        metadataRoot.findFile("metadata") ?: checkNotNull(metadataRoot.createDirectory("metadata"))
        if (LocalLibraryContentType.COMICS in contentTypes) {
            root.findFile("Comics") ?: checkNotNull(root.createDirectory("Comics"))
        }
        if (LocalLibraryContentType.BOOKS in contentTypes) {
            root.findFile("Books") ?: checkNotNull(root.createDirectory("Books"))
        }
        val existingManifest = metadataRoot.findFile("library.json")
        val effectiveLibraryId = if (existingManifest != null) {
            existingManifest.openInputStream().use { input ->
                json.decodeFromString<LocalLibraryManifest>(
                    input.readBytes().toString(StandardCharsets.UTF_8),
                ).libraryId
            }
        } else {
            val manifest = LocalLibraryManifest(
                libraryId = libraryId,
                contentType = contentTypes.singleOrNull() ?: LocalLibraryContentType.MIXED,
            )
            val manifestFile = checkNotNull(metadataRoot.createFile("library.json"))
            manifestFile.openOutputStream().use { output ->
                output.write(
                    json.encodeToString(LocalLibraryManifest.serializer(), manifest)
                        .toByteArray(StandardCharsets.UTF_8),
                )
            }
            libraryId
        }
        LocalManagedLibraryLayout(
            libraryId = effectiveLibraryId,
            roots = buildList {
                if (LocalLibraryContentType.COMICS in contentTypes) {
                    add(
                        LocalLibraryRootConfig(
                            id = UUID.randomUUID().toString(),
                            treeUri = uri.toString(),
                            displayPath = "$displayPath/Comics",
                            contentType = LocalLibraryContentType.COMICS,
                            relativePath = "Comics",
                            managed = true,
                        ),
                    )
                }
                if (LocalLibraryContentType.BOOKS in contentTypes) {
                    add(
                        LocalLibraryRootConfig(
                            id = UUID.randomUUID().toString(),
                            treeUri = uri.toString(),
                            displayPath = "$displayPath/Books",
                            contentType = LocalLibraryContentType.BOOKS,
                            relativePath = "Books",
                            managed = true,
                        ),
                    )
                }
            },
        )
    }.getOrNull()
}

internal data class LocalManagedLibraryLayout(
    val libraryId: String,
    val roots: List<LocalLibraryRootConfig>,
)
