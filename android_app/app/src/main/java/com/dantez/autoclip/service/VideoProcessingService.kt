package com.dantez.autoclip.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.dantez.autoclip.R
import com.dantez.autoclip.ai.AiEngine
import com.dantez.autoclip.data.AutoCaptionResult
import com.dantez.autoclip.data.SettingsManager
import com.dantez.autoclip.ui.MainActivity
import com.dantez.autoclip.video.FFmpegEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

class VideoProcessingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var processingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationTime = 0L

    private lateinit var settingsManager: SettingsManager
    private lateinit var aiEngine: AiEngine
    private lateinit var ffmpegEngine: FFmpegEngine

    override fun onCreate() {
        super.onCreate()
        settingsManager = SettingsManager(this)
        aiEngine = AiEngine(settingsManager)
        ffmpegEngine = FFmpegEngine()
        createNotificationChannels()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH)
                if (videoPath.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val durationValue = intent.getStringExtra(EXTRA_DURATION_VAL) ?: "60"
                val resolutionValue = intent.getStringExtra(EXTRA_RESOLUTION_VAL) ?: "1080p"
                val watermark = intent.getStringExtra(EXTRA_WATERMARK) ?: ""
                val zoomVal = intent.getFloatExtra(EXTRA_ZOOM, 1.0f)
                val yOffsetVal = intent.getFloatExtra(EXTRA_Y_OFFSET, 0.0f)
                val sharpenVal = intent.getFloatExtra(EXTRA_SHARPEN, 0.0f)
                val relativePath = intent.getStringExtra(EXTRA_RELATIVE_PATH)
                val parentDirPath = intent.getStringExtra(EXTRA_PARENT_DIR)
                val videoUri = intent.getStringExtra(EXTRA_VIDEO_URI)
                val videoName = intent.getStringExtra(EXTRA_VIDEO_NAME) ?: File(videoPath).name
                val videoSizeMb = intent.getDoubleExtra(EXTRA_VIDEO_SIZE_MB, 0.0)

                startForegroundProcessing(
                    videoPath = videoPath,
                    videoUriString = videoUri,
                    videoName = videoName,
                    videoSizeMb = videoSizeMb,
                    durationValue = durationValue,
                    resolutionValue = resolutionValue,
                    watermark = watermark,
                    zoomVal = zoomVal,
                    yOffsetVal = yOffsetVal,
                    sharpenVal = sharpenVal,
                    relativePath = relativePath,
                    parentDirPath = parentDirPath
                )
            }
            ACTION_STOP -> {
                stopProcessingInternal("Proses dibatalkan oleh pengguna.")
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundProcessing(
        videoPath: String,
        videoUriString: String?,
        videoName: String,
        videoSizeMb: Double,
        durationValue: String,
        resolutionValue: String,
        watermark: String,
        zoomVal: Float,
        yOffsetVal: Float,
        sharpenVal: Float,
        relativePath: String?,
        parentDirPath: String?
    ) {
        isRunning = true
        activeTask = ActiveTaskInfo(
            videoPath = videoPath,
            videoUriString = videoUriString,
            videoName = videoName,
            videoSizeMb = videoSizeMb,
            durationVal = durationValue,
            resolutionVal = resolutionValue,
            watermark = watermark,
            zoomVal = zoomVal,
            yOffsetVal = yOffsetVal,
            sharpenVal = sharpenVal,
            relativePath = relativePath,
            parentDirPath = parentDirPath
        )
        logHistory.clear()

        val initialNotification = buildOngoingNotification(0, "Memulai pemrosesan video di latar belakang...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        acquireWakeLock()

        processingJob?.cancel()
        processingJob = serviceScope.launch {
            val video = File(videoPath)
            val parentDir = if (!parentDirPath.isNullOrBlank()) File(parentDirPath) else null
            val workDir = File(cacheDir, "job_${System.currentTimeMillis()}").apply { mkdirs() }
            val audioFile = File(workDir, "audio.mp3")

            try {
                updateProgress(2, "⚡ Memulai pemrosesan video lokal di background...")

                // Step 1: Ekstraksi Audio
                ffmpegEngine.extractAudio(video.absolutePath, audioFile.absolutePath) { pct, msg ->
                    updateProgress(pct, msg)
                }

                // Step 2: Transkripsi dengan Groq Whisper
                val segments = aiEngine.transcribeAudio(audioFile) { pct, msg ->
                    updateProgress(pct, msg)
                }

                // Step 3: Seleksi Klip dengan Groq LLaMA
                val videoDurationSec = try {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(video.absolutePath)
                    val time = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    retriever.release()
                    (time?.toLongOrNull() ?: 60000L) / 1000.0
                } catch (e: Exception) {
                    segments.lastOrNull()?.end ?: 60.0
                }

                val requestedCount = settingsManager.getOutputVideoCount().coerceIn(1, 5)
                val allPlannedClips = aiEngine.planMultipleVideos(segments, videoDurationSec, durationValue, requestedCount) { pct, msg ->
                    updateProgress(pct, msg)
                }

                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val enableDynamicZoom = settingsManager.isDynamicZoomEnabled()
                val sfxTransition = settingsManager.getSfxTransition()
                val enableSubtitles = settingsManager.isSubtitlesEnabled()
                val enableAntiReused = settingsManager.isAntiReusedEnabled()

                val sfxBytes = if (sfxTransition != "none") {
                    val rawResId = if (sfxTransition == "pop") com.dantez.autoclip.R.raw.pop else com.dantez.autoclip.R.raw.whoosh
                    try {
                        resources.openRawResource(rawResId).use { it.readBytes() }
                    } catch (e: Exception) {
                        null
                    }
                } else null

                val savedVideos = mutableListOf<File>()
                val allCaptions = mutableListOf<AutoCaptionResult>()

                for (idx in allPlannedClips.indices) {
                    val partNum = idx + 1
                    val plannedClips = allPlannedClips[idx]
                    val partLabel = if (allPlannedClips.size > 1) " (Part $partNum/${allPlannedClips.size})" else ""

                    // Step 4: Auto Caption & Hashtag TikTok dengan Groq AI
                    updateProgress(65 + (idx * 5 / allPlannedClips.size), "Menyusun auto caption & hashtag$partLabel...")
                    val captionResult = aiEngine.generateTikTokCaption(plannedClips, segments) { _, _ -> }
                    allCaptions.add(captionResult)

                    // Step 5: Render Video Final 9:16 (Vertikal) MediaCodec & mvhd max
                    val outFileName = if (allPlannedClips.size > 1) {
                        "AutoClip_Part${partNum}_${timeStamp}_${resolutionValue}.mp4"
                    } else {
                        "AutoClip_${timeStamp}_${resolutionValue}.mp4"
                    }
                    val tempRender = File(workDir, "temp_render_part$partNum.mp4")

                    // Variasi anti-reused content unik per video:
                    val speedFactor = when (idx % 5) {
                        0 -> 1.025f
                        1 -> 1.055f
                        2 -> 0.980f
                        3 -> 1.040f
                        else -> 1.015f
                    }
                    val noiseFilter = when (idx % 5) {
                        0 -> "noise=alls=2:allf=t"
                        1 -> "noise=alls=3:allf=t"
                        2 -> "noise=alls=2:allf=p"
                        3 -> "noise=alls=4:allf=t"
                        else -> "noise=alls=3:allf=p"
                    }
                    val zoomVariation = when (idx % 5) {
                        0 -> zoomVal
                        1 -> (zoomVal * 1.03f).coerceAtMost(1.30f)
                        2 -> (zoomVal * 1.015f).coerceAtMost(1.30f)
                        3 -> (zoomVal * 1.04f).coerceAtMost(1.30f)
                        else -> zoomVal
                    }

                    val renderBasePct = 70 + (idx * 24 / allPlannedClips.size)
                    val renderSpan = 24 / allPlannedClips.size
                    updateProgress(renderBasePct, "Rendering video vertikal 9:16 MediaCodec$partLabel...")

                    ffmpegEngine.renderFinalVideo(
                        context = this@VideoProcessingService,
                        videoPath = video.absolutePath,
                        clips = plannedClips,
                        outPath = tempRender.absolutePath,
                        watermark = watermark,
                        zoom = zoomVariation,
                        yOffset = yOffsetVal,
                        casStrength = activeTask?.sharpenVal ?: 0.0f,
                        resolution = resolutionValue,
                        enableDynamicZoom = enableDynamicZoom,
                        enableSubtitles = enableSubtitles,
                        enableAntiReused = enableAntiReused,
                        customSpeedFactor = speedFactor,
                        customNoiseParams = noiseFilter,
                        sfxBytes = sfxBytes,
                        sfxType = sfxTransition
                    ) { pct, msg ->
                        val mappedPct = renderBasePct + (pct * renderSpan / 100)
                        updateProgress(mappedPct.coerceIn(renderBasePct, 95), "$msg$partLabel")
                    }

                    updateProgress(96, "Menyimpan video$partLabel ke galeri...")
                    val outVideo = saveVideoToDestination(
                        tempFile = tempRender,
                        fileName = outFileName,
                        relativePath = relativePath ?: "DCIM/ScreenRecorder/",
                        parentDir = parentDir
                    )
                    tempRender.delete()
                    savedVideos.add(outVideo)
                }

                updateProgress(100, "✅ ${savedVideos.size} Video selesai diproses & di-patch mvhd max!")
                val primaryFile = savedVideos.first()
                val primaryCaption = allCaptions.first()

                _serviceState.value = ServiceState.Success(
                    outputFile = primaryFile,
                    captionResult = primaryCaption,
                    allFiles = savedVideos,
                    allCaptions = allCaptions
                )

                showCompletionNotification(primaryFile, savedVideos.size)

            } catch (e: Exception) {
                val err = e.message ?: "Terjadi kesalahan tidak diketahui"
                val errorLog = "❌ Error: $err\n"
                logHistory.add(errorLog)
                _logFlow.tryEmit(errorLog)
                _serviceState.value = ServiceState.Error(err)
                showErrorNotification(err)
            } finally {
                try {
                    workDir.deleteRecursively()
                } catch (e: Exception) {}
                releaseWakeLock()
                activeTask = null
                isRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopProcessingInternal(reason: String) {
        processingJob?.cancel()
        try {
            FFmpegKit.cancel()
        } catch (e: Exception) {}
        val cancelLog = "⚠️ $reason\n"
        logHistory.add(cancelLog)
        _logFlow.tryEmit(cancelLog)
        _serviceState.value = ServiceState.Idle
        releaseWakeLock()
        activeTask = null
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateProgress(percent: Int, message: String) {
        val logLine = "[$percent%] $message\n"
        logHistory.add(logLine)
        _logFlow.tryEmit(logLine)
        _serviceState.value = ServiceState.Running(percent, message)

        val now = System.currentTimeMillis()
        if (percent == 0 || percent == 100 || now - lastNotificationTime >= 600L) {
            lastNotificationTime = now
            val notification = buildOngoingNotification(percent, message)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildOngoingNotification(percent: Int, stageMsg: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, VideoProcessingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_PROGRESS_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Dantez Cliping: Memproses Video ($percent%)")
            .setContentText(stageMsg)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Batalkan", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showCompletionNotification(outputFile: File, totalCount: Int = 1) {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val folderName = outputFile.parentFile?.name ?: "Galeri"
        val title = if (totalCount > 1) "✅ $totalCount Video Berhasil Dikliping!" else "✅ Video Berhasil Dikliping!"
        val content = if (totalCount > 1) {
            "Tersimpan di $folderName (Part 1 - Part $totalCount)"
        } else {
            val sizeMb = Math.round(outputFile.length() / (1024.0 * 1024.0) * 10.0) / 10.0
            "Tersimpan di $folderName/${outputFile.name} ($sizeMb MB)"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_COMPLETE_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(COMPLETE_NOTIFICATION_ID, notification)

        if (settingsManager.isVibrateOnExportEnabled()) {
            triggerCompletionVibration()
        }
    }

    private fun triggerCompletionVibration() {
        try {
            val pattern = longArrayOf(0, 250, 150, 250)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {
            // Silently ignore if vibration hardware is absent
        }
    }

    private fun showErrorNotification(errorMsg: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_COMPLETE_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("❌ Kliping Video Gagal")
            .setContentText(errorMsg)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(COMPLETE_NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return

            val progressChannel = NotificationChannel(
                CHANNEL_PROGRESS_ID,
                "Status Pemrosesan Video",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menampilkan progress pemrosesan video dan auto caption di latar belakang"
                setShowBadge(false)
            }
            manager.createNotificationChannel(progressChannel)

            val completeChannel = NotificationChannel(
                CHANNEL_COMPLETE_ID,
                "Hasil Video Kliping",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi saat video kliping selesai diproses"
                enableVibration(true)
            }
            manager.createNotificationChannel(completeChannel)
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "DantezCliping:VideoProcessingWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.acquire(60 * 60 * 1000L) // 1 jam safety timeout
        } catch (e: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {}
        wakeLock = null
    }

    private fun saveVideoToDestination(
        tempFile: File,
        fileName: String,
        relativePath: String?,
        parentDir: File?
    ): File {
        var finalFile: File? = null

        // 1. Coba simpan langsung via File API jika parent folder writable
        if (parentDir != null && parentDir.exists() && parentDir.canWrite()) {
            val destFile = File(parentDir, fileName)
            try {
                tempFile.copyTo(destFile, overwrite = true)
                if (destFile.exists() && destFile.length() > 0) {
                    finalFile = destFile
                }
            } catch (e: Exception) {
                finalFile = null
            }
        }

        // 2. Jika belum tersimpan (misal Scoped Storage di Android 11+), simpan via MediaStore dengan RELATIVE_PATH
        if (finalFile == null) {
            val targetRel = relativePath ?: "DCIM/ScreenRecorder/"
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, targetRel)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            try {
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { outStream ->
                        tempFile.inputStream().use { inStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentValues.clear()
                        contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                        contentResolver.update(uri, contentValues, null, null)
                    }

                    val path = getPathFromUri(uri)
                    if (!path.isNullOrBlank()) {
                        finalFile = File(path)
                    } else if (parentDir != null) {
                        finalFile = File(parentDir, fileName)
                    } else {
                        val baseDir = Environment.getExternalStorageDirectory()
                        finalFile = File(File(baseDir, targetRel.trimEnd('/')), fileName)
                    }
                }
            } catch (e: Exception) {
                finalFile = null
            }
        }

        // 3. Fallback jika kedua cara di atas gagal: simpan ke DIRECTORY_MOVIES
        if (finalFile == null || !finalFile.exists()) {
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            if (!moviesDir.exists()) moviesDir.mkdirs()
            val fallbackFile = File(moviesDir, fileName)
            tempFile.copyTo(fallbackFile, overwrite = true)
            finalFile = fallbackFile
        }

        // 4. SEGERA picu MediaScanner agar video langsung terindeks di Galeri
        triggerMediaScan(finalFile)

        return finalFile
    }

    private fun getPathFromUri(uri: Uri): String? {
        return try {
            val proj = arrayOf(MediaStore.MediaColumns.DATA)
            contentResolver.query(uri, proj, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (idx != -1 && cursor.moveToFirst()) {
                    cursor.getString(idx)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun triggerMediaScan(file: File) {
        try {
            MediaScannerConnection.scanFile(
                this@VideoProcessingService,
                arrayOf(file.absolutePath),
                arrayOf("video/mp4")
            ) { _, _ -> }

            val scanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                data = Uri.fromFile(file)
            }
            sendBroadcast(scanIntent)
        } catch (e: Exception) {
            // Safe ignore
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        processingJob?.cancel()
        serviceScope.cancel()
        isRunning = false
    }

    sealed class ServiceState {
        object Idle : ServiceState()
        data class Running(val percent: Int, val message: String) : ServiceState()
        data class Success(
            val outputFile: File,
            val captionResult: AutoCaptionResult,
            val allFiles: List<File> = listOf(outputFile),
            val allCaptions: List<AutoCaptionResult> = listOf(captionResult)
        ) : ServiceState()
        data class Error(val message: String) : ServiceState()
    }

    data class ActiveTaskInfo(
        val videoPath: String,
        val videoUriString: String?,
        val videoName: String,
        val videoSizeMb: Double,
        val durationVal: String,
        val resolutionVal: String,
        val watermark: String,
        val zoomVal: Float,
        val yOffsetVal: Float,
        val sharpenVal: Float = 0.0f,
        val relativePath: String?,
        val parentDirPath: String?
    )

    companion object {
        const val CHANNEL_PROGRESS_ID = "dantez_processing_channel"
        const val CHANNEL_COMPLETE_ID = "dantez_complete_channel"
        const val NOTIFICATION_ID = 1001
        const val COMPLETE_NOTIFICATION_ID = 1002

        const val ACTION_START = "com.dantez.autoclip.service.ACTION_START"
        const val ACTION_STOP = "com.dantez.autoclip.service.ACTION_STOP"

        const val EXTRA_VIDEO_PATH = "extra_video_path"
        const val EXTRA_VIDEO_URI = "extra_video_uri"
        const val EXTRA_VIDEO_NAME = "extra_video_name"
        const val EXTRA_VIDEO_SIZE_MB = "extra_video_size_mb"
        const val EXTRA_DURATION_VAL = "extra_duration_val"
        const val EXTRA_RESOLUTION_VAL = "extra_resolution_val"
        const val EXTRA_WATERMARK = "extra_watermark"
        const val EXTRA_ZOOM = "extra_zoom"
        const val EXTRA_Y_OFFSET = "extra_y_offset"
        const val EXTRA_SHARPEN = "extra_sharpen"
        const val EXTRA_RELATIVE_PATH = "extra_relative_path"
        const val EXTRA_PARENT_DIR = "extra_parent_dir"

        private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Idle)
        val serviceState = _serviceState.asStateFlow()

        val logHistory = Collections.synchronizedList(mutableListOf<String>())
        private val _logFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
        val logFlow = _logFlow.asSharedFlow()

        var isRunning: Boolean = false
            private set

        var activeTask: ActiveTaskInfo? = null
            private set

        fun startProcessing(
            context: Context,
            videoPath: String,
            videoUriString: String?,
            videoName: String,
            videoSizeMb: Double,
            durationValue: String,
            resolutionValue: String,
            watermark: String,
            zoomVal: Float,
            yOffsetVal: Float,
            sharpenVal: Float = 0.0f,
            relativePath: String?,
            parentDirPath: String?
        ) {
            val intent = Intent(context, VideoProcessingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_VIDEO_PATH, videoPath)
                putExtra(EXTRA_VIDEO_URI, videoUriString)
                putExtra(EXTRA_VIDEO_NAME, videoName)
                putExtra(EXTRA_VIDEO_SIZE_MB, videoSizeMb)
                putExtra(EXTRA_DURATION_VAL, durationValue)
                putExtra(EXTRA_RESOLUTION_VAL, resolutionValue)
                putExtra(EXTRA_WATERMARK, watermark)
                putExtra(EXTRA_ZOOM, zoomVal)
                putExtra(EXTRA_Y_OFFSET, yOffsetVal)
                putExtra(EXTRA_SHARPEN, sharpenVal)
                putExtra(EXTRA_RELATIVE_PATH, relativePath)
                putExtra(EXTRA_PARENT_DIR, parentDirPath)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopProcessing(context: Context) {
            val intent = Intent(context, VideoProcessingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
