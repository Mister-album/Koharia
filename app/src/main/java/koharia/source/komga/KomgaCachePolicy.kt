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
