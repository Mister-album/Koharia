package koharia.source.komga

import okhttp3.CacheControl
import okhttp3.Request

enum class KomgaCachePolicy {
    Default,
    NetworkFirst,
}

internal fun Request.Builder.komgaCachePolicy(policy: KomgaCachePolicy): Request.Builder {
    tag(KomgaCachePolicy::class.java, policy)
    if (policy == KomgaCachePolicy.NetworkFirst) {
        cacheControl(CacheControl.FORCE_NETWORK)
    }
    return this
}

internal val Request.komgaCachePolicy: KomgaCachePolicy
    get() = tag(KomgaCachePolicy::class.java) ?: KomgaCachePolicy.Default

internal fun Request.forKomgaDetails(
    namespace: String,
    forceNetwork: Boolean,
    intervalMillis: Long,
    revision: Long,
    nowMillis: Long = System.currentTimeMillis(),
): Request = newBuilder()
    .komgaCachePolicy(if (forceNetwork) KomgaCachePolicy.NetworkFirst else KomgaCachePolicy.Default)
    // Metadata freshness owns this decision; do not renew it from the one-hour HTTP cache.
    .cacheControl(CacheControl.FORCE_NETWORK)
    .tag(KomgaCacheNamespace::class.java, KomgaCacheNamespace(namespace, maxOf(nowMillis - intervalMillis, revision)))
    .build()
