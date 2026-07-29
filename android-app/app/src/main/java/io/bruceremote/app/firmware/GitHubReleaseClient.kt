package io.bruceremote.app.firmware

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Client for fetching BruceRemote firmware releases from GitHub.
 *
 * Expected release structure:
 *   https://github.com/floatme/BruceRemote/releases
 *
 * Each release contains:
 *   - Bruce-Remote-{board}.bin   (merged firmware image)
 *   - manifest.json              (device profile, hashes)
 *   - manifest.sig               (signature)
 *   - RELEASE_NOTES.md
 */
class GitHubReleaseClient(
    private val context: Context,
    private val repoOwner: String = "floatme",
    private val repoName: String = "BruceRemote",
) {
    data class Release(
        val tagName: String,
        val name: String,
        val body: String,
        val publishedAt: Date,
        val assets: List<ReleaseAsset>,
        val htmlUrl: String,
    ) {
        fun findAssetForBoard(boardId: String): ReleaseAsset? {
            val expectedName = "Bruce-Remote-${boardId}.bin"
            return assets.find { it.name.equals(expectedName, ignoreCase = true) }
        }

        fun findManifestAsset(): ReleaseAsset? =
            assets.find { it.name.equals("manifest.json", ignoreCase = true) }

        fun findSignatureAsset(): ReleaseAsset? =
            assets.find { it.name.equals("manifest.sig", ignoreCase = true) }
    }

    data class ReleaseAsset(
        val name: String,
        val downloadUrl: String,
        val size: Long,
        val contentType: String,
    )

    data class DownloadResult(
        val file: File,
        val asset: ReleaseAsset,
    )

    private val cacheDir: File
        get() = File(context.cacheDir, "github_releases").also { it.mkdirs() }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Last release tag we notified the user about. */
    var lastNotifiedTag: String?
        get() = prefs.getString(KEY_LAST_NOTIFIED_TAG, null)
        set(value) = prefs.edit().putString(KEY_LAST_NOTIFIED_TAG, value).apply()

    /** Timestamp of last check (ms). */
    var lastCheckTimeMs: Long
        get() = prefs.getLong(KEY_LAST_CHECK_TIME, 0)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK_TIME, value).apply()

    /**
     * Fetch the latest release from GitHub API.
     * Returns null on network failure or if no releases exist.
     */
    suspend fun fetchLatestRelease(): Release? = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) return@withContext null

        try {
            val url = URL("https://api.github.com/repos/$repoOwner/$repoName/releases/latest")
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                connection.disconnect()
                return@withContext null
            }

            val response = connection.inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream)).use { it.readText() }
            }
            connection.disconnect()

            parseRelease(JSONObject(response))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Download a release asset to the app's cache directory.
     */
    suspend fun downloadAsset(
        asset: ReleaseAsset,
        progressCallback: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): DownloadResult = withContext(Dispatchers.IO) {
        val outputFile = File(cacheDir, asset.name)

        val url = URL(asset.downloadUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/octet-stream")
        }

        connection.inputStream.use { input ->
            outputFile.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var downloaded = 0L
                var read: Int

                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    progressCallback(downloaded, asset.size)
                }
            }
        }
        connection.disconnect()

        DownloadResult(outputFile, asset)
    }

    /**
     * Verify a downloaded file matches expected SHA-256.
     */
    fun verifySha256(file: File, expectedHash: String): Boolean {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(8192)
                var read: Int
                while (stream.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            hash.equals(expectedHash, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Check if a newer release is available than what we've notified about.
     */
    suspend fun checkForUpdate(): Release? {
        val release = fetchLatestRelease() ?: return null
        val lastTag = lastNotifiedTag
        return if (lastTag == null || isNewerTag(release.tagName, lastTag)) {
            release
        } else {
            null
        }
    }

    /**
     * Clean old cached files, keeping only the most recent versions.
     */
    fun pruneCache(keepCount: Int = 3) {
        val files = cacheDir.listFiles() ?: return
        files.groupBy { it.nameWithoutExtension }
            .values
            .forEach { group ->
                group.sortedByDescending { it.lastModified() }
                    .drop(keepCount)
                    .forEach { it.delete() }
            }
    }

    private fun parseRelease(json: JSONObject): Release {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")

        val assetsArray = json.getJSONArray("assets")
        val assets = mutableListOf<ReleaseAsset>()
        for (i in 0 until assetsArray.length()) {
            val assetJson = assetsArray.getJSONObject(i)
            assets.add(
                ReleaseAsset(
                    name = assetJson.getString("name"),
                    downloadUrl = assetJson.getString("browser_download_url"),
                    size = assetJson.getLong("size"),
                    contentType = assetJson.optString("content_type", "application/octet-stream"),
                ),
            )
        }

        return Release(
            tagName = json.getString("tag_name"),
            name = json.optString("name", ""),
            body = json.optString("body", ""),
            publishedAt = dateFormat.parse(json.getString("published_at")) ?: Date(),
            assets = assets,
            htmlUrl = json.getString("html_url"),
        )
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Simple semver comparison: v1.2.3 > v1.2.0
     */
    private fun isNewerTag(newTag: String, oldTag: String): Boolean {
        val newParts = newTag.trimStart('v', 'V').split(".")
        val oldParts = oldTag.trimStart('v', 'V').split(".")
        val maxLen = maxOf(newParts.size, oldParts.size)
        for (i in 0 until maxLen) {
            val newVal = newParts.getOrNull(i)?.toIntOrNull() ?: 0
            val oldVal = oldParts.getOrNull(i)?.toIntOrNull() ?: 0
            if (newVal != oldVal) return newVal > oldVal
        }
        return false
    }

    companion object {
        private const val PREFS_NAME = "github_release_prefs"
        private const val KEY_LAST_NOTIFIED_TAG = "last_notified_tag"
        private const val KEY_LAST_CHECK_TIME = "last_check_time"

        /** Minimum interval between update checks (24 hours). */
        const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
