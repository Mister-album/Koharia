package tachiyomi.data.release

import android.os.Build
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService

class ReleaseServiceImpl(
    private val networkService: NetworkHelper,
    private val json: Json,
) : ReleaseService {

    override suspend fun latest(arguments: GetApplicationRelease.Arguments): Release? {
        if (arguments.repository == KOHARIA_REPOSITORY) {
            try {
                latestFromKoharia(arguments)?.let { return it }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logcat(LogPriority.WARN, error) {
                    "Koharia release metadata proxy failed; falling back to GitHub"
                }
            }
        }

        return latestFromGitHub(arguments)
    }

    private suspend fun latestFromKoharia(arguments: GetApplicationRelease.Arguments): Release? {
        val response = with(json) {
            networkService.client
                .newCall(GET("$DOWNLOAD_BASE_URL/api/releases/latest"))
                .awaitSuccess()
                .parseAs<KohariaReleaseResponse>()
        }
        if (
            response.apiVersion != KOHARIA_RELEASE_API_VERSION ||
            response.repository != arguments.repository ||
            response.release.draft ||
            response.release.prerelease
        ) {
            return null
        }

        val release = response.release
        val version = release.tagName.takeIf(String::isNotBlank) ?: return null
        val releaseLink = release.htmlUrl.validHttpsUrl() ?: return null
        val assets = release.assets.ifEmpty { listOfNotNull(release.primaryApk) }
        val downloadAsset = selectDownloadAsset(assets, arguments.isFoss) { it.name } ?: return null
        val proxyDownloadLink = downloadAsset.downloadUrl.validHttpsUrl()
        val githubDownloadLink = downloadAsset.githubDownloadUrl.validHttpsUrl()
        val primaryDownloadLink = proxyDownloadLink ?: githubDownloadLink ?: return null

        return Release(
            version = version,
            info = release.body.withLinkedGitHubMentions(),
            releaseLink = releaseLink,
            downloadLink = primaryDownloadLink,
            fallbackDownloadLinks = listOfNotNull(
                githubDownloadLink.takeIf { it != primaryDownloadLink },
            ),
            expectedSize = downloadAsset.size.takeIf { it > 0L },
            expectedSha256 = downloadAsset.sha256.validSha256()
                ?: downloadAsset.digest.sha256FromDigest(),
        )
    }

    private suspend fun latestFromGitHub(arguments: GetApplicationRelease.Arguments): Release? {
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
            info = release.info.withLinkedGitHubMentions(),
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
        return selectDownloadAsset(release.assets, isFoss) { it.name }
    }

    private fun <T> selectDownloadAsset(
        assets: List<T>,
        isFoss: Boolean,
        name: (T) -> String,
    ): T? {
        val map = assets
            .filter { asset ->
                name(asset).startsWith("Koharia") && name(asset).endsWith(".apk", ignoreCase = true)
            }
            .associate { asset ->
                BUILD_TYPES.find { "-$it" in name(asset) } to asset
            }

        return if (!isFoss) {
            map[Build.SUPPORTED_ABIS[0]] ?: map[null]
        } else {
            map[FOSS]
        }
    }

    private fun String.withLinkedGitHubMentions(): String {
        return substringBeforeLast("<!-->").replace(gitHubUsernameMentionRegex) { mention ->
            "[${mention.value}](https://github.com/${mention.value.substring(1)})"
        }
    }

    private fun String?.validSha256(): String? = this?.takeIf(SHA256_REGEX::matches)

    private fun String?.sha256FromDigest(): String? {
        return this
            ?.substringAfter("sha256:", missingDelimiterValue = "")
            .validSha256()
    }

    private fun String.validHttpsUrl(): String? {
        return toHttpUrlOrNull()
            ?.takeIf { it.isHttps }
            ?.toString()
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
        private const val KOHARIA_RELEASE_API_VERSION = 1
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
