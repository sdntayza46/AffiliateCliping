package com.dantez.autoclip.ai

import android.media.MediaMetadataRetriever
import android.util.Base64
import com.arthenica.ffmpegkit.FFmpegKit
import com.dantez.autoclip.data.AutoCaptionResult
import com.dantez.autoclip.data.PlannedClip
import com.dantez.autoclip.data.SettingsManager
import com.dantez.autoclip.data.TranscriptSegment
import com.dantez.autoclip.data.WordTimestamp
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class AiEngine(private val settingsManager: SettingsManager) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    private val trailingConnectors = setOf(
        "dan", "terus", "karena", "soalnya", "jadinya", "jadi", "nah", "tapi",
        "untuk", "bisa", "yang", "juga", "atau", "ataupun", "kalau", "kalo", "pas",
        "kan", "sih", "nih", "tuh", "ya", "buat", "seperti", "kayak", "masih", "lagi",
        "sambil", "supaya", "biar", "sehingga", "maupun", "yaitu", "yakni", "ini", "itu", "gitu",
        "warna", "ukuran", "bahan", "model", "tipe", "contohnya", "misalnya", "dengan", "ke", "di",
        "pada", "oleh", "tentang", "maka", "lalu", "kemudian", "bahkan", "ada", "mau", "akan", "udah"
    )

    // Regex larangan TikTok Affiliate & Compliance: Harga, Promo, Live Streaming, Overclaim, Urgensi Palsu, Estimasi Pengiriman
    private val forbiddenPatterns = listOf(
        // 1. Nominal Angka, Satuan Uang, Singkatan Mata Uang (e.g. 120k, 120rb, 120 ribu, 120.000, Rp 120)
        Regex("""\b(?:\d+[\.,]?\d*|\d+)\s*(?:k|rb|ribu|rebu|jt|juta|rupiah|perak)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:rp\.?|idr)\s*(?:\d+[\.,]?\d*|\d+)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b\d{1,3}(?:\.\d{3})+\b""", RegexOption.IGNORE_CASE),
        Regex("""\b\d{2,3}\s*000\b""", RegexOption.IGNORE_CASE),

        // 2. Penyebutan Angka Harga dalam Bahasa Indonesia (e.g. "seratus dua puluh ribu", "seratus dua puluh", "dua ratus lima puluh", "seratus ribu")
        Regex("""\b(?:seratus|dua\s*ratus|tiga\s*ratus|empat\s*ratus|lima\s*ratus|enam\s*ratus|tujuh\s*ratus|delapan\s*ratus|sembilan\s*ratus)(?:\s+(?:satu|dua|tiga|empat|lima|enam|tujuh|delapan|sembilan|sepuluh|sebelas|belas|belasan|dua\s*belas|tiga\s*belas|empat\s*belas|lima\s*belas|dua\s*puluh|tiga\s*puluh|empat\s*puluh|lima\s*puluh|enam\s*puluh|tujuh\s*puluh|delapan\s*puluh|sembilan\s*puluh|puluh|puluhan))*(?:\s+(?:satu|dua|tiga|empat|lima|enam|tujuh|delapan|sembilan))?(?:\s+(?:ribu|rebu|rupiah|perak|k|rb))?\b""", RegexOption.IGNORE_CASE),

        Regex("""\b(?:sepuluh|sebelas|dua\s*belas|tiga\s*belas|empat\s*belas|lima\s*belas|dua\s*puluh|tiga\s*puluh|empat\s*puluh|lima\s*puluh|enam\s*puluh|tujuh\s*puluh|delapan\s*puluh|sembilan\s*puluh)(?:\s+(?:satu|dua|tiga|empat|lima|enam|tujuh|delapan|sembilan))?\s*(?:ribu|rebu|rupiah|perak|k|rb)\b""", RegexOption.IGNORE_CASE),

        Regex("""\b(?:seribu|dua\s*ribu|tiga\s*ribu|empat\s*ribu|lima\s*ribu|enam\s*ribu|tujuh\s*ribu|delapan\s*ribu|sembilan\s*ribu|sepuluh\s*ribu|belasan\s*ribu|puluhan\s*ribu|ratusan\s*ribu)\b""", RegexOption.IGNORE_CASE),

        // 3. Istilah Harga, Live Selling Slang, Promo, Voucher, Ongkir
        Regex("""\b(?:harga|harganya|seharga|nominal|rupiah|rp\.?|idr|ribu|rebu|ribuan|ratusan\s*ribu|jutaan|juta|murah|kemurahan|terjangkau|bayar|pembayaran|tagihan)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:cepek|gocap|seceng|noceng|goceng|cepe|ceng|noban|pekgo)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:ongkir|ongkos\s*kirim|biaya\s*pengiriman|gratis\s*ongkir|bebas\s*ongkir|subsidi\s*ongkir)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:diskon|promo|promosi|cashback|voucher|kupon|potongan\s*harga|flash\s*sale|sale|deal|etalase)\b""", RegexOption.IGNORE_CASE),

        // 4. Frasa Transaksi Live (e.g. "cuma seratus dua puluh", "dapet 120", "ambil di seratus dua puluh", "bisa checkout 120")
        Regex("""\b(?:cuma|hanya|dapet|dapat|kena|ambil\s*di|tebus|ditebus|bayarnya|checkout\s*di|co\s*di)\s+(?:rp\.?\s*)?(?:\d+|seratus|dua|tiga|empat|lima|enam|tujuh|delapan|sembilan|puluh|ratus|ribu)\b""", RegexOption.IGNORE_CASE),

        // 5. Durasi Pengiriman & Estimasi Pengiriman (Dilarang TikTok Affiliate)
        Regex("""\b(?:estimasi pengiriman|lama pengiriman|pengiriman berapa lama|berapa lama pengiriman|kapan sampai|kapan sampainya|berapa hari sampai|pengiriman berapa hari|sampai berapa hari|kirim hari ini|langsung dikirim|dikirim hari ini|langsung kirim|hari ini kirim|bisa dikirim|proses pengiriman|ekspedisi|pengiriman cepat|besok sampai|lusa sampai|sehari sampai|2 hari sampai|3 hari sampai|\d+\s*hari sampai)\b""", RegexOption.IGNORE_CASE),

        // 6. Overclaim & Garansi Tidak Terbukti
        Regex("""\b(?:pasti sampai|dijamin sampai|1 hari sampai|garansi sampai|langsung sampai besok|sehari sampai)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:pasti bikin|langsung bikin kurus|bikin langsing|menutupi semua kekurangan|bikin badan ideal|hasil instan|pasti nyaman seharian)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:langsung putih|bikin glowing instan|pasti putih|menghilangkan jerawat seketika|sembuh total|pasti manjur)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:pasti muat semua|cocok untuk semua tinggi|dijamin nggak kekecilan|pasti pas|baterai tahan selamanya|nggak perlu lihat size)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:nggak ada tandingannya|nomor satu|terbaik sedunia|semua orang cocok)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:direkomendasikan dokter|dipakai artis)\b""", RegexOption.IGNORE_CASE),

        // 7. Ucapan Live Streaming
        Regex("""\b(?:baru masuk|masuk live|penonton live|komen ya|komentar ya|tunggu aku ambil|aku ambilin|sudah di-?pin|udah di-?pin|jangan pergi dulu|tap-?tap layar|tap layar|tap-?tap|moderator|bantu tap|bantu like|share live|selama live ini|checkout saat live)\b""", RegexOption.IGNORE_CASE),

        // 8. Urgensi & Fear-based Selling
        Regex("""\b(?:cuma hari ini|stok tinggal|buruan sebelum|kalau nggak beli pasti rugi|jangan sampai menyesal|wajib beli sekarang|kalau kehabisan salah sendiri|nggak beli berarti rugi)\b""", RegexOption.IGNORE_CASE),

        // 9. Pre-Order & PO (Dilarang TikTok Affiliate / Menyesatkan Kesiapan Barang)
        Regex("""\b(?:pre[\s-]*order|preorder|p\.?o\.?|p/o)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:sistem|ikut|ikutan|masih|barang|kena|lagi|bisa|wajib|daftar|jadwal)\s+p\.?o\.?\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:p\.?o\.?)\s+(?:ya|kak|kakak|say|sayang|bun|bunda|dulu|berapa\s*hari|\d+\s*hari)\b""", RegexOption.IGNORE_CASE)
    )

    // Regex prioritas varian warna produk untuk TikTok Affiliate
    private val colorPattern = Regex(
        """\b(?:warna|warnanya|hitam|black|putih|white|broken\s*white|bw|sage|sage\s*green|navy|mocca|moka|maroon|marun|denim|dusty|dusty\s*pink|lilac|terracotta|terakota|terakot|army|coksu|cokelat\s*susu|cokelat|brown|cream|krem|nude|khaki|kaki|olive|hijau|green|mint|emerald|botol|biru|blue|baby\s*blue|sky\s*blue|merah|red|burgundy|pink|fuschia|fanta|magenta|rose|peach|salem|kuning|yellow|mustard|lemon|abu|abu-abu|grey|gray|silver|charcoal|taro|ungu|purple|violet|gold|emas|caramel|biscuit|bata|taupe|hazelnut)\b""",
        RegexOption.IGNORE_CASE
    )

    companion object {
        private const val MAX_GROQ_AUDIO_BYTES = 20 * 1024 * 1024L // 20 MB (batas aman dari limit 25 MB Groq Whisper)
        private const val CHUNK_DURATION_SEC = 600.0 // 10 menit per chunk jika audio > 20 MB
    }

    // --- 1. GROQ WHISPER TRANSCRIPTION ---
    suspend fun transcribeAudio(
        audioFile: File,
        onProgress: (Int, String) -> Unit
    ): List<TranscriptSegment> = withContext(Dispatchers.IO) {
        if (!audioFile.exists() || audioFile.length() == 0L) {
            throw IllegalArgumentException("File audio tidak ditemukan atau kosong!")
        }

        // Jalur 1: Ukuran audio aman (<= 20 MB). Transkripsi langsung dalam 1 file.
        if (audioFile.length() <= MAX_GROQ_AUDIO_BYTES) {
            try {
                onProgress(15, "Mentranskripsi suara dengan Groq Whisper AI...")
                return@withContext transcribeSingleAudioFile(
                    audioFile = audioFile,
                    timeOffset = 0.0,
                    startId = 1,
                    onProgress = onProgress
                )
            } catch (e: Exception) {
                // Jika server tetap menolak dengan error 413 / payload too large, beralih ke chunking otomatis!
                val msg = e.message ?: ""
                if (msg.contains("413") || msg.contains("too large", ignoreCase = true) || msg.contains("431")) {
                    android.util.Log.w("AiEngine", "Ukuran audio ditolak Groq ($msg). Mengaktifkan pemotongan chunk otomatis...")
                } else {
                    throw e
                }
            }
        }

        // Jalur 2: Audio video sangat panjang / file > 20 MB. Potong jadi beberapa bagian (10 menit per chunk).
        return@withContext transcribeInChunks(audioFile, onProgress)
    }

    private suspend fun transcribeInChunks(
        audioFile: File,
        onProgress: (Int, String) -> Unit
    ): List<TranscriptSegment> {
        onProgress(14, "Audio video berdurasi panjang (> 20 MB). Memotong audio otomatis ke segmen aman...")

        val durationSec = try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(audioFile.absolutePath)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            retriever.release()
            (ms / 1000.0).coerceAtLeast(60.0)
        } catch (e: Exception) {
            (audioFile.length() / 4000.0).coerceAtLeast(60.0)
        }

        val chunkDur = CHUNK_DURATION_SEC // 600 detik = 10 menit
        val numChunks = Math.ceil(durationSec / chunkDur).toInt().coerceAtLeast(2)
        val allSegments = mutableListOf<TranscriptSegment>()
        var currentId = 1

        for (idx in 0 until numChunks) {
            val chunkStart = idx * chunkDur
            val currentChunkDur = Math.min(chunkDur, durationSec - chunkStart)
            if (currentChunkDur <= 0.5) break

            val chunkFile = File(audioFile.parentFile, "whisper_chunk_${idx}_${System.currentTimeMillis()}.mp3")
            try {
                // Potong chunk MP3
                val cmd = "-y -ss $chunkStart -t $currentChunkDur -i \"${audioFile.absolutePath}\" -acodec copy \"${chunkFile.absolutePath}\""
                FFmpegKit.execute(cmd)

                // Fallback re-encode jika chunk kosong atau rusak (< 1 KB)
                if (!chunkFile.exists() || chunkFile.length() < 1024L) {
                    val fallbackCmd = "-y -ss $chunkStart -t $currentChunkDur -i \"${audioFile.absolutePath}\" -acodec libmp3lame -ac 1 -ar 16000 -b:a 32k \"${chunkFile.absolutePath}\""
                    FFmpegKit.execute(fallbackCmd)
                }

                if (!chunkFile.exists() || chunkFile.length() < 512L) {
                    continue
                }

                val pct = 15 + (idx * 20 / numChunks)
                val endSecFormat = Math.min(durationSec.toInt(), (chunkStart + currentChunkDur).toInt())
                onProgress(
                    pct,
                    "Mentranskripsi audio bagian ${idx + 1}/$numChunks (${chunkStart.toInt()}s - ${endSecFormat}s)..."
                )

                val chunkSegments = transcribeSingleAudioFile(
                    audioFile = chunkFile,
                    timeOffset = chunkStart,
                    startId = currentId,
                    onProgress = null
                )
                allSegments.addAll(chunkSegments)
                currentId += chunkSegments.size

                if (idx < numChunks - 1) {
                    kotlinx.coroutines.delay(600)
                }
            } catch (e: Exception) {
                android.util.Log.e("AiEngine", "Gagal transkripsi audio chunk $idx: ${e.message}")
                if (allSegments.isEmpty() && idx == numChunks - 1) {
                    throw e
                }
            } finally {
                if (chunkFile.exists()) {
                    chunkFile.delete()
                }
            }
        }

        if (allSegments.isEmpty()) {
            throw IllegalStateException("Transkripsi Groq tidak menghasilkan teks dari rekaman audio.")
        }

        onProgress(35, "Transkripsi multi-bagian tuntas (${allSegments.size} segmen kata terpetakan)")
        return allSegments
    }

    private fun transcribeSingleAudioFile(
        audioFile: File,
        timeOffset: Double = 0.0,
        startId: Int = 1,
        onProgress: ((Int, String) -> Unit)? = null
    ): List<TranscriptSegment> {
        val activeKey = settingsManager.getActiveKey("groq")
            ?: throw IllegalStateException("Tidak ada API Key Groq aktif! Silakan tambahkan di pengaturan.")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-large-v3-turbo")
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "word")
            .addFormDataPart("timestamp_granularities[]", "segment")
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody("audio/mpeg".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer ${activeKey.key}")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 429) {
                    settingsManager.markKeyExhausted("groq", activeKey.id)
                }
                val errBody = response.body?.string() ?: ""
                throw RuntimeException("Groq Transcription gagal (${response.code}): $errBody")
            }

            val bodyStr = response.body?.string() ?: ""
            val jsonObject = JsonParser.parseString(bodyStr).asJsonObject
            val segmentsArray = jsonObject.getAsJsonArray("segments") ?: return emptyList()

            // 1. Ekstraksi seluruh kata dengan Word-Level Timestamps dari Groq Whisper
            val allWordsList = mutableListOf<WordTimestamp>()
            if (jsonObject.has("words") && jsonObject.get("words").isJsonArray) {
                val wordsArr = jsonObject.getAsJsonArray("words")
                for (w in 0 until wordsArr.size()) {
                    val wObj = wordsArr[w].asJsonObject
                    val rawWord = wObj.get("word")?.asString?.trim() ?: ""
                    val cleanWord = rawWord.replace(Regex("""[^\p{L}\p{N}]"""), "").trim()
                    if (cleanWord.isNotBlank()) {
                        allWordsList.add(
                            WordTimestamp(
                                word = rawWord,
                                start = (wObj.get("start")?.asDouble ?: 0.0) + timeOffset,
                                end = (wObj.get("end")?.asDouble ?: 0.0) + timeOffset
                            )
                        )
                    }
                }
            }

            // 2. Format segmen lengkap dengan offset timestamp dan ID lanjutan
            val segments = mutableListOf<TranscriptSegment>()
            for (i in 0 until segmentsArray.size()) {
                val seg = segmentsArray[i].asJsonObject
                val segStart = seg.get("start").asDouble + timeOffset
                val segEnd = seg.get("end").asDouble + timeOffset
                val segText = seg.get("text").asString.trim()

                val segWords = mutableListOf<WordTimestamp>()
                if (seg.has("words") && seg.get("words").isJsonArray) {
                    val segWordsArr = seg.getAsJsonArray("words")
                    for (w in 0 until segWordsArr.size()) {
                        val wObj = segWordsArr[w].asJsonObject
                        val rawWord = wObj.get("word")?.asString?.trim() ?: ""
                        val cleanWord = rawWord.replace(Regex("""[^\p{L}\p{N}]"""), "").trim()
                        if (cleanWord.isNotBlank()) {
                            segWords.add(
                                WordTimestamp(
                                    word = rawWord,
                                    start = (wObj.get("start")?.asDouble ?: 0.0) + timeOffset,
                                    end = (wObj.get("end")?.asDouble ?: 0.0) + timeOffset
                                )
                            )
                        }
                    }
                } else {
                    allWordsList.filterTo(segWords) { it.start >= segStart - 0.15 && it.end <= segEnd + 0.15 }
                }

                segments.add(
                    TranscriptSegment(
                        id = startId + i,
                        start = segStart,
                        end = segEnd,
                        text = segText,
                        words = segWords
                    )
                )
            }
            onProgress?.invoke(35, "Transkripsi berhasil (${segments.size} segmen, ${allWordsList.size} kata terpetakan presisi)")
            return segments
        }
    }

    // --- 2. CLIP SELECTION VIA GROQ LLaMA (100% PURE GROQ AI) ---
    suspend fun planClips(
        segments: List<TranscriptSegment>,
        totalDuration: Double,
        durationLimit: String,
        onProgress: (Int, String) -> Unit
    ): List<PlannedClip> = withContext(Dispatchers.IO) {
        val maxDurationSec = when {
            durationLimit == "max_90" || durationLimit == "90" || durationLimit == "60-90" -> 90.0
            durationLimit == "max_120" || durationLimit == "120" || durationLimit == "90-120" -> 120.0
            durationLimit.toDoubleOrNull() != null -> durationLimit.toDouble().coerceIn(15.0, 300.0)
            else -> 60.0 // Default maksimal 1 menit (60 detik)
        }

        val promptTarget = Math.round(maxDurationSec * 0.85).toInt()

        // 1. Format transkrip ringkas: nomor ID dan teks ucapan (tanpa timestamp detik)
        // Menghemat ~40% token untuk mencegah batas kuota 8000 TPM (Tokens Per Minute) Groq Free Tier
        val transcriptText = segments.joinToString("\n") {
            "[${it.id}] ${it.text}"
        }

        val systemPrompt = """
            Bertindak sebagai AI Compliance Editor khusus untuk UCAPAN TALENT/PENJUAL dalam video TikTok Shop Affiliate UNTUK SEMUA KATEGORI PRODUK (Fashion & Pakaian, Skincare & Beauty, Elektronik & Gadget, Perlengkapan Rumah Tangga, Makanan & Minuman, Otomotif, dll).

            TUGAS UTAMA:
            Analisis dan bersihkan seluruh ucapan orang yang sedang menjual atau mempromosikan produk dari kategori apa pun. Hapus setiap kalimat yang berpotensi menyebabkan pelanggaran regulasi TikTok Shop, menyesatkan konsumen, mengandung klaim tidak terbukti/overclaim, atau membuat kesan video sedang siaran langsung.
            Pilih rentang segmen video yang bersih, faktual, dan aman untuk dirangkai menjadi video promosi produk (VT biasa) dengan BATAS MAKSIMAL TOTAL DURASI tidak boleh lebih dari ${maxDurationSec.toInt()} detik (ideal sekitar $promptTarget - ${maxDurationSec.toInt()} detik).

            Jangan membuat klaim baru. Jangan memperindah klaim berisiko. Jika ucapan berisiko, HAPUS/JANGAN PILIH bagian tersebut.

            VIDEO INI ADALAH VT EDITAN BIASA, BUKAN LIVE STREAMING.

            PRIORITAS UTAMA - SEMUA VARIAN WARNA PRODUK (SANGAT PENTING):
            - Jika host/talent menyebutkan, memperlihatkan, atau mereview varian warna produk (misal: hitam, sage, navy, mocca, maroon, denim, dusty, lilac, terracotta, coksu, army, broken white, dll), WAJIB PILIH DAN MASUKKAN SEMUA segmen varian warna tersebut ke dalam klip!
            - Jangan pernah menghilangkan segmen penyebutan warna apa pun. Penonton TikTok Shop membutuhkan variasi warna lengkap untuk memutuskan checkout.
            - Buang bagian basa-basi atau penjelasan umum yang tidak penting demi memasukkan semua varian warna yang disebutkan.

            PERTAHANKAN HANYA UCAPAN YANG:
            - Menjelaskan produk secara faktual (berlaku untuk semua kategori produk).
            - Menyebut nama/merek/jenis produk.
            - Menjelaskan warna, model, motif, desain, bentuk, dan detail fisik yang terlihat.
            - Menjelaskan bahan/material/kandungan/komposisi yang memang tersedia dalam informasi resmi produk.
            - Menjelaskan ukuran, dimensi, kapasitas, berat, watt/daya, atau BB/TB jika relevan dan datanya tersedia.
            - Menjelaskan fitur, spesifikasi teknis, fungsi, dan cara penggunaan yang benar-benar tercantum.
            - Mengajak penonton melihat produk secara netral.

            WAJIB HAPUS UCAPAN BERIKUT (11 ATURAN COMPLIANCE TIKTOK):
            1. KLAIM BERLEBIHAN
               Hapus kata/kalimat: “Pasti”, “dijamin”, “100%”, “terbaik”, “nomor satu”, “paling bagus”, “paling canggih”, “wajib punya”, “semua orang cocok”, “nggak ada tandingannya”.
            2. JANJI HASIL / MANFAAT TIDAK TERBUKTI (OVERCLAIM)
               Hapus janji hasil instan untuk semua kategori: “Pasti bikin langsing”, “langsung putih/glowing”, “langsung bikin kurus”, “menutupi semua kekurangan”, “bikin badan ideal”, “hasil instan”, “langsung sembuh”, “pasti nyaman seharian”, atau janji hasil yang tidak didukung informasi resmi.
            3. DURASI PENGIRIMAN & ESTIMASI SAMPAI (DILARANG KERAS)
               Hapus semua ucapan terkait estimasi waktu/lama pengiriman: “estimasi pengiriman”, “pengiriman berapa lama”, “kapan sampai”, “berapa hari sampai”, “kirim hari ini”, “langsung dikirim”, “besok sampai”, “2 hari sampai”, “proses pengiriman cepat”, atau janji tiba paket. TikTok affiliate dilarang menjanjikan estimasi pengiriman.
            4. JEDA DIAM, DEAD-AIR & MAIN HP (DILARANG)
               Hapus dan jangan pilih segmen di mana host terdiam beberapa detik, jeda lama tanpa bicara, melihat/memainkan handphone, atau membaca komentar dalam hati. Ambil HANYA segmen di mana host aktif berbicara menerangkan produk.
            5. TESTIMONI ATAU PENGALAMAN PALSU
               Hapus ucapan seolah-olah talent:
               - Sudah memakai produk.
               - Sudah mencoba produk.
               - Mendapat hasil tertentu.
               - Membuktikan produk berhasil.
               - Mengalami perubahan setelah penggunaan.
               Kecuali pengalaman tersebut benar-benar diberikan sebagai fakta yang sah.
            6. KLAIM UKURAN / SPESIFIKASI MENYESATKAN
               Hapus: “Pasti muat semua BB”, “cocok untuk semua tinggi badan”, “dijamin nggak kekecilan”, “pasti pas”, “baterai tahan selamanya”, “nggak perlu lihat size chart”.
               Pertahankan hanya informasi ukuran dan spesifikasi yang benar-benar tersedia.
            7. KLAIM KEASLIAN / OTORITAS TANPA BUKTI
               Hapus: “Original resmi”, “produk import langsung”, “direkomendasikan dokter/ahli”, “dipakai artis”, “brand terkenal nomor satu”, jika tidak ada bukti yang diberikan.
            8. HARGA / PROMO / URGENSI MENYESATKAN
               Hapus: Seluruh penyebutan harga, nominal rupiah/rp, murah, diskon, promo, cashback, voucher, gratis ongkir, “Cuma hari ini”, “stok tinggal sedikit”, “buruan sebelum habis”, “harga termurah”, “diskon terbesar”, “wajib checkout sekarang”.
            9. PERBANDINGAN KOMPETITOR
               Hapus: “Lebih bagus dari brand lain”, “mengalahkan semua produk”, “produk lain jelek/palsu”, “paling murah dibanding yang lain”.
            10. UCAPAN LIVE STREAMING
               Hapus semua ucapan yang membuat kesan sedang live:
               “Yang baru masuk live”, “halo penonton live”, “komen ya”, “tunggu aku ambilkan”, “produk sudah dipin”, “jangan pergi dulu”, “tap layar”, “yang mau checkout saat live”, “selama live ini”, “moderator”, atau interaksi real-time lainnya.
               Video harus terdengar seperti VT promosi biasa yang direkam dan diedit, bukan siaran langsung.
            11. TEKANAN MEMBELI / FEAR-BASED SELLING
               Hapus: “Kalau nggak beli pasti rugi”, “jangan sampai menyesal”, “wajib beli sekarang”, “kalau kehabisan salah sendiri”, “semua orang harus punya”, “nggak beli berarti rugi”.
            12. INFORMASI TIDAK RELEVAN
                Hapus: Basa-basi, percakapan personal, candaan tidak relevan, filler, pengulangan, percakapan dengan orang lain, dan kalimat di luar konteks produk.
            13. PRE-ORDER / PO (DILARANG KERAS)
                Hapus semua ucapan terkait barang PO, pre-order, waktu PO, sistem PO, atau ikut PO: "ini PO ya", "masih PO", "sistem PO", "ikut PO", "PO berapa hari", "preorder". Video VT affiliate harus menampilkan produk ready stok.

            ATURAN PEMBERSIHAN & PEMOTONGAN:
            - Berlaku konsisten untuk SELURUH kategori produk affiliate TikTok.
            - Jangan mengganti klaim berisiko dengan klaim baru.
            - Jangan mengarang informasi produk atau menambahkan manfaat yang tidak disebutkan.
            - Jangan mengubah fakta menjadi klaim marketing berlebihan.
            - Jika satu kalimat berisi bagian aman dan bagian berisiko, hapus bagian berisikonya. Jika seluruh kalimat berisiko, hapus seluruh kalimat.
            - Prioritaskan ucapan faktual daripada ucapan persuasif.
            - KEUTUHAN KALIMAT (TIDAK BOLEH GANTUNG): Wajib menjaga keutuhan setiap kalimat. Jangan pernah memotong ucapan di tengah kalimat, frasa, kata, atau informasi yang belum selesai. Tentukan rentang segmen utuh (start_segment sampai end_segment).
            - Pertahankan alur ucapan agar tetap natural setelah dipotong: hook faktual -> detail produk/fitur/spesifikasi/bahan -> CTA netral penutup.
            - BATAS TOTAL DURASI (SANGAT KETAT): Total akumulasi durasi seluruh klip TIDAK BOLEH lebih dari ${maxDurationSec.toInt()} detik. Pilih 2 sampai 4 kandidat klip terbaik.
            - Jangan membuat talent terdengar seperti sedang live.
            - Jangan menambahkan dialog baru yang tidak ada dalam materi asli.

            CTA YANG BOLEH DIPERTAHANKAN:
            “Cek detail produknya di keranjang kuning.”
            “Lihat pilihan ukuran dan detail produknya.”
            “Kalau sesuai kebutuhanmu, cek produknya di bawah.”

            FORMAT OUTPUT (WAJIB JSON MURNI TANPA MARKDOWN):
            Kembalikan HANYA format JSON valid tanpa tanda markdown:
            {
              "candidates": [
                {"start_segment": 1, "end_segment": 4, "reason": "hook faktual", "text": "ucapan talent faktual yang sudah dibersihkan"},
                {"start_segment": 8, "end_segment": 15, "reason": "detail dan bahan", "text": "ucapan talent faktual yang sudah dibersihkan"},
                {"start_segment": 22, "end_segment": 26, "reason": "cta netral", "text": "ajakan cek keranjang"}
              ]
            }
        """.trimIndent()

        var rawPlanned: List<PlannedClip> = emptyList()

        // 1. Groq AI Selection (1 Key Groq All-in-One)
        val groqKey = settingsManager.getActiveKey("groq")
            ?: throw IllegalStateException("Tidak ada API Key Groq aktif! Silakan tambahkan API key Groq di menu pengaturan.")

        try {
            if (totalDuration > 360.0 && segments.size > 70) {
                // Video berdurasi panjang (> 6 menit): Bagi ke dalam jendela waktu (5 menit per chunk)
                // agar tidak melampaui batas 8000 TPM (Tokens Per Minute) Groq Free Tier
                val chunkDuration = 300.0 // 5 menit per jendela
                val numChunks = Math.ceil(totalDuration / chunkDuration).toInt()
                val allChunkClips = mutableListOf<PlannedClip>()

                for (chunkIdx in 0 until numChunks) {
                    val chunkStart = chunkIdx * chunkDuration
                    val chunkEnd = (chunkIdx + 1) * chunkDuration
                    val chunkSegments = segments.filter { it.start >= chunkStart && it.start < chunkEnd }
                    if (chunkSegments.isEmpty()) continue

                    val chunkTranscript = chunkSegments.joinToString("\n") { "[${it.id}] ${it.text}" }
                    val pct = 40 + (chunkIdx * 10 / numChunks)
                    onProgress(
                        pct,
                        "Menganalisis highlight bagian ${chunkIdx + 1}/$numChunks (${chunkStart.toInt()}s-${Math.min(totalDuration.toInt(), chunkEnd.toInt())}s)..."
                    )

                    val chunkClips = callGroqChat(systemPrompt, chunkTranscript, groqKey.key, segments, totalDuration, onProgress)
                    allChunkClips.addAll(chunkClips)

                    if (chunkIdx < numChunks - 1) {
                        kotlinx.coroutines.delay(1000)
                    }
                }
                if (allChunkClips.isNotEmpty()) {
                    rawPlanned = allChunkClips
                }
            } else {
                rawPlanned = callGroqChat(systemPrompt, transcriptText, groqKey.key, segments, totalDuration, onProgress)
            }
        } catch (e: Exception) {
            android.util.Log.e("AiEngine", "Groq chat error: ${e.message}")
        }

        if (rawPlanned.isEmpty()) {
            throw IllegalStateException("AI Groq belum berhasil menyusun klip. Jika video berdurasi sangat panjang (>10 menit), pastikan kuota rate-limit Groq (TPM) tidak sedang terpakai bersamaan.")
        }

        // 4. Programmatic Post-Filter: Buang dan potong bagian harga, promo, voucher, atau logistik overclaim
        val cleanClips = filterForbiddenClips(rawPlanned, segments)
        val validClips = if (cleanClips.isNotEmpty()) {
            cleanClips
        } else {
            // Jika seluruh klip AI kebetulan terbuang karena harga, ambil dari segmen yang 100% aman
            val safeSegs = segments.filter { seg ->
                seg.text.length > 8 &&
                forbiddenPatterns.none { it.containsMatchIn(seg.text) }
            }
            buildFallbackClipsFromSegments(safeSegs, maxDurationSec)
        }

        // 5. HARD DURATION ENFORCEMENT: Jamin total durasi TIDAK AKAN PERNAH melebihi maxDurationSec!
        val finalClips = enforceMaxDuration(validClips, segments, maxDurationSec)
        val finalDuration = Math.round(finalClips.sumOf { it.end - it.start } * 10.0) / 10.0

        onProgress(60, "Rencana klip disusun (${finalClips.size} klip tuntas, total durasi: ${finalDuration}s, maks: ${maxDurationSec.toInt()}s)")
        return@withContext finalClips
    }

    private fun isTimeRangeOverlapping(
        start: Double,
        end: Double,
        usedRanges: List<Pair<Double, Double>>,
        tolerance: Double = 0.1
    ): Boolean {
        for (range in usedRanges) {
            val overlapStart = Math.max(start, range.first)
            val overlapEnd = Math.min(end, range.second)
            if (overlapEnd - overlapStart > tolerance) {
                return true
            }
        }
        return false
    }

    // --- 2b. PERENCANAAN MULTI-VIDEO UNTUK ANTI-REUSED CONTENT (1 - 5 VIDEO) ---
    suspend fun planMultipleVideos(
        segments: List<TranscriptSegment>,
        totalDuration: Double,
        durationLimit: String,
        videoCount: Int,
        onProgress: (Int, String) -> Unit
    ): List<List<PlannedClip>> = withContext(Dispatchers.IO) {
        val safeCount = videoCount.coerceIn(1, 5)
        if (safeCount == 1) {
            val single = planClips(segments, totalDuration, durationLimit, onProgress)
            return@withContext listOf(single)
        }

        val maxDurationSec = when {
            durationLimit == "max_90" || durationLimit == "90" || durationLimit == "60-90" -> 90.0
            durationLimit == "max_120" || durationLimit == "120" || durationLimit == "90-120" -> 120.0
            durationLimit.toDoubleOrNull() != null -> durationLimit.toDouble().coerceIn(15.0, 300.0)
            else -> 60.0
        }
        val promptTarget = Math.round(maxDurationSec * 0.85).toInt()

        onProgress(38, "Membagi timeline ke dalam $safeCount jendela waktu mandiri (100% timestamp terpisah)...")

        val groqKey = settingsManager.getActiveKey("groq")
            ?: throw IllegalStateException("Tidak ada API Key Groq aktif! Silakan tambahkan di pengaturan.")

        val globalUsedRanges = mutableListOf<Pair<Double, Double>>()
        val cleanPlans = mutableListOf<List<PlannedClip>>()

        // ARSITEKTUR TIMELINE WINDOW PARTITIONING:
        // Membagi total durasi video (misal 10 menit = 600s) menjadi N jendela waktu mandiri
        // (contoh untuk 2 video: Window 1 = 0 - 300s, Window 2 = 300 - 600s).
        // Setiap jendela waktu memiliki pasokan bahan berlimpah dan 100% DIJAMIN tidak akan pernah bertabrakan timestamp-nya!
        val windowDuration = totalDuration / safeCount

        for (k in 0 until safeCount) {
            val partNum = k + 1
            val winStart = k * windowDuration
            val winEnd = if (k == safeCount - 1) totalDuration else (k + 1) * windowDuration

            val pct = 38 + (k * 24 / safeCount)
            onProgress(
                pct,
                "Menyusun Video Part $partNum/$safeCount (${winStart.toInt()}s - ${winEnd.toInt()}s, target: ${maxDurationSec.toInt()}s)..."
            )

            // 1. Ambil segmen ucapan yang berada di dalam jendela waktu ini dan belum dipakai
            var winSegs = segments.filter { seg ->
                seg.start >= winStart && seg.start < winEnd &&
                !isTimeRangeOverlapping(seg.start, seg.end, globalUsedRanges)
            }

            // Jika jendela ini minim ucapan (< 15 detik), perluas dengan segmen lain yang masih bebas di seluruh video
            if (winSegs.sumOf { it.end - it.start } < 15.0) {
                winSegs = segments.filter { seg ->
                    !isTimeRangeOverlapping(seg.start, seg.end, globalUsedRanges)
                }
            }

            if (winSegs.isEmpty()) continue

            // 2. Susun prompt AI fokus untuk Part ini dengan target durasi penuh
            val winTranscript = winSegs.joinToString("\n") { "[${it.id}] ${it.text}" }
            val firstSegId = winSegs.first().id
            val lastSegId = winSegs.last().id
            val partPrompt = """
                Bertindak sebagai AI Compliance Editor khusus untuk UCAPAN TALENT/PENJUAL dalam video TikTok Shop Affiliate UNTUK SEMUA KATEGORI PRODUK.
                TUGAS UTAMA:
                Pilih dan susun klip terbaik untuk VIDEO PART $partNum dari $safeCount bagian rekaman siaran ini.
                Analisis transkrip ucapan pada rentang waktu ini.
                
                TARGET DURASI (SANGAT PENTING):
                - Wajib memilih beberapa rentang klip (3 sampai 6 kandidat) agar total akumulasi durasi klip MENCAPAI TARGET sekitar $promptTarget sampai ${maxDurationSec.toInt()} detik.
                - JANGAN HANYA MEMILIH 1 ATAU 2 SEGMEN PENDEK! Penonton membutuhkan penjelasan produk yang utuh, mendalam, dan meyakinkan.
                
                PRIORITAS UTAMA - VARIAN WARNA & DETAIL PRODUK:
                - Jika host memperlihatkan atau mereview varian warna produk (hitam, sage, mocca, maroon, navy, dll), WAJIB masukkan semua segmen warna tersebut.
                - Pertahankan penjelasan bahan, ukuran, fungsi, spesifikasi, dan fitur fisik yang terlihat.
                
                WAJIB HAPUS UCAPAN BERIKUT (ATURAN COMPLIANCE TIKTOK):
                1. HARGA, RUPIAH, ATAU NOMINAL UANG (e.g. 120k, 120rb, seratus dua puluh ribu, cepek, seharga).
                2. PROMO, DISKON, VOUCHER, CASHBACK, GRATIS ONGKIR, ETALASE.
                3. PRE-ORDER / PO (DILARANG KERAS): Hapus semua ucapan PO, pre order, pre-order, sistem PO, ikut PO, PO berapa hari. Video harus mengesankan produk ready stok.
                4. ESTIMASI & DURASI PENGIRIMAN: Hapus estimasi hari sampai, pengiriman cepat, kirim hari ini.
                5. KLAIM BERLEBIHAN & OVERCLAIM: Hapus "pasti", "dijamin", "nomor satu", "hasil instan", "pasti langsing", "langsung putih".
                6. JEDA DIAM / DEAD-AIR / MAIN HP: Hapus saat host terdiam atau melihat layar HP.
                7. UCAPAN LIVE STREAMING: Hapus "baru gabung", "tap layar", "komen ya", "checkout saat live", "sudah dipin".
                
                KEUTUHAN KALIMAT:
                - Jaga keutuhan setiap kalimat. Tentukan rentang segmen utuh (start_segment sampai end_segment).
                - Total durasi akumulasi TIDAK BOLEH melebihi ${maxDurationSec.toInt()} detik.
                
                FORMAT OUTPUT (WAJIB JSON MURNI TANPA MARKDOWN):
                {
                  "candidates": [
                    {"start_segment": $firstSegId, "end_segment": ${Math.min(lastSegId, firstSegId + 4)}, "reason": "hook faktual", "text": "ucapan talent faktual"},
                    {"start_segment": ${Math.min(lastSegId, firstSegId + 5)}, "end_segment": ${Math.min(lastSegId, firstSegId + 10)}, "reason": "varian warna dan detail", "text": "ucapan talent faktual"}
                  ]
                }
            """.trimIndent()

            var rawClips = emptyList<PlannedClip>()
            try {
                rawClips = callGroqChat(
                    systemPrompt = partPrompt,
                    userMessage = winTranscript,
                    apiKey = groqKey.key,
                    segments = segments,
                    totalDuration = totalDuration,
                    onProgress = { p, m ->
                        val subPct = pct + (p * 5 / 100)
                        onProgress(subPct, "Part $partNum: $m")
                    }
                )
            } catch (e: Exception) {
                android.util.Log.e("AiEngine", "Groq chat error on part $partNum: ${e.message}")
            }

            // 3. Saring dari klip yang tumpang tindih dengan video lain
            val nonOverlapping = rawClips.filter { clip ->
                !isTimeRangeOverlapping(clip.start, clip.end, globalUsedRanges)
            }

            // 4. Saring dan potong kata-kata terlarang tingkat kata (Harga, Promo, PO / Pre-Order, dll)
            val sanitizedClips = filterForbiddenClips(nonOverlapping, segments)

            // 5. SUPLEMENTASI KLIP OTOMATIS: Jika klip AI kurang dari target (misal AI cuma kasih 25s padahal target 120s),
            // tambahkan segmen-segmen aman dari jendela ini hingga mendekati target durasi!
            val safeWindowSegments = winSegs.filter { seg ->
                seg.text.length > 6 &&
                forbiddenPatterns.none { it.containsMatchIn(seg.text) } &&
                !isTimeRangeOverlapping(seg.start, seg.end, globalUsedRanges)
            }

            val filledClips = if (sanitizedClips.sumOf { it.end - it.start } < maxDurationSec * 0.75) {
                buildFallbackClipsFromSegments(safeWindowSegments, maxDurationSec, sanitizedClips)
            } else {
                sanitizedClips
            }

            // 6. Hard Duration Enforcement: Pastikan durasi pas sesuai batas maksimal (60s, 90s, atau 120s)
            val merged = mergeCloseClips(filledClips)
            val enforced = enforceMaxDuration(merged, segments, maxDurationSec)
            val totalPartDur = enforced.sumOf { it.end - it.start }

            // 7. STRICT MINIMUM DURATION GUARD (Anti Video 2 Detik!):
            // Video hanya diterima jika durasi valid minimal 15 detik!
            if (enforced.isNotEmpty() && totalPartDur >= 15.0) {
                cleanPlans.add(enforced)
                for (c in enforced) {
                    globalUsedRanges.add(c.start to c.end)
                }
            } else {
                // Jika masih kurang dari 15 detik, coba ambil segmen aman murni dari seluruh video
                val globalSafe = segments.filter { seg ->
                    seg.text.length > 6 &&
                    forbiddenPatterns.none { it.containsMatchIn(seg.text) } &&
                    !isTimeRangeOverlapping(seg.start, seg.end, globalUsedRanges)
                }
                val rescuedClips = buildFallbackClipsFromSegments(globalSafe, maxDurationSec)
                val rescuedEnforced = enforceMaxDuration(rescuedClips, segments, maxDurationSec)
                if (rescuedEnforced.isNotEmpty() && rescuedEnforced.sumOf { it.end - it.start } >= 15.0) {
                    cleanPlans.add(rescuedEnforced)
                    for (c in rescuedEnforced) {
                        globalUsedRanges.add(c.start to c.end)
                    }
                }
            }

            // Delay sejenak antar panggilan Groq untuk menjaga kuota rate limit
            if (k < safeCount - 1) {
                kotlinx.coroutines.delay(1000)
            }
        }

        // 8. Verifikasi Akhir: Jamin urutan clipId di setiap video rapi dan urut
        val finalVerifiedPlans = cleanPlans.map { videoClips ->
            videoClips.sortedBy { it.start }.mapIndexed { idx, clip ->
                clip.copy(clipId = idx + 1)
            }
        }

        onProgress(62, "Berhasil menyusun ${finalVerifiedPlans.size} video dengan timestamp 100% berbeda tanpa tumpang tindih!")
        return@withContext finalVerifiedPlans
    }

    private fun enforceMaxDuration(
        clips: List<PlannedClip>,
        segments: List<TranscriptSegment>,
        maxSeconds: Double
    ): List<PlannedClip> {
        if (clips.isEmpty()) return clips

        val totalCurrentDuration = clips.sumOf { it.end - it.start }
        if (totalCurrentDuration <= maxSeconds) {
            return clips
        }

        // Jika hanya 1 klip tetapi melebihi batas:
        if (clips.size == 1) {
            val single = clips.first()
            val targetEnd = single.start + maxSeconds
            val segsInClip = segments.filter { it.start >= single.start - 0.1 && it.end <= targetEnd + 0.1 }
            val bestEnd = segsInClip.lastOrNull()?.end ?: targetEnd
            val cappedEnd = Math.min(bestEnd + 0.2, targetEnd)
            return listOf(
                single.copy(
                    end = Math.round(cappedEnd * 1000.0) / 1000.0,
                    duration = Math.round((cappedEnd - single.start) * 100.0) / 100.0
                )
            )
        }

        // Multi-clip greedy selection: Prioritaskan Hook (pertama), Varian Warna (utama), dan CTA (terakhir)
        val firstClip = clips.first()
        val isLastCta = clips.last().reason.contains("cta", ignoreCase = true) ||
                clips.last().text.contains("keranjang", ignoreCase = true) ||
                clips.last().text.contains("beli", ignoreCase = true) ||
                clips.last().text.contains("checkout", ignoreCase = true) ||
                clips.last().text.contains("co", ignoreCase = true)
        val lastClip = if (clips.size > 1 && isLastCta) clips.last() else null

        val selected = mutableListOf<PlannedClip>()
        var accumulatedTime = 0.0

        // 1. Masukkan Hook (first clip)
        selected.add(firstClip)
        accumulatedTime += (firstClip.end - firstClip.start)

        // 2. Ruang untuk CTA jika ada
        val ctaDuration = if (lastClip != null) (lastClip.end - lastClip.start) else 0.0

        // 3. Pisahkan klip tengah: Prioritaskan klip yang menyebutkan varian warna produk!
        val middleClips = if (lastClip != null) {
            clips.subList(1, clips.size - 1)
        } else {
            clips.subList(1, clips.size)
        }

        val (colorClips, otherClips) = middleClips.partition { clip ->
            colorPattern.containsMatchIn(clip.text)
        }

        // 3a. Prioritas Utama: Masukkan klip varian warna terlebih dahulu
        for (m in colorClips) {
            val mDuration = m.end - m.start
            if (accumulatedTime + mDuration + ctaDuration <= maxSeconds) {
                selected.add(m)
                accumulatedTime += mDuration
            }
        }

        // 3b. Prioritas Kedua: Masukkan klip detail/fitur produk lainnya jika durasi masih tersisa
        for (m in otherClips) {
            val mDuration = m.end - m.start
            if (accumulatedTime + mDuration + ctaDuration <= maxSeconds) {
                selected.add(m)
                accumulatedTime += mDuration
            }
        }

        // 4. Masukkan CTA di akhir jika ada
        if (lastClip != null) {
            if (accumulatedTime + ctaDuration <= maxSeconds) {
                selected.add(lastClip)
                accumulatedTime += ctaDuration
            } else if (accumulatedTime + 4.0 <= maxSeconds) {
                val ctaMax = maxSeconds - accumulatedTime
                val ctaEnd = Math.min(lastClip.end, lastClip.start + ctaMax)
                selected.add(
                    lastClip.copy(
                        end = Math.round(ctaEnd * 1000.0) / 1000.0,
                        duration = Math.round((ctaEnd - lastClip.start) * 100.0) / 100.0
                    )
                )
            }
        }

        // 5. Susun kembali secara kronologis berdasarkan waktu kemunculan (start time)
        return selected.sortedBy { it.start }.mapIndexed { idx, clip -> clip.copy(clipId = idx + 1) }
    }

    private fun getAvailableGroqModels(apiKey: String): List<String> {
        val preferredOrder = listOf(
            "qwen/qwen3.8-27b",
            "openai/gpt-oss-120b",
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "llama3-70b-8192",
            "llama3-8b-8192",
            "openai/gpt-oss-20b"
        )
        try {
            val req = Request.Builder()
                .url("https://api.groq.com/openai/v1/models")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JsonParser.parseString(body).asJsonObject
                    val dataArr = json.getAsJsonArray("data") ?: return preferredOrder
                    val serverModelIds = mutableSetOf<String>()
                    for (i in 0 until dataArr.size()) {
                        val id = dataArr[i].asJsonObject.get("id")?.asString ?: ""
                        if (!id.contains("whisper") && !id.contains("prompt-guard")) {
                            serverModelIds.add(id)
                        }
                    }
                    val sorted = mutableListOf<String>()
                    for (p in preferredOrder) {
                        if (serverModelIds.contains(p)) sorted.add(p)
                    }
                    for (s in serverModelIds) {
                        if (!sorted.contains(s)) sorted.add(s)
                    }
                    if (sorted.isNotEmpty()) return sorted
                }
            }
        } catch (e: Exception) {
            // fallback
        }
        return preferredOrder
    }

    private fun callGroqChat(
        systemPrompt: String,
        userMessage: String,
        apiKey: String,
        segments: List<TranscriptSegment>,
        totalDuration: Double,
        onProgress: (Int, String) -> Unit
    ): List<PlannedClip> {
        val models = getAvailableGroqModels(apiKey)
        for (model in models) {
            try {
                val cleanModelName = model.substringAfterLast("/")
                onProgress(42, "Menganalisis konten dengan Groq ($cleanModelName)...")
                val payload = JsonObject().apply {
                    addProperty("model", model)
                    addProperty("temperature", 0.2)
                    add("response_format", JsonObject().apply {
                        addProperty("type", "json_object")
                    })
                    val messages = com.google.gson.JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("role", "system")
                            addProperty("content", "$systemPrompt\nIMPORTANT: Output valid JSON object.")
                        })
                        add(JsonObject().apply {
                            addProperty("role", "user")
                            addProperty("content", userMessage)
                        })
                    }
                    add("messages", messages)
                }

                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                var resultClips: List<PlannedClip>? = null
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = JsonParser.parseString(body).asJsonObject
                        val content = json.getAsJsonArray("choices")[0].asJsonObject
                            .getAsJsonObject("message").get("content").asString
                        val clips = parseCandidatesFromJson(content, segments, totalDuration)
                        if (clips.isNotEmpty()) {
                            resultClips = clips
                        }
                    } else {
                        val errBody = response.body?.string() ?: ""
                        android.util.Log.e("AiEngine", "Groq error $model (${response.code}): $errBody")
                        if (response.code == 429) {
                            try { Thread.sleep(2500) } catch (e: Exception) {}
                        }
                    }
                    Unit
                }
                if (!resultClips.isNullOrEmpty()) {
                    return resultClips!!
                }
            } catch (e: Exception) {
                android.util.Log.e("AiEngine", "Groq exception for $model: ${e.message}")
            }
        }
        return emptyList()
    }



    private data class CandidateRange(
        val startSeg: Int,
        val endSeg: Int,
        val reason: String,
        val text: String
    )

    private fun parseCandidatesFromJson(
        rawText: String,
        segments: List<TranscriptSegment>,
        totalDuration: Double
    ): List<PlannedClip> {
        var clean = rawText.trim()
        if (clean.startsWith("```")) {
            val lines = clean.lines()
            val startIdx = if (lines.first().startsWith("```")) 1 else 0
            val endIdx = if (lines.last().startsWith("```")) lines.size - 1 else lines.size
            clean = lines.subList(startIdx, endIdx).joinToString("\n").trim()
        }

        val element = try {
            JsonParser.parseString(clean)
        } catch (e: Exception) {
            return emptyList()
        }

        return parseCandidatesFromJsonElement(element, segments, totalDuration)
    }

    private fun parseCandidatesFromJsonElement(
        element: com.google.gson.JsonElement,
        segments: List<TranscriptSegment>,
        totalDuration: Double
    ): List<PlannedClip> {
        val array = when {
            element.isJsonArray -> element.asJsonArray
            element.isJsonObject && element.asJsonObject.has("candidates") -> element.asJsonObject.getAsJsonArray("candidates")
            element.isJsonObject && element.asJsonObject.has("clips") -> element.asJsonObject.getAsJsonArray("clips")
            element.isJsonObject && element.asJsonObject.has("segments") -> element.asJsonObject.getAsJsonArray("segments")
            element.isJsonObject && element.asJsonObject.has("data") -> element.asJsonObject.getAsJsonArray("data")
            element.isJsonObject && element.asJsonObject.has("result") -> element.asJsonObject.getAsJsonArray("result")
            element.isJsonObject && element.asJsonObject.has("candidate_clips") -> element.asJsonObject.getAsJsonArray("candidate_clips")
            else -> com.google.gson.JsonArray()
        }

        val byId = segments.associateBy { it.id }
        val rawCandidates = mutableListOf<CandidateRange>()

        for (i in 0 until array.size()) {
            val item = array[i].asJsonObject
            val reason = item.get("reason")?.asString ?: "highlight"
            val textSummary = item.get("text")?.asString ?: ""

            val startSeg = try {
                when {
                    item.has("start_segment") -> item.get("start_segment").asString.replace(Regex("""[^0-9]"""), "").toIntOrNull()
                    item.has("start_id") -> item.get("start_id").asString.replace(Regex("""[^0-9]"""), "").toIntOrNull()
                    item.has("start") && item.get("start").asString.contains(Regex("""^\d+$""")) -> item.get("start").asString.toIntOrNull()
                    else -> null
                }
            } catch (e: Exception) { null }

            val endSeg = try {
                when {
                    item.has("end_segment") -> item.get("end_segment").asString.replace(Regex("""[^0-9]"""), "").toIntOrNull()
                    item.has("end_id") -> item.get("end_id").asString.replace(Regex("""[^0-9]"""), "").toIntOrNull()
                    item.has("end") && item.get("end").asString.contains(Regex("""^\d+$""")) -> item.get("end").asString.toIntOrNull()
                    else -> null
                }
            } catch (e: Exception) { null }

            if (startSeg != null && endSeg != null && startSeg in 1..segments.size && endSeg in startSeg..segments.size) {
                rawCandidates.add(CandidateRange(startSeg, endSeg, reason, textSummary))
            } else if (item.has("start") && item.has("end")) {
                val sSec = try { item.get("start").asDouble } catch (e: Exception) { 0.0 }
                val eSec = try { item.get("end").asDouble } catch (e: Exception) { 0.0 }
                // Snap timestamp detik ke nomor segmen ID
                val sSeg = segments.firstOrNull { it.end >= sSec }?.id ?: 1
                val eSeg = segments.lastOrNull { it.start <= eSec }?.id ?: segments.size
                if (eSeg >= sSeg) {
                    rawCandidates.add(CandidateRange(sSeg, eSeg, reason, textSummary))
                }
            }
        }

        val result = mutableListOf<PlannedClip>()
        for (cand in rawCandidates) {
            // 1. Linguistic Snapping: Periksa apakah kalimat menggantung di segmen akhir
            val (snappedStart, snappedEnd) = snapToCompleteThought(cand.startSeg, cand.endSeg, segments)

            // Ambil seluruh segmen ucapan valid dalam rentang ini
            val candSegs = (snappedStart..snappedEnd)
                .mapNotNull { byId[it] }
                .filter { isMeaningfulSegment(it) }

            if (candSegs.isEmpty()) continue

            // 2. Kumpulkan seluruh kata individual (Word-Level Timestamps) dalam rentang kandidat
            val candWords = candSegs.flatMap { it.words }.sortedBy { it.start }

            // 3. DEAD-AIR & SILENCE SLICER TINGKAT KATA (WORD-LEVEL):
            // Memotong jeda hening / diam / main HP dengan batas ambang ketat (0.70 detik)
            if (candWords.isNotEmpty()) {
                val wordChunks = mutableListOf<MutableList<WordTimestamp>>()
                var currentChunk = mutableListOf<WordTimestamp>()

                for (word in candWords) {
                    if (currentChunk.isEmpty()) {
                        currentChunk.add(word)
                    } else {
                        val prevWord = currentChunk.last()
                        val pauseDuration = word.start - prevWord.end
                        // Jika jeda antar kata >= 0.70 detik -> host terdiam, menatap HP, atau jeda kosong
                        if (pauseDuration >= 0.70) {
                            wordChunks.add(currentChunk)
                            currentChunk = mutableListOf(word)
                        } else {
                            currentChunk.add(word)
                        }
                    }
                }
                if (currentChunk.isNotEmpty()) {
                    wordChunks.add(currentChunk)
                }

                for (chunk in wordChunks) {
                    // Buang potongan kata tunggal yang hanya filler kosong saat melamun/main HP ("eh", "um", "ah")
                    if (chunk.size == 1 && isIsolatedFiller(chunk.first().word)) {
                        continue
                    }

                    val firstWord = chunk.first()
                    val lastWord = chunk.last()

                    // Padding audio presisi (-0.06s di awal agar konsonan jelas, +0.18s di akhir agar vokal tidak tersendat)
                    val paddedStart = Math.max(0.0, firstWord.start - 0.06)
                    val paddedEnd = Math.min(totalDuration, lastWord.end + 0.18)
                    val dur = paddedEnd - paddedStart

                    // Minimal 0.60 detik agar potongan video stabil
                    if (dur >= 0.60) {
                        val chunkText = chunk.joinToString(" ") { it.word }
                        result.add(
                            PlannedClip(
                                clipId = result.size + 1,
                                start = Math.round(paddedStart * 1000.0) / 1000.0,
                                end = Math.round(paddedEnd * 1000.0) / 1000.0,
                                duration = Math.round(dur * 100.0) / 100.0,
                                reason = cand.reason,
                                text = chunkText,
                                words = chunk
                            )
                        )
                    }
                }
            } else {
                // Fallback: Segment-level chunking jika data kata tidak tersedia
                val segChunks = mutableListOf<MutableList<TranscriptSegment>>()
                var currentSegChunk = mutableListOf<TranscriptSegment>()

                for (seg in candSegs) {
                    if (currentSegChunk.isEmpty()) {
                        currentSegChunk.add(seg)
                    } else {
                        val prevSeg = currentSegChunk.last()
                        val pause = seg.start - prevSeg.end
                        if (pause >= 0.70) {
                            segChunks.add(currentSegChunk)
                            currentSegChunk = mutableListOf(seg)
                        } else {
                            currentSegChunk.add(seg)
                        }
                    }
                }
                if (currentSegChunk.isNotEmpty()) {
                    segChunks.add(currentSegChunk)
                }

                for (chunk in segChunks) {
                    val segStart = chunk.first()
                    val segEnd = chunk.last()
                    val paddedStart = Math.max(0.0, segStart.start - 0.06)
                    val paddedEnd = Math.min(totalDuration, segEnd.end + 0.20)
                    val dur = paddedEnd - paddedStart

                    if (dur >= 0.60) {
                        val chunkText = chunk.joinToString(" ") { it.text }
                        result.add(
                            PlannedClip(
                                clipId = result.size + 1,
                                start = Math.round(paddedStart * 1000.0) / 1000.0,
                                end = Math.round(paddedEnd * 1000.0) / 1000.0,
                                duration = Math.round(dur * 100.0) / 100.0,
                                reason = cand.reason,
                                text = chunkText,
                                words = chunk.flatMap { it.words }
                            )
                        )
                    }
                }
            }
        }

        // 4. Smart Merge: Satukan klip yang sangat rapat (< 0.10s) saja, jeda diam tetap terpotong
        return mergeCloseClips(result)
    }

    private fun isIsolatedFiller(word: String): Boolean {
        val w = word.trim().lowercase().replace(Regex("""[^\p{L}]"""), "")
        return w in setOf("eh", "um", "uh", "ah", "hmm", "eng", "ya", "sih", "kan")
    }

    private fun isMeaningfulSegment(seg: TranscriptSegment): Boolean {
        val clean = seg.text
            .replace(Regex("""\[.*?\]|\(.*?\)|[.,!?\s]"""), "")
            .trim()
        return clean.isNotBlank()
    }

    private fun snapToCompleteThought(
        startSegId: Int,
        endSegId: Int,
        segments: List<TranscriptSegment>,
        maxExtensions: Int = 2
    ): Pair<Int, Int> {
        val byId = segments.associateBy { it.id }
        var currEnd = endSegId
        var extensions = 0
        while (extensions < maxExtensions && currEnd < segments.size) {
            val currSeg = byId[currEnd] ?: break
            val nextSeg = byId[currEnd + 1] ?: break
            val currText = currSeg.text.trim()
            val nextText = nextSeg.text.trim().lowercase()

            val isIncomplete = isTextIncomplete(currText)
            val isTightGap = (nextSeg.start - currSeg.end) < 0.75 &&
                    !currText.endsWith(".") && !currText.endsWith("!") && !currText.endsWith("?")

            if (isIncomplete || isTightGap) {
                val forbidden = forbiddenPatterns.any { it.containsMatchIn(nextText) }
                if (!forbidden) {
                    currEnd += 1
                    extensions += 1
                } else {
                    break
                }
            } else {
                break
            }
        }
        return Pair(startSegId, currEnd)
    }

    private fun isTextIncomplete(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return true
        if (t.endsWith(".") || t.endsWith("!") || t.endsWith("?")) return false
        val words = t.split(Regex("""\s+""")).map { it.replace(Regex("""[^a-zA-Z0-9]"""), "") }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val lastWord = words.last()
        if (trailingConnectors.contains(lastWord)) return true
        if (words.size >= 2) {
            val twoWords = "${words[words.size - 2]} $lastWord"
            if (trailingConnectors.contains(twoWords)) return true
        }
        return false
    }

    private fun mergeCloseClips(clips: List<PlannedClip>): List<PlannedClip> {
        if (clips.size <= 1) return clips
        val sorted = clips.sortedBy { it.start }
        val merged = mutableListOf<PlannedClip>()
        var current = sorted[0]

        for (i in 1 until sorted.size) {
            val next = sorted[i]
            // Jika overlapping atau jeda antar klip sangat rapat (< 0.10 detik)
            if (next.start <= current.end + 0.10) {
                val newEnd = Math.max(current.end, next.end)
                current = current.copy(
                    end = newEnd,
                    duration = Math.round((newEnd - current.start) * 100.0) / 100.0,
                    text = "${current.text} ${next.text}".trim(),
                    words = current.words + next.words
                )
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    private fun isPrecedingPriceFiller(word: String): Boolean {
        val clean = word.lowercase().trim().replace(Regex("""[^\p{L}\p{N}]"""), "")
        return clean in setOf(
            "cuma", "hanya", "cuman", "seharga", "bayarnya", "kena",
            "dapet", "dapat", "co", "checkout", "ambil", "ditebus", "tebus", "di",
            "sistem", "ikut", "ikutan", "masih", "barang", "lagi", "ini", "itu"
        )
    }

    private fun isTrailingPriceFiller(word: String): Boolean {
        val clean = word.lowercase().trim().replace(Regex("""[^\p{L}\p{N}]"""), "")
        return clean in setOf(
            "aja", "saja", "kak", "kakak", "ya", "bun", "bunda",
            "sis", "say", "sayang", "deh", "nih", "tuh", "dong", "koq", "kok",
            "gan", "bro", "sayangku", "bundaku", "dulu", "minggu", "hari"
        )
    }

    /**
     * Saring dan potong kata-kata harga / komisi / promo / overclaim secara presisi (Word-Level Excision)
     * Menggunakan timestamp tingkat kata dari Groq Whisper. Jika host menjelaskan produk lalu
     * menyebut harga di tengah atau akhir (misal: "Bahan katun adem banget ya kak, seratus dua puluh ribu aja kak"),
     * fungsi ini membuang HANYA frasa harga dan kata filler-nya ("seratus dua puluh ribu aja kak"),
     * dan menyisakan penjelasan produk ("Bahan katun adem banget ya kak").
     */
    private fun sanitizeClipWords(
        clip: PlannedClip,
        allSegments: List<TranscriptSegment>
    ): List<PlannedClip> {
        // Ambil data kata dari klip atau fallback dari segmen transkrip
        val words = if (clip.words.isNotEmpty()) {
            clip.words
        } else {
            allSegments
                .filter { it.end > clip.start && it.start < clip.end }
                .flatMap { it.words }
                .filter { it.start >= clip.start - 0.20 && it.end <= clip.end + 0.20 }
        }.sortedBy { it.start }

        // Jika tidak ada data kata, lakukan filter di tingkat teks klip / segmen
        if (words.isEmpty()) {
            val overlappingText = allSegments
                .filter { it.end > clip.start && it.start < clip.end }
                .joinToString(" ") { it.text } + " " + clip.text
            val isForbidden = forbiddenPatterns.any { it.containsMatchIn(overlappingText) }
            return if (isForbidden) emptyList() else listOf(clip)
        }

        // Petakan setiap kata ke rentang karakter pada teks gabungan
        data class IndexedWord(val wordTs: WordTimestamp, val charStart: Int, val charEnd: Int, val index: Int)
        val indexedWords = mutableListOf<IndexedWord>()
        val fullTextSb = StringBuilder()

        for (i in words.indices) {
            val w = words[i]
            val wText = w.word.trim()
            if (i > 0) fullTextSb.append(" ")
            val startChar = fullTextSb.length
            fullTextSb.append(wText)
            val endChar = fullTextSb.length
            indexedWords.add(IndexedWord(w, startChar, endChar, i))
        }
        val fullText = fullTextSb.toString()

        // Cari semua kemunculan forbiddenPatterns pada teks gabungan
        val forbiddenWordIndices = mutableSetOf<Int>()
        for (pattern in forbiddenPatterns) {
            var match = pattern.find(fullText)
            while (match != null) {
                val mRange = match.range
                for (iw in indexedWords) {
                    if (iw.charStart < mRange.last + 1 && iw.charEnd > mRange.first) {
                        forbiddenWordIndices.add(iw.index)
                    }
                }
                match = match.next()
            }
        }

        // Jika klip ini sama sekali tidak mengandung frasa terlarang, kembalikan klip utuh
        if (forbiddenWordIndices.isEmpty()) {
            return listOf(clip)
        }

        // Perluas pemotongan ke kata-kata filler di sekitar harga (misal: "cuma", "hanya", "aja", "kak", "ya", "deh")
        val expandedForbidden = forbiddenWordIndices.toMutableSet()
        for (idx in forbiddenWordIndices) {
            var prevIdx = idx - 1
            while (prevIdx >= 0 && isPrecedingPriceFiller(words[prevIdx].word)) {
                expandedForbidden.add(prevIdx)
                prevIdx--
            }
            var nextIdx = idx + 1
            while (nextIdx < words.size && isTrailingPriceFiller(words[nextIdx].word)) {
                expandedForbidden.add(nextIdx)
                nextIdx++
            }
        }

        // Kelompokkan kata-kata bersih yang tersisa menjadi potongan klip valid
        val cleanWordRuns = mutableListOf<List<WordTimestamp>>()
        var currentRun = mutableListOf<WordTimestamp>()

        for (i in words.indices) {
            if (i !in expandedForbidden) {
                currentRun.add(words[i])
            } else {
                if (currentRun.isNotEmpty()) {
                    cleanWordRuns.add(currentRun)
                    currentRun = mutableListOf()
                }
            }
        }
        if (currentRun.isNotEmpty()) {
            cleanWordRuns.add(currentRun)
        }

        val resultClips = mutableListOf<PlannedClip>()
        for (run in cleanWordRuns) {
            if (run.isEmpty()) continue
            val firstWord = run.first()
            val lastWord = run.last()
            val rawDur = lastWord.end - firstWord.start

            // Minimal 0.70 detik & minimal 2 kata agar tidak menghasilkan potongan glitch/patah
            if (rawDur < 0.70 && run.size < 2) continue

            // Audio padding aman (tidak boleh tumpang tindih dengan kata harga yang dibuang)
            val firstIdx = words.indexOf(firstWord)
            val lastIdx = words.indexOf(lastWord)

            val minBound = if (firstIdx > 0 && (firstIdx - 1) in expandedForbidden) {
                words[firstIdx - 1].end + 0.04
            } else {
                Math.max(0.0, firstWord.start - 0.05)
            }

            val maxBound = if (lastIdx < words.size - 1 && (lastIdx + 1) in expandedForbidden) {
                words[lastIdx + 1].start - 0.04
            } else {
                lastWord.end + 0.12
            }

            val safeStart = Math.min(firstWord.start, Math.max(minBound, firstWord.start - 0.05))
            val safeEnd = Math.max(lastWord.end, Math.min(maxBound, lastWord.end + 0.12))

            if (safeEnd - safeStart < 0.60) continue

            val runText = run.joinToString(" ") { it.word.trim() }

            // Verifikasi ganda: pastikan teks hasil pemotongan benar-benar bebas larangan
            if (forbiddenPatterns.none { it.containsMatchIn(runText) }) {
                resultClips.add(
                    PlannedClip(
                        clipId = clip.clipId,
                        start = Math.round(safeStart * 1000.0) / 1000.0,
                        end = Math.round(safeEnd * 1000.0) / 1000.0,
                        duration = Math.round((safeEnd - safeStart) * 100.0) / 100.0,
                        reason = clip.reason,
                        text = runText,
                        words = run
                    )
                )
            }
        }

        return resultClips
    }

    private fun filterForbiddenClips(
        clips: List<PlannedClip>,
        segments: List<TranscriptSegment>
    ): List<PlannedClip> {
        val sanitized = mutableListOf<PlannedClip>()
        for (clip in clips) {
            val cleanParts = sanitizeClipWords(clip, segments)
            for (part in cleanParts) {
                if (forbiddenPatterns.none { it.containsMatchIn(part.text) }) {
                    sanitized.add(part)
                }
            }
        }
        return sanitized.mapIndexed { idx, c -> c.copy(clipId = idx + 1) }
    }

    private fun buildFallbackClipsFromSegments(
        safeSegments: List<TranscriptSegment>,
        maxDurationSec: Double,
        existingClips: List<PlannedClip> = emptyList()
    ): List<PlannedClip> {
        val resultClips = existingClips.toMutableList()
        var accumulated = resultClips.sumOf { it.end - it.start }

        for (seg in safeSegments) {
            if (accumulated >= maxDurationSec - 3.0) break
            if (forbiddenPatterns.any { it.containsMatchIn(seg.text) }) continue
            val dur = seg.end - seg.start
            if (dur < 0.8) continue

            // Pastikan segmen tidak tumpang tindih dengan klip yang sudah ada
            val overlaps = resultClips.any { c ->
                val overlapStart = Math.max(seg.start, c.start)
                val overlapEnd = Math.min(seg.end, c.end)
                overlapEnd - overlapStart > 0.1
            }
            if (overlaps) continue

            if (accumulated + dur <= maxDurationSec) {
                resultClips.add(
                    PlannedClip(
                        clipId = resultClips.size + 1,
                        start = Math.round(seg.start * 1000.0) / 1000.0,
                        end = Math.round(seg.end * 1000.0) / 1000.0,
                        duration = Math.round(dur * 100.0) / 100.0,
                        reason = if (existingClips.isEmpty()) "konten aman fallback" else "konten aman pelengkap",
                        text = seg.text,
                        words = seg.words
                    )
                )
                accumulated += dur
            }
        }
        return resultClips.sortedBy { it.start }.mapIndexed { idx, c -> c.copy(clipId = idx + 1) }
    }

    // --- 3. AUTO CAPTION & HASHTAGS GENERATION VIA GROQ AI ---
    suspend fun generateTikTokCaption(
        clips: List<PlannedClip>,
        segments: List<TranscriptSegment>,
        onProgress: ((Int, String) -> Unit)? = null
    ): AutoCaptionResult = withContext(Dispatchers.IO) {
        val groqKey = settingsManager.getActiveKey("groq") ?: return@withContext AutoCaptionResult(
            caption = "Produk rekomendasi terbaik buat kamu! Cek detail dan pilihannya sekarang ya.",
            hashtags = listOf("#racuntiktok", "#fyp", "#tiktokshop", "#affiliate", "#rekomendasi"),
            formattedText = "Produk rekomendasi terbaik buat kamu! Cek detail dan pilihannya sekarang ya.\n.\n#racuntiktok #fyp #tiktokshop #affiliate #rekomendasi"
        )

        onProgress?.invoke(72, "Menyusun auto caption & hashtag TikTok dengan Groq AI...")

        // Ambil konteks ucapan dari klip terpilih (atau cuplikan transkrip utama)
        val contextText = if (clips.isNotEmpty()) {
            clips.joinToString(" ") { it.text }.take(1500)
        } else {
            segments.joinToString(" ") { it.text }.take(1500)
        }

        val systemPrompt = """
            Anda adalah TikTok Marketing & Affiliate Specialist profesional.
            TUGAS UTAMA:
            1. Analisis transkrip ucapan video promosi produk berikut.
            2. Buat CAPTION TikTok yang SINGKAT, padat, dan memikat (maksimal 1-2 kalimat saja, jangan terlalu panjang, gaya bahasa kasual Indonesia kekinian yang menjual dan persuasif mengajak penonton cek keranjang kuning tanpa overclaim, tanpa menyebut harga, dan tanpa menyebut estimasi pengiriman).
            3. Buat MAKSIMAL 5 HASHTAG (#) yang paling relevan dengan produk, fungsi/kategori produk, dan tren TikTok Affiliate (contoh: #racuntiktok, nama produk, kategori, #affiliate, #fyp). Jangan lebih dari 5 hashtag.

            FORMAT OUTPUT (WAJIB JSON VALID TANPA MARKDOWN):
            {
              "caption": "Teks caption singkat yang memikat penonton...",
              "hashtags": ["#tag1", "#tag2", "#tag3", "#tag4", "#tag5"]
            }
        """.trimIndent()

        val models = getAvailableGroqModels(groqKey.key)
        for (model in models) {
            try {
                val payload = JsonObject().apply {
                    addProperty("model", model)
                    addProperty("temperature", 0.4)
                    add("response_format", JsonObject().apply {
                        addProperty("type", "json_object")
                    })
                    val messages = com.google.gson.JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("role", "system")
                            addProperty("content", "$systemPrompt\nIMPORTANT: Return valid JSON object with 'caption' and 'hashtags' keys only.")
                        })
                        add(JsonObject().apply {
                            addProperty("role", "user")
                            addProperty("content", "Transkrip ucapan:\n$contextText")
                        })
                    }
                    add("messages", messages)
                }

                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .header("Authorization", "Bearer ${groqKey.key}")
                    .header("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = JsonParser.parseString(body).asJsonObject
                        val content = json.getAsJsonArray("choices")[0].asJsonObject
                            .getAsJsonObject("message").get("content").asString
                        val parsed = JsonParser.parseString(content).asJsonObject
                        val caption = parsed.get("caption")?.asString?.trim() ?: ""
                        val tagsArr = parsed.getAsJsonArray("hashtags")
                        val tagsList = mutableListOf<String>()
                        if (tagsArr != null) {
                            for (i in 0 until Math.min(5, tagsArr.size())) {
                                var tag = tagsArr[i].asString.trim()
                                if (!tag.startsWith("#")) tag = "#$tag"
                                if (tag.length > 1 && !tagsList.contains(tag)) {
                                    tagsList.add(tag)
                                }
                            }
                        }

                        val cleanTags = if (tagsList.isNotEmpty()) tagsList.take(5) else listOf("#racuntiktok", "#fyp", "#tiktokshop", "#affiliate", "#rekomendasi")
                        val cleanCaption = if (caption.isNotBlank()) caption else "Rekomendasi produk terbaik buat kamu! Cek detail dan pilihannya di keranjang kuning ya."

                        // Format rapi dengan pemisah estetik baris titik khas TikTok
                        val formatted = "$cleanCaption\n.\n${cleanTags.joinToString(" ")}"

                        return@withContext AutoCaptionResult(
                            hookHeadline = "",
                            caption = cleanCaption,
                            hashtags = cleanTags,
                            formattedText = formatted
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AiEngine", "Gagal generate caption dengan $model: ${e.message}")
            }
        }

        // Fallback default jika terjadi kendala jaringan/API
        val defaultCap = "Rekomendasi produk terbaik buat kamu! Cek detail dan pilihannya sekarang ya."
        val defaultTags = listOf("#racuntiktok", "#fyp", "#tiktokshop", "#affiliate", "#rekomendasi")
        return@withContext AutoCaptionResult(
            hookHeadline = "",
            caption = defaultCap,
            hashtags = defaultTags,
            formattedText = "$defaultCap\n.\n${defaultTags.joinToString(" ")}"
        )
    }
}
