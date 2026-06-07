package org.jellyfin.androidtv.data.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.androidtv.BuildConfig
import timber.log.Timber

@Serializable
data class GitHubRelease(
	@SerialName("tag_name") val tagName: String,
	@SerialName("name") val name: String,
	@SerialName("html_url") val htmlUrl: String,
	@SerialName("assets") val assets: List<GitHubAsset>,
	@SerialName("body") val body: String? = null,
	@SerialName("prerelease") val prerelease: Boolean = false,
)

@Serializable
data class GitHubAsset(
	@SerialName("name") val name: String,
	@SerialName("browser_download_url") val downloadUrl: String,
	@SerialName("size") val size: Long,
)

data class UpdateInfo(
	val version: String,
	val downloadUrl: String,
	val releaseNotesUrl: String,
	val releaseNotes: String?,
	val apkSize: Long,
)

class UpdateCheckerService(
	private val httpClient: OkHttpClient,
) {
	private val json = Json {
		ignoreUnknownKeys = true
		coerceInputValues = true
	}

	suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
		try {
			// Only check for updates in enhanced build
			if (!BuildConfig.BUILD_TYPE.equals("enhanced", ignoreCase = true)) {
				Timber.d("Update checker only works in enhanced build")
				return@withContext null
			}

			// Get repo info from BuildConfig (only available in enhanced build)
			val owner = try {
				BuildConfig::class.java.getField("GITHUB_REPO_OWNER").get(null) as? String
			} catch (e: Exception) { null } ?: "crunchprank"

			val repo = try {
				BuildConfig::class.java.getField("GITHUB_REPO_NAME").get(null) as? String
			} catch (e: Exception) { null } ?: "jellyfin-androidtv"
			val apiUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"

			Timber.d("Checking for updates at: $apiUrl")

			val request = Request.Builder()
				.url(apiUrl)
				.addHeader("Accept", "application/vnd.github.v3+json")
				.build()

			val response = httpClient.newCall(request).execute()
			if (!response.isSuccessful) {
				Timber.w("Failed to check for updates: ${response.code}")
				return@withContext null
			}

			val responseBody = response.body?.string() ?: run {
				Timber.w("Empty response body")
				return@withContext null
			}

			val release = json.decodeFromString<GitHubRelease>(responseBody)

			// Skip pre-releases
			if (release.prerelease) {
				Timber.d("Skipping pre-release: ${release.tagName}")
				return@withContext null
			}

			// Find the APK asset (look for .apk file)
			val apkAsset = release.assets.find { it.name.endsWith(".apk", ignoreCase = true) }
			if (apkAsset == null) {
				Timber.w("No APK found in release: ${release.tagName}")
				return@withContext null
			}

			// Extract version from tag (remove 'v' prefix if present)
			val remoteVersion = release.tagName.removePrefix("v")
			val currentVersion = BuildConfig.VERSION_NAME

			Timber.d("Current version: $currentVersion, Remote version: $remoteVersion")

			// Compare versions
			if (isNewerVersion(currentVersion, remoteVersion)) {
				Timber.i("Update available: $remoteVersion")
				return@withContext UpdateInfo(
					version = remoteVersion,
					downloadUrl = apkAsset.downloadUrl,
					releaseNotesUrl = release.htmlUrl,
					releaseNotes = release.body,
					apkSize = apkAsset.size,
				)
			} else {
				Timber.d("No update available")
				return@withContext null
			}
		} catch (e: Exception) {
			Timber.e(e, "Error checking for updates")
			return@withContext null
		}
	}

	private fun isNewerVersion(current: String, remote: String): Boolean {
		try {
			// Simple version comparison - split by dots and compare each part
			val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
			val remoteParts = remote.split(".").map { it.toIntOrNull() ?: 0 }

			val maxLength = maxOf(currentParts.size, remoteParts.size)
			for (i in 0 until maxLength) {
				val currentPart = currentParts.getOrNull(i) ?: 0
				val remotePart = remoteParts.getOrNull(i) ?: 0

				if (remotePart > currentPart) return true
				if (remotePart < currentPart) return false
			}

			return false // Versions are equal
		} catch (e: Exception) {
			Timber.e(e, "Error comparing versions: $current vs $remote")
			return false
		}
	}
}
