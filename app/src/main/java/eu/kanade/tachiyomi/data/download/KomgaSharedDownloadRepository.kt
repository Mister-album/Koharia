package eu.kanade.tachiyomi.data.download

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.serialization.json.JsonObject
import tachiyomi.data.Database

data class KomgaSharedDownloadMatch(
    val serverId: Long,
    val bookUrl: String,
    val seriesUrl: String,
    val fileHash: String,
    val sizeBytes: Long,
    val isbn: String,
    val seriesTitle: String,
    val bookTitle: String,
    val numberSort: Double,
    val localRelativePath: String,
    val fileName: String,
    val fileKind: String,
    val createdAt: Long,
    val lastVerifiedAt: Long,
)

data class KomgaChapterSeed(
    val chapterId: Long,
    val chapterUrl: String,
    val chapterName: String,
    val chapterScanlator: String?,
    val memo: JsonObject,
    val mangaTitle: String,
    val sourceId: Long,
)

class KomgaSharedDownloadRepository(
    private val database: Database,
) {

    suspend fun findByServerIdAndBookUrl(serverId: Long, bookUrl: String): KomgaSharedDownloadMatch? {
        return database.komga_shared_download_matchesQueries
            .findByServerIdAndBookUrl(serverId, bookUrl, ::mapMatch)
            .awaitAsOneOrNull()
    }

    suspend fun findByServerId(serverId: Long): List<KomgaSharedDownloadMatch> {
        return database.komga_shared_download_matchesQueries
            .findByServerId(serverId, ::mapMatch)
            .awaitAsList()
    }

    suspend fun findByFileHash(fileHash: String): List<KomgaSharedDownloadMatch> {
        return database.komga_shared_download_matchesQueries
            .findByFileHash(fileHash, ::mapMatch)
            .awaitAsList()
    }

    suspend fun findByIsbn(isbn: String): List<KomgaSharedDownloadMatch> {
        return database.komga_shared_download_matchesQueries
            .findByIsbn(isbn, ::mapMatch)
            .awaitAsList()
    }

    suspend fun findBySeriesNumberSize(
        seriesTitle: String,
        numberSort: Double,
        sizeBytes: Long,
    ): List<KomgaSharedDownloadMatch> {
        return database.komga_shared_download_matchesQueries
            .findBySeriesNumberSize(seriesTitle, numberSort, sizeBytes, ::mapMatch)
            .awaitAsList()
    }

    suspend fun findByBookTitleSize(bookTitle: String, sizeBytes: Long): List<KomgaSharedDownloadMatch> {
        return database.komga_shared_download_matchesQueries
            .findByBookTitleSize(bookTitle, sizeBytes, ::mapMatch)
            .awaitAsList()
    }

    suspend fun findByLocalRelativePath(localRelativePath: String): List<KomgaSharedDownloadMatch> {
        return database.komga_shared_download_matchesQueries
            .findByLocalRelativePath(localRelativePath, ::mapMatch)
            .awaitAsList()
    }

    suspend fun findByLocalRelativePathPrefix(localRelativePathPrefix: String): List<KomgaSharedDownloadMatch> {
        return database.komga_shared_download_matchesQueries
            .findByLocalRelativePathPrefix(localRelativePathPrefix, ::mapMatch)
            .awaitAsList()
    }

    suspend fun countByLocalRelativePath(localRelativePath: String): Long {
        return database.komga_shared_download_matchesQueries
            .countByLocalRelativePath(localRelativePath)
            .awaitAsOne()
    }

    suspend fun upsert(match: KomgaSharedDownloadMatch) {
        database.komga_shared_download_matchesQueries.upsert(
            serverId = match.serverId,
            bookUrl = match.bookUrl,
            seriesUrl = match.seriesUrl,
            fileHash = match.fileHash,
            sizeBytes = match.sizeBytes,
            isbn = match.isbn,
            seriesTitle = match.seriesTitle,
            bookTitle = match.bookTitle,
            numberSort = match.numberSort,
            localRelativePath = match.localRelativePath,
            fileName = match.fileName,
            fileKind = match.fileKind,
            createdAt = match.createdAt,
            lastVerifiedAt = match.lastVerifiedAt,
        )
    }

    suspend fun deleteByServerId(serverId: Long) {
        database.komga_shared_download_matchesQueries.deleteByServerId(serverId)
    }

    suspend fun deleteByServerIdAndBookUrl(serverId: Long, bookUrl: String) {
        database.komga_shared_download_matchesQueries.deleteByServerIdAndBookUrl(serverId, bookUrl)
    }

    suspend fun deleteByLocalRelativePath(localRelativePath: String) {
        database.komga_shared_download_matchesQueries.deleteByLocalRelativePath(localRelativePath)
    }

    suspend fun updateLocalRelativePath(
        oldLocalRelativePath: String,
        newLocalRelativePath: String,
        newFileName: String,
        lastVerifiedAt: Long,
    ) {
        database.komga_shared_download_matchesQueries.updateLocalRelativePath(
            oldLocalRelativePath = oldLocalRelativePath,
            newLocalRelativePath = newLocalRelativePath,
            newFileName = newFileName,
            lastVerifiedAt = lastVerifiedAt,
        )
    }

    suspend fun updateLocalRelativePathPrefix(
        oldPrefix: String,
        newPrefix: String,
        lastVerifiedAt: Long,
    ) {
        database.komga_shared_download_matchesQueries.updateLocalRelativePathPrefix(
            oldPrefix = oldPrefix,
            oldPrefixLength = oldPrefix.length.toString(),
            newPrefix = newPrefix,
            lastVerifiedAt = lastVerifiedAt,
        )
    }

    suspend fun getChapterSeedByUrl(chapterUrl: String): KomgaChapterSeed? {
        return database.komga_shared_download_matchesQueries
            .getKomgaChapterSeedByUrl(chapterUrl, ::mapChapterSeed)
            .awaitAsOneOrNull()
    }

    suspend fun getChapterSeedsBySourceId(sourceId: Long): List<KomgaChapterSeed> {
        return database.komga_shared_download_matchesQueries
            .getKomgaChapterSeedsBySourceId(sourceId, ::mapWarmupChapterSeed)
            .awaitAsList()
    }

    suspend fun updateChapterMemo(chapterId: Long, memo: JsonObject) {
        database.komga_shared_download_matchesQueries.updateChapterMemoOnly(
            memo = memo,
            chapterId = chapterId,
        )
    }

    private fun mapMatch(
        serverId: Long,
        bookUrl: String,
        seriesUrl: String,
        fileHash: String,
        sizeBytes: Long,
        isbn: String,
        seriesTitle: String,
        bookTitle: String,
        numberSort: Double,
        localRelativePath: String,
        fileName: String,
        fileKind: String,
        createdAt: Long,
        lastVerifiedAt: Long,
    ): KomgaSharedDownloadMatch {
        return KomgaSharedDownloadMatch(
            serverId = serverId,
            bookUrl = bookUrl,
            seriesUrl = seriesUrl,
            fileHash = fileHash,
            sizeBytes = sizeBytes,
            isbn = isbn,
            seriesTitle = seriesTitle,
            bookTitle = bookTitle,
            numberSort = numberSort,
            localRelativePath = localRelativePath,
            fileName = fileName,
            fileKind = fileKind,
            createdAt = createdAt,
            lastVerifiedAt = lastVerifiedAt,
        )
    }

    private fun mapChapterSeed(
        chapterId: Long,
        chapterUrl: String,
        chapterName: String,
        chapterScanlator: String?,
        memo: JsonObject,
        mangaTitle: String,
        sourceId: Long,
    ): KomgaChapterSeed {
        return KomgaChapterSeed(
            chapterId = chapterId,
            chapterUrl = chapterUrl,
            chapterName = chapterName,
            chapterScanlator = chapterScanlator,
            memo = memo,
            mangaTitle = mangaTitle,
            sourceId = sourceId,
        )
    }

    private fun mapWarmupChapterSeed(
        chapterId: Long,
        chapterUrl: String,
        chapterName: String,
        chapterScanlator: String?,
        memo: JsonObject,
        mangaTitle: String,
    ): KomgaChapterSeed {
        return KomgaChapterSeed(
            chapterId = chapterId,
            chapterUrl = chapterUrl,
            chapterName = chapterName,
            chapterScanlator = chapterScanlator,
            memo = memo,
            mangaTitle = mangaTitle,
            sourceId = -1L,
        )
    }
}
