package tachiyomi.data.release

import android.os.Build
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService

class ReleaseServiceImpl(
    private val networkService: NetworkHelper,
    private val json: Json,
) : ReleaseService {

    override suspend fun latest(arguments: GetApplicationRelease.Arguments): Release? {
        val release = with(json) {
            networkService.client
                .newCall(
                    GET("https://api.github.com/repos/${arguments.repository}/releases/latest")
                        .newBuilder()
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .build(),
                )
                .awaitSuccess()
                .parseAs<GithubRelease>()
        }

        val downloadAsset = getDownloadAsset(release = release, isFoss = arguments.isFoss) ?: return null
        val proxyDownloadLink = buildProxyDownloadLink(
            repository = arguments.repository,
            tag = release.version,
            assetName = downloadAsset.name,
        )
        val expectedSha256 = downloadAsset.digest
            ?.substringAfter("sha256:", missingDelimiterValue = "")
            ?.takeIf { SHA256_REGEX.matches(it) }

        return Release(
            version = release.version,
            info = release.info.substringBeforeLast("<!-->").replace(gitHubUsernameMentionRegex) { mention ->
                "[${mention.value}](https://github.com/${mention.value.substring(1)})"
            },
            releaseLink = release.releaseLink,
            downloadLink = proxyDownloadLink ?: downloadAsset.downloadLink,
            fallbackDownloadLinks = listOfNotNull(
                downloadAsset.downloadLink.takeIf { it != proxyDownloadLink },
            ),
            expectedSize = downloadAsset.size.takeIf { it > 0L },
            expectedSha256 = expectedSha256,
        )
    }

    private fun getDownloadAsset(release: GithubRelease, isFoss: Boolean): GitHubAsset? {
        val map = release.assets
            .filter { it.name.startsWith("Koharia") && it.name.endsWith(".apk", ignoreCase = true) }
            .associate { asset ->
                BUILD_TYPES.find { "-$it" in asset.name } to asset
            }

        return if (!isFoss) {
            map[Build.SUPPORTED_ABIS[0]] ?: map[null]
        } else {
            map[FOSS]
        }
    }

    private fun buildProxyDownloadLink(repository: String, tag: String, assetName: String): String? {
        if (repository != KOHARIA_REPOSITORY) return null

        return DOWNLOAD_BASE_URL.toHttpUrl()
            .newBuilder()
            .addPathSegment("releases")
            .addPathSegment("download")
            .addPathSegment(tag)
            .addPathSegment(assetName)
            .build()
            .toString()
    }

    companion object {
        private const val KOHARIA_REPOSITORY = "Mister-album/Koharia"
        private const val DOWNLOAD_BASE_URL = "https://download.koharia.org"
        private const val FOSS = "foss"
        private val BUILD_TYPES = listOf(FOSS, "arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        private val SHA256_REGEX = Regex("[0-9a-fA-F]{64}")

        /**
         * Regular expression that matches a mention to a valid GitHub username, like it's
         * done in GitHub Flavored Markdown. It follows these constraints:
         *
         * - Alphanumeric with single hyphens (no consecutive hyphens)
         * - Cannot begin or end with a hyphen
         * - Max length of 39 characters
         *
         * Reference: https://stackoverflow.com/a/30281147
         */
        private val gitHubUsernameMentionRegex = """\B@([a-z0-9](?:-(?=[a-z0-9])|[a-z0-9]){0,38}(?<=[a-z0-9]))"""
            .toRegex(RegexOption.IGNORE_CASE)
    }
}
