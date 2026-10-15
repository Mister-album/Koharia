package koharia.source.komga

import okhttp3.Request
import tachiyomi.domain.source.service.SourceManager

internal class KomgaProgressSession(
    val sourceId: Long,
    val source: KomgaSource,
    val account: KomgaProgressAccount,
    val guard: KomgaProgressRequestGuard,
) {
    fun request(builder: Request.Builder): Request {
        guard.requireCurrent()
        return builder.headers(account.headers)
            .tag(KomgaProgressAccount::class.java, account)
            .tag(KomgaProgressRequestGuard::class.java, guard)
            .tag(KomgaProgressWriteAttempt::class.java, KomgaProgressWriteAttempt())
            .komgaProgressSync().build().also(account::requireDestination)
    }
}

internal fun captureKomgaProgressSession(source: KomgaSource, sourceManager: SourceManager): KomgaProgressSession {
    val sourceId = source.id
    if (sourceManager.get(sourceId) !== source) throw KomgaProgressOwnerChanged()
    val account = source.progressAccount()
    val guard = KomgaProgressRequestGuard {
        if (sourceManager.get(sourceId) !== source) throw KomgaProgressOwnerChanged()
        account.requireCurrent(source.progressAccount().key)
    }
    guard.requireCurrent()
    return KomgaProgressSession(sourceId, source, account, guard)
}
