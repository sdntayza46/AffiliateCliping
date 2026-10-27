package com.dantez.autoclip.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.dantez.autoclip.data.PlannedClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class FFmpegEngine {

    // --- 1. EKSTRAKSI AUDIO MP3 DARI VIDEO LOKAL ---
    suspend fun extractAudio(
        videoPath: String,
        audioOutPath: String,
        onProgress: (Int, String) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        onProgress(5, "Mengekstrak audio MP3 dari video rekaman...")
        // Kompresi optimal Whisper AI: mono (-ac 1), 16000 Hz (-ar 16000), 32 kbps (-b:a 32k).
        // File 4x-5x lebih kecil dibanding stereo 128k (1 jam hanya ~14 MB vs ~60 MB),
        // mencegah error HTTP 413 (Payload/Request Entity Too Large 25 MB) dari Groq Whisper.
        val cmd = "-y -i \"$videoPath\" -vn -acodec libmp3lame -ac 1 -ar 16000 -b:a 32k \"$audioOutPath\""
        executeFFmpegCommand(cmd)
        onProgress(12, "Ekstraksi audio MP3 selesai")
    }

    // --- 2. EKSTRAKSI KEYFRAME GAMBAR UNTUK VISUAL AI ---
    suspend fun extractKeyframe(
        videoPath: String,
        timestampSec: Double,
        outJpgPath: String
    ): Unit = withContext(Dispatchers.IO) {
        val cmd = "-y -ss $timestampSec -i \"$videoPath\" -vframes 1 -q:v 2 \"$outJpgPath\""
        executeFFmpegCommand(cmd)
    }

    // Ekstrak font dari assets ke direktori aplikasi untuk render subtitel libass
    fun prepareFonts(context: Context): File {
        val fontsDir = File(context.filesDir, "fonts")
        if (!fontsDir.exists()) fontsDir.mkdirs()
        val fontNames = listOf("arial.ttf", "arialbd.ttf")
        for (name in fontNames) {
            val dest = File(fontsDir, name)
            if (!dest.exists() || dest.length() == 0L) {
                try {
                    context.assets.open("fonts/$name").use { input ->
                        FileOutputStream(dest).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("FFmpegEngine", "Gagal mengekstrak font $name: ${e.message}")
                }
            }
        }
        return fontsDir
    }

    // --- 3. RENDER VIDEO FINAL KE FORMAT 9:16 (TIKTOK / REELS / SHORTS) DENGAN MEDIACODEC & MVHD MAX ---
    suspend fun renderFinalVideo(
        context: Context,
        videoPath: String,
        clips: List<PlannedClip>,
        outPath: String,
        watermark: String = "",
        zoom: Float = 1.0f,
        yOffset: Float = 0.0f,
        casStrength: Float = 0.0f,
        resolution: String = "1080p",
        enableDynamicZoom: Boolean = true,
        enableSubtitles: Boolean = true,
        enableAntiReused: Boolean = true,
        customSpeedFactor: Float? = null,
        customNoiseParams: String? = null,
        sfxBytes: ByteArray? = null,
        sfxType: String = "whoosh",
        onProgress: (Int, String) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        if (clips.isEmpty()) throw IllegalArgumentException("Tidak ada klip untuk dirender!")

        val (outW, outH) = if (resolution.equals("720p", ignoreCase = true)) {
            720 to 1280
        } else {
            1080 to 1920
        }

        onProgress(80, "Memulai render video vertikal 9:16 ($resolution 30 FPS MediaCodec)...")

        val speedFactor = if (enableAntiReused) (customSpeedFactor ?: 1.05f) else 1.0f
        val ptsFilter = if (enableAntiReused) {
            String.format(java.util.Locale.US, "setpts=(PTS-STARTPTS)/%.4f,fps=30", speedFactor)
        } else {
            "setpts=PTS-STARTPTS"
        }
        val atempoFilter = if (enableAntiReused) {
            String.format(java.util.Locale.US, ",atempo=%.4f", speedFactor)
        } else ""

        // Membangun filter_complex untuk setiap potongan klip
        val filterParts = StringBuilder()
        val concatInputs = StringBuilder()

        val safeZoom = zoom.coerceIn(1.0f, 1.30f)
        val yNorm = yOffset.coerceIn(-0.20f, 0.20f)
        val yExpr = "max(0,min(ih-oh,(ih-oh)/2+($yNorm*ih)))"

        clips.forEachIndexed { i, c ->
            val vTag = "v$i"
            val aTag = "a$i"

            // Dynamic Punch-In Zoom (Micro Jump-Cut 1.08x selang-seling ala TikTok viral)
            val clipZoom = if (enableDynamicZoom && (i % 2 == 1)) {
                (safeZoom * 1.08f).coerceAtMost(1.30f)
            } else {
                safeZoom
            }
            val cropFilter = "crop=w='min(iw,ih*9/16)/$clipZoom':h='min(ih,iw*16/9)/$clipZoom':x='(iw-ow)/2':y='$yExpr',scale=$outW:$outH,setsar=1,format=yuv420p"

            filterParts.append(
                "[0:v]trim=start=${c.start}:end=${c.end},$ptsFilter," +
                "$cropFilter[$vTag];"
            )
            filterParts.append(
                "[0:a]atrim=start=${c.start}:end=${c.end},asetpts=PTS-STARTPTS$atempoFilter[$aTag];"
            )
            concatInputs.append("[$vTag][$aTag]")
        }

        var sfxTrackFile: File? = null
        if (sfxBytes != null && clips.size > 1) {
            val cutPoints = mutableListOf<Double>()
            var elapsed = 0.0
            for (i in 0 until clips.size - 1) {
                elapsed += (clips[i].end - clips[i].start)
                cutPoints.add(elapsed / speedFactor)
            }
            val totalDur = (elapsed + (clips.last().end - clips.last().start)) / speedFactor
            val leadOffset = if (sfxType == "whoosh") 0.09 else 0.01

            try {
                sfxTrackFile = File.createTempFile("sfx_track_", ".wav")
                generateSfxTrackWav(
                    sfxBytes = sfxBytes,
                    cutPointsSec = cutPoints,
                    totalDurationSec = totalDur,
                    leadOffsetSec = leadOffset,
                    gain = 0.55f,
                    outFile = sfxTrackFile
                )
            } catch (e: Exception) {
                android.util.Log.e("FFmpegEngine", "Gagal menyusun SFX track: ${e.message}")
                sfxTrackFile?.delete()
                sfxTrackFile = null
            }
        }

        val extraInputs = StringBuilder()
        var nextInputIdx = 1

        var sfxInputIdx = -1
        if (sfxTrackFile != null && sfxTrackFile.exists() && sfxTrackFile.length() > 0) {
            sfxInputIdx = nextInputIdx++
            extraInputs.append(" -i \"${sfxTrackFile.absolutePath}\"")
        }

        var overlayFile: File? = null
        var wmInputIdx = -1
        if (watermark.isNotBlank()) {
            overlayFile = File.createTempFile("overlay_", ".png")
            generateOverlayPng(watermark, outW, outH, overlayFile)
            wmInputIdx = nextInputIdx++
            extraInputs.append(" -i \"${overlayFile.absolutePath}\"")
        }

        filterParts.append("${concatInputs}concat=n=${clips.size}:v=1:a=1[basev][basea];")

        var currentV = "basev"
        var currentA = "basea"

        // 1. Contrast Adaptive Sharpening (CAS - FidelityFX)
        if (casStrength > 0.0f) {
            val safeCas = casStrength.coerceIn(0.0f, 1.0f)
            filterParts.append(String.format(java.util.Locale.US, "[$currentV]cas=strength=%.2f[cas_v];", safeCas))
            currentV = "cas_v"
        }

        // 2. Anti-Reused Micro Invisible Grain (Mengubah sidik jari video tanpa mengubah warna produk)
        if (enableAntiReused) {
            val grainFilter = customNoiseParams ?: "noise=alls=2:allf=t"
            filterParts.append("[$currentV]$grainFilter[grain_v];")
            currentV = "grain_v"
        }

        // 2. Subtitle Animasi Teks Berjalan
        var subFile: File? = null
        if (enableSubtitles) {
            try {
                val fontsDir = prepareFonts(context)
                subFile = File.createTempFile("subs_", ".ass")
                generateAssSubtitles(
                    clips = clips,
                    speedFactor = speedFactor,
                    outW = outW,
                    outH = outH,
                    outFile = subFile
                )
                if (subFile.exists() && subFile.length() > 0) {
                    val escPath = subFile.absolutePath.replace("\\", "/").replace(":", "\\:").replace("'", "\\'")
                    val escFontsDir = fontsDir.absolutePath.replace("\\", "/").replace(":", "\\:").replace("'", "\\'")
                    filterParts.append("[$currentV]subtitles='${escPath}':fontsdir='${escFontsDir}'[sub_v];")
                    currentV = "sub_v"
                }
            } catch (e: Exception) {
                android.util.Log.e("FFmpegEngine", "Gagal menyusun subtitel ASS: ${e.message}")
                subFile?.delete()
                subFile = null
            }
        }

        // 3. Watermark Overlay
        if (wmInputIdx != -1) {
            filterParts.append("[$currentV][$wmInputIdx:v]overlay=0:0:eof_action=repeat[wm_v];")
            currentV = "wm_v"
        }

        // 4. Sound Effect Transisi Audio Mix
        if (sfxInputIdx != -1) {
            filterParts.append("[$currentA][$sfxInputIdx:a]amix=inputs=2:duration=first:dropout_transition=0:weights=1.0 0.55[sfx_a];")
            currentA = "sfx_a"
        }

        var filterStr = filterParts.toString().trim()
        if (filterStr.endsWith(";")) {
            filterStr = filterStr.substring(0, filterStr.length - 1)
        }

        // Parameter encoding & metadata dari buat_mvhd_max.py
        val vBitrate = if (resolution.equals("720p", ignoreCase = true)) "2500k" else "4500k"
        val audioAndMeta = "-c:a aac -b:a 346k -ar 48000 -ac 2 " +
                "-map_metadata -1 " +
                "-metadata artist=transcode.vague-infinity.com " +
                "-metadata comment=Pulse " +
                "-movflags +faststart " +
                "-brand isom"

        val tempRemux = File(outPath + ".remux_tmp.mp4")

        onProgress(86, "Sedang rendering video dengan akselerasi MediaCodec...")

        try {
            // Coba pertama dengan hardware encoder MediaCodec (h264_mediacodec)
            val primaryCmd = "-y -i \"$videoPath\"$extraInputs -filter_complex \"$filterStr\" " +
                    "-map \"[$currentV]\" -map \"[$currentA]\" " +
                    "-c:v h264_mediacodec -b:v $vBitrate -pix_fmt yuv420p " +
                    "$audioAndMeta \"${tempRemux.absolutePath}\""

            try {
                executeFFmpegCommand(primaryCmd)
            } catch (e: Exception) {
                android.util.Log.w("FFmpegEngine", "MediaCodec gagal, mencoba fallback ke libx264: ${e.message}")
                val fallbackCmd = "-y -i \"$videoPath\"$extraInputs -filter_complex \"$filterStr\" " +
                        "-map \"[$currentV]\" -map \"[$currentA]\" " +
                        "-c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p " +
                        "$audioAndMeta \"${tempRemux.absolutePath}\""
                executeFFmpegCommand(fallbackCmd)
            }

            // Terapkan sistem buat_mvhd_max.py: patch atom mvhd menjadi versi 1 dengan max duration
            onProgress(94, "Menerapkan sistem mvhd max (buat_mvhd_max.py) pada kontainer video...")
            val finalDestFile = File(outPath)
            MvhdPatcher.process(tempRemux, finalDestFile)

        } finally {
            tempRemux.delete()
            overlayFile?.delete()
            sfxTrackFile?.delete()
            subFile?.delete()
        }

        onProgress(100, "Ekspor video berhasil! Video siap dinikmati.")
    }

    private fun extractPcmFromWav(wavBytes: ByteArray): ShortArray {
        var dataIdx = -1
        for (i in 0 until wavBytes.size - 4) {
            if (wavBytes[i] == 'd'.code.toByte() &&
                wavBytes[i + 1] == 'a'.code.toByte() &&
                wavBytes[i + 2] == 't'.code.toByte() &&
                wavBytes[i + 3] == 'a'.code.toByte()
            ) {
                dataIdx = i + 8
                break
            }
        }
        if (dataIdx == -1 || dataIdx >= wavBytes.size) {
            dataIdx = 44.coerceAtMost(wavBytes.size)
        }

        val numSamples = (wavBytes.size - dataIdx) / 2
        val samples = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val b0 = wavBytes[dataIdx + i * 2].toInt() and 0xFF
            val b1 = wavBytes[dataIdx + i * 2 + 1].toInt()
            samples[i] = ((b1 shl 8) or b0).toShort()
        }
        return samples
    }

    private fun generateSfxTrackWav(
        sfxBytes: ByteArray,
        cutPointsSec: List<Double>,
        totalDurationSec: Double,
        leadOffsetSec: Double,
        gain: Float = 0.55f,
        outFile: File
    ) {
        val sfxSamples = extractPcmFromWav(sfxBytes)
        if (sfxSamples.isEmpty() || cutPointsSec.isEmpty()) return

        val sampleRate = 44100
        val totalSamples = ((totalDurationSec + 0.5) * sampleRate).toInt().coerceAtLeast(sampleRate)
        val trackBuffer = ShortArray(totalSamples)

        for (t in cutPointsSec) {
            val startSample = ((t - leadOffsetSec) * sampleRate).toInt()
            for (i in sfxSamples.indices) {
                val targetIdx = startSample + i
                if (targetIdx in 0 until totalSamples) {
                    val mixed = trackBuffer[targetIdx] + (sfxSamples[i] * gain).toInt()
                    trackBuffer[targetIdx] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
        }

        val dataSize = totalSamples * 2
        val totalSize = 36 + dataSize
        FileOutputStream(outFile).use { fos ->
            val header = ByteArray(44)
            // RIFF
            header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
            header[4] = (totalSize and 0xFF).toByte()
            header[5] = ((totalSize shr 8) and 0xFF).toByte()
            header[6] = ((totalSize shr 16) and 0xFF).toByte()
            header[7] = ((totalSize shr 24) and 0xFF).toByte()
            // WAVE
            header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
            // fmt 
            header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
            header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
            header[20] = 1; header[21] = 0
            header[22] = 1; header[23] = 0
            // SampleRate = 44100
            header[24] = (sampleRate and 0xFF).toByte()
            header[25] = ((sampleRate shr 8) and 0xFF).toByte()
            header[26] = ((sampleRate shr 16) and 0xFF).toByte()
            header[27] = ((sampleRate shr 24) and 0xFF).toByte()
            // ByteRate = 88200
            val byteRate = sampleRate * 2
            header[28] = (byteRate and 0xFF).toByte()
            header[29] = ((byteRate shr 8) and 0xFF).toByte()
            header[30] = ((byteRate shr 16) and 0xFF).toByte()
            header[31] = ((byteRate shr 24) and 0xFF).toByte()
            header[32] = 2; header[33] = 0
            header[34] = 16; header[35] = 0
            // data
            header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
            header[40] = (dataSize and 0xFF).toByte()
            header[41] = ((dataSize shr 8) and 0xFF).toByte()
            header[42] = ((dataSize shr 16) and 0xFF).toByte()
            header[43] = (dataSize shr 24).toByte()

            fos.write(header)

            val byteBuffer = ByteArray(4096)
            var bufIdx = 0
            for (sample in trackBuffer) {
                byteBuffer[bufIdx++] = (sample.toInt() and 0xFF).toByte()
                byteBuffer[bufIdx++] = ((sample.toInt() shr 8) and 0xFF).toByte()
                if (bufIdx >= 4096) {
                    fos.write(byteBuffer, 0, bufIdx)
                    bufIdx = 0
                }
            }
            if (bufIdx > 0) {
                fos.write(byteBuffer, 0, bufIdx)
            }
        }
    }

    private fun generateOverlayPng(
        watermark: String,
        width: Int,
        height: Int,
        outFile: File
    ) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Gambar Watermark Transparan di tengah (jika ada)
        if (watermark.isNotBlank()) {
            val paint = Paint().apply {
                color = Color.WHITE
                alpha = (255 * 0.18f).toInt() // Opasitas 18%
                textSize = if (width >= 1080) 42f else 28f
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            val x = width / 2f
            val y = (height / 2f) - ((paint.descent() + paint.ascent()) / 2f)
            canvas.drawText(watermark, x, y, paint)
        }

        FileOutputStream(outFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        bitmap.recycle()
    }

    private fun generateAssSubtitles(
        clips: List<PlannedClip>,
        speedFactor: Float,
        outW: Int,
        outH: Int,
        outFile: File
    ) {
        val fontSize = if (outW >= 1080) 52 else 36
        val marginV = if (outH >= 1920) 380 else 250

        val sb = StringBuilder()
        sb.append(
            """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: $outW
            PlayResY: $outH

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: TikTokStyle, Arial, $fontSize, &H0020FFFF, &H00000000, &H00000000, &H80000000, -1, 0, 0, 0, 100, 100, 0, 0, 1, 4, 2, 2, 40, 40, $marginV, 1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text

            """.trimIndent()
        )
        sb.append("\n")

        var clipVideoStart = 0.0

        for (clip in clips) {
            val clipDurScaled = (clip.end - clip.start) / speedFactor

            if (clip.words.isNotEmpty()) {
                val groups = mutableListOf<MutableList<com.dantez.autoclip.data.WordTimestamp>>()
                var curr = mutableListOf<com.dantez.autoclip.data.WordTimestamp>()
                for (w in clip.words) {
                    if (curr.isEmpty()) {
                        curr.add(w)
                    } else {
                        val prev = curr.last()
                        if (curr.size >= 4 || (w.start - prev.end) >= 0.35) {
                            groups.add(curr)
                            curr = mutableListOf(w)
                        } else {
                            curr.add(w)
                        }
                    }
                }
                if (curr.isNotEmpty()) groups.add(curr)

                for (grp in groups) {
                    val rawS = grp.first().start - clip.start
                    val rawE = grp.last().end - clip.start
                    val startSec = (clipVideoStart + (rawS / speedFactor)).coerceAtLeast(clipVideoStart)
                    val endSec = Math.max(startSec + 0.60, (clipVideoStart + (rawE / speedFactor))).coerceAtMost(clipVideoStart + clipDurScaled)

                    val text = grp.joinToString(" ") { it.word.uppercase().trim() }
                        .replace("\\", "")
                        .replace("{", "")
                        .replace("}", "")

                    if (text.isNotBlank() && endSec > startSec) {
                        sb.append("Dialogue: 0,${formatAssTime(startSec)},${formatAssTime(endSec)},TikTokStyle,,0,0,0,,$text\n")
                    }
                }
            } else if (clip.text.isNotBlank()) {
                val words = clip.text.split(Regex("""\s+""")).filter { it.isNotBlank() }
                val numChunks = Math.max(1, Math.ceil(words.size / 4.0).toInt())
                val durPerChunk = clipDurScaled / numChunks

                for (idx in 0 until numChunks) {
                    val startIdx = idx * 4
                    val endIdx = Math.min(words.size, (idx + 1) * 4)
                    val chunkWords = words.subList(startIdx, endIdx)
                    val startSec = clipVideoStart + (idx * durPerChunk)
                    val endSec = Math.min(clipVideoStart + clipDurScaled, startSec + durPerChunk)
                    val text = chunkWords.joinToString(" ") { it.uppercase().trim() }

                    if (text.isNotBlank() && endSec > startSec) {
                        sb.append("Dialogue: 0,${formatAssTime(startSec)},${formatAssTime(endSec)},TikTokStyle,,0,0,0,,$text\n")
                    }
                }
            }

            clipVideoStart += clipDurScaled
        }

        FileOutputStream(outFile).use { fos ->
            fos.write(sb.toString().toByteArray(Charsets.UTF_8))
        }
    }

    private fun formatAssTime(sec: Double): String {
        val sTotal = sec.coerceAtLeast(0.0)
        val h = (sTotal / 3600).toInt()
        val m = ((sTotal % 3600) / 60).toInt()
        val s = (sTotal % 60).toInt()
        val cs = Math.round((sTotal - sTotal.toInt()) * 100.0).toInt().coerceIn(0, 99)
        return String.format(java.util.Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs)
    }

    private fun executeFFmpegCommand(cmd: String) {
        val session = FFmpegKit.execute(cmd)
        val returnCode = session.returnCode
        if (ReturnCode.isSuccess(returnCode)) {
            return
        } else if (ReturnCode.isCancel(returnCode)) {
            throw RuntimeException("FFmpeg dibatalkan.")
        } else {
            val failMsg = session.failStackTrace ?: session.allLogsAsString ?: "Unknown FFmpeg error"
            throw RuntimeException("FFmpeg gagal (${returnCode.value}): $failMsg")
        }
    }
}
