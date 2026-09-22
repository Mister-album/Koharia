package koharia.smanga

import kotlinx.serialization.Serializable
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.security.MessageDigest

@Serializable
data class SmangaPageManifest(val chapterId: Long, val version: String, val pages: List<SmangaManifestPage>) {
    val pageCount: Int get() = pages.size

    companion object {
        /** JSON preserves the user's display order; OPDS indexes UTF-16 lexical full-path order. */
        fun create(chapterId: Long, orderedPaths: List<String>): SmangaPageManifest {
            require(chapterId > 0)
            if (orderedPaths.isEmpty()) throw SmangaException(SmangaException.Reason.EMPTY)
            if (orderedPaths.any(String::isBlank) || orderedPaths.distinct().size != orderedPaths.size) {
                throw SmangaException(SmangaException.Reason.PROTOCOL)
            }
            val lexicalIndices = orderedPaths.sorted().withIndex().associate { it.value to it.index + 1 }
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("smanga-manifest-v1:$chapterId".toByteArray(Charsets.UTF_8))
            orderedPaths.forEach { path ->
                val bytes = path.toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                digest.update(bytes)
            }
            return SmangaPageManifest(
                chapterId = chapterId,
                version = digest.digest().toByteString().hex(),
                pages = orderedPaths.mapIndexed { index, path ->
                    SmangaManifestPage(index, lexicalIndices.getValue(path))
                },
            )
        }
    }
}

@Serializable
data class SmangaManifestPage(val displayIndex: Int, val opdsPage: Int)
