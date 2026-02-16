package app.sst.pinto.utils

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads and caches the idle/screensaver video for the Pinto app.
 *
 * - Remote videos are downloaded to the app's external files directory:
 *   /Android/data/app.sst.pinto/files/screensaver/[sitename].mp4
 * - The most recently downloaded video path is stored in shared preferences so
 *   the screensaver can play the cached file instead of the default resource.
 *
 * Supports:
 * - Site name input (e.g. "charlie") which maps to a configured cloud library
 * - Full video URL input (https://...) for direct downloads
 */
class VideoDownloadManager(private val context: Context) {

    private val logger = FileLogger.getInstance(context)

    companion object {
        private const val TAG = "VideoDownloadManager"

        // Base URL for Pinto screensaver assets in cloud storage.
        // Examples of resulting URLs:
        // - Name only: "screensaver" → https://selfservicetechnology.blob.core.windows.net/pinto-assets/screensaver.mp4
        // - Custom name with extension: "charlie.mp4" → https://selfservicetechnology.blob.core.windows.net/pinto-assets/charlie.mp4
        // - Custom name without extension: "charlie" → https://selfservicetechnology.blob.core.windows.net/pinto-assets/charlie.mp4
        private const val BASE_URL =
            "https://selfservicetechnology.blob.core.windows.net/pinto-assets"

        // Directory under external files where videos are cached
        private const val VIDEO_DIR = "screensaver"

        // Network timeouts
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val READ_TIMEOUT_SECONDS = 180L // matches spec: 180s timeout

        // SharedPreferences used to store current cached video path
        const val PREFS_NAME = "screensaver_video_prefs"
        const val KEY_CURRENT_VIDEO_PATH = "current_video_path"
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Directory where cached screensaver videos are stored:
     * /Android/data/app.sst.pinto/files/screensaver
     */
    private val videoDir: File by lazy {
        File(context.getExternalFilesDir(null), VIDEO_DIR).apply { mkdirs() }
    }

    /**
     * Download a screensaver video either by:
     * - Site name (e.g. "charlie") -> maps to $BASE_URL/charlie.mp4
     * - Full URL (starts with http/https) -> downloaded directly
     *
     * Returns Result<File> with the cached video file on success.
     */
    suspend fun downloadVideo(siteNameOrUrl: String): Result<File> =
        withContext(Dispatchers.IO) {
            val input = siteNameOrUrl.trim()
            if (input.isEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Site name or URL is empty")
                )
            }

            try {
                // Decide if the user entered a full URL or just a site name
                val (videoUrl, fileName) =
                    if (input.startsWith("http://", ignoreCase = true) ||
                        input.startsWith("https://", ignoreCase = true)
                    ) {
                        val uri = Uri.parse(input)
                        val nameFromUrl = uri.lastPathSegment
                            ?.takeIf { it.isNotBlank() }
                            ?: "screensaver.mp4"
                        input to nameFromUrl
                    } else {
                        // Name-based download (no protocol). Handle special defaults and arbitrary names.
                        val safeName = input.lowercase(Locale.ROOT)

                        // Special-case: "screensaver" maps directly to screensaver.mp4 in root
                        val (path, localFileName) = if (safeName == "screensaver") {
                            "screensaver.mp4" to "screensaver.mp4"
                        } else {
                            // If user included an extension, keep it; otherwise assume .mp4
                            val hasExtension = safeName.contains('.')
                            val filePart = if (hasExtension) safeName else "$safeName.mp4"
                            filePart to filePart
                        }

                        val url = "$BASE_URL/$path"
                        url to localFileName
                    }

                val localFile = File(videoDir, fileName)

                // If file is already cached and non-empty, reuse it
                if (localFile.exists() && localFile.length() > 0) {
                    logger.i(TAG, "Screensaver video already cached: ${localFile.absolutePath}")
                    saveCurrentVideoPath(localFile)
                    return@withContext Result.success(localFile)
                }

                logger.i(TAG, "Downloading screensaver video from: $videoUrl")

                val request = Request.Builder()
                    .url(videoUrl)
                    .build()

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    logger.e(TAG, "Failed to download video. HTTP ${response.code}")
                    return@withContext Result.failure(
                        Exception("Download failed: HTTP ${response.code}")
                    )
                }

                val responseBody = response.body
                if (responseBody == null) {
                    logger.e(TAG, "Response body is null while downloading video")
                    return@withContext Result.failure(Exception("Empty response body"))
                }

                // Ensure parent directory exists
                localFile.parentFile?.mkdirs()

                // Stream response body to file
                FileOutputStream(localFile).use { output ->
                    responseBody.byteStream().use { inputStream ->
                        inputStream.copyTo(output)
                    }
                }

                if (localFile.length() <= 0) {
                    logger.e(TAG, "Downloaded file is empty: ${localFile.absolutePath}")
                    return@withContext Result.failure(Exception("Downloaded file is empty"))
                }

                logger.i(
                    TAG,
                    "Screensaver video downloaded successfully: ${localFile.absolutePath} (${localFile.length()} bytes)"
                )

                saveCurrentVideoPath(localFile)
                Result.success(localFile)
            } catch (e: Exception) {
                logger.e(TAG, "Error downloading screensaver video", e)
                Result.failure(e)
            }
        }

    /**
     * Persist the last successfully downloaded video path so the screensaver
     * can load the cached video.
     */
    private fun saveCurrentVideoPath(file: File) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_CURRENT_VIDEO_PATH, file.absolutePath)
            .apply()
    }

    /**
     * Get the currently cached video path, if any.
     */
    fun getCurrentVideoPath(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CURRENT_VIDEO_PATH, null)
    }
}

