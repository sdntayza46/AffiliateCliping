import os
import json
import re
import shutil
import subprocess
import time
import uuid
import base64
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Callable, Optional, Dict, Any, List

from core.key_manager import (
    get_active_keys, mark_key_exhausted, record_key_success
)
from core.ffmpeg_utils import get_ffmpeg_paths

OUT_W = 720
OUT_H = 1280
FPS = 30
TARGET_DURATION = 50.0
MAX_DURATION = 60.0
CHUNK_SECONDS = 300
MAX_UPLOAD_MB = 24.5

RESOLUTIONS = {
    "720p": {"w": 720, "h": 1280, "label": "720p (720x1280)", "crf": "22"},
    "1080p": {"w": 1080, "h": 1920, "label": "1080p (1080x1920)", "crf": "23"},
}

DURATION_PRESETS = {
    "30-60": {"min": 30.0, "target": 50.0, "max": 60.0, "label": "30 detik – 1 menit (30–60s)"},
    "60-90": {"min": 60.0, "target": 75.0, "max": 90.0, "label": "1 menit – 1:30 menit (60–90s)"},
    "90-120": {"min": 90.0, "target": 105.0, "max": 120.0, "label": "1:30 menit – 2 menit (90–120s)"},
}

VERBOO_BASE_URL = "https://code.verboo.ai/router/v1"
VERBOO_MODELS = [
    "deepseek-v4.1-flash",
    "qwen3.8-27b",
    "glm-5.3-flash",
    "mimo-v2.5",
]

# Model khusus untuk vision (Qwen 3.8 jauh lebih cepat & akurat untuk visualisasi gambar)
VERBOO_VISION_MODELS = [
    "qwen3.8-27b",
    "deepseek-v4.1-flash",
]

def call_verboo_chat(messages: list, model: str, api_key: str, temperature: float = 0.2, timeout: int = 45) -> dict:
    url = f"{VERBOO_BASE_URL}/chat/completions"
    payload = {
        "model": model,
        "messages": messages,
        "temperature": temperature
    }
    req = urllib.request.Request(
        url,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json"
        },
        data=json.dumps(payload).encode("utf-8")
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = json.loads(resp.read().decode("utf-8"))
        raw_text = data["choices"][0]["message"]["content"]
        t = (raw_text or "").strip()
        if t.startswith("```"):
            t = t.split("\n", 1)[1]
            if t.endswith("```"):
                t = t[:-3]
        return json.loads(t)

GEMINI_MODELS = [
    "gemini-3.5-flash",
    "gemini-3.5-flash-lite",
    "gemini-3-flash-preview",
    "gemini-flash-latest",
    "gemini-2.5-flash",
    "gemini-2.0-flash",
    "gemini-1.5-flash",
]

TRAILING_CONNECTORS = {
    "dan", "terus", "karena", "soalnya", "jadinya", "jadi", "nah", "tapi",
    "untuk", "bisa", "yang", "juga", "atau", "ataupun", "kalau", "kalo", "pas",
    "kan", "sih", "nih", "tuh", "ya", "buat", "seperti", "kayak", "masih", "lagi",
    "sambil", "supaya", "biar", "sehingga", "maupun", "yaitu", "yakni", "ini", "itu",
    "terus juga", "nah jadi", "nah ini", "ini tuh", "ya guys", "ini ya", "gitu", "nah gitu",
    "warna", "ukuran", "bahan", "model", "tipe", "contohnya", "misalnya", "dengan", "ke", "di",
    "pada", "oleh", "tentang", "maka", "lalu", "kemudian", "bahkan", "ada", "mau", "akan", "udah"
}

def run_cmd(cmd: list, check=True) -> subprocess.CompletedProcess:
    cmd = [str(c) for c in cmd]
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=False)
    if check and res.returncode != 0:
        err_msg = (res.stderr or res.stdout or "Unknown ffmpeg error").strip()
        raise RuntimeError(f"FFmpeg error ({res.returncode}): {err_msg[-1500:]}")
    return res

def probe_video(video_path: Path) -> dict:
    paths = get_ffmpeg_paths()
    ffprobe = paths["ffprobe"]
    cmd = [
        ffprobe, "-v", "error", "-show_format", "-show_streams",
        "-of", "json", str(video_path)
    ]
    res = run_cmd(cmd)
    return json.loads(res.stdout)

def get_video_duration(video_path: Path) -> float:
    data = probe_video(video_path)
    return float(data.get("format", {}).get("duration", 0) or 0)

def find_font_file() -> Optional[Path]:
    candidates = [
        Path("C:/Windows/Fonts/arial.ttf"),
        Path("C:/Windows/Fonts/segoeui.ttf"),
        Path("/system/fonts/Roboto-Regular.ttf"),
        Path("/system/fonts/DroidSans.ttf"),
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
    ]
    for c in candidates:
        if c.exists():
            return c
    return None

def crop_filter(target_w: int = 720, target_h: int = 1280, watermark_textfile: Optional[Path] = None, zoom: float = 1.0, y_offset: float = 0.0):
    # Validasi parameter zoom & vertical offset (maksimal 130%)
    zoom = max(1.0, min(1.30, float(zoom or 1.0)))
    y_norm = max(-0.20, min(0.20, float(y_offset or 0.0)))
    y_expr = f"max(0,min(ih-oh,(ih-oh)/2+({y_norm:.3f}*ih)))"

    # Center-crop to 9:16 dengan zoom & offset, lalu scale ke target_w:target_h
    vf = (
        f"crop=w='min(iw,ih*9/16)/{zoom:.3f}':h='min(ih,iw*16/9)/{zoom:.3f}':"
        f"x='(iw-ow)/2':y='{y_expr}',"
        f"scale={target_w}:{target_h},setsar=1,format=yuv420p"
    )
    if watermark_textfile and watermark_textfile.exists():
        font = find_font_file()
        font_opt = ""
        if font:
            font_escaped = str(font).replace("\\", "/").replace(":", r"\:")
            font_opt = f"fontfile='{font_escaped}':"
        file_escaped = str(watermark_textfile).replace("\\", "/").replace(":", r"\:")
        # Text kecil (fontsize=24 untuk 720p, fontsize=36 untuk 1080p), posisi di tengah, opacity 18%
        font_sz = int(round(24 * (target_w / 720)))
        vf += f",drawtext={font_opt}textfile='{file_escaped}':fontsize={font_sz}:fontcolor=white@0.18:x=(w-text_w)/2:y=(h-text_h)/2"
    return vf

def crop_filter_720p(watermark_textfile: Optional[Path] = None, zoom: float = 1.0, y_offset: float = 0.0):
    return crop_filter(720, 1280, watermark_textfile=watermark_textfile, zoom=zoom, y_offset=y_offset)

def extract_audio(video_path: Path, output_mp3: Path, log_cb: Callable):
    log_cb("AUDIO_EXTRACT", 5, "Mengekstrak audio dari video (MP3 16kHz)...")
    output_mp3.parent.mkdir(parents=True, exist_ok=True)
    paths = get_ffmpeg_paths()
    ffmpeg = paths["ffmpeg"]
    
    cmd = [
        ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(video_path), "-map", "0:a:0", "-vn",
        "-af", "aresample=async=1:first_pts=0",
        "-ac", "1", "-ar", "16000",
        "-c:a", "libmp3lame", "-b:a", "32k",
        str(output_mp3)
    ]
    run_cmd(cmd)
    log_cb("AUDIO_EXTRACT", 15, f"Audio berhasil diekstrak ({output_mp3.stat().st_size / 1024 / 1024:.1f} MB)")

def transcribe_with_groq_fallback(audio_path: Path, work_dir: Path, log_cb: Callable) -> List[dict]:
    log_cb("GROQ_TRANSCRIBE", 18, "Membagi audio menjadi segmen 5 menit untuk Whisper...")
    parts_dir = work_dir / "audio_parts"
    if parts_dir.exists():
        shutil.rmtree(parts_dir)
    parts_dir.mkdir(parents=True, exist_ok=True)
    
    paths = get_ffmpeg_paths()
    ffmpeg = paths["ffmpeg"]
    
    run_cmd([
        ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(audio_path), "-f", "segment",
        "-segment_time", str(CHUNK_SECONDS),
        "-reset_timestamps", "1", "-c", "copy",
        str(parts_dir / "audio_%03d.mp3")
    ])
    
    parts = sorted(parts_dir.glob("audio_*.mp3"))
    if not parts:
        raise RuntimeError("Gagal membuat potongan audio.")

    all_segments = []
    offset = 0.0

    for idx, part in enumerate(parts, start=1):
        log_cb("GROQ_TRANSCRIBE", 20 + int((idx / len(parts)) * 25), 
               f"Mentranskrip bagian {idx}/{len(parts)} dengan Groq AI Whisper...")
        
        chunk_success = False
        while not chunk_success:
            active_keys = get_active_keys("groq")
            if not active_keys:
                env_key = os.environ.get("GROQ_API_KEY")
                if env_key:
                    active_keys = [{"key": env_key, "id": "env"}]
                else:
                    raise RuntimeError("Semua API Key Groq telah habis kuota atau belum dimasukkan!")

            current_item = active_keys[0]
            current_key = current_item["key"]
            masked = f"{current_key[:5]}...{current_key[-4:]}"

            try:
                from groq import Groq
                client = Groq(api_key=current_key)
                with part.open("rb") as f:
                    result = client.audio.transcriptions.create(
                        file=(part.name, f.read()),
                        model="whisper-large-v3-turbo",
                        language="id",
                        response_format="verbose_json",
                        timestamp_granularities=["segment"]
                    )
                
                segments = getattr(result, "segments", None) or []
                if isinstance(segments, str):
                    segments = json.loads(segments)
                for seg in segments:
                    if hasattr(seg, "model_dump"):
                        seg = seg.model_dump()
                    elif not isinstance(seg, dict):
                        seg = dict(seg)
                    start = float(seg.get("start", 0)) + offset
                    end = float(seg.get("end", 0)) + offset
                    text = str(seg.get("text", "")).strip()
                    if text and end > start:
                        all_segments.append({
                            "id": len(all_segments) + 1,
                            "start": round(start, 3),
                            "end": round(end, 3),
                            "text": text
                        })
                offset += get_video_duration(part)
                record_key_success("groq", current_key)
                chunk_success = True
            except Exception as exc:
                err_str = str(exc)
                log_cb("GROQ_TRANSCRIBE", 25, f"⚠️ Groq key ({masked}) error: {err_str[:120]}. Beralih ke key berikutnya...")
                mark_key_exhausted("groq", current_key, err_str)
                time.sleep(1)

    if not all_segments:
        raise RuntimeError("Transkripsi tidak menghasilkan segmen teks ucapan.")

    log_cb("GROQ_TRANSCRIBE", 45, f"Transkripsi selesai! Ditemukan {len(all_segments)} segmen ucapan.")
    return all_segments

def ask_ai_with_fallback(prompt: str, log_cb: Callable) -> dict:
    # 1. AI UTAMA: VERBOO (DeepSeek, Qwen, GLM, MiMo)
    verboo_keys = get_active_keys("verboo")
    for item in verboo_keys:
        current_key = item["key"]
        masked = f"{current_key[:5]}...{current_key[-4:]}"
        messages = [
            {"role": "system", "content": "You are a professional TikTok video editor. Output valid JSON only."},
            {"role": "user", "content": prompt}
        ]
        key_failed = False
        for model in VERBOO_MODELS:
            try:
                log_cb("AI_SELECT", 50, f"⚡ AI Utama (Verboo: {model}, Key: {masked}) menganalisis highlight...")
                data = call_verboo_chat(messages, model, current_key, temperature=0.2)
                if isinstance(data, dict):
                    record_key_success("verboo", current_key)
                    log_cb("AI_SELECT", 54, f"✅ Sukses! Highlight disusun oleh AI Utama Verboo ({model}).")
                    return data
            except Exception as exc:
                err_str = str(exc).lower()
                is_quota = any(x in err_str for x in ["quota", "resource_exhausted", "429", "rate limit", "unauthorized", "invalid_api_key", "exceeded", "balance", "forbidden"])
                if is_quota:
                    log_cb("AI_SELECT", 51, f"⚠️ Verboo key ({masked}) kuota habis / limit. Beralih ke AI cadangan...")
                    mark_key_exhausted("verboo", current_key, str(exc))
                    key_failed = True
                    break
                else:
                    continue
        if key_failed:
            time.sleep(1)
            continue

    # 2. AI CADANGAN: GOOGLE GEMINI
    gemini_keys = get_active_keys("gemini")
    if gemini_keys:
        log_cb("AI_SELECT", 52, "ℹ️ AI Utama (Verboo) tidak aktif / habis. Beralih ke AI Cadangan (Google Gemini)...")
        for item in gemini_keys:
            current_key = item["key"]
            masked = f"{current_key[:5]}...{current_key[-4:]}"
            from google import genai
            client = genai.Client(api_key=current_key)
            key_failed = False
            for model in GEMINI_MODELS:
                try:
                    log_cb("AI_SELECT", 53, f"Cadangan (Gemini: {model}, Key: {masked}) menganalisis highlight...")
                    response = client.models.generate_content(
                        model=model,
                        contents=prompt,
                        config={
                            "response_mime_type": "application/json",
                            "temperature": 0.2
                        }
                    )
                    text = (response.text or "").strip()
                    if text.startswith("```"):
                        text = text.split("\n", 1)[1]
                        if text.endswith("```"):
                            text = text[:-3]
                    data = json.loads(text)
                    if isinstance(data, dict):
                        record_key_success("gemini", current_key)
                        log_cb("AI_SELECT", 55, f"✅ Sukses! Highlight disusun oleh AI Cadangan Gemini ({model}).")
                        return data
                except Exception as exc:
                    err_str = str(exc).lower()
                    is_quota = any(x in err_str for x in ["quota", "resource_exhausted", "429", "rate limit", "key", "unauthorized"])
                    if is_quota:
                        log_cb("AI_SELECT", 53, f"⚠️ Gemini key ({masked}) kehabisan kuota / limit. Mengganti API key...")
                        mark_key_exhausted("gemini", current_key, str(exc))
                        key_failed = True
                        break
                    else:
                        continue
            if key_failed:
                time.sleep(1)
                continue

    # 3. AI CADANGAN TERAKHIR: GROQ (LLaMA)
    groq_keys = get_active_keys("groq")
    if groq_keys:
        log_cb("AI_SELECT", 54, "ℹ️ Mencoba AI Cadangan (Groq LLaMA)...")
        for item in groq_keys:
            current_key = item["key"]
            try:
                from groq import Groq
                client = Groq(api_key=current_key)
                chat_resp = client.chat.completions.create(
                    model="llama-3.3-70b-versatile",
                    messages=[
                        {"role": "system", "content": "You are a professional TikTok video editor. Output valid JSON only."},
                        {"role": "user", "content": prompt}
                    ],
                    response_format={"type": "json_object"},
                    temperature=0.2
                )
                text = (chat_resp.choices[0].message.content or "").strip()
                data = json.loads(text)
                if isinstance(data, dict):
                    record_key_success("groq", current_key)
                    log_cb("AI_SELECT", 55, "✅ Sukses! Highlight disusun oleh AI Cadangan Groq LLaMA.")
                    return data
            except Exception:
                continue

    raise RuntimeError("Semua AI (Verboo utama, Gemini cadangan, dan Groq) telah habis kuota atau belum dimasukkan!")

ask_gemini_with_fallback = ask_ai_with_fallback

def validate_plan(clips: list, by_id: dict, video_duration: float) -> list:
    used_ids = set()
    cleaned = []
    for clip in clips:
        try:
            first = int(clip["start_segment"])
            last = int(clip["end_segment"])
        except Exception:
            continue
        if first > last or first not in by_id or last not in by_id:
            continue
        ids = set(range(first, last + 1))
        if ids & used_ids or any(i not in by_id for i in ids):
            continue
        start = float(by_id[first]["start"])
        end = float(by_id[last]["end"])
        if start < 0 or end <= start or end > video_duration + 1.0:
            continue

        spoken_text = " ".join(str(by_id[i].get("text", "")) for i in sorted(ids))
        forbidden_patterns = [
            (r"\betalase\b", "etalase"),
            (r"\b(?:harga|harganya|seharga|nominal|rupiah|rp\.?|idr|ribu|murah|bayar)\b", "harga/nominal"),
            (r"\b(?:ongkir|ongkos kirim|biaya pengiriman|gratis ongkir)\b", "ongkir"),
            (r"\b(?:diskon|promo|promosi|cashback|voucher|kupon|potongan harga|flash sale|sale|deal)\b", "promo/diskon"),
            (r"\b(?:hemat\s+\d+|cuma\s+\d+|hanya\s+\d+|mulai\s+dari\s+\d+)\b", "penawaran"),
            (r"\b(?:pasti sampai|dijamin sampai|1 hari sampai|garansi sampai|langsung sampai besok|sehari sampai)\b", "overclaim logistik"),
            (r"\b(?:pasti bikin|langsung bikin kurus|bikin langsing|menutupi semua kekurangan|bikin badan ideal|hasil instan|pasti nyaman seharian)\b", "janji hasil/overclaim"),
            (r"\b(?:langsung putih|bikin glowing instan|pasti putih|menghilangkan jerawat seketika|sembuh total|pasti manjur)\b", "overclaim kecantikan/kesehatan"),
            (r"\b(?:pasti muat semua|cocok untuk semua tinggi|dijamin nggak kekecilan|pasti pas|baterai tahan selamanya|nggak perlu lihat size)\b", "klaim ukuran/spek menyesatkan"),
            (r"\b(?:nggak ada tandingannya|nomor satu|terbaik sedunia|semua orang cocok)\b", "klaim berlebihan"),
            (r"\b(?:direkomendasikan dokter|dipakai artis)\b", "klaim otoritas"),
            (r"\b(?:baru masuk|masuk live|penonton live|komen ya|komentar ya|tunggu aku ambil|aku ambilin|sudah di-?pin|udah di-?pin|jangan pergi dulu|tap-?tap layar|tap layar|tap-?tap|moderator|bantu tap|bantu like|share live|selama live ini|checkout saat live)\b", "ucapan live streaming"),
            (r"\b(?:cuma hari ini|stok tinggal|buruan sebelum|kalau nggak beli pasti rugi|jangan sampai menyesal|wajib beli sekarang|kalau kehabisan salah sendiri|nggak beli berarti rugi)\b", "urgensi palsu/fear-based selling"),
        ]
        rejected = next((reason for pattern, reason in forbidden_patterns if re.search(pattern, spoken_text, re.IGNORECASE)), None)
        if rejected:
            continue

        used_ids.update(ids)
        cleaned.append({
            "start_segment": first,
            "end_segment": last,
            "start": round(start, 3),
            "end": round(end, 3),
            "reason": str(clip.get("reason", ""))[:100]
        })
    cleaned.sort(key=lambda x: x["start"])
    return cleaned

def is_text_incomplete(text: str) -> bool:
    t = text.strip().lower()
    if not t:
        return True
    if t[-1] in ".!?":
        return False
    words = re.findall(r"\b\w+\b", t)
    if not words:
        return True
    last_word = words[-1]
    if last_word in TRAILING_CONNECTORS:
        return True
    if len(words) >= 2 and f"{words[-2]} {words[-1]}" in TRAILING_CONNECTORS:
        return True
    return False

def snap_to_complete_thought(clips: list, by_id: dict, video_duration: float, max_extension_segments: int = 2) -> list:
    total_segments = len(by_id)
    refined = []
    
    for clip in clips:
        c = dict(clip)
        start_seg = int(c["start_segment"])
        end_seg = int(c["end_segment"])
        
        # Periksa apakah kalimat/penjelasan di segmen akhir belum tuntas
        extensions = 0
        while extensions < max_extension_segments and end_seg < total_segments:
            curr_text = str(by_id.get(end_seg, {}).get("text", ""))
            next_seg = end_seg + 1
            if next_seg in by_id:
                curr_end = float(by_id[end_seg]["end"])
                next_start = float(by_id[next_seg]["start"])
                # Jika kalimat menggantung ATAU jeda bicara sangat pendek (< 0.7s) tanpa tanda baca akhir
                if is_text_incomplete(curr_text) or (next_start - curr_end < 0.7 and not curr_text.endswith((".", "!", "?"))):
                    next_text = str(by_id[next_seg].get("text", "")).lower()
                    forbidden = any(w in next_text for w in ["etalase", "harga", "ongkir", "diskon", "promo", "voucher"])
                    if not forbidden:
                        end_seg = next_seg
                        extensions += 1
                    else:
                        break
                else:
                    break
            else:
                break
        
        c["end_segment"] = end_seg
        c["start"] = float(by_id[start_seg]["start"])
        raw_end = float(by_id[end_seg]["end"])
        # Tambahkan safety audio padding (+0.3s) agar kata terakhir tidak terpotong
        padded_end = min(video_duration, raw_end + 0.30)
        c["end"] = round(padded_end, 3)
        c["duration"] = round(c["end"] - c["start"], 2)
        c["text"] = " ".join(str(by_id[i].get("text", "")).strip() for i in range(start_seg, end_seg + 1) if i in by_id)
        refined.append(c)
        
    return refined

def extract_single_frame(video_path: Path, timestamp: float, output_jpg: Path, size: str = "360x640") -> bool:
    output_jpg.parent.mkdir(parents=True, exist_ok=True)
    paths = get_ffmpeg_paths()
    ffmpeg = paths["ffmpeg"]
    cmd = [
        ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
        "-ss", f"{max(0.0, timestamp):.3f}",
        "-i", str(video_path),
        "-vframes", "1",
        "-s", size,
        "-q:v", "6",
        str(output_jpg)
    ]
    try:
        run_cmd(cmd)
        return output_jpg.exists() and output_jpg.stat().st_size > 0
    except Exception:
        return False

def verify_single_clip_visual(
    clip: dict,
    idx: int,
    video_path: Path,
    frames_base_dir: Path,
    thumbs_dir: Path,
    video_duration: float,
    verboo_keys: list,
    gemini_keys: list
) -> dict:
    c = dict(clip)
    s = float(c["start"])
    e = float(c["end"])
    d = max(0.5, e - s)

    clip_dir = frames_base_dir / f"clip_{idx}"
    clip_dir.mkdir(parents=True, exist_ok=True)

    t_mid = s + (d * 0.5)
    t_end = max(s, e - min(0.5, d * 0.1))

    f_mid = clip_dir / "f_mid.jpg"
    f_end = clip_dir / "f_end.jpg"

    extract_single_frame(video_path, t_mid, f_mid, size="360x640")
    extract_single_frame(video_path, t_end, f_end, size="360x640")

    available_frames = [f for f in [f_mid, f_end] if f.exists()]
    
    # Simpan thumbnail publik untuk Web UI
    ui_thumb = thumbs_dir / f"clip_{idx}.jpg"
    if f_mid.exists():
        shutil.copy2(f_mid, ui_thumb)
    elif available_frames:
        shutil.copy2(available_frames[0], ui_thumb)
    c["thumb_file"] = str(ui_thumb) if ui_thumb.exists() else None

    if not available_frames:
        c["visual_score"] = 8
        c["visual_status"] = "OK (Linguistik Snapped)"
        return c

    spoken_text = c.get("text", "")
    vision_success = False

    prompt_vision = f"""Kamu AI Visual Editor TikTok Affiliate handal.
Berikut {len(available_frames)} frame (tengah demo dan detik akhir) dari klip video affiliate.
Teks ucapan: "{spoken_text}"

TUGAS PENTING:
1. Apakah host memperlihatkan / mendemonstrasikan produk secara visual?
2. Periksa frame terakhir: Apakah demonstrasi produk atau ucapan host sudah tuntas, ATAU masih menggantung / terpotong canggung di tengah-tengah penjelasan produk?
3. Jika masih menggantung, berapa detik durasi perlu diperpanjang agar tuntas? (0-3 detik).

Format respon WAJIB JSON:
{{
  "has_product_visual": true,
  "product_score": 8,
  "is_cut_prematurely": false,
  "need_extension": false,
  "extend_seconds": 0,
  "reason": "Host memperagakan produk dengan jelas dan penjelasan tuntas"
}}"""

    # 1. COBA AI UTAMA: VERBOO MULTIMODAL (Qwen 3.8 Vision lebih cepat & akurat)
    if verboo_keys:
        vb_content = [{"type": "text", "text": prompt_vision}]
        for f in available_frames:
            b64_img = base64.b64encode(f.read_bytes()).decode("ascii")
            vb_content.append({
                "type": "image_url",
                "image_url": {"url": f"data:image/jpeg;base64,{b64_img}"}
            })
        vb_messages = [
            {"role": "system", "content": "You are a visual video editor. Return JSON only."},
            {"role": "user", "content": vb_content}
        ]
        for item in verboo_keys:
            for model in VERBOO_VISION_MODELS:
                try:
                    v_res = call_verboo_chat(vb_messages, model, item["key"], temperature=0.1, timeout=25)
                    if isinstance(v_res, dict):
                        c["visual_score"] = int(v_res.get("product_score", 8))
                        c["has_product_visual"] = bool(v_res.get("has_product_visual", True))
                        c["visual_note"] = str(v_res.get("reason", ""))[:120]

                        if v_res.get("is_cut_prematurely") or v_res.get("need_extension"):
                            ext = float(v_res.get("extend_seconds", 1.0))
                            if ext > 0:
                                c["end"] = min(video_duration, round(c["end"] + ext, 3))
                                c["duration"] = round(c["end"] - c["start"], 2)
                                c["visual_status"] = f"Diperpanjang +{ext:.1f}s (Demo Tuntas - Verboo)"
                            else:
                                c["visual_status"] = "Visual Tuntas (Verboo)"
                        else:
                            c["visual_status"] = "Visual Tuntas (Verboo)"

                        record_key_success("verboo", item["key"])
                        vision_success = True
                        break
                except Exception:
                    continue
            if vision_success:
                break

    # 2. JIKA VERBOO GAGAL / KUOTA HABIS, FALLBACK KE GEMINI VISION
    if not vision_success and gemini_keys:
        from google import genai
        from google.genai import types

        for item in gemini_keys:
            client = genai.Client(api_key=item["key"])
            for model in GEMINI_MODELS:
                try:
                    parts = []
                    for f in available_frames:
                        parts.append(types.Part.from_bytes(data=f.read_bytes(), mime_type="image/jpeg"))
                    parts.append(prompt_vision)
                    resp = client.models.generate_content(
                        model=model,
                        contents=parts,
                        config={
                            "response_mime_type": "application/json",
                            "temperature": 0.1
                        }
                    )
                    text = (resp.text or "").strip()
                    if text.startswith("```"):
                        text = text.split("\n", 1)[1]
                        if text.endswith("```"):
                            text = text[:-3]
                    v_res = json.loads(text)
                    c["visual_score"] = int(v_res.get("product_score", 8))
                    c["has_product_visual"] = bool(v_res.get("has_product_visual", True))
                    c["visual_note"] = str(v_res.get("reason", ""))[:120]

                    if v_res.get("is_cut_prematurely") or v_res.get("need_extension"):
                        ext = float(v_res.get("extend_seconds", 1.0))
                        if ext > 0:
                            c["end"] = min(video_duration, round(c["end"] + ext, 3))
                            c["duration"] = round(c["end"] - c["start"], 2)
                            c["visual_status"] = f"Diperpanjang +{ext:.1f}s (Demo Tuntas - Gemini)"
                        else:
                            c["visual_status"] = "Visual Tuntas (Gemini)"
                    else:
                        c["visual_status"] = "Visual Tuntas (Gemini)"

                    record_key_success("gemini", item["key"])
                    vision_success = True
                    break
                except Exception:
                    continue
            if vision_success:
                break

    # 3. JIKA DUA-DUANYA GAGAL, GUNAKAN SNAPPING AUDIO
    if not vision_success:
        c["visual_score"] = 8
        c["visual_status"] = "Tuntas (Snapping Audio)"

    return c

def verify_clip_visuals(
    video_path: Path,
    clips: list,
    work_dir: Path,
    log_cb: Callable,
    video_duration: float,
    by_id: dict
) -> list:
    frames_base_dir = work_dir / "keyframes"
    thumbs_dir = work_dir / "thumbs"
    frames_base_dir.mkdir(parents=True, exist_ok=True)
    thumbs_dir.mkdir(parents=True, exist_ok=True)

    verboo_keys = get_active_keys("verboo")
    gemini_keys = get_active_keys("gemini")

    log_cb("AI_SELECT", 56, f"AI Visual Paralel: Menganalisis frame kamera untuk {len(clips)} klip sekaligus...")

    workers = min(4, len(clips)) if len(clips) > 0 else 1
    verified_clips = [None] * len(clips)

    with ThreadPoolExecutor(max_workers=workers) as executor:
        future_to_idx = {
            executor.submit(
                verify_single_clip_visual,
                clip,
                idx,
                video_path,
                frames_base_dir,
                thumbs_dir,
                video_duration,
                verboo_keys,
                gemini_keys
            ): idx - 1
            for idx, clip in enumerate(clips, start=1)
        }
        for future in future_to_idx:
            idx = future_to_idx[future]
            try:
                verified_clips[idx] = future.result()
            except Exception:
                c = dict(clips[idx])
                c["visual_score"] = 8
                c["visual_status"] = "Tuntas (Snapping Audio)"
                verified_clips[idx] = c

    verified_clips = [c for c in verified_clips if c is not None]

    try:
        if frames_base_dir.exists():
            shutil.rmtree(frames_base_dir)
    except Exception:
        pass

    log_cb("AI_SELECT", 64, f"✅ Verifikasi visual AI selesai untuk {len(verified_clips)} klip.")
    return verified_clips

verify_clip_visuals_with_gemini = verify_clip_visuals

def select_candidate_combination(candidates: list, target_duration: float = TARGET_DURATION, max_duration: float = MAX_DURATION) -> tuple:
    def _kind(clip):
        r = str(clip.get("reason", "")).lower()
        if any(w in r for w in ("hook", "pembuka", "menarik", "tanya")):
            return "hook"
        if any(w in r for w in ("cta", "ajakan", "keranjang", "beli", "penutup")):
            return "cta"
        return "body"

    ranked = sorted(candidates, key=lambda c: 10 if _kind(c) == "hook" else (8 if _kind(c) == "cta" else 5), reverse=True)
    selected = []
    total = 0.0

    def can_add(clip):
        s, e = float(clip["start"]), float(clip["end"])
        d = e - s
        if d <= 0 or total + d > max_duration:
            return False
        return not any(not (e <= chosen["start"] + 0.15 or s >= chosen["end"] - 0.15) for chosen in selected)

    # 1. Hook
    for c in [x for x in ranked if _kind(x) == "hook"]:
        if can_add(c):
            selected.append(dict(c))
            total += float(c["end"]) - float(c["start"])
            break

    # 2. Body
    for c in [x for x in ranked if _kind(x) == "body"]:
        if total >= target_duration:
            break
        if can_add(c):
            selected.append(dict(c))
            total += float(c["end"]) - float(c["start"])

    # 3. CTA
    for c in [x for x in ranked if _kind(x) == "cta"]:
        if can_add(c):
            selected.append(dict(c))
            total += float(c["end"]) - float(c["start"])
            break

    # 4. Lengkapi sisa durasi jika masih di bawah target_duration
    if total < target_duration:
        for c in ranked:
            if c in selected:
                continue
            if can_add(c):
                selected.append(dict(c))
                total += float(c["end"]) - float(c["start"])
                if total >= target_duration:
                    break

    hooks = [c for c in selected if _kind(c) == "hook"]
    ctas = [c for c in selected if _kind(c) == "cta"]
    bodies = [c for c in selected if _kind(c) == "body"]
    ordered = hooks[:1] + bodies + ctas[:1]
    return ordered, sum(float(c["end"]) - float(c["start"]) for c in ordered)

def analyze_and_plan_clips(
    segments: list,
    video_duration: float,
    log_cb: Callable,
    duration_range: str = "30-60",
    video_path: Optional[Path] = None,
    work_dir: Optional[Path] = None,
    enable_visual_ai: bool = True
) -> list:
    cfg = DURATION_PRESETS.get(duration_range, DURATION_PRESETS["30-60"])
    min_dur = cfg["min"]
    target_dur = cfg["target"]
    max_dur = cfg["max"]
    label_dur = cfg["label"]

    log_cb("AI_SELECT", 48, f"Menyusun kurasi highlight ({label_dur}) dengan AI Utama Verboo...")
    transcript = "\n".join(f'[{s["id"]}] [{s["start"]:.2f}-{s["end"]:.2f}] {s["text"]}' for s in segments)
    
    prompt = f"""
Bertindak sebagai AI Compliance Editor khusus untuk UCAPAN TALENT/PENJUAL dalam video TikTok Shop Affiliate UNTUK SEMUA KATEGORI PRODUK (Fashion & Pakaian, Skincare & Beauty, Elektronik & Gadget, Perlengkapan Rumah Tangga, Makanan & Minuman, Otomotif, dll).

TUGAS UTAMA:
Analisis dan bersihkan seluruh ucapan orang yang sedang menjual atau mempromosikan produk dari kategori apa pun. Hapus setiap kalimat yang berpotensi menyebabkan pelanggaran regulasi TikTok Shop, menyesatkan konsumen, mengandung klaim tidak terbukti/overclaim, atau membuat kesan video sedang siaran langsung.
Pilih 15–30 kandidat rentang segmen video yang bersih, faktual, dan aman untuk dirangkai menjadi video affiliate berdurasi target {int(min_dur)}–{int(max_dur)} detik (Maksimal {int(max_dur)} detik).

Jangan membuat klaim baru. Jangan memperindah klaim berisiko. Jika ucapan berisiko, HAPUS/JANGAN PILIH bagian tersebut.

VIDEO INI ADALAH VT EDITAN BIASA, BUKAN LIVE STREAMING.

PERTAHANKAN HANYA UCAPAN YANG:
- Menjelaskan produk secara faktual (berlaku untuk semua kategori produk).
- Menyebut nama/merek/jenis produk.
- Menjelaskan warna, model, motif, desain, bentuk, dan detail fisik yang terlihat.
- Menjelaskan bahan/material/kandungan/komposisi yang memang tersedia dalam informasi resmi produk.
- Menjelaskan ukuran, dimensi, kapasitas, berat, watt/daya, atau BB/TB jika relevan dan datanya tersedia.
- Menjelaskan fitur, spesifikasi teknis, fungsi, dan cara penggunaan yang benar-benar tercantum.
- Mengajak penonton melihat produk secara netral.

WAJIB HAPUS UCAPAN BERIKUT (10 ATURAN COMPLIANCE TIKTOK):
1. KLAIM BERLEBIHAN
   Hapus kata/kalimat: “Pasti”, “dijamin”, “100%”, “terbaik”, “nomor satu”, “paling bagus”, “paling canggih”, “wajib punya”, “semua orang cocok”, “nggak ada tandingannya”.
2. JANJI HASIL / MANFAAT TIDAK TERBUKTI (OVERCLAIM)
   Hapus janji hasil instan untuk semua kategori: “Pasti bikin langsing”, “langsung putih/glowing”, “langsung bikin kurus”, “menutupi semua kekurangan”, “bikin badan ideal”, “hasil instan”, “langsung sembuh”, “pasti nyaman seharian”, atau janji hasil yang tidak didukung informasi resmi.
3. TESTIMONI ATAU PENGALAMAN PALSU
   Hapus ucapan seolah-olah talent: sudah memakai produk, sudah mencoba produk, mendapat hasil tertentu, membuktikan produk berhasil, mengalami perubahan setelah penggunaan, kecuali pengalaman tersebut benar-benar diberikan sebagai fakta yang sah.
4. KLAIM UKURAN / SPESIFIKASI MENYESATKAN
   Hapus: “Pasti muat semua BB”, “cocok untuk semua tinggi badan”, “dijamin nggak kekecilan”, “pasti pas”, “baterai tahan selamanya”, “nggak perlu lihat size chart”. Pertahankan hanya informasi ukuran dan spesifikasi yang benar-benar tersedia.
5. KLAIM KEASLIAN / OTORITAS TANPA BUKTI
   Hapus: “Original resmi”, “produk import langsung”, “direkomendasikan dokter/ahli”, “dipakai artis”, “brand terkenal nomor satu”, jika tidak ada bukti yang diberikan.
6. HARGA / PROMO / URGENSI MENYESATKAN
   Hapus: Seluruh penyebutan harga, nominal rupiah/rp, murah, diskon, promo, cashback, voucher, gratis ongkir, “Cuma hari ini”, “stok tinggal sedikit”, “buruan sebelum habis”, “harga termurah”, “diskon terbesar”, “wajib checkout sekarang”.
7. PERBANDINGAN KOMPETITOR
   Hapus: “Lebih bagus dari brand lain”, “mengalahkan semua produk”, “produk lain jelek/palsu”, “paling murah dibanding yang lain”.
8. UCAPAN LIVE STREAMING
   Hapus semua ucapan yang membuat kesan sedang live:
   “Yang baru masuk live”, “halo penonton live”, “komen ya”, “tunggu aku ambilkan”, “produk sudah dipin”, “jangan pergi dulu”, “tap layar”, “yang mau checkout saat live”, “selama live ini”, “moderator”, atau interaksi real-time lainnya. Video harus terdengar seperti VT promosi biasa yang direkam dan diedit, bukan siaran langsung.
9. TEKANAN MEMBELI / FEAR-BASED SELLING
   Hapus: “Kalau nggak beli pasti rugi”, “jangan sampai menyesal”, “wajib beli sekarang”, “kalau kehabisan salah sendiri”, “semua orang harus punya”, “nggak beli berarti rugi”.
10. INFORMASI TIDAK RELEVAN
    Hapus: Basa-basi, percakapan personal, candaan tidak relevan, filler, pengulangan, percakapan dengan orang lain, dan kalimat di luar konteks produk.

ATURAN PEMBERSIHAN & PEMOTONGAN:
- Jangan mengganti klaim berisiko dengan klaim baru atau mengarang informasi.
- Prioritaskan ucapan faktual daripada ucapan persuasif.
- KEUTUHAN KALIMAT (TIDAK BOLEH GANTUNG): Wajib menjaga keutuhan setiap kalimat. Jangan pernah memotong ucapan di tengah kalimat, frasa, kata, atau informasi yang belum selesai.
- Pertahankan alur ucapan agar tetap natural setelah dipotong: hook faktual -> detail produk/fitur/bahan -> CTA netral penutup.
- Jangan membuat talent terdengar seperti sedang live.
- CTA YANG BOLEH DIPERTAHANKAN:
  “Cek detail produknya di keranjang kuning.”
  “Lihat pilihan ukuran dan detail produknya.”
  “Kalau sesuai kebutuhanmu, cek produknya di bawah.”

FORMAT OUTPUT (WAJIB JSON MURNI TANPA MARKDOWN):
{{"candidates": [{{"start_segment": 1, "end_segment": 3, "reason": "hook faktual", "text": "ucapan talent faktual yang sudah dibersihkan"}}]}}

TRANSKRIP:
{transcript}
"""
    response = ask_ai_with_fallback(prompt, log_cb)
    raw = response.get("candidates", response.get("clips", []))
    by_id = {int(s["id"]): s for s in segments}
    
    valid_candidates = []
    for r in raw:
        val = validate_plan([r], by_id, video_duration)
        if val:
            valid_candidates.append(val[0])
            
    # Terapkan penyempurnaan batas kalimat (Thought Snapping) agar tidak ada kalimat menggantung
    snapped_candidates = snap_to_complete_thought(valid_candidates, by_id, video_duration)
    
    chosen, dur = select_candidate_combination(snapped_candidates, target_duration=target_dur, max_duration=max_dur)
    if not chosen:
        chosen = snapped_candidates[:6]
        dur = sum(float(c["end"]) - float(c["start"]) for c in chosen)

    # Jalankan verifikasi AI Visual Multimodal jika diaktifkan
    if enable_visual_ai and video_path and work_dir and video_path.exists():
        chosen = verify_clip_visuals(video_path, chosen, work_dir, log_cb, video_duration, by_id)
        dur = sum(float(c["end"]) - float(c["start"]) for c in chosen)
    else:
        thumbs_dir = work_dir / "thumbs" if work_dir else None
        if thumbs_dir and video_path and video_path.exists():
            thumbs_dir.mkdir(parents=True, exist_ok=True)
            for idx, c in enumerate(chosen, start=1):
                t_mid = float(c["start"]) + (float(c["end"]) - float(c["start"])) * 0.5
                ui_thumb = thumbs_dir / f"clip_{idx}.jpg"
                extract_single_frame(video_path, t_mid, ui_thumb, size="360x640")
                c["thumb_file"] = str(ui_thumb) if ui_thumb.exists() else None
                c["visual_score"] = 8
                c["visual_status"] = "Linguistik Snapped (Tuntas)"
        log_cb("AI_SELECT", 68, "⚡ Mode Cepat: AI Visual dilewati (snapping audio tuntas).")

    log_cb("AI_SELECT", 68, f"Klip tersusun sempurna! Terpilih {len(chosen)} bagian (Total durasi: {dur:.1f}s, target {label_dur}).")
    return chosen

def render_final_video(video_path: Path, clips: list, output_video: Path, log_cb: Callable, 
                       watermark: Optional[str] = None, zoom: float = 1.0, y_offset: float = 0.0,
                       resolution: str = "720p"):
    res_cfg = RESOLUTIONS.get(resolution, RESOLUTIONS["720p"])
    target_w = res_cfg["w"]
    target_h = res_cfg["h"]
    res_label = res_cfg["label"]
    crf_val = res_cfg.get("crf", "22")

    log_cb("VIDEO_RENDER", 72, f"Memulai pemotongan & render ke resolusi {res_label} {FPS} FPS...")
    work_dir = output_video.parent / "temp_render"
    if work_dir.exists():
        shutil.rmtree(work_dir)
    work_dir.mkdir(parents=True, exist_ok=True)
    
    if zoom > 1.0 or y_offset != 0.0:
        log_cb("VIDEO_RENDER", 72, f"Scale/Zoom: {int(zoom*100)}%, Offset: {int(y_offset*100)}% (status bar & nav bar terpotong rapi)")

    wm_textfile = None
    if watermark and watermark.strip():
        wm_textfile = work_dir / "watermark.txt"
        wm_textfile.write_text(watermark.strip(), encoding="utf-8")
        log_cb("VIDEO_RENDER", 73, f"Watermark teks diterapkan: '{watermark.strip()}' (tengah, opacity 18%)")

    paths = get_ffmpeg_paths()
    ffmpeg = paths["ffmpeg"]
    
    concat_lines = []
    total_clips = len(clips)
    
    for i, clip in enumerate(clips, start=1):
        s = float(clip["start"])
        e = float(clip["end"])
        d = e - s
        if d <= 0:
            continue
        clip_out = work_dir / f"clip_{i:03d}.mp4"
        log_cb("VIDEO_RENDER", 72 + int((i / total_clips) * 20), f"Memotong & crop 9:16 ({target_w}x{target_h}) klip {i}/{total_clips} ({d:.1f}s)...")
        
        cmd = [
            ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
            "-ss", f"{s:.3f}", "-i", str(video_path),
            "-t", f"{d:.3f}",
            "-vf", crop_filter(target_w, target_h, watermark_textfile=wm_textfile, zoom=zoom, y_offset=y_offset),
            "-r", str(FPS),
            "-c:v", "libx264", "-preset", "veryfast", "-crf", crf_val,
            "-c:a", "aac", "-b:a", "128k", "-ar", "44100",
            "-movflags", "+faststart",
            str(clip_out)
        ]
        run_cmd(cmd)
        escaped = clip_out.as_posix().replace("'", "'\\''")
        concat_lines.append(f"file '{escaped}'")
        
    concat_file = work_dir / "concat.txt"
    concat_file.write_text("\n".join(concat_lines) + "\n", encoding="utf-8")
    
    log_cb("VIDEO_RENDER", 93, f"Menggabungkan klip menjadi hasil akhir {res_label} {FPS} FPS...")
    run_cmd([
        ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
        "-f", "concat", "-safe", "0", "-i", str(concat_file),
        "-c", "copy", "-movflags", "+faststart",
        str(output_video)
    ])
    
    # Cleanup temp render clips
    try:
        shutil.rmtree(work_dir)
    except Exception:
        pass
    
    final_info = probe_video(output_video)
    final_dur = float(final_info.get("format", {}).get("duration", 0) or 0)
    final_mb = output_video.stat().st_size / (1024 * 1024)
    log_cb("COMPLETED", 100, f"Selesai! Video siap didownload ({final_dur:.1f} detik, {final_mb:.1f} MB, {target_w}x{target_h} {FPS}FPS)")

def render_final_720p(video_path: Path, clips: list, output_video: Path, log_cb: Callable, 
                       watermark: Optional[str] = None, zoom: float = 1.0, y_offset: float = 0.0):
    return render_final_video(video_path, clips, output_video, log_cb, watermark=watermark, zoom=zoom, y_offset=y_offset, resolution="720p")
