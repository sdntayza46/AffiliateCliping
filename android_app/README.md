# Auto Clip Studio — Native Android App (Kotlin + FFmpegKit)
**dantez x mamank recing — Native Video Engine**

Aplikasi Android mandiri (*standalone APK*) yang menjalankan seluruh proses pemotongan dan rendering video **100% lokal di HP Anda (Zero-Upload)** tanpa memerlukan server luar atau VPS.

---

## 🚀 Keunggulan Sistem Native di HP
1. **Zero-Upload (Hemat Kuota & Waktu)**:
   - File video rekaman layar (meski 500 MB – 2 GB) tidak pernah di-upload ke server luar.
   - Yang dikirim ke internet hanyalah audio MP3 kecil (2–4 MB) ke Groq Whisper untuk transkripsi akurat.
2. **Rendering Native FFmpegKit**:
   - Pemotongan video, crop rasio vertikal 9:16 (TikTok/Reels/Shorts), dan rendering teks watermark dilakukan langsung oleh CPU/GPU HP menggunakan engine C++ native `ffmpeg-kit-full`.
3. **Watermark Tersimpan Permanen**:
   - Watermark disimpan di memori HP (`SharedPreferences`).
   - Otomatis dipakai di semua proses video berikutnya hingga Anda memilih untuk mengganti atau menghapusnya.
4. **1 Key Groq All-in-One AI**:
   - Transkripsi suara super cepat dengan **Groq Whisper** (`whisper-large-v3-turbo`).
   - Kurasi klip patuh aturan TikTok Affiliate dengan **Groq LLaMA 3.3**.
   - **Auto Caption & 5 Hashtags TikTok**: AI otomatis membuatkan caption singkat yang memikat dan 5 hashtag relevan dengan tombol salin satu-klik (Copy to Clipboard).
5. **UI Interaktif & Ringan**:
   - Live Preview 9:16 dengan slider zoom & vertical offset (buang status bar HP).
   - Console logs *collapsible* (default minimize dengan panah expand/collapse).
   - Auto-scroll otomatis ke progress saat proses video dimulai.

---

## 📁 Struktur Direktori Project
```
android_app/
├── app/
│   ├── build.gradle.kts                # Konfigurasi dependensi (FFmpegKit, OkHttp, Gson)
│   ├── src/main/
│   │   ├── AndroidManifest.xml         # Izin storage, internet, FileProvider
│   │   ├── java/com/dantez/autoclip/
│   │   │   ├── data/
│   │   │   │   ├── Models.kt           # Data classes (PlannedClip, TranscriptSegment, dll.)
│   │   │   │   └── SettingsManager.kt  # Penyimpanan watermark permanen & multi-key AI
│   │   │   ├── ai/
│   │   │   │   └── AiEngine.kt         # Integrasi Verboo, Groq Whisper, & Qwen 3.8 Vision
│   │   │   ├── video/
│   │   │   │   └── FFmpegEngine.kt     # Ekstraksi audio, keyframes & render 9:16 vertical
│   │   │   └── ui/
│   │   │       └── MainActivity.kt     # Logika UI studio, progress bar & live logs
│   │   └── res/
│   │       ├── layout/activity_main.xml# Tampilan studio gelap modern
│   │       ├── layout/dialog_keys.xml  # Modal kelola API Keys
│   │       └── values/                 # Colors, strings, themes
├── build.gradle.kts                    # Konfigurasi Gradle root
├── settings.gradle.kts                 # Pengaturan modul Gradle
└── README.md                           # Dokumentasi panduan
```

---

## 🛠️ Cara Membuka & Menghasilkan File APK

### Langkah 1: Buka di Android Studio
1. Buka software **Android Studio**.
2. Pilih menu **File > Open...**.
3. Arahkan ke folder:
   ```
   D:\Downloads\moonray\BOT\bot kliping\android_app
   ```
4. Tunggu beberapa saat hingga Android Studio selesai melakukan **Gradle Sync** dan mengunduh dependensi (termasuk FFmpegKit).

### Langkah 2: Build File APK
1. Di menu atas Android Studio, klik **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
2. Android Studio akan mengompilasi kode dan menghasilkan file APK.
3. Setelah selesai, klik notifikasi **locate** di pojok kanan bawah.
4. File APK siap pakai berada di:
   ```
   android_app/app/build/outputs/apk/debug/app-debug.apk
   ```

### Langkah 3: Install ke HP Android
* **Cara A (Paling Mudah)**: Kirim file `app-debug.apk` ke HP Anda melalui WhatsApp, Telegram, Google Drive, atau kabel USB, lalu buka dan klik **Install**.
* **Cara B (Developer / ADB)**: Sambungkan HP dengan kabel data (USB Debugging aktif), lalu jalankan:
  ```bash
  adb install app-debug.apk
  ```

---

## 🔄 Aturan Sinkronisasi Kode (Web vs Android)
Sesuai arahan, jika ada perubahan logika atau fitur baru:
- **Folder Web Bot (`bot kliping/`)**: Diperbarui pada `core/processor.py`, `app.py`, dan `static/app.js`.
- **Folder Android (`android_app/`)**: Diperbarui pada `AiEngine.kt`, `FFmpegEngine.kt`, dan `MainActivity.kt`.
Kedua folder selalu berjalan selaras dengan fitur yang identik.
