@file:Suppress("UNNECESSARY_SAFE_CALL", "USELESS_ELVIS")

package com.orion.store.platform

import com.orion.store.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

object ApkMirrorResolver {

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

    private val releaseRegex = Regex("""href="(/apk/[^/]+/[^/]+/[^"]+-release/)"""")
    private val downloadPageRegex = Regex("""href="(/apk/[^/]+/[^/]+/[^"]+-android-apk-download/)"""")
    private val directDownloadRegex = Regex("""href="(/apk/[^/]+/[^/]+/[^"]+-android-apk-download/download/[^"]*)"""")

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
                .header("User-Agent", USER_AGENT)
                .build()

            val releaseUrl = Net.client.newCall(searchReq).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val html = resp.body?.string().orEmpty()
                if (html.isEmpty()) return@withContext null

                val releaseMatch = releaseRegex.find(html) ?: return@withContext null
                "https://www.apkmirror.com" + releaseMatch.groupValues[1]
            }

            onProgress("Fetching release variants from APKMirror...", 15)
            val releaseReq = Request.Builder()
                .url(releaseUrl)
                .header("User-Agent", USER_AGENT)
                .build()
            val downloadPageUrl = Net.client.newCall(releaseReq).execute().use { releaseResp ->
                if (!releaseResp.isSuccessful) return@withContext null
                val releaseHtml = releaseResp.body?.string().orEmpty()

                val downloadPageMatch = downloadPageRegex.find(releaseHtml) ?: return@withContext null
                "https://www.apkmirror.com" + downloadPageMatch.groupValues[1]
            }

            val downloadBtnReq = Request.Builder()
                .url(downloadPageUrl)
                .header("User-Agent", USER_AGENT)
                .build()
            val finalPageUrl = Net.client.newCall(downloadBtnReq).execute().use { downloadBtnResp ->
                if (!downloadBtnResp.isSuccessful) return@withContext null
                val downloadBtnHtml = downloadBtnResp.body?.string().orEmpty()

                val directMatch = directDownloadRegex.find(downloadBtnHtml) ?: return@withContext null
                "https://www.apkmirror.com" + directMatch.groupValues[1]
            }

            onProgress("Streaming APK from APKMirror...", 18)
            val streamReq = Request.Builder()
                .url(finalPageUrl)
                .header("User-Agent", USER_AGENT)
                .build()
            val destFile = File(targetDir, "apkmirror_download.apk")
            val streamResp = DownloadOrchestrator.downloadClient.newCall(streamReq).execute()
            streamResp.use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                FileOutputStream(destFile).use { out ->
                    body.byteStream().use { input ->
                        input.copyTo(out)
                    }
                }
            }

            if (destFile.exists() && destFile.length() > 50_000) {
                return@withContext DownloadOrchestrator.normalizeApkBundle(destFile, targetDir, "APKMirror", requestedVersion)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }
}
