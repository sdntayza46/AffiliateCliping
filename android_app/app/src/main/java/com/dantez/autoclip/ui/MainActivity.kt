package com.dantez.autoclip.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dantez.autoclip.R
import com.dantez.autoclip.ai.AiEngine
import com.dantez.autoclip.data.AutoCaptionResult
import com.dantez.autoclip.data.SettingsManager
import com.dantez.autoclip.databinding.ActivityMainBinding
import com.dantez.autoclip.databinding.DialogSettingsBinding
import com.dantez.autoclip.databinding.ItemKeyRowBinding
import com.dantez.autoclip.databinding.ItemWatermarkRowBinding
import com.dantez.autoclip.databinding.ItemRenderedVideoBinding
import com.dantez.autoclip.service.VideoProcessingService
import com.dantez.autoclip.updater.AppUpdateManager
import com.dantez.autoclip.video.FFmpegEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsManager: SettingsManager
    private lateinit var aiEngine: AiEngine
    private lateinit var ffmpegEngine: FFmpegEngine

    private var selectedVideoFile: File? = null
    private var selectedVideoUri: Uri? = null
    private var selectedVideoRelativePath: String? = null
    private var selectedVideoParentDir: File? = null
    private var outputVideoFile: File? = null


    // Video 9:16 Live Preview properties
    private var mediaPlayer: MediaPlayer? = null
    private var previewSurface: Surface? = null
    private var videoNaturalWidth = 0
    private var videoNaturalHeight = 0
    private var currentZoom = 1.0f
    private var currentYOffsetNorm = 0.0f
    private var currentSharpenVal = 0.0f

    // File picker launcher
    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            handleSelectedVideoUri(uri)
        }
    }

    // Permission launcher
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        val granted = perms.values.all { it }
        if (granted) {
            pickVideoLauncher.launch("video/*")
        } else {
            Toast.makeText(this, "Izin membaca file video dibutuhkan untuk memilih rekaman.", Toast.LENGTH_LONG).show()
        }
    }

    // Notification permission launcher for Android 13+ (API 33+)
    private val requestNotificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        executeStartProcessingService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsManager = SettingsManager(this)
        aiEngine = AiEngine(settingsManager)
        ffmpegEngine = FFmpegEngine()

        // Hanya bersihkan file sementara usang jika TIDAK ADA proses background service yang sedang aktif
        if (!VideoProcessingService.isRunning) {
            lifecycleScope.launch(Dispatchers.IO) {
                cleanStaleTempFiles()
            }
        }

        updatePreviewWatermarkOverlay()
        binding.tvHeaderSubtitle.text = "dantez x mamank recing — Native v${com.dantez.autoclip.BuildConfig.VERSION_NAME}"
        setupDurationSpinner()
        setupResolutionRadio()
        setupListeners()
        playLiquidEntranceAnimation()
        restoreActiveStateIfRunning(savedInstanceState)
        observeProcessingService()

        // Pengecekan update otomatis di latar belakang jika diaktifkan di Pengaturan
        if (settingsManager.isAutoCheckUpdateEnabled()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val repo = settingsManager.getGithubRepo()
                if (repo.isNotBlank()) {
                    val updateResult = AppUpdateManager.checkForUpdate(repo)
                    updateResult.getOrNull()?.let { info ->
                        if (info.hasUpdate) {
                            withContext(Dispatchers.Main) {
                                if (!isFinishing && !isDestroyed) {
                                    AppUpdateManager.showUpdateDialog(this@MainActivity, info)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (VideoProcessingService.isRunning && selectedVideoFile == null) {
            restoreActiveStateIfRunning(null)
        }

        // Cek jika user kembali setelah memberikan izin 'Install unknown apps'
        val pendingApk = AppUpdateManager.pendingInstallApk
        if (pendingApk != null && pendingApk.exists()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
                AppUpdateManager.pendingInstallApk = null
                AppUpdateManager.installApk(this, pendingApk)
            }
        }
    }

    // --- 1. PREVIEW WATERMARK OVERLAY ---
    private fun updatePreviewWatermarkOverlay() {
        val curWm = settingsManager.getWatermark()
        if (selectedVideoUri != null && curWm.isNotBlank()) {
            binding.tvPreviewWatermark.text = curWm
            binding.tvPreviewWatermark.visibility = View.VISIBLE
        } else {
            binding.tvPreviewWatermark.visibility = View.GONE
        }
    }

    // --- 2. DURATION & RESOLUTION CONFIG ---
    private fun setupDurationSpinner() {
        val durations = arrayOf(
            "Maksimal 1 Menit (≤ 60 detik)",
            "Maksimal 1 Menit 30 Detik (≤ 90 detik)",
            "Maksimal 2 Menit (≤ 120 detik)",
            "Kustom (Tentukan sendiri...)"
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, durations)
        binding.spinnerDuration.adapter = adapter

        val saved = settingsManager.getDurationRange()
        val initialPosition = when {
            saved == "90" || saved == "max_90" -> 1
            saved == "120" || saved == "max_120" -> 2
            saved.toDoubleOrNull() != null && saved != "60" -> {
                binding.etCustomDuration.setText(saved)
                3
            }
            else -> 0 // Default 1 menit
        }

        binding.spinnerDuration.setSelection(initialPosition)
        binding.layoutCustomDuration.visibility = if (initialPosition == 3) View.VISIBLE else View.GONE

        binding.spinnerDuration.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 3) {
                    binding.layoutCustomDuration.visibility = View.VISIBLE
                    binding.etCustomDuration.requestFocus()
                } else {
                    binding.layoutCustomDuration.visibility = View.GONE
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupResolutionRadio() {
        if (settingsManager.getResolution() == "1080p") {
            binding.rb1080p.isChecked = true
        } else {
            binding.rb720p.isChecked = true
        }

        binding.rgResolution.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == binding.rb1080p.id) {
                settingsManager.setResolution("1080p")
            } else {
                settingsManager.setResolution("720p")
            }
        }
    }

    // --- 3. EVENT LISTENERS ---
    private fun setupListeners() {
        setupCropSliders()

        binding.btnSelectVideo.setOnClickListener {
            checkAndPickVideo()
        }

        binding.btnClearFile.setOnClickListener {
            val fileToDelete = selectedVideoFile
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    fileToDelete?.delete()
                    cleanStaleVideoCacheFiles()
                } catch (e: Exception) {}
            }
            selectedVideoFile = null
            selectedVideoUri = null
            selectedVideoRelativePath = null
            selectedVideoParentDir = null
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            videoNaturalWidth = 0
            videoNaturalHeight = 0
            binding.layoutSelectedFile.visibility = View.GONE
            binding.btnSelectVideo.visibility = View.VISIBLE
            binding.layoutPreviewPlaceholder.visibility = View.VISIBLE
            binding.tvPreviewBadge.visibility = View.GONE
            binding.btnPreviewPlayPause.visibility = View.GONE
            binding.tvPreviewWatermark.visibility = View.GONE
        }

        binding.btnSelectVideo.applyLiquidTouchFeedback()
        binding.btnClearFile.applyLiquidTouchFeedback()
        binding.btnMenuSettings.applyLiquidTouchFeedback()
        binding.btnStartProcess.applyLiquidTouchFeedback()
        binding.btnResetCrop.applyLiquidTouchFeedback()
        binding.btnPreviewPlayPause.applyLiquidTouchFeedback()
        binding.btnToggleLogs.applyLiquidTouchFeedback()
        binding.btnCancelProcess.applyLiquidTouchFeedback()

        binding.btnMenuSettings.setOnClickListener {
            showSettingsDialog()
        }

        binding.btnStartProcess.setOnClickListener {
            startFullProcessingPipeline()
        }

        binding.btnToggleLogs.setOnClickListener {
            val isVisible = binding.scrollLogs.visibility == View.VISIBLE
            if (isVisible) {
                binding.scrollLogs.visibility = View.GONE
                binding.tvLogsChevron.animate().rotation(0f).setDuration(220).start()
                binding.tvLogsToggleTitle.text = "Lihat Log Console Proses"
            } else {
                binding.scrollLogs.visibility = View.VISIBLE
                binding.tvLogsChevron.animate().rotation(90f).setDuration(220).start()
                binding.tvLogsToggleTitle.text = "Sembunyikan Log Console"
            }
        }

        binding.btnCancelProcess.setOnClickListener {
            VideoProcessingService.stopProcessing(this)
            Toast.makeText(this, "Membatalkan proses...", Toast.LENGTH_SHORT).show()
        }
    }



    private fun setupCropSliders() {
        binding.seekBarZoom.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                currentZoom = 1.0f + (progress / 100f)
                binding.tvZoomValue.text = "${100 + progress}%"
                updatePreviewTransform()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.seekBarYOffset.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val offset = progress - 15
                currentYOffsetNorm = offset / 100f
                binding.tvYOffsetValue.text = "$offset%"
                updatePreviewTransform()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // Contrast Adaptive Sharpening (CAS) Slider
        val savedSharpen = settingsManager.getCasSharpen()
        currentSharpenVal = savedSharpen
        val initSharpenProgress = (savedSharpen * 100).toInt().coerceIn(0, 100)
        binding.seekBarSharpen.progress = initSharpenProgress
        binding.tvSharpenValue.text = if (initSharpenProgress == 0) "Off (0%)" else "$initSharpenProgress%"

        binding.seekBarSharpen.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                currentSharpenVal = progress / 100f
                binding.tvSharpenValue.text = if (progress == 0) "Off (0%)" else "$progress%"
                settingsManager.setCasSharpen(currentSharpenVal)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.btnResetCrop.setOnClickListener {
            binding.seekBarZoom.progress = 0
            binding.seekBarYOffset.progress = 15
            binding.seekBarSharpen.progress = 0
            currentZoom = 1.0f
            currentYOffsetNorm = 0.0f
            currentSharpenVal = 0.0f
            settingsManager.setCasSharpen(0.0f)
            binding.tvZoomValue.text = "100%"
            binding.tvYOffsetValue.text = "0%"
            binding.tvSharpenValue.text = "Off (0%)"
            updatePreviewTransform()
        }

        binding.previewTextureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                previewSurface = Surface(surface)
                mediaPlayer?.setSurface(previewSurface)
                updatePreviewTransform()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                updatePreviewTransform()
            }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                previewSurface?.release()
                previewSurface = null
                mediaPlayer?.setSurface(null)
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        binding.btnPreviewPlayPause.setOnClickListener {
            togglePlayPausePreview()
        }

        binding.previewTextureView.setOnClickListener {
            togglePlayPausePreview()
        }
    }

    private fun togglePlayPausePreview() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            binding.btnPreviewPlayPause.setImageResource(android.R.drawable.ic_media_play)
        } else {
            mp.start()
            binding.btnPreviewPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        }
    }

    private fun initPreviewPlayer(uri: Uri) {
        selectedVideoUri = uri
        binding.layoutPreviewPlaceholder.visibility = View.GONE
        binding.tvPreviewBadge.visibility = View.VISIBLE
        binding.btnPreviewPlayPause.visibility = View.VISIBLE

        updatePreviewWatermarkOverlay()

        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                previewSurface?.let { setSurface(it) }
                isLooping = true
                setVolume(1.0f, 1.0f)
                setOnVideoSizeChangedListener { _, width, height ->
                    if (width > 0 && height > 0) {
                        videoNaturalWidth = width
                        videoNaturalHeight = height
                        updatePreviewTransform()
                    }
                }
                setOnPreparedListener { mp ->
                    mp.seekTo(1)
                    binding.btnPreviewPlayPause.setImageResource(android.R.drawable.ic_media_play)
                    updatePreviewTransform()
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updatePreviewTransform() {
        val view = binding.previewTextureView
        val viewWidth = view.width.toFloat()
        val viewHeight = view.height.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0 || videoNaturalWidth <= 0 || videoNaturalHeight <= 0) {
            return
        }

        val matrix = Matrix()
        val videoAspect = videoNaturalWidth.toFloat() / videoNaturalHeight.toFloat()
        val viewAspect = viewWidth / viewHeight

        val sx: Float
        val sy: Float
        if (viewAspect > videoAspect) {
            // Container 9:16 is wider than tall video (e.g. 20:9 phone recording)
            sx = 1.0f
            sy = (viewWidth / videoAspect) / viewHeight
        } else {
            // Container is taller than video (e.g. landscape)
            sx = (viewHeight * videoAspect) / viewWidth
            sy = 1.0f
        }

        val finalScaleX = sx * currentZoom
        val finalScaleY = sy * currentZoom
        val px = viewWidth / 2f
        val py = viewHeight / 2f

        matrix.setScale(finalScaleX, finalScaleY, px, py)

        val offsetY = -currentYOffsetNorm * viewHeight
        matrix.postTranslate(0f, offsetY)

        view.setTransform(matrix)
        view.invalidate()
    }

    // --- 4. VIDEO PICKER & STORAGE RESOLUTION ---
    private fun checkAndPickVideo() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (allGranted) {
            pickVideoLauncher.launch("video/*")
        } else {
            requestPermissionLauncher.launch(permissions)
        }
    }

    private fun handleSelectedVideoUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var fileName = "video.mp4"
                var fileSize = 0L
                var relPath: String? = null
                var rawPath: String? = null

                // Coba query lengkap termasuk DATA dan RELATIVE_PATH
                val projection = arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE,
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.RELATIVE_PATH
                )

                try {
                    contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)

                        if (cursor.moveToFirst()) {
                            if (nameIdx != -1) fileName = cursor.getString(nameIdx) ?: fileName
                            if (sizeIdx != -1) fileSize = cursor.getLong(sizeIdx)
                            if (dataIdx != -1) rawPath = cursor.getString(dataIdx)
                            if (relIdx != -1) relPath = cursor.getString(relIdx)
                        }
                    }
                } catch (e: Exception) {
                    try {
                        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (cursor.moveToFirst()) {
                                if (nameIdx != -1) fileName = cursor.getString(nameIdx) ?: fileName
                                if (sizeIdx != -1) fileSize = cursor.getLong(sizeIdx)
                            }
                        }
                    } catch (e2: Exception) {}
                }

                // Tentukan folder asal video mentahan
                var parentDir: File? = null
                var candidateRelPath = relPath
                if (!rawPath.isNullOrBlank()) {
                    val rawFile = File(rawPath)
                    parentDir = rawFile.parentFile
                    if (candidateRelPath.isNullOrBlank() && parentDir != null) {
                        val extRoot = Environment.getExternalStorageDirectory().absolutePath
                        if (parentDir.absolutePath.startsWith(extRoot)) {
                            candidateRelPath = parentDir.absolutePath.removePrefix(extRoot).trimStart('/') + "/"
                        }
                    }
                }

                val fixedRel = candidateRelPath
                if (!fixedRel.isNullOrBlank() && parentDir == null) {
                    val extRoot = Environment.getExternalStorageDirectory()
                    parentDir = File(extRoot, fixedRel.trimEnd('/'))
                }

                val normalizedRel = if (!fixedRel.isNullOrBlank()) {
                    if (fixedRel.endsWith("/")) fixedRel else "$fixedRel/"
                } else null

                selectedVideoRelativePath = normalizedRel
                selectedVideoParentDir = parentDir

                // Bersihkan cache video mentahan lama agar tidak menumpuk di internal storage
                cleanStaleVideoCacheFiles()

                // Simpan copy sementara ke cache directory untuk eksekusi FFmpeg langsung
                val cacheFile = File(cacheDir, "input_${System.currentTimeMillis()}_$fileName")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }

                selectedVideoFile = cacheFile
                val sizeMb = Math.round(cacheFile.length() / (1024.0 * 1024.0) * 10.0) / 10.0

                withContext(Dispatchers.Main) {
                    binding.tvSelectedFileName.text = fileName
                    binding.tvSelectedFileSize.text = "Ukuran: $sizeMb MB (Lokal Siap Diproses)"
                    binding.layoutSelectedFile.visibility = View.VISIBLE
                    binding.btnSelectVideo.visibility = View.GONE
                    initPreviewPlayer(uri)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Gagal membuka file: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // --- 5. FULL VIDEO PROCESSING PIPELINE (FOREGROUND SERVICE) ---
    private fun startFullProcessingPipeline() {
        val video = selectedVideoFile
        if (video == null || !video.exists()) {
            Toast.makeText(this, "Silakan pilih video rekaman terlebih dahulu!", Toast.LENGTH_SHORT).show()
            return
        }

        if (settingsManager.getAllKeys()["groq"].isNullOrEmpty()) {
            Toast.makeText(this, "Silakan masukkan Groq API Key di Pengaturan (titik 3)!", Toast.LENGTH_LONG).show()
            showSettingsDialog()
            return
        }

        // Cek izin notifikasi di Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        executeStartProcessingService()
    }

    private fun executeStartProcessingService() {
        val video = selectedVideoFile ?: return

        val durationValue = when (binding.spinnerDuration.selectedItemPosition) {
            1 -> "90"
            2 -> "120"
            3 -> {
                val customInput = binding.etCustomDuration.text.toString().trim()
                val customSec = customInput.toDoubleOrNull()
                if (customSec != null && customSec in 10.0..600.0) {
                    customSec.toInt().toString()
                } else {
                    "60"
                }
            }
            else -> "60"
        }
        val resolutionValue = if (binding.rb1080p.isChecked) "1080p" else "720p"
        val watermark = settingsManager.getWatermark()
        val zoomVal = 1.0f + (binding.seekBarZoom.progress / 100f)
        val yOffsetVal = (binding.seekBarYOffset.progress - 15) / 100f

        binding.layoutProgress.visibility = View.VISIBLE
        binding.layoutResult.visibility = View.GONE
        binding.btnStartProcess.isEnabled = false
        binding.tvLogs.text = ""
        binding.progressBar.setProgressCompat(0, false)
        binding.progressPulse.visibility = View.VISIBLE
        binding.btnCancelProcess.visibility = View.VISIBLE

        binding.mainScrollView.post {
            binding.mainScrollView.smoothScrollTo(0, binding.layoutProgress.top)
        }

        mediaPlayer?.pause()
        binding.btnPreviewPlayPause.setImageResource(android.R.drawable.ic_media_play)

        val sizeMb = Math.round(video.length() / (1024.0 * 1024.0) * 10.0) / 10.0
        VideoProcessingService.startProcessing(
            context = this,
            videoPath = video.absolutePath,
            videoUriString = selectedVideoUri?.toString(),
            videoName = binding.tvSelectedFileName.text.toString().ifBlank { video.name },
            videoSizeMb = sizeMb,
            durationValue = durationValue,
            resolutionValue = resolutionValue,
            watermark = watermark,
            zoomVal = zoomVal,
            yOffsetVal = yOffsetVal,
            sharpenVal = currentSharpenVal,
            relativePath = selectedVideoRelativePath,
            parentDirPath = selectedVideoParentDir?.absolutePath
        )
    }

    private fun observeProcessingService() {
        // Pulihkan log console yang sudah berjalan sebelumnya saat activity dibuka kembali
        if (VideoProcessingService.logHistory.isNotEmpty()) {
            binding.tvLogs.text = VideoProcessingService.logHistory.joinToString("")
        }

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    VideoProcessingService.serviceState.collect { state ->
                        when (state) {
                            is VideoProcessingService.ServiceState.Idle -> {
                                binding.btnStartProcess.isEnabled = true
                                binding.progressPulse.visibility = View.GONE
                                binding.btnCancelProcess.visibility = View.GONE
                            }
                            is VideoProcessingService.ServiceState.Running -> {
                                binding.layoutProgress.visibility = View.VISIBLE
                                binding.layoutResult.visibility = View.GONE
                                binding.btnStartProcess.isEnabled = false
                                binding.progressPulse.visibility = View.VISIBLE
                                binding.btnCancelProcess.visibility = View.VISIBLE
                                binding.progressBar.setProgressCompat(state.percent, true)
                                binding.tvProgressPercent.text = "${state.percent}%"
                                binding.tvProgressStage.text = state.message
                            }
                            is VideoProcessingService.ServiceState.Success -> {
                                binding.btnStartProcess.isEnabled = true
                                binding.progressPulse.visibility = View.GONE
                                binding.btnCancelProcess.visibility = View.GONE

                                val outVideo = state.outputFile
                                outputVideoFile = outVideo
                                val folderName = outVideo.parentFile?.name ?: "Galeri"

                                if (state.allFiles.size > 1) {
                                    val totalMb = Math.round(state.allFiles.sumOf { it.length() } / (1024.0 * 1024.0) * 10.0) / 10.0
                                    binding.tvResultPath.text = "Tersimpan di: $folderName (${state.allFiles.size} Video Unik • Total $totalMb MB)"
                                    Toast.makeText(this@MainActivity, "✅ Sukses! ${state.allFiles.size} Video berhasil diekspor ke $folderName.", Toast.LENGTH_LONG).show()
                                } else {
                                    val sizeMb = Math.round(outVideo.length() / (1024.0 * 1024.0) * 10.0) / 10.0
                                    val durSec = getVideoDurationSec(outVideo)
                                    val durFormatted = formatDuration(durSec)
                                    binding.tvResultPath.text = "Tersimpan di: $folderName/${outVideo.name} ($sizeMb MB • $durFormatted)"
                                    Toast.makeText(this@MainActivity, "✅ Sukses! Video berhasil diekspor ke $folderName.", Toast.LENGTH_LONG).show()
                                }

                                binding.layoutRenderedVideosList.removeAllViews()

                                for (i in state.allFiles.indices) {
                                    val file = state.allFiles[i]
                                    val caption = state.allCaptions.getOrNull(i) ?: AutoCaptionResult()
                                    val sizeMb = Math.round(file.length() / (1024.0 * 1024.0) * 10.0) / 10.0
                                    val durationSec = getVideoDurationSec(file)
                                    val durationFormatted = formatDuration(durationSec)
                                    val partNum = i + 1

                                    val itemBinding = ItemRenderedVideoBinding.inflate(
                                        layoutInflater,
                                        binding.layoutRenderedVideosList,
                                        false
                                    )

                                    itemBinding.tvVideoPartBadge.text = if (state.allFiles.size > 1) "Part $partNum" else "Hasil Video"
                                    itemBinding.tvItemFileName.text = file.name
                                    itemBinding.tvItemMeta.text = "📁 $sizeMb MB  •  ⏱ $durationFormatted"

                                    itemBinding.btnItemPlay.setOnClickListener {
                                        openVideoWithPlayer(file)
                                    }

                                    itemBinding.btnItemShare.setOnClickListener {
                                        shareVideoFile(file)
                                    }

                                    itemBinding.etItemCaption.setText(caption.formattedText)
                                    itemBinding.btnItemCopyCaption.text = if (state.allFiles.size > 1) "📋 SALIN CAPTION PART $partNum" else "📋 SALIN CAPTION & HASHTAG"
                                    itemBinding.btnItemCopyCaption.setOnClickListener {
                                        val textToCopy = itemBinding.etItemCaption.text.toString().trim()
                                        if (textToCopy.isNotBlank()) {
                                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText("TikTok Caption Part $partNum", textToCopy)
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(this@MainActivity, "✅ Caption Part $partNum disalin ke clipboard!", Toast.LENGTH_SHORT).show()
                                            triggerCopyVibration()
                                        } else {
                                            Toast.makeText(this@MainActivity, "Caption masih kosong.", Toast.LENGTH_SHORT).show()
                                        }
                                    }

                                    itemBinding.btnItemPlay.applyLiquidTouchFeedback()
                                    itemBinding.btnItemShare.applyLiquidTouchFeedback()
                                    itemBinding.btnItemCopyCaption.applyLiquidTouchFeedback()
                                    itemBinding.btnToggleCaption.applyLiquidTouchFeedback()

                                    itemBinding.btnToggleCaption.setOnClickListener {
                                        val isVisible = itemBinding.layoutCaptionContent.visibility == View.VISIBLE
                                        if (isVisible) {
                                            itemBinding.layoutCaptionContent.visibility = View.GONE
                                            itemBinding.tvCaptionChevron.animate().rotation(0f).setDuration(220).start()
                                            itemBinding.tvCaptionToggleTitle.text = "📝 Caption & Hashtag TikTok (Ketuk untuk tampilkan)"
                                        } else {
                                            itemBinding.layoutCaptionContent.visibility = View.VISIBLE
                                            itemBinding.tvCaptionChevron.animate().rotation(180f).setDuration(220).start()
                                            itemBinding.tvCaptionToggleTitle.text = "📝 Caption & Hashtag TikTok (Ketuk untuk sembunyikan)"
                                        }
                                    }

                                    itemBinding.root.alpha = 0f
                                    itemBinding.root.translationY = 30f
                                    binding.layoutRenderedVideosList.addView(itemBinding.root)
                                    itemBinding.root.animate()
                                        .alpha(1f)
                                        .translationY(0f)
                                        .setDuration(350)
                                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                                        .start()
                                }

                                binding.layoutResult.visibility = View.VISIBLE
                                binding.mainScrollView.post {
                                    binding.mainScrollView.smoothScrollTo(0, binding.layoutResult.top)
                                }
                            }
                            is VideoProcessingService.ServiceState.Error -> {
                                binding.btnStartProcess.isEnabled = true
                                binding.progressPulse.visibility = View.GONE
                                binding.btnCancelProcess.visibility = View.GONE
                                Toast.makeText(this@MainActivity, "Terjadi kesalahan: ${state.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }

                launch {
                    VideoProcessingService.logFlow.collect { logLine ->
                        binding.tvLogs.append(logLine)
                        binding.scrollLogs.post {
                            binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
                        }
                    }
                }
            }
        }
    }

    private fun triggerExportSuccessVibration() {
        try {
            val pattern = longArrayOf(0, 200, 100, 200)
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

    private fun triggerCopyVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) {}
    }

    // --- 6. DIALOG PENGATURAN (SETTINGS) ---
    private fun showSettingsDialog() {
        val dialogBinding = DialogSettingsBinding.inflate(LayoutInflater.from(this))
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialogBinding.btnCloseSettings.applyLiquidTouchFeedback()

        dialogBinding.tvSettingsVersion.text = "Dantez Cliping v${com.dantez.autoclip.BuildConfig.VERSION_NAME}"

        // A. Toggle Getar saat export berhasil
        dialogBinding.switchVibrateExport.isChecked = settingsManager.isVibrateOnExportEnabled()
        dialogBinding.switchVibrateExport.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.setVibrateOnExportEnabled(isChecked)
        }

        // B. Dynamic Punch-In Zoom & SFX Transisi (TikTok Viral Effects)
        dialogBinding.switchDynamicZoom.isChecked = settingsManager.isDynamicZoomEnabled()
        dialogBinding.switchDynamicZoom.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.setDynamicZoomEnabled(isChecked)
        }

        when (settingsManager.getSfxTransition()) {
            "whoosh" -> dialogBinding.rbSfxWhoosh.isChecked = true
            "pop" -> dialogBinding.rbSfxPop.isChecked = true
            else -> dialogBinding.rbSfxNone.isChecked = true
        }
        dialogBinding.rgSfxTransition.setOnCheckedChangeListener { _, checkedId ->
            val sfx = when (checkedId) {
                com.dantez.autoclip.R.id.rbSfxWhoosh -> "whoosh"
                com.dantez.autoclip.R.id.rbSfxPop -> "pop"
                else -> "none"
            }
            settingsManager.setSfxTransition(sfx)
        }

        // Subtitles Toggle
        dialogBinding.switchSubtitles.isChecked = settingsManager.isSubtitlesEnabled()
        dialogBinding.switchSubtitles.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.setSubtitlesEnabled(isChecked)
        }

        // Anti-Reused Content Toggle
        dialogBinding.switchAntiReused.isChecked = settingsManager.isAntiReusedEnabled()
        dialogBinding.switchAntiReused.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.setAntiReusedEnabled(isChecked)
        }

        // Jumlah Video Output (1 - 5)
        fun updateVideoCountBadge(count: Int) {
            dialogBinding.tvVideoCountBadge.text = "$count Video"
        }
        val currentCount = settingsManager.getOutputVideoCount()
        updateVideoCountBadge(currentCount)
        when (currentCount) {
            1 -> dialogBinding.rbCount1.isChecked = true
            2 -> dialogBinding.rbCount2.isChecked = true
            3 -> dialogBinding.rbCount3.isChecked = true
            4 -> dialogBinding.rbCount4.isChecked = true
            5 -> dialogBinding.rbCount5.isChecked = true
            else -> dialogBinding.rbCount1.isChecked = true
        }
        dialogBinding.rgVideoCount.setOnCheckedChangeListener { _, checkedId ->
            val count = when (checkedId) {
                com.dantez.autoclip.R.id.rbCount1 -> 1
                com.dantez.autoclip.R.id.rbCount2 -> 2
                com.dantez.autoclip.R.id.rbCount3 -> 3
                com.dantez.autoclip.R.id.rbCount4 -> 4
                com.dantez.autoclip.R.id.rbCount5 -> 5
                else -> 1
            }
            settingsManager.setOutputVideoCount(count)
            updateVideoCountBadge(count)
        }

        // C. Multi-Watermark Preset Management
        fun renderWatermarks() {
            dialogBinding.layoutWatermarkList.removeAllViews()
            val list = settingsManager.getWatermarkList()
            val activeWm = settingsManager.getSelectedWatermark()

            dialogBinding.tvWmCountBadge.text = if (list.isEmpty()) "Belum ada preset" else "${list.size} Watermark Tersimpan"

            // 1. Row: "Tanpa Watermark"
            val noneBinding = ItemWatermarkRowBinding.inflate(layoutInflater, dialogBinding.layoutWatermarkList, false)
            noneBinding.tvWmItemText.text = "Tanpa Watermark (Nonaktif)"
            noneBinding.tvWmItemSub.text = "Video akan diekspor bersih tanpa watermark"
            val isNoneActive = activeWm.isBlank()
            noneBinding.rbWatermarkSelect.isChecked = isNoneActive
            if (isNoneActive) {
                noneBinding.tvWmItemText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_emerald))
                noneBinding.tvWmItemBadge.visibility = View.VISIBLE
                noneBinding.tvWmItemBadge.text = "Aktif"
            } else {
                noneBinding.tvWmItemText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                noneBinding.tvWmItemBadge.visibility = View.GONE
            }
            noneBinding.btnDeleteWmItem.visibility = View.GONE
            noneBinding.rootWatermarkRow.setOnClickListener {
                settingsManager.setSelectedWatermark("")
                renderWatermarks()
                updatePreviewWatermarkOverlay()
            }
            dialogBinding.layoutWatermarkList.addView(noneBinding.root)

            // 2. Row: Masing-masing Watermark Tersimpan
            for (wm in list) {
                val rowBinding = ItemWatermarkRowBinding.inflate(layoutInflater, dialogBinding.layoutWatermarkList, false)
                rowBinding.tvWmItemText.text = wm
                rowBinding.tvWmItemSub.text = "Ketuk untuk memilih watermark ini"
                val isActive = (wm == activeWm)
                rowBinding.rbWatermarkSelect.isChecked = isActive
                if (isActive) {
                    rowBinding.tvWmItemText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_emerald))
                    rowBinding.tvWmItemBadge.visibility = View.VISIBLE
                    rowBinding.tvWmItemBadge.text = "Aktif"
                } else {
                    rowBinding.tvWmItemText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    rowBinding.tvWmItemBadge.visibility = View.GONE
                }

                rowBinding.rootWatermarkRow.setOnClickListener {
                    settingsManager.setSelectedWatermark(wm)
                    renderWatermarks()
                    updatePreviewWatermarkOverlay()
                }

                rowBinding.btnDeleteWmItem.setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Hapus Watermark")
                        .setMessage("Hapus preset watermark '$wm'?")
                        .setPositiveButton("Hapus") { _, _ ->
                            settingsManager.deleteWatermark(wm)
                            renderWatermarks()
                            updatePreviewWatermarkOverlay()
                            Toast.makeText(this@MainActivity, "Watermark '$wm' dihapus.", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Batal", null)
                        .show()
                }

                dialogBinding.layoutWatermarkList.addView(rowBinding.root)
            }
        }

        dialogBinding.btnAddWatermark.setOnClickListener {
            val input = dialogBinding.etNewWatermark.text.toString().trim()
            if (input.isNotBlank()) {
                val added = settingsManager.addWatermark(input)
                if (added) {
                    dialogBinding.etNewWatermark.setText("")
                    renderWatermarks()
                    updatePreviewWatermarkOverlay()
                    Toast.makeText(this@MainActivity, "Watermark '$input' berhasil ditambahkan dan diaktifkan!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Watermark sudah ada dalam daftar.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this@MainActivity, "Ketik teks watermark terlebih dahulu.", Toast.LENGTH_SHORT).show()
            }
        }

        // C. API Key Groq Management
        fun updateKeyStatus() {
            val allKeys = settingsManager.getAllKeys()
            val gCount = allKeys["groq"]?.count { it.status == "active" } ?: 0
            dialogBinding.tvActiveKeyCount.text = "Key Aktif: $gCount Groq (Whisper + Pemilihan Klip)"
        }

        fun renderSavedKeys() {
            val provider = "groq"
            dialogBinding.tvSavedKeysHeader.text = "API Key Groq Tersimpan:"

            val allKeys = settingsManager.getAllKeys()
            val keysForProvider = allKeys[provider] ?: emptyList()

            dialogBinding.layoutSavedKeysList.removeAllViews()

            if (keysForProvider.isEmpty()) {
                dialogBinding.layoutSavedKeysList.addView(dialogBinding.tvNoKeys)
                dialogBinding.tvNoKeys.visibility = View.VISIBLE
                dialogBinding.btnClearProviderKeys.visibility = View.GONE
            } else {
                dialogBinding.tvNoKeys.visibility = View.GONE
                dialogBinding.btnClearProviderKeys.visibility = View.VISIBLE

                for (item in keysForProvider) {
                    val rowBinding = ItemKeyRowBinding.inflate(LayoutInflater.from(this@MainActivity), dialogBinding.layoutSavedKeysList, false)

                    val keyStr = item.key
                    val masked = if (keyStr.length > 14) {
                        "${keyStr.take(7)}...${keyStr.takeLast(4)}"
                    } else {
                        keyStr
                    }
                    rowBinding.tvKeyMasked.text = masked

                    if (item.status == "active") {
                        rowBinding.tvKeyStatus.text = "● Aktif"
                        rowBinding.tvKeyStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_emerald))
                    } else {
                        rowBinding.tvKeyStatus.text = "● Limit / Exhausted"
                        rowBinding.tvKeyStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_amber))
                    }

                    rowBinding.btnDeleteKey.setOnClickListener {
                        settingsManager.removeKey(provider, item.id)
                        renderSavedKeys()
                        updateKeyStatus()
                        Toast.makeText(this@MainActivity, "Key $masked berhasil dihapus.", Toast.LENGTH_SHORT).show()
                    }

                    dialogBinding.layoutSavedKeysList.addView(rowBinding.root)
                }
            }
        }

        dialogBinding.btnClearProviderKeys.setOnClickListener {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Hapus Semua Key Groq")
                .setMessage("Hapus seluruh API key Groq yang tersimpan di aplikasi?")
                .setPositiveButton("Hapus Semua") { _, _ ->
                    settingsManager.clearKeys("groq")
                    renderSavedKeys()
                    updateKeyStatus()
                    Toast.makeText(this@MainActivity, "Semua key Groq berhasil dihapus.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Batal", null)
                .show()
        }

        dialogBinding.btnSaveKeys.setOnClickListener {
            val text = dialogBinding.etKeyInput.text.toString().trim()
            if (text.isBlank()) {
                Toast.makeText(this@MainActivity, "Ketik atau tempel API key terlebih dahulu.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val added = settingsManager.addKey("groq", text)
            dialogBinding.etKeyInput.setText("")
            renderSavedKeys()
            updateKeyStatus()
            Toast.makeText(this@MainActivity, "Berhasil menambahkan $added key Groq!", Toast.LENGTH_SHORT).show()
        }

        dialogBinding.btnResetKeys.setOnClickListener {
            settingsManager.resetAllKeys()
            renderSavedKeys()
            updateKeyStatus()
            Toast.makeText(this@MainActivity, "Semua status key di-reset ke active.", Toast.LENGTH_SHORT).show()
        }

        // D. Storage & Cache Management
        fun updateCacheBadge() {
            val sizeMb = getCacheSizeMb()
            dialogBinding.tvCacheSizeBadge.text = "$sizeMb MB"
            if (sizeMb > 20.0) {
                dialogBinding.tvCacheSizeBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_amber))
            } else {
                dialogBinding.tvCacheSizeBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_emerald))
            }
        }

        dialogBinding.btnClearCache.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val freedBytes = cleanAllCache(keepCurrentSelected = true)
                val freedMb = Math.round(freedBytes / (1024.0 * 1024.0) * 10.0) / 10.0
                withContext(Dispatchers.Main) {
                    updateCacheBadge()
                    Toast.makeText(this@MainActivity, "Cache dibersihkan (Membebaskan $freedMb MB).", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // E. In-App Update Management (GitHub Releases)
        dialogBinding.etGithubRepo.setText(settingsManager.getGithubRepo())
        dialogBinding.switchAutoCheckUpdate.isChecked = settingsManager.isAutoCheckUpdateEnabled()
        dialogBinding.switchAutoCheckUpdate.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.setAutoCheckUpdateEnabled(isChecked)
        }

        dialogBinding.btnCheckUpdateNow.setOnClickListener {
            val repoInput = dialogBinding.etGithubRepo.text.toString().trim()
            if (repoInput.isBlank()) {
                Toast.makeText(this@MainActivity, "Ketik format repository GitHub (contoh: owner/repo)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            settingsManager.setGithubRepo(repoInput)
            dialogBinding.tvAppUpdateStatusBadge.text = "Memeriksa..."
            dialogBinding.btnCheckUpdateNow.isEnabled = false

            lifecycleScope.launch(Dispatchers.IO) {
                val checkResult = AppUpdateManager.checkForUpdate(repoInput)
                withContext(Dispatchers.Main) {
                    dialogBinding.btnCheckUpdateNow.isEnabled = true
                    if (checkResult.isSuccess) {
                        val info = checkResult.getOrNull()!!
                        if (info.hasUpdate) {
                            dialogBinding.tvAppUpdateStatusBadge.text = "Tersedia v${info.latestVersionName}"
                            dialogBinding.tvAppUpdateStatusBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_emerald))
                            AppUpdateManager.showUpdateDialog(this@MainActivity, info)
                        } else {
                            dialogBinding.tvAppUpdateStatusBadge.text = "Versi Terbaru (v${info.currentVersionName})"
                            dialogBinding.tvAppUpdateStatusBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_emerald))
                            Toast.makeText(this@MainActivity, "Aplikasi sudah menggunakan versi terbaru (v${info.currentVersionName})", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        dialogBinding.tvAppUpdateStatusBadge.text = "Gagal Memeriksa"
                        dialogBinding.tvAppUpdateStatusBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_amber))
                        val err = checkResult.exceptionOrNull()?.message ?: "Gagal terhubung ke GitHub"
                        Toast.makeText(this@MainActivity, err, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        dialogBinding.btnCloseSettings.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnDoneSettings.setOnClickListener {
            dialog.dismiss()
        }

        // Initial renders
        renderWatermarks()
        renderSavedKeys()
        updateKeyStatus()
        updateCacheBadge()

        dialog.setOnDismissListener {
            settingsManager.setGithubRepo(dialogBinding.etGithubRepo.text.toString().trim())
            updatePreviewWatermarkOverlay()
        }

        dialog.show()
    }

    // --- 7. OPEN & SHARE VIDEO ---
    private fun openVideoWithPlayer(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak ada pemutar video: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareVideoFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Bagikan Video Hasil Klip"))
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membagikan file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getVideoDurationSec(file: File): Int {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val timeMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            retriever.release()
            (timeMs / 1000).toInt()
        } catch (e: Exception) {
            0
        }
    }

    private fun formatDuration(totalSec: Int): String {
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(java.util.Locale.US, "%02d:%02d (%d detik)", m, s, totalSec)
    }

    override fun onPause() {
        super.onPause()
        mediaPlayer?.pause()
        binding.btnPreviewPlayPause.setImageResource(android.R.drawable.ic_media_play)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectedVideoFile?.absolutePath?.let { outState.putString("saved_video_path", it) }
        selectedVideoUri?.toString()?.let { outState.putString("saved_video_uri", it) }
        outState.putString("saved_file_name", binding.tvSelectedFileName.text.toString())
        outState.putString("saved_file_size", binding.tvSelectedFileSize.text.toString())
        selectedVideoRelativePath?.let { outState.putString("saved_rel_path", it) }
        selectedVideoParentDir?.absolutePath?.let { outState.putString("saved_parent_dir", it) }
    }

    private fun restoreActiveStateIfRunning(savedInstanceState: Bundle?) {
        val active = VideoProcessingService.activeTask
        if (VideoProcessingService.isRunning && active != null) {
            val f = File(active.videoPath)
            if (f.exists()) {
                selectedVideoFile = f
                selectedVideoUri = active.videoUriString?.let { Uri.parse(it) }
                selectedVideoRelativePath = active.relativePath
                selectedVideoParentDir = active.parentDirPath?.let { File(it) }

                binding.tvSelectedFileName.text = active.videoName
                binding.tvSelectedFileSize.text = "Ukuran: ${active.videoSizeMb} MB (Sedang Diproses)"
                binding.layoutSelectedFile.visibility = View.VISIBLE
                binding.btnSelectVideo.visibility = View.GONE

                binding.layoutProgress.visibility = View.VISIBLE
                binding.layoutResult.visibility = View.GONE
                binding.btnStartProcess.isEnabled = false
                binding.progressPulse.visibility = View.VISIBLE
                binding.btnCancelProcess.visibility = View.VISIBLE

                if (selectedVideoUri != null) {
                    initPreviewPlayer(selectedVideoUri!!)
                }
                return
            }
        }

        savedInstanceState?.let { bundle ->
            val path = bundle.getString("saved_video_path")
            val uriStr = bundle.getString("saved_video_uri")
            val name = bundle.getString("saved_file_name")
            val size = bundle.getString("saved_file_size")
            if (!path.isNullOrBlank()) {
                val f = File(path)
                if (f.exists()) {
                    selectedVideoFile = f
                    selectedVideoUri = uriStr?.let { Uri.parse(it) }
                    selectedVideoRelativePath = bundle.getString("saved_rel_path")
                    selectedVideoParentDir = bundle.getString("saved_parent_dir")?.let { File(it) }

                    binding.tvSelectedFileName.text = name ?: f.name
                    binding.tvSelectedFileSize.text = size ?: "Lokal Siap Diproses"
                    binding.layoutSelectedFile.visibility = View.VISIBLE
                    binding.btnSelectVideo.visibility = View.GONE
                    if (selectedVideoUri != null) {
                        initPreviewPlayer(selectedVideoUri!!)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
        previewSurface?.release()
        previewSurface = null
        // JANGAN bersihkan cache di onDestroy agar Background Service tetap berjalan aman saat di-minimize!
    }

    // --- 8. CACHE & STORAGE MANAGEMENT ---
    private fun getCacheSizeMb(): Double {
        val totalBytes = try {
            cacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } catch (e: Exception) {
            0L
        }
        return Math.round(totalBytes / (1024.0 * 1024.0) * 10.0) / 10.0
    }

    private fun cleanStaleVideoCacheFiles(exceptFile: File? = null) {
        try {
            val activePath = VideoProcessingService.activeTask?.videoPath
            cacheDir.listFiles()?.forEach { file ->
                if (activePath != null && file.absolutePath == activePath) return@forEach
                if (file.name.startsWith("input_") && (exceptFile == null || file.absolutePath != exceptFile.absolutePath)) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cleanAllCache(keepCurrentSelected: Boolean = false): Long {
        if (VideoProcessingService.isRunning) {
            return 0L // DILARANG keras menghapus cache saat Background Service sedang aktif
        }
        var deletedBytes = 0L
        try {
            val activePath = VideoProcessingService.activeTask?.videoPath
            cacheDir.listFiles()?.forEach { file ->
                if (keepCurrentSelected && selectedVideoFile != null && file.absolutePath == selectedVideoFile?.absolutePath) {
                    return@forEach
                }
                if (activePath != null && file.absolutePath == activePath) {
                    return@forEach
                }
                if (file.isDirectory) {
                    deletedBytes += file.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
                    file.deleteRecursively()
                } else {
                    deletedBytes += file.length()
                    file.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return deletedBytes
    }

    private fun cleanStaleTempFiles() {
        if (VideoProcessingService.isRunning) return
        try {
            val now = System.currentTimeMillis()
            val activePath = VideoProcessingService.activeTask?.videoPath
            cacheDir.listFiles()?.forEach { file ->
                if (activePath != null && file.absolutePath == activePath) return@forEach
                if (selectedVideoFile != null && file.absolutePath == selectedVideoFile?.absolutePath) return@forEach

                // Hapus folder job render lama yang sudah lebih dari 2 jam jika service tidak berjalan
                if (file.isDirectory && file.name.startsWith("job_")) {
                    if (now - file.lastModified() > 2 * 3600 * 1000) {
                        file.deleteRecursively()
                    }
                } else if (file.isFile && file.name.startsWith("input_")) {
                    // Hapus input copy lama yang sudah lebih dari 2 jam dan tidak sedang dipilih
                    if (now - file.lastModified() > 2 * 3600 * 1000) {
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- LIQUID GLASS ANIMATIONS & INTERACTIONS ---
    private fun View.applyLiquidTouchFeedback() {
        setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(0.96f)
                        .scaleY(0.96f)
                        .setDuration(120)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(220)
                        .setInterpolator(android.view.animation.OvershootInterpolator(2.0f))
                        .start()
                }
            }
            false
        }
    }

    private fun playLiquidEntranceAnimation() {
        val cards = listOf(
            binding.cardVideoSelector,
            binding.cardConfig,
            binding.cardCropZoom,
            binding.btnStartProcess
        )
        cards.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 40f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((index * 80).toLong())
                .setDuration(450)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }
}
