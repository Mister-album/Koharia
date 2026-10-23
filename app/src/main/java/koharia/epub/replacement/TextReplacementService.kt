package koharia.epub.replacement

import koharia.connection.ConnectionSourceSession
import koharia.connection.SharedAppPreferences
import koharia.domain.textreplacement.EffectiveRuleSet
import koharia.domain.textreplacement.ReplacementScope
import koharia.domain.textreplacement.ReplacementScopeState
import koharia.domain.textreplacement.StoredReplacementScope
import koharia.domain.textreplacement.TextReplacementContext
import koharia.domain.textreplacement.TextReplacementRepository
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest

class TextReplacementService(
    val repository: TextReplacementRepository = Injekt.get(),
) {
    suspend fun context(mangaId: Long, chapterId: Long? = null): TextReplacementContext {
        val manga = checkNotNull(Injekt.get<GetManga>().await(mangaId))
        val source = checkNotNull(Injekt.get<SourceManager>().get(manga.source))
        val session = ConnectionSourceSession.capture(source)
        val owner = (source as? koharia.connection.ConnectionTextIdentityAdapter)?.textReplacementOwnerKey()
            ?: session.ownerKey
        val chapter = chapterId?.let { checkNotNull(Injekt.get<GetChapter>().await(it)) }
        require(chapter == null || chapter.mangaId == manga.id)
        session.checkCurrent()
        return TextReplacementContext(
            source.id,
            identity(owner),
            identity(manga.url),
            chapter?.url?.let(::identity).orEmpty(),
        )
    }

    suspend fun snapshot(context: TextReplacementContext): EffectiveRuleSet = resolve(context, repository.all())

    fun resolve(context: TextReplacementContext, entries: List<StoredReplacementScope>): EffectiveRuleSet =
        EffectiveRuleSet.resolve(
            states(context, entries),
            Injekt.get<SharedAppPreferences>().epubReaderPreferences().textReplacementEnabled.get(),
        )

    fun states(context: TextReplacementContext, entries: List<StoredReplacementScope>) = ReplacementScope.entries
        .filter { it == ReplacementScope.GLOBAL || context.seriesKey.isNotEmpty() }
        .filter { it != ReplacementScope.BOOK || context.bookKey.isNotEmpty() }
        .associateWith { scope -> entries.find { it.key == context.key(scope) }?.state ?: ReplacementScopeState() }

    companion object {
        suspend fun supports(
            source: eu.kanade.tachiyomi.source.Source,
            chapter: tachiyomi.domain.chapter.model.Chapter,
        ): Boolean {
            val metadata = (source as? koharia.connection.ConnectionPublicationAdapter)?.resolvePublication(
                chapter,
                allowRemoteLookup = false,
            )
            val extension = (metadata?.fileName ?: chapter.url).substringBefore(
                '?',
            ).substringBefore('#').substringAfterLast('.').lowercase()
            return metadata?.mediaType in setOf("application/epub+zip", "application/pdf") ||
                extension in koharia.media.LocalMediaFormats.reflowableBookExtensions ||
                (
                    extension == "pdf" &&
                        (
                            source is koharia.connection.ConnectionRawDownloadAdapter ||
                                source is koharia.connection.ConnectionLocalFileAdapter
                            )
                    )
        }

        fun identity(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
