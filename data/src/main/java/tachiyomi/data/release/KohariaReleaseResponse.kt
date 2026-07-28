package tachiyomi.data.release

import kotlinx.serialization.Serializable

@Serializable
data class KohariaReleaseResponse(
    val apiVersion: Int,
    val repository: String,
    val release: KohariaRelease,
)

@Serializable
data class KohariaRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val publishedAt: String,
    val primaryApk: KohariaReleaseAsset? = null,
    val assets: List<KohariaReleaseAsset> = emptyList(),
)

@Serializable
data class KohariaReleaseAsset(
    val name: String,
    val size: Long,
    val digest: String? = null,
    val sha256: String? = null,
    val downloadUrl: String,
    val githubDownloadUrl: String,
)
