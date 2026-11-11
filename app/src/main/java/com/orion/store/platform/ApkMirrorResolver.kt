package com.orion.store.platform

import com.orion.store.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

object ApkMirrorResolver {

    data class ApkMirrorVariant(
        val id: String,
        val abi: String,
        val dpi: String,
        val downloadUrl: String
    )

    fun buildSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return "https://www.apkmirror.com/?post_type=app_release&searchtype=apk&s=$encoded"
    }

    fun selectBestVariant(variants: List<ApkMirrorVariant>): ApkMirrorVariant? {
        return variants.firstOrNull { it.abi.contains("arm64-v8a", ignoreCase = true) && it.dpi.contains("nodpi", ignoreCase = true) }
            ?: variants.firstOrNull { it.abi.contains("arm64-v8a", ignoreCase = true) }
            ?: variants.firstOrNull { it.abi.contains("universal", ignoreCase = true) }
            ?: variants.firstOrNull()
    }

    suspend fun resolveAndDownload(
        packageName: String,
        appName: String,
        requestedVersion: String,
        targetDir: File,
        onProgress: (stage: String, percent: Int) -> Unit
    ): DownloadOrchestrator.ResolvedApk? = withContext(Dispatchers.IO) {
        try {
            onProgress("Searching APKMirror for $appName...", 12)
            val searchUrl = buildSearchUrl(packageName)
            val searchReq = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36")
                .build()

            val resp = Net.client.newCall(searchReq).execute()
            if (!resp.isSuccessful) return@withContext null
            val html = resp.body.string()
            if (html.isEmpty()) return@withContext null

            // Find release links in APKMirror results
            val releaseRegex = Regex("""href="(/apk/[^/]+/[^/]+/[^"]+-release/)"""")
            val releaseMatch = releaseRegex.find(html) ?: return@withContext null
            val releasePath = releaseMatch.groupValues[1]
            val releaseUrl = "https://www.apkmirror.com$releasePath"

            onProgress("Fetching release variants from APKMirror...", 15)
            val releaseReq = Request.Builder().url(releaseUrl).header("User-Agent", "Mozilla/5.0").build()
            val releaseResp = Net.client.newCall(releaseReq).execute()
            if (!releaseResp.isSuccessful) return@withContext null
            val releaseHtml = releaseResp.body.string()

            // Find download page link
            val downloadPageRegex = Regex("""href="(/apk/[^/]+/[^/]+/[^"]+-android-apk-download/)"""")
            val downloadPageMatch = downloadPageRegex.find(releaseHtml) ?: return@withContext null
            val downloadPageUrl = "https://www.apkmirror.com" + downloadPageMatch.groupValues[1]

            val downloadBtnReq = Request.Builder().url(downloadPageUrl).header("User-Agent", "Mozilla/5.0").build()
            val downloadBtnResp = Net.client.newCall(downloadBtnReq).execute()
            if (!downloadBtnResp.isSuccessful) return@withContext null
            val downloadBtnHtml = downloadBtnResp.body.string()

            val directDownloadRegex = Regex("""href="(/apk/[^/]+/[^/]+/[^"]+-android-apk-download/download/[^"]*)"""")
            val directMatch = directDownloadRegex.find(downloadBtnHtml) ?: return@withContext null
            val finalPageUrl = "https://www.apkmirror.com" + directMatch.groupValues[1]

            onProgress("Streaming APK from APKMirror...", 18)
            val streamReq = Request.Builder().url(finalPageUrl).header("User-Agent", "Mozilla/5.0").build()
            val streamResp = DownloadOrchestrator.downloadClient.newCall(streamReq).execute()
            if (!streamResp.isSuccessful) return@withContext null

            val body = streamResp.body
            val destFile = File(targetDir, "apkmirror_download.apk")
            FileOutputStream(destFile).use { out -> body.byteStream().copyTo(out) }

            if (destFile.exists() && destFile.length() > 50_000) {
                return@withContext DownloadOrchestrator.normalizeApkBundle(destFile, targetDir, "APKMirror", requestedVersion)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }
}
