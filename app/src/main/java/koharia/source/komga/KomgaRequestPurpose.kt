package koharia.source.komga

import okhttp3.Request

internal enum class KomgaRequestPurpose {
    ProgressSync,
    RequireNetwork,
}

internal fun Request.Builder.komgaRequireNetwork(): Request.Builder =
    tag(KomgaRequestPurpose::class.java, KomgaRequestPurpose.RequireNetwork)

internal val Request.isKomgaNetworkRequired: Boolean
    get() = tag(KomgaRequestPurpose::class.java) == KomgaRequestPurpose.RequireNetwork

internal data class KomgaCacheNamespace(val value: String, val minimumFetchedAt: Long = 0L)

internal fun Request.Builder.komgaProgressSync(): Request.Builder =
    tag(KomgaRequestPurpose::class.java, KomgaRequestPurpose.ProgressSync)

internal val Request.isKomgaProgressSync: Boolean
    get() = tag(KomgaRequestPurpose::class.java) == KomgaRequestPurpose.ProgressSync
