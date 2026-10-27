package com.dantez.autoclip.updater

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.dantez.autoclip.BuildConfig
import com.dantez.autoclip.R
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val hasUpdate: Boolean,
    val latestVersionName: String,
    val currentVersionName: String,
    val releaseTitle: String,
    val changelog: String,
    val downloadUrl: String?,
    val apkSize: Long = 0L,
    val publishedAt: String = ""
)

object AppUpdateManager {

    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    var pendingInstallApk: File? = null

    /**
     * Memeriksa apakah ada pembaruan versi baru di GitHub Releases
     */
    suspend fun checkForUpdate(
        repo: String,
        currentVersion: String = BuildConfig.VERSION_NAME
    ): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        try {
            val cleanRepo = repo.trim().removePrefix("https://github.com/").removeSuffix("/")
            if (!cleanRepo.contains("/")) {
                return@withContext Result.failure(IllegalArgumentException("Format repository harus 'owner/repo' (contoh: username/dantez-cliping)"))
            }

            val url = "https://api.github.com/repos/$cleanRepo/releases/latest"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "DantezCliping-App")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 404) {
                        return@withContext Result.failure(IllegalStateException("Repository '$cleanRepo' atau Release belum ditemukan di GitHub. Pastikan Anda sudah membuat Release di GitHub."))
                    }
                    return@withContext Result.failure(IllegalStateException("Gagal menghubungi GitHub (${response.code})"))
                }

                val bodyStr = response.body?.string() ?: ""
                val json = JsonParser.parseString(bodyStr).asJsonObject

                val rawTag = json.get("tag_name")?.asString ?: ""
                val latestVer = rawTag.trim().removePrefix("v").removePrefix("V")
                val releaseTitle = json.get("name")?.asString ?: "Versi $latestVer"
                val changelog = json.get("body")?.asString ?: "Pembaruan sistem dan peningkatan stabilitas."
                val publishedAt = json.get("published_at")?.asString ?: ""

                var downloadUrl: String? = null
                var apkSize = 0L

                val assetsArr = json.getAsJsonArray("assets")
                if (assetsArr != null) {
                    for (i in 0 until assetsArr.size()) {
                        val assetObj = assetsArr[i].asJsonObject
                        val name = assetObj.get("name")?.asString ?: ""
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = assetObj.get("browser_download_url")?.asString
                            apkSize = assetObj.get("size")?.asLong ?: 0L
                            break
                        }
                    }
                }

                val hasUpdate = isVersionNewer(latestVer, currentVersion) && !downloadUrl.isNullOrBlank()

                return@withContext Result.success(
                    UpdateInfo(
                        hasUpdate = hasUpdate,
                        latestVersionName = latestVer,
                        currentVersionName = currentVersion,
                        releaseTitle = releaseTitle,
                        changelog = changelog,
                        downloadUrl = downloadUrl,
                        apkSize = apkSize,
                        publishedAt = publishedAt
                    )
                )
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    /**
     * Membandingkan dua string versi SemVer (misal 1.3.3 vs 1.3.2)
     */
    fun isVersionNewer(latest: String, current: String): Boolean {
        try {
            val lParts = latest.split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
            val cParts = current.split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }

            val maxLen = Math.max(lParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val lVal = if (i < lParts.size) lParts[i] else 0
                val cVal = if (i < cParts.size) cParts[i] else 0
                if (lVal > cVal) return true
                if (lVal < cVal) return false
            }
        } catch (e: Exception) {
            return latest.trim() != current.trim()
        }
        return false
    }

    /**
     * Mengunduh file APK dengan callback progress
     */
    suspend fun downloadApk(
        downloadUrl: String,
        outFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long, pct: Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (outFile.exists()) outFile.delete()

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "DantezCliping-App")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("Download gagal (${response.code})"))
                }

                val body = response.body ?: return@withContext Result.failure(IllegalStateException("Response body kosong"))
                val totalBytes = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        var downloaded = 0L
                        var lastReportTime = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastReportTime >= 100 || downloaded == totalBytes) {
                                lastReportTime = now
                                val pct = if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                                onProgress(downloaded, totalBytes, pct)
                            }
                        }
                        output.flush()
                    }
                }
                return@withContext Result.success(outFile)
            }
        } catch (e: Exception) {
            outFile.delete()
            return@withContext Result.failure(e)
        }
    }

    /**
     * Membuka installer bawaan Android untuk menginstal file APK
     */
    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Toast.makeText(context, "File installer APK tidak ditemukan!", Toast.LENGTH_SHORT).show()
            return
        }

        // Android 8.0+ (Oreo): Cek izin instalasi aplikasi tidak dikenal
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                pendingInstallApk = apkFile
                Toast.makeText(
                    context,
                    "Harap aktifkan izin 'Izinkan dari sumber ini' untuk mengupdate aplikasi.",
                    Toast.LENGTH_LONG
                ).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }
        }

        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal membuka installer APK: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Menampilkan dialog Liquid Glass Pembaruan Tersedia
     */
    fun showUpdateDialog(
        activity: Activity,
        updateInfo: UpdateInfo,
        onDismiss: (() -> Unit)? = null
    ) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_update_available, null)
        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val tvCurrentVersion = dialogView.findViewById<TextView>(R.id.tvCurrentVersion)
        val tvLatestVersion = dialogView.findViewById<TextView>(R.id.tvLatestVersion)
        val tvApkSizeBadge = dialogView.findViewById<TextView>(R.id.tvApkSizeBadge)
        val tvChangelog = dialogView.findViewById<TextView>(R.id.tvChangelog)
        val tvReleaseDate = dialogView.findViewById<TextView>(R.id.tvReleaseDate)
        val layoutDownloadProgress = dialogView.findViewById<View>(R.id.layoutDownloadProgress)
        val progressDownloadApk = dialogView.findViewById<ProgressBar>(R.id.progressDownloadApk)
        val tvDownloadProgressDetail = dialogView.findViewById<TextView>(R.id.tvDownloadProgressDetail)
        val tvDownloadPercent = dialogView.findViewById<TextView>(R.id.tvDownloadPercent)
        val btnDownloadUpdate = dialogView.findViewById<Button>(R.id.btnDownloadUpdate)
        val btnLaterUpdate = dialogView.findViewById<Button>(R.id.btnLaterUpdate)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseUpdateDialog)

        tvCurrentVersion.text = "v${updateInfo.currentVersionName}"
        tvLatestVersion.text = "v${updateInfo.latestVersionName}"

        val sizeMb = if (updateInfo.apkSize > 0) {
            String.format("%.1f MB", updateInfo.apkSize / (1024.0 * 1024.0))
        } else "~88 MB"
        tvApkSizeBadge.text = sizeMb

        tvChangelog.text = updateInfo.changelog.trim().ifBlank {
            "Pembaruan versi ${updateInfo.latestVersionName} dengan peningkatan fitur dan stabilitas."
        }

        if (updateInfo.publishedAt.isNotBlank()) {
            val dateStr = updateInfo.publishedAt.take(10)
            tvReleaseDate.text = "Rilis: $dateStr (GitHub)"
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
            onDismiss?.invoke()
        }

        btnLaterUpdate.setOnClickListener {
            dialog.dismiss()
            onDismiss?.invoke()
        }

        var isDownloading = false
        var downloadedApkFile: File? = null

        btnDownloadUpdate.setOnClickListener {
            if (downloadedApkFile != null && downloadedApkFile!!.exists()) {
                installApk(activity, downloadedApkFile!!)
                return@setOnClickListener
            }

            val downloadUrl = updateInfo.downloadUrl ?: return@setOnClickListener
            if (isDownloading) return@setOnClickListener

            isDownloading = true
            btnDownloadUpdate.isEnabled = false
            btnDownloadUpdate.text = "Sedang Mengunduh..."
            btnLaterUpdate.visibility = View.GONE
            layoutDownloadProgress.visibility = View.VISIBLE

            val destFile = File(activity.getExternalFilesDir(null), "DantezCliping_v${updateInfo.latestVersionName}.apk")

            CoroutineScope(Dispatchers.Main).launch {
                val result = downloadApk(downloadUrl, destFile) { downloaded, total, pct ->
                    activity.runOnUiThread {
                        progressDownloadApk.progress = pct
                        tvDownloadPercent.text = "$pct%"
                        val downMb = String.format("%.1f", downloaded / (1024.0 * 1024.0))
                        val totMb = if (total > 0) String.format("%.1f", total / (1024.0 * 1024.0)) else "?"
                        tvDownloadProgressDetail.text = "Mengunduh: $downMb MB / $totMb MB"
                    }
                }

                isDownloading = false
                btnDownloadUpdate.isEnabled = true

                if (result.isSuccess) {
                    downloadedApkFile = result.getOrNull()
                    btnDownloadUpdate.text = "Pasang Pembaruan (Install)"
                    tvDownloadProgressDetail.text = "Selesai diunduh! Membuka installer..."

                    if (downloadedApkFile != null) {
                        installApk(activity, downloadedApkFile!!)
                    }
                } else {
                    btnDownloadUpdate.text = "Coba Download Lagi"
                    btnLaterUpdate.visibility = View.VISIBLE
                    val err = result.exceptionOrNull()?.message ?: "Gagal mengunduh"
                    Toast.makeText(activity, "Download gagal: $err", Toast.LENGTH_LONG).show()
                }
            }
        }

        dialog.show()
    }
}
