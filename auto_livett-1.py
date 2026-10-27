#!/usr/bin/env python3
# AUTO LIVETT — Groq transcription, Gemini highlight selection, MediaCodec render,
# preview validation and final export for Termux Android.
#
# Default input: any stable top-level MP4 in /storage/emulated/0/autoclip/livett
# Input is moved into jobs/<job_id>/ before processing to prevent source collisions.
# Output: final1.mp4, final2.mp4, ... in the main livett folder.
#
# Requirements:
#   pkg install ffmpeg
#   pip install groq google-genai
#   export GROQ_API_KEY=...
#   export GEMINI_API_KEY=...
#
# MediaCodec is attempted first when FFmpeg exposes h264_mediacodec.
# If MediaCodec fails, the affected render falls back to libx264.
# "etalase", price/nominal, shipping-cost, discount and promotion mentions are prohibited.
# Cart and transaction CTAs are allowed only if they contain no price/promo mentions.

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import uuid
from datetime import datetime
from pathlib import Path

try:
    from rich.console import Console
    from rich.progress import Progress, BarColumn, TextColumn, TimeElapsedColumn, TimeRemainingColumn, TaskProgressColumn
    from rich.panel import Panel
    RICH_AVAILABLE = True
    console = Console()
except ImportError:
    RICH_AVAILABLE = False
    console = None


BASE = Path(__file__).resolve().parent
JOBS_DIR = BASE / "jobs"

# Paths are configured separately for every job.
JOB_ID = ""
JOB_DIR = BASE
WORK = BASE / "work"
AUDIO_PARTS = WORK / "audio_parts"
CLIP_DIR = WORK / "clips"
VIDEO = BASE / "video.mp4"
AUDIO = WORK / "video.mp3"
TRANSCRIPT = WORK / "transcript_groq.json"
PLAN = WORK / "edit_plan.json"
MANIFEST = WORK / "manifest.json"
PREVIEW = BASE / "preview.mp4"
FINAL = BASE / "final.mp4"


def configure_job(input_path=None, job_id=None, resume=False, claimed=False):
    """Isolate intermediate files and outputs for each concurrent job.

    claimed=True is used when auto-queue has already moved the source into
    its unique jobs/<job_id>/ directory.
    """
    global JOB_ID, JOB_DIR, WORK, AUDIO_PARTS, CLIP_DIR, VIDEO
    global AUDIO, TRANSCRIPT, PLAN, MANIFEST, PREVIEW, FINAL

    VIDEO = Path(input_path).expanduser() if input_path else BASE / "video.mp4"
    VIDEO = VIDEO.resolve()
    if job_id:
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", job_id):
            raise ValueError("Job ID hanya boleh berisi huruf, angka, garis bawah, dan tanda hubung.")
        JOB_ID = job_id
    else:
        JOB_ID = datetime.now().strftime("%Y%m%d_%H%M%S_%f") + "_" + uuid.uuid4().hex[:6]

    JOB_DIR = JOBS_DIR / JOB_ID
    if JOB_DIR.exists() and not resume and not claimed:
        raise FileExistsError(
            f"Job ID '{JOB_ID}' sudah ada. Gunakan ID baru atau --resume --job-id {JOB_ID}."
        )
    if resume and not JOB_DIR.exists():
        raise FileNotFoundError(f"Folder job tidak ditemukan untuk dilanjutkan: {JOB_DIR}")
    WORK = JOB_DIR / "work"
    AUDIO_PARTS = WORK / "audio_parts"
    CLIP_DIR = WORK / "clips"
    AUDIO = WORK / "video.mp3"
    TRANSCRIPT = WORK / "transcript_groq.json"
    PLAN = WORK / "edit_plan.json"
    MANIFEST = WORK / "manifest.json"
    PREVIEW = JOB_DIR / "preview.mp4"
    output_record = JOB_DIR / "final_output.txt"
    if resume and output_record.exists():
        FINAL = Path(output_record.read_text(encoding="utf-8").strip())
    else:
        FINAL = reserve_root_final_path()
        JOB_DIR.mkdir(parents=True, exist_ok=True)
        output_record.write_text(str(FINAL), encoding="utf-8")


def reserve_root_final_path():
    """Atomically reserve final1.mp4, final2.mp4, ... in BASE for this job."""
    BASE.mkdir(parents=True, exist_ok=True)
    number = 1
    while True:
        candidate = BASE / f"final{number}.mp4"
        lock = BASE / f".final{number}.lock"
        if candidate.exists():
            number += 1
            continue
        try:
            fd = os.open(str(lock), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        except FileExistsError:
            number += 1
            continue
        else:
            os.close(fd)
            # Re-check after acquiring the reservation in case a non-cooperating
            # process created the output at the same time.
            if candidate.exists():
                try:
                    lock.unlink()
                except OSError:
                    pass
                number += 1
                continue
            return candidate

CHUNK_SECONDS = 300
MAX_UPLOAD_MB = 24.5
MIN_DURATION = 0.0  # Tidak ada batas minimum; hasil pendek tetap diterima.
MAX_DURATION = 60.0
TARGET_DURATION = 50.0
OUT_W = 1080
OUT_H = 1920
FPS = 30
MAX_FINAL_MB = 50
AUDIO_BITRATE = "96k"
PREVIEW_VIDEO_BITRATE = "4500k"
FINAL_AUDIO_BITRATE = "64k"
MAX_GEMINI_ATTEMPTS_PER_MODEL = 2

# Edit these IDs to match models enabled for your Gemini API key.
# A model unavailable to the key will be skipped after its first failure.
GEMINI_MODELS = [
    "gemini-3.5-flash-lite",
    "gemini-3.1-flash-lite",
    "gemini-2.5-flash-lite",
    "gemini-3.5-flash",
    "gemini-3.6-flash",
    "gemini-3.7-flash",
    "gemini-3.8-flash",
    "gemini-3-flash",
    "gemini-2.5-flash",
]

def log(message, style=None):
    if RICH_AVAILABLE:
        console.print(message, style=style)
    else:
        print(message, flush=True)


def banner(title, subtitle=""):
    if RICH_AVAILABLE:
        body = f"[bold cyan]{title}[/bold cyan]"
        if subtitle:
            body += f"\n[dim]{subtitle}[/dim]"
        console.print(Panel(body, border_style="cyan", padding=(0, 1)))
    else:
        print("\n" + "=" * 58)
        print(title)
        if subtitle:
            print(subtitle)
        print("=" * 58, flush=True)


def run(cmd, *, capture=False, label=None, check=True):
    cmd = list(map(str, cmd))
    if label:
        log(f"  > {label}", "cyan")
    if capture:
        result = subprocess.run(
            cmd, check=False, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE
        )
        if check and result.returncode != 0:
            detail = (result.stderr or result.stdout or "Tidak ada detail error").strip()
            raise RuntimeError(f"Perintah gagal ({result.returncode}): {detail[-2500:]}")
        return result
    result = subprocess.run(cmd, check=False)
    if check and result.returncode != 0:
        raise RuntimeError(
            f"Perintah gagal dengan exit code {result.returncode}: {' '.join(cmd[:5])} ..."
        )
    return result


def require_program(name):
    if shutil.which(name) is None:
        raise RuntimeError(f"Program '{name}' tidak ditemukan. Pasang dengan: pkg install {name}")


def probe_json(path):
    result = run([
        "ffprobe", "-v", "error", "-show_format", "-show_streams",
        "-of", "json", str(path)
    ], capture=True)
    return json.loads(result.stdout)


def duration_of(path):
    data = probe_json(path)
    return float(data.get("format", {}).get("duration", 0) or 0)


def stream_of(path, stream_type):
    data = probe_json(path)
    return next((s for s in data.get("streams", []) if s.get("codec_type") == stream_type), None)


def has_encoder(name):
    result = run(["ffmpeg", "-hide_banner", "-encoders"], capture=True, check=False)
    return result.returncode == 0 and re.search(r"\b" + re.escape(name) + r"\b", result.stdout) is not None


def video_fingerprint():
    stat = VIDEO.stat()
    return {
        "path": str(VIDEO),
        "size": stat.st_size,
        "mtime_ns": stat.st_mtime_ns,
    }


def read_manifest():
    try:
        return json.loads(MANIFEST.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}


def write_manifest(**updates):
    data = read_manifest()
    data.update(updates)
    data["video_fingerprint"] = video_fingerprint() if VIDEO.exists() else None
    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def checkpoint_valid(path, stage, resume):
    if not resume or not path.exists():
        return False
    manifest = read_manifest()
    if manifest.get("video_fingerprint") != video_fingerprint():
        return False
    if manifest.get(stage) is not True:
        return False
    return path.stat().st_size > 0


def load_keys_to_env():
    """Load API keys from data/keys.json into os.environ if not already present."""
    keys_file = Path(__file__).resolve().parent / "data" / "keys.json"
    if keys_file.exists():
        try:
            kdata = json.loads(keys_file.read_text(encoding="utf-8"))
            if not os.environ.get("VERBOO_API_KEY") and kdata.get("verboo"):
                for k in kdata["verboo"]:
                    if k.get("status") == "active":
                        os.environ["VERBOO_API_KEY"] = k["key"]
                        break
            if not os.environ.get("GROQ_API_KEY") and kdata.get("groq"):
                for k in kdata["groq"]:
                    if k.get("status") == "active":
                        os.environ["GROQ_API_KEY"] = k["key"]
                        break
            if not os.environ.get("GEMINI_API_KEY") and kdata.get("gemini"):
                for k in kdata["gemini"]:
                    if k.get("status") == "active":
                        os.environ["GEMINI_API_KEY"] = k["key"]
                        break
        except Exception:
            pass
    if not os.environ.get("VERBOO_API_KEY"):
        os.environ["VERBOO_API_KEY"] = "vbk_pro_dc22f73b35587571924cbbf2425cb5a8dc061084"

def check_environment():
    load_keys_to_env()
    require_program("ffmpeg")
    require_program("ffprobe")
    if not VIDEO.exists():
        raise FileNotFoundError(f"Video input tidak ditemukan: {VIDEO}")
    if not os.environ.get("GROQ_API_KEY"):
        raise RuntimeError("GROQ_API_KEY belum tersedia. Atur environment atau masukkan di data/keys.json.")
    if not os.environ.get("VERBOO_API_KEY") and not os.environ.get("GEMINI_API_KEY"):
        raise RuntimeError("VERBOO_API_KEY atau GEMINI_API_KEY harus tersedia.")
    try:
        from groq import Groq  # noqa: F401
    except ImportError as exc:
        raise RuntimeError("Library groq belum terpasang. Jalankan: pip install groq") from exc

    video_stream = stream_of(VIDEO, "video")
    if not video_stream:
        raise RuntimeError("File input tidak memiliki stream video.")
    if not stream_of(VIDEO, "audio"):
        raise RuntimeError("File input tidak memiliki audio. AUTO LIVETT membutuhkan audio untuk transkripsi.")
    duration = duration_of(VIDEO)
    if duration <= 0:
        raise RuntimeError("Durasi video tidak terbaca.")
    log(f"Video sumber: {video_stream.get('width')}x{video_stream.get('height')}, {duration:.1f} detik")
    log(f"Encoder MediaCodec: {'tersedia' if has_encoder('h264_mediacodec') else 'tidak terdeteksi; gunakan libx264'}")


def convert_audio(resume=False, force=False):
    if not force and checkpoint_valid(AUDIO, "audio_ready", resume):
        log(f"Audio sudah tersedia, lewati konversi: {AUDIO}")
        return
    print("\n[1/6] Mengubah audio video menjadi MP3...")
    WORK.mkdir(parents=True, exist_ok=True)
    run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(VIDEO), "-map", "0:a:0", "-vn",
        "-af", "aresample=async=1:first_pts=0",
        "-ac", "1", "-ar", "16000",
        "-c:a", "libmp3lame", "-b:a", "32k",
        str(AUDIO)
    ])
    write_manifest(audio_ready=True)
    print(f"Audio selesai: {AUDIO} ({AUDIO.stat().st_size / 1024 / 1024:.1f} MB)")


def transcribe_audio(resume=False, force=False):
    print("\n[2/6] Membagi audio dan melakukan transkripsi Groq...")
    if not force and checkpoint_valid(TRANSCRIPT, "transcript_ready", resume):
        log(f"Transkrip valid, lewati transkripsi: {TRANSCRIPT}")
        return

    from groq import Groq
    WORK.mkdir(parents=True, exist_ok=True)
    if AUDIO_PARTS.exists():
        shutil.rmtree(AUDIO_PARTS)
    AUDIO_PARTS.mkdir(parents=True)

    run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(AUDIO), "-f", "segment",
        "-segment_time", str(CHUNK_SECONDS),
        "-reset_timestamps", "1", "-c", "copy",
        str(AUDIO_PARTS / "audio_%03d.mp3")
    ])

    parts = sorted(AUDIO_PARTS.glob("audio_*.mp3"))
    if not parts:
        raise RuntimeError("Tidak ada potongan audio yang berhasil dibuat.")

    client = Groq(api_key=os.environ["GROQ_API_KEY"])
    all_segments = []
    offset = 0.0

    progress = None
    task = None
    if RICH_AVAILABLE:
        progress = Progress(
            TextColumn("[bold cyan]{task.description}"),
            BarColumn(bar_width=26), TaskProgressColumn(),
            TimeElapsedColumn(), TimeRemainingColumn(), console=console
        )
        task = progress.add_task("Transkripsi Groq", total=len(parts))
        progress.start()

    try:
        for index, part in enumerate(parts, start=1):
            size_mb = part.stat().st_size / (1024 * 1024)
            if size_mb > MAX_UPLOAD_MB:
                raise RuntimeError(
                    f"{part.name} berukuran {size_mb:.1f} MB, melewati batas aman "
                    f"{MAX_UPLOAD_MB} MB. Turunkan CHUNK_SECONDS."
                )
            log(f"  Potongan {index}/{len(parts)}: {part.name} ({size_mb:.1f} MB)", "cyan")
            with part.open("rb") as audio_file:
                result = client.audio.transcriptions.create(
                    file=(part.name, audio_file.read()),
                    model="whisper-large-v3-turbo",
                    language="id",
                    response_format="verbose_json",
                    timestamp_granularities=["segment"],
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
            # Use actual part duration to translate local timestamps to source time.
            offset += duration_of(part)
            if progress is not None and task is not None:
                progress.update(task, advance=1)
            elif not RICH_AVAILABLE:
                log(f"  Transkripsi {index}/{len(parts)} selesai")
    finally:
        if progress is not None:
            progress.stop()

    if not all_segments:
        raise RuntimeError("Transkripsi tidak menghasilkan segmen teks.")

    TRANSCRIPT.write_text(
        json.dumps({"segments": all_segments}, ensure_ascii=False, indent=2),
        encoding="utf-8"
    )
    write_manifest(transcript_ready=True, plan_ready=False, render_ready=False)
    print(f"Transkrip tersimpan: {TRANSCRIPT} ({len(all_segments)} segmen)")


def get_gemini_client():
    from google import genai
    return genai.Client(api_key=os.environ["GEMINI_API_KEY"])


def ask_gemini(prompt):
    client = get_gemini_client()
    last_error = None

    for model in GEMINI_MODELS:
        for attempt in range(MAX_GEMINI_ATTEMPTS_PER_MODEL):
            try:
                print(f"  Analisis dengan {model} (percobaan {attempt + 1}/{MAX_GEMINI_ATTEMPTS_PER_MODEL})...")
                response = client.models.generate_content(
                    model=model,
                    contents=prompt,
                    config={
                        "response_mime_type": "application/json",
                        "temperature": 0.2,
                    },
                )
                response_text = (response.text or "").strip()
                if response_text.startswith("```"):
                    response_text = response_text.split("\n", 1)[1]
                    if response_text.endswith("```"):
                        response_text = response_text[:-3]
                data = json.loads(response_text)
                if not isinstance(data, dict):
                    raise ValueError("Respons Gemini bukan objek JSON.")
                write_manifest(last_gemini_model=model)
                return data
            except Exception as exc:
                last_error = exc
                message = str(exc)
                print(f"  Gagal: {message[:220]}")
                low = message.lower()
                permanent = any(term in low for term in (
                    "not found", "not supported", "unauthorized", "api key",
                    "permission denied", "invalid argument"
                ))
                quota_error = any(term in low for term in (
                    "quota", "resource_exhausted", "rate limit", "429"
                ))
                # Don't retry the same model for permanent errors or exhausted quota.
                if permanent or quota_error:
                    break
                if attempt + 1 < MAX_GEMINI_ATTEMPTS_PER_MODEL:
                    time.sleep(2 + attempt * 3)

    raise RuntimeError(
        "Gemini gagal. Periksa ID model, akses API, dan kuota. "
        f"Error terakhir: {last_error}"
    )


VERBOO_BASE_URL = "https://code.verboo.ai/router/v1"
VERBOO_MODELS = [
    "deepseek-v4.1-flash",
    "qwen3.8-27b",
    "glm-5.3-flash",
    "mimo-v2.5",
]

def ask_verboo(prompt):
    api_key = os.environ.get("VERBOO_API_KEY", "vbk_pro_dc22f73b35587571924cbbf2425cb5a8dc061084")
    url = f"{VERBOO_BASE_URL}/chat/completions"
    import urllib.request
    messages = [
        {"role": "system", "content": "You are a professional video editor. Output valid JSON only."},
        {"role": "user", "content": prompt}
    ]
    for model in VERBOO_MODELS:
        try:
            print(f"  Analisis AI Utama (Verboo: {model})...")
            payload = {
                "model": model,
                "messages": messages,
                "temperature": 0.2
            }
            req = urllib.request.Request(
                url,
                headers={
                    "Authorization": f"Bearer {api_key}",
                    "Content-Type": "application/json"
                },
                data=json.dumps(payload).encode("utf-8")
            )
            with urllib.request.urlopen(req, timeout=45) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                text = (data["choices"][0]["message"]["content"] or "").strip()
                if text.startswith("```"):
                    text = text.split("\n", 1)[1]
                    if text.endswith("```"):
                        text = text[:-3]
                parsed = json.loads(text)
                if isinstance(parsed, dict):
                    print(f"  ✅ Sukses dengan AI Utama Verboo ({model})!")
                    write_manifest(last_verboo_model=model)
                    return parsed
        except Exception as exc:
            print(f"  Verboo ({model}) error: {str(exc)[:120]}")
            continue
    raise RuntimeError("Semua model Verboo gagal.")

def ask_ai(prompt):
    # 1. Coba AI Utama: Verboo
    try:
        return ask_verboo(prompt)
    except Exception as e:
        print(f"  ⚠️ AI Utama (Verboo) habis atau tidak tersedia. Beralih ke AI Cadangan (Gemini)...")
    
    # 2. Fallback: Google Gemini
    if os.environ.get("GEMINI_API_KEY"):
        return ask_gemini(prompt)
    raise RuntimeError("Semua AI (Verboo & Gemini) gagal atau tidak tersedia.")



def validate_segments(segments):
    if not segments:
        raise RuntimeError("Transkrip kosong.")
    duration = duration_of(VIDEO)
    for seg in segments:
        try:
            start = float(seg["start"])
            end = float(seg["end"])
            sid = int(seg["id"])
        except (KeyError, TypeError, ValueError) as exc:
            raise RuntimeError("Format segmen transkrip tidak valid.") from exc
        if start < 0 or end <= start or end > duration + 1.0:
            raise RuntimeError(f"Timestamp transkrip tidak valid pada segmen {sid}.")


def validate_plan(clips, by_id):
    used_ids = set()
    cleaned = []
    video_duration = duration_of(VIDEO)

    for clip in clips:
        try:
            first = int(clip["start_segment"])
            last = int(clip["end_segment"])
        except (KeyError, TypeError, ValueError):
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

        # Ban etalase, prices, and promotions. Cart/transaction CTA is allowed.
        spoken_text = " ".join(str(by_id[i].get("text", "")) for i in sorted(ids))
        forbidden_patterns = [
            (r"\betalase\b", "etalase"),
            (r"\b(?:harga|harganya|seharga|nominal|rupiah|rp\.?|idr)\b", "harga/nominal"),
            (r"\b(?:ongkir|ongkos kirim|biaya pengiriman|gratis ongkir)\b", "ongkir/pengiriman"),
            (r"\b(?:diskon|promo|promosi|cashback|voucher|kupon|potongan harga|flash sale|sale|deal)\b", "promo/diskon"),
            (r"\b(?:hemat\s+\d+|cuma\s+\d+|hanya\s+\d+|mulai\s+dari\s+\d+)\b", "nominal/penawaran"),
        ]
        rejected_reason = next(
            (reason for pattern, reason in forbidden_patterns
             if re.search(pattern, spoken_text, flags=re.IGNORECASE)),
            None
        )
        if rejected_reason:
            log(f"  Rentang {first}-{last} ditolak karena menyebut {rejected_reason}.", "yellow")
            continue

        used_ids.update(ids)
        cleaned.append({
            "start_segment": first,
            "end_segment": last,
            "start": round(start, 3),
            "end": round(end, 3),
            "reason": str(clip.get("reason", ""))[:100],
        })

    cleaned.sort(key=lambda item: item["start"])
    return cleaned


def make_analysis_prompt(segments, extra_instruction=""):
    transcript = "\n".join(
        f'[{s["id"]}] [{s["start"]:.2f}-{s["end"]:.2f}] {s["text"]}'
        for s in segments
    )
    return f"""
Kamu editor video TikTok Affiliate dari livestream berbahasa Indonesia.
TUGAS: buat DAFTAR KANDIDAT klip yang dapat dirangkai menjadi satu video promosi natural.
Jangan hanya memberikan satu susunan final. Berikan 12–20 kandidat rentang yang BERBEDA,
masing-masing memakai ID segmen asli. Kandidat boleh menjadi alternatif yang saling tumpang tindih,
tetapi usahakan mencakup bagian transkrip yang berbeda dan beragam.

SASARAN EDIT FINAL:
- Target durasi final 45–55 detik; batas keras maksimal {MAX_DURATION:.0f} detik.
- Usahakan tersedia kandidat yang cukup agar Python dapat memilih kombinasi total minimal 35 detik.
- WAJIB usahakan beberapa kandidat berlabel "hook" untuk pembuka dan kandidat berlabel "CTA" untuk penutup.
- Hook harus berupa kalimat pembuka yang menarik perhatian, pertanyaan, masalah, atau pernyataan kuat yang benar-benar diucapkan.
- CTA harus berupa ajakan yang benar-benar diucapkan (misalnya cek keranjang/lihat produk) tanpa harga/promo.
- Pilihan akhir akan disusun hook di awal, isi di tengah, CTA di akhir jika masing-masing tersedia.
- Setelah itu prioritaskan manfaat/detail, demonstrasi/cara pakai yang benar-benar disebut, dan bukti yang memang ada.
- Setiap kandidat sebaiknya berupa kalimat/ide utuh, umumnya 3–15 detik jika materi memungkinkan.
- Label reason harus berupa salah satu kategori singkat: "hook", "manfaat", "detail produk", "demonstrasi", "bukti", "CTA", atau "lain".

ATURAN KERAS:
- Hanya gunakan segmen transkrip yang tersedia; jangan mengarang ucapan.
- Pertahankan urutan kata dan konteks asli; jangan memotong di tengah kalimat/informasi penting.
- DILARANG memilih rentang yang menyebut "etalase" (abaikan kapitalisasi).
- DILARANG memilih rentang yang menyebut harga, nominal uang, ongkir, diskon, promo, cashback,
  voucher, gratis ongkir, atau penawaran khusus.
- Penyebutan keranjang/keranjang kuning dan ajakan transaksi DIPERBOLEHKAN selama tidak menyebut harga/promo.
- Jangan mengulang rentang identik. Kandidat boleh tumpang tindih sebagai alternatif, tetapi jangan
  mengembalikan banyak kandidat yang hampir identik; cari bagian lain yang relevan.
- Jangan memasukkan obrolan di luar topik hanya demi durasi.
{extra_instruction}

Keluarkan JSON valid saja dengan format:
{{"candidates":[{{"start_segment":1,"end_segment":3,"reason":"hook"}}]}}

TRANSKRIP:
{transcript}
"""


def _reason_kind(clip):
    """Normalize Gemini's short category label."""
    reason = str(clip.get("reason", "")).lower()
    if any(word in reason for word in ("hook", "pembuka", "menarik perhatian", "pertanyaan")):
        return "hook"
    if any(word in reason for word in ("cta", "ajakan", "keranjang", "beli", "transaksi", "penutup")):
        return "cta"
    if "manfaat" in reason or "keunggulan" in reason:
        return "manfaat"
    if "demonstrasi" in reason or "demo" in reason or "cara pakai" in reason:
        return "demo"
    if "bukti" in reason or "testimoni" in reason:
        return "bukti"
    if "detail" in reason or "fitur" in reason or "produk" in reason:
        return "detail"
    return "lain"


def _candidate_score(clip):
    """Score candidates, strongly preferring a clear hook and a usable CTA."""
    kind = _reason_kind(clip)
    score = {"hook": 12.0, "manfaat": 6.0, "demo": 5.5, "detail": 5.0,
             "bukti": 4.0, "cta": 7.0, "lain": 1.0}.get(kind, 1.0)
    duration = max(0.0, float(clip["end"]) - float(clip["start"]))
    if 3.0 <= duration <= 12.0:
        score += 2.0
    elif duration < 2.0:
        score -= 2.0
    elif duration > 18.0:
        score -= 1.0
    return score


def select_candidate_combination(candidates, target=TARGET_DURATION, minimum=35.0, maximum=MAX_DURATION):
    """Select non-overlapping candidates and order output as hook -> body -> CTA.

    Hook and CTA are preferred when available, but never fabricated. A CTA is
    placed last in the rendered sequence even if it occurs earlier in source video.
    """
    ranked = sorted(candidates, key=_candidate_score, reverse=True)
    selected = []
    total = 0.0

    def can_add(clip):
        start, end = float(clip["start"]), float(clip["end"])
        duration = end - start
        if duration <= 0 or total + duration > maximum:
            return False
        return not any(not (end <= chosen["start"] + 0.15 or start >= chosen["end"] - 0.15)
                       for chosen in selected)

    # Reserve a hook for the opening whenever Gemini found a valid one.
    hook_candidates = [c for c in ranked if _reason_kind(c) == "hook"]
    for clip in hook_candidates:
        if can_add(clip):
            selected.append(dict(clip))
            total += float(clip["end"]) - float(clip["start"])
            break

    # Fill the body using high-scoring non-hook/non-CTA candidates first.
    body_ranked = [c for c in ranked if _reason_kind(c) not in ("hook", "cta")]
    for clip in body_ranked:
        if total >= target:
            break
        if can_add(clip):
            selected.append(dict(clip))
            total += float(clip["end"]) - float(clip["start"])

    # CTA is appended last when available and there is room.
    cta_candidates = [c for c in ranked if _reason_kind(c) == "cta"]
    for clip in cta_candidates:
        if can_add(clip):
            selected.append(dict(clip))
            total += float(clip["end"]) - float(clip["start"])
            break

    # Fill remaining duration with any safe, non-overlapping candidate.
    if total < target:
        for clip in ranked:
            if clip in selected:
                continue
            if can_add(clip):
                selected.append(dict(clip))
                total += float(clip["end"]) - float(clip["start"])
                if total >= target:
                    break

    # Render order is intentional: hook first, body chronological, CTA last.
    hooks = [c for c in selected if _reason_kind(c) == "hook"]
    ctas = [c for c in selected if _reason_kind(c) == "cta"]
    body = [c for c in selected if _reason_kind(c) not in ("hook", "cta")]
    hooks.sort(key=lambda item: item["start"])
    body.sort(key=lambda item: item["start"])
    ctas.sort(key=lambda item: item["start"])
    ordered = hooks[:1] + body + ctas[:1]
    # If no hook was found, retain the best opening-like candidate first.
    if not hooks and ordered:
        ordered = sorted(ordered, key=lambda item: item["start"])
    return ordered, sum(float(c["end"]) - float(c["start"]) for c in ordered)


def analyze_edit(resume=False, force=False):
    print("\n[3/6] Memilih kandidat klip dan menyusun durasi...")
    if not force and checkpoint_valid(PLAN, "plan_ready", resume):
        try:
            saved = json.loads(PLAN.read_text(encoding="utf-8"))
            saved_clips = saved.get("clips", [])
            saved_total = sum(float(c["end"]) - float(c["start"]) for c in saved_clips)
            if saved_clips and 0 < saved_total <= MAX_DURATION:
                log(f"Rencana edit valid, lewati analisis: {PLAN} ({saved_total:.1f} detik)")
                return
        except (OSError, json.JSONDecodeError, TypeError, ValueError):
            pass

    data = json.loads(TRANSCRIPT.read_text(encoding="utf-8"))
    segments = data.get("segments", [])
    validate_segments(segments)
    by_id = {int(seg["id"]): seg for seg in segments}

    candidate_pool = []
    candidate_keys = set()
    cleaned = []
    total = 0.0
    extra_instruction = ""

    # Initial candidate discovery plus up to three expansion/repair rounds.
    for round_index in range(4):
        if round_index == 0:
            print("  Mengumpulkan kandidat hook, manfaat, detail, demonstrasi, dan CTA...")
        else:
            print(f"  Durasi terpilih {total:.1f}s; mencari kandidat tambahan ({round_index}/3)...")
            extra_instruction = (
                "Rencana sebelumnya masih belum mencapai durasi target. Cari KANDIDAT TAMBAHAN "
                "dari bagian transkrip yang belum tercakup, terutama manfaat/detail/demonstrasi/CTA. "
                "Jangan hanya mengembalikan kandidat yang sama. Kandidat yang sudah tersedia: "
                + json.dumps([{"start_segment": c["start_segment"], "end_segment": c["end_segment"]}
                              for c in candidate_pool], ensure_ascii=False)
            )
        response = ask_ai(make_analysis_prompt(segments, extra_instruction))
        raw_candidates = response.get("candidates", response.get("clips", []))
        added = 0
        for raw in raw_candidates if isinstance(raw_candidates, list) else []:
            # Validate each candidate individually so overlapping alternatives are not
            # discarded before Python can compare them.
            one = validate_plan([raw], by_id)
            if not one:
                continue
            clip = one[0]
            key = (clip["start_segment"], clip["end_segment"])
            if key in candidate_keys:
                continue
            candidate_keys.add(key)
            candidate_pool.append(clip)
            added += 1

        cleaned, total = select_candidate_combination(candidate_pool)
        print(f"  Kandidat aman: {len(candidate_pool)}; kombinasi terpilih: {total:.1f} detik")
        if 45.0 <= total <= 55.0:
            break
        if total >= 35.0 and round_index >= 2:
            # Already acceptable for use; one more search may improve toward target.
            if round_index == 3:
                break
        if round_index > 0 and added == 0:
            log("Tidak ada kandidat baru dari Gemini pada putaran ini.", "yellow")
            break

    if not cleaned:
        raise RuntimeError("Gemini tidak menghasilkan kandidat klip valid. Folder job dipertahankan untuk pemeriksaan.")
    if total > MAX_DURATION:
        raise RuntimeError(f"Rencana {total:.1f} detik melebihi batas {MAX_DURATION:.0f} detik.")
    if total < 35.0:
        log(
            f"PERINGATAN: hanya {total:.1f} detik kandidat aman/non-overlap yang tersedia setelah pencarian tambahan. "
            "Tetap diproses sesuai permintaan; periksa hasil final.", "yellow"
        )
    elif total < 45.0:
        log(f"Durasi {total:.1f} detik belum mencapai target ideal 45–55 detik; tetap diproses.", "yellow")

    PLAN.write_text(
        json.dumps({"clips": cleaned, "total_duration": round(total, 3),
                    "candidate_count": len(candidate_pool)}, ensure_ascii=False, indent=2),
        encoding="utf-8"
    )
    write_manifest(plan_ready=True, render_ready=False)
    print(f"Rencana tersimpan: {PLAN}; total durasi {total:.1f} detik dari {len(candidate_pool)} kandidat")
    for clip in cleaned:
        print(f'  {clip["start"]:.2f} - {clip["end"]:.2f} '
              f'({clip["end"] - clip["start"]:.1f}s) — {clip["reason"]}')


def crop_filter():
    # Center-crop to 9:16, then scale/pad without stretching.
    return (
        "crop=w='min(iw,ih*9/16)':h='min(ih,iw*16/9)':"
        "x='(iw-ow)/2':y='(ih-oh)/2',"
        "scale=1080:1920:force_original_aspect_ratio=decrease,"
        "pad=1080:1920:(ow-iw)/2:(oh-ih)/2,"
        "setsar=1,fps=30,format=yuv420p"
    )


def render_one_clip(start, duration, out, prefer_mediacodec=True):
    base_cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-ss", f"{start:.3f}", "-i", str(VIDEO),
        "-t", f"{duration:.3f}",
        "-map", "0:v:0", "-map", "0:a:0",
        "-vf", crop_filter(),
        "-af", "aresample=async=1:first_pts=0",
        "-r", str(FPS),
        "-c:a", "aac", "-b:a", AUDIO_BITRATE, "-ar", "44100",
        "-movflags", "+faststart",
    ]

    # MediaCodec options vary by Android/FFmpeg build. Try it, then safely fall back.
    if prefer_mediacodec and has_encoder("h264_mediacodec"):
        mc_cmd = base_cmd + [
            "-c:v", "h264_mediacodec",
            "-b:v", PREVIEW_VIDEO_BITRATE,
            "-pix_fmt", "yuv420p",
            str(out)
        ]
        result = run(mc_cmd, check=False)
        if result.returncode == 0 and out.exists() and out.stat().st_size > 0:
            return "h264_mediacodec"
        if out.exists():
            out.unlink()
        log("  MediaCodec gagal untuk klip ini; fallback ke libx264.", "yellow")

    x264_cmd = base_cmd + [
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
        "-pix_fmt", "yuv420p", str(out)
    ]
    run(x264_cmd)
    return "libx264"


def concat_clips(concat_file, output):
    run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "concat", "-safe", "0", "-i", str(concat_file),
        "-c", "copy", "-movflags", "+faststart", str(output)
    ])


def render_preview(resume=False, force=False):
    print("\n[4/6] Memotong klip, crop 9:16, dan menyusun preview...")
    if not force and checkpoint_valid(PREVIEW, "render_ready", resume):
        log(f"Preview valid menurut checkpoint, lewati render: {PREVIEW}")
        return

    plan = json.loads(PLAN.read_text(encoding="utf-8"))
    clips = plan.get("clips", [])
    if not clips:
        raise RuntimeError("Rencana edit tidak berisi klip.")

    if CLIP_DIR.exists():
        shutil.rmtree(CLIP_DIR)
    CLIP_DIR.mkdir(parents=True, exist_ok=True)
    concat_file = WORK / "concat.txt"
    concat_lines = []
    codecs_used = []

    progress = None
    task = None
    if RICH_AVAILABLE:
        progress = Progress(
            TextColumn("[bold cyan]{task.description}"),
            BarColumn(bar_width=26), TaskProgressColumn(),
            TimeElapsedColumn(), TimeRemainingColumn(), console=console
        )
        task = progress.add_task("Render klip", total=len(clips))
        progress.start()

    try:
        for index, clip in enumerate(clips, start=1):
            out = CLIP_DIR / f"clip_{index:03d}.mp4"
            start = float(clip["start"])
            duration = float(clip["end"]) - start
            if duration <= 0:
                continue
            codec = render_one_clip(start, duration, out, prefer_mediacodec=True)
            codecs_used.append(codec)
            # Quote path for concat demuxer.
            escaped = out.as_posix().replace("'", "'\\''")
            concat_lines.append(f"file '{escaped}'")
            if progress is not None and task is not None:
                progress.update(task, advance=1)
            else:
                log(f"  Klip {index}/{len(clips)} selesai ({codec})")
    finally:
        if progress is not None:
            progress.stop()

    if not concat_lines:
        raise RuntimeError("Tidak ada klip yang berhasil dirender.")

    concat_file.write_text("\n".join(concat_lines) + "\n", encoding="utf-8")
    try:
        concat_clips(concat_file, PREVIEW)
    except Exception as exc:
        log(f"Concat stream-copy gagal ({exc}); mencoba concat dengan encoding ulang.", "yellow")
        # Re-encode the combined result to normalize timestamps/streams.
        run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "concat", "-safe", "0", "-i", str(concat_file),
            "-vf", crop_filter(),
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
            "-pix_fmt", "yuv420p", "-r", str(FPS),
            "-c:a", "aac", "-b:a", AUDIO_BITRATE, "-ar", "44100",
            "-movflags", "+faststart", str(PREVIEW)
        ])

    write_manifest(render_ready=True, render_codecs=codecs_used)
    print(f"Preview dibuat: {PREVIEW}")
    print(f"Encoder yang dipakai: {', '.join(sorted(set(codecs_used)))}")


def validate_video(path, enforce_size=False):
    data = probe_json(path)
    duration = float(data.get("format", {}).get("duration", 0) or 0)
    video_stream = next((s for s in data.get("streams", []) if s.get("codec_type") == "video"), None)
    audio_stream = next((s for s in data.get("streams", []) if s.get("codec_type") == "audio"), None)
    if not video_stream:
        raise RuntimeError(f"{path.name} tidak memiliki stream video.")
    if not audio_stream:
        raise RuntimeError(f"{path.name} tidak memiliki stream audio.")

    width, height = int(video_stream["width"]), int(video_stream["height"])
    fps = video_stream.get("r_frame_rate", "")
    size_mb = path.stat().st_size / (1024 * 1024)

    if (width, height) != (OUT_W, OUT_H):
        raise RuntimeError(f"Resolusi {path.name} salah: {width}x{height}; seharusnya {OUT_W}x{OUT_H}.")
    if duration > MAX_DURATION + 0.75:
        raise RuntimeError(
            f"Durasi {path.name} {duration:.2f} detik melebihi batas "
            f"{MAX_DURATION:.0f} detik."
        )
    if duration < 35.0:
        log(
            f"Hasil masih pendek ({duration:.2f} detik). Tetap diterima, tetapi seharusnya "
            "hanya terjadi jika materi aman/relevan memang tidak cukup.",
            "yellow"
        )
    if fps != "30/1":
        log(f"Peringatan: metadata FPS terbaca {fps}; periksa hasil pemutaran.", "yellow")

    run(["ffmpeg", "-hide_banner", "-v", "error", "-i", str(path), "-f", "null", "-"])
    if enforce_size and size_mb > MAX_FINAL_MB:
        raise RuntimeError(f"Ukuran {path.name} {size_mb:.1f} MB melebihi target {MAX_FINAL_MB} MB.")

    return {
        "duration": duration, "width": width, "height": height,
        "fps": fps, "size_mb": size_mb,
        "video_codec": video_stream.get("codec_name"),
        "audio_codec": audio_stream.get("codec_name"),
    }


def validate_preview():
    print("\n[5/6] Memeriksa durasi, resolusi, FPS, audio, dan integritas...")
    info = validate_video(PREVIEW)
    print(
        f"Preview valid: {info['width']}x{info['height']}, {info['fps']} FPS, "
        f"{info['duration']:.2f} detik, {info['size_mb']:.1f} MB, "
        f"video={info['video_codec']}, audio={info['audio_codec']}"
    )
    if info["size_mb"] > MAX_FINAL_MB:
        print(f"Preview melebihi {MAX_FINAL_MB} MB; final akan dikompresi.")


def encode_final_with_bitrate(video_bps, use_mediacodec=True):
    audio_bps = 64000
    common = [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(PREVIEW), "-map", "0:v:0", "-map", "0:a:0",
        "-vf", "scale=1080:1920:flags=lanczos,setsar=1,format=yuv420p",
        "-r", str(FPS),
        "-c:a", "aac", "-b:a", str(audio_bps),
        "-movflags", "+faststart",
    ]

    if use_mediacodec and has_encoder("h264_mediacodec"):
        result = run(common + [
            "-c:v", "h264_mediacodec",
            "-b:v", str(video_bps), "-pix_fmt", "yuv420p", str(FINAL)
        ], check=False)
        if result.returncode == 0 and FINAL.exists() and FINAL.stat().st_size > 0:
            return "h264_mediacodec"
        if FINAL.exists():
            FINAL.unlink()
        log("  MediaCodec gagal saat ekspor final; fallback ke libx264.", "yellow")

    run(common + [
        "-c:v", "libx264", "-preset", "veryfast",
        "-b:v", str(video_bps), "-maxrate", str(video_bps),
        "-bufsize", str(video_bps * 2),
        "-pix_fmt", "yuv420p", str(FINAL)
    ])
    return "libx264"


def export_final():
    print(f"\n[6/6] Mengekspor {FINAL.name}...")
    preview_info = validate_video(PREVIEW)
    preview_mb = preview_info["size_mb"]

    if preview_mb <= MAX_FINAL_MB:
        shutil.copy2(PREVIEW, FINAL)
        encoder = "copy preview"
    else:
        duration = max(preview_info["duration"], 1.0)
        # Leave headroom for MP4 container overhead.
        target_total_bps = int((MAX_FINAL_MB * 1024 * 1024 * 8 * 0.90) / duration)
        audio_bps = 64000
        video_bps = max(300000, target_total_bps - audio_bps)
        encoder = ""
        for attempt in range(3):
            print(f"  Encoding final percobaan {attempt + 1}/3; video bitrate {video_bps // 1000} kbps")
            encoder = encode_final_with_bitrate(video_bps, use_mediacodec=True)
            size_mb = FINAL.stat().st_size / (1024 * 1024)
            if size_mb <= MAX_FINAL_MB:
                break
            # Reduce bitrate based on actual overshoot with extra margin.
            video_bps = max(200000, int(video_bps * (MAX_FINAL_MB / size_mb) * 0.88))
        else:
            raise RuntimeError(
                f"Final tetap melebihi {MAX_FINAL_MB} MB setelah 3 percobaan. "
                f"Ukuran terakhir: {FINAL.stat().st_size / (1024 * 1024):.1f} MB."
            )

    final_info = validate_video(FINAL, enforce_size=True)
    print("\nSELESAI")
    print(f"Job ID   : {JOB_ID}")
    print(f"Final    : {FINAL}")
    print(f"Resolusi : {final_info['width']}x{final_info['height']}")
    print(f"Durasi   : {final_info['duration']:.2f} detik")
    print(f"FPS      : {final_info['fps']}")
    print(f"Ukuran   : {final_info['size_mb']:.1f} MB")
    print(f"Video    : {final_info['video_codec']}")
    print(f"Audio    : {final_info['audio_codec']}")
    print(f"Ekspor   : {encoder}")
    write_manifest(final_ready=True, final_size_mb=round(final_info["size_mb"], 2),
                   final_output=str(FINAL))
    # Keep the numbered output permanently; release only the temporary reservation.
    match = re.fullmatch(r"final(\d+)\.mp4", FINAL.name)
    if match:
        lock = BASE / f".final{match.group(1)}.lock"
        try:
            lock.unlink()
        except FileNotFoundError:
            pass



def cleanup_temporary_outputs():
    """Remove intermediate clip renders and preview only after final export validates."""
    removed = []
    errors = []

    # Delete rendered intermediate clips under work/clips.
    if CLIP_DIR.exists():
        try:
            shutil.rmtree(CLIP_DIR)
            removed.append(str(CLIP_DIR))
        except OSError as exc:
            errors.append(f"Gagal menghapus folder klip: {exc}")

    # Delete preview only after final.mp4 has been confirmed valid.
    if PREVIEW.exists():
        try:
            PREVIEW.unlink()
            removed.append(str(PREVIEW))
        except OSError as exc:
            errors.append(f"Gagal menghapus preview: {exc}")

    if removed:
        print("\nPembersihan file sementara:")
        for item in removed:
            print(f"  Dihapus: {item}")
    if errors:
        for error in errors:
            print(f"  PERINGATAN: {error}", file=sys.stderr)
        # Cleanup failure should not invalidate a successfully exported final.

def cleanup_successful_job():
    """Delete the complete job directory only when its final output is valid."""
    final_path = Path(FINAL)
    job_path = Path(JOB_DIR)
    if not final_path.is_file() or final_path.stat().st_size <= 0:
        log("Folder job dipertahankan: file final tidak ditemukan atau kosong.", "yellow")
        return
    try:
        # Recheck the final with ffprobe before deleting the only job workspace.
        validate_video(final_path, enforce_size=True)
    except Exception as exc:
        log(f"Folder job dipertahankan karena validasi final gagal: {exc}", "yellow")
        return
    try:
        shutil.rmtree(job_path)
        print(f"Folder job dibersihkan otomatis: {job_path}")
    except OSError as exc:
        log(f"Peringatan: folder job tidak bisa dihapus otomatis: {exc}", "yellow")


def parse_args():
    parser = argparse.ArgumentParser(
        description="AUTO LIVETT — transkrip, pilih klip affiliate, render dengan MediaCodec jika tersedia."
    )
    parser.add_argument(
        "--input", help="Path video sumber tertentu. Jika tidak diisi, otomatis antrekan MP4 di folder utama."
    )
    parser.add_argument("--job-id", help="ID pekerjaan; gunakan kembali untuk melanjutkan pekerjaan.")
    parser.add_argument("--resume", action="store_true", help="Lanjutkan pekerjaan yang sama; wajib gunakan --job-id.")
    parser.add_argument("--force", action="store_true", help="Paksa ulang tahap-tahap yang biasanya bisa dilewati.")
    parser.add_argument("--check", action="store_true", help="Periksa environment dan input saja, tanpa memproses video.")
    parser.add_argument(
        "--keep-temp", action="store_true",
        help="Simpan seluruh folder job untuk pemeriksaan; default-nya folder job dihapus setelah final valid."
    )
    return parser.parse_args()


def _job_id_now():
    return datetime.now().strftime("%Y%m%d_%H%M%S_%f") + "_" + uuid.uuid4().hex[:6]


def _recording_sort_key(path):
    """Sort Screenrecorder timestamps chronologically; fallback to mtime/name."""
    match = re.search(r"Screenrecorder-(\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2})(?:-(\d+))?", path.name, re.I)
    if match:
        try:
            stamp = datetime.strptime(match.group(1), "%Y-%m-%d-%H-%M-%S").timestamp()
            suffix = int(match.group(2) or 0)
            return (0, stamp, suffix, path.name.lower())
        except ValueError:
            pass
    try:
        return (1, path.stat().st_mtime, 0, path.name.lower())
    except OSError:
        return (2, 0, 0, path.name.lower())


def list_pending_videos():
    """Only top-level MP4 inputs; never treat numbered outputs as source videos."""
    BASE.mkdir(parents=True, exist_ok=True)
    candidates = []
    for path in BASE.iterdir():
        if not path.is_file() or path.suffix.lower() != ".mp4" or path.name.startswith("."):
            continue
        if re.fullmatch(r"final\d+\.mp4", path.name, re.I):
            continue
        if path.name.lower() in {"preview.mp4"}:
            continue
        candidates.append(path)
    return sorted(candidates, key=_recording_sort_key)


def wait_until_stable(path, checks=3, interval=0.8):
    """Require several unchanged size/mtime samples to avoid grabbing an active recording."""
    previous = None
    stable_count = 0
    for _ in range(checks + 4):
        try:
            stat = path.stat()
            current = (stat.st_size, stat.st_mtime_ns)
        except OSError:
            return False
        if stat.st_size <= 0:
            stable_count = 0
        elif current == previous:
            stable_count += 1
        else:
            stable_count = 0
        if stable_count >= checks:
            return True
        previous = current
        time.sleep(interval)
    return False


def claim_next_video():
    """Atomically move the oldest stable root MP4 into a unique jobs folder."""
    for source in list_pending_videos():
        if not wait_until_stable(source):
            log(f"Lewati sementara (file belum stabil/masih direkam): {source.name}", "yellow")
            continue

        job_id = _job_id_now()
        job_dir = JOBS_DIR / job_id
        try:
            job_dir.mkdir(parents=True, exist_ok=False)
        except FileExistsError:
            continue
        destination = job_dir / source.name
        try:
            # Rename within the same storage volume; a second process that tries
            # to claim this same source will fail because the source has vanished.
            source.rename(destination)
            log(f"Input diamankan: {source.name} -> {destination}", "green")
            return destination, job_id
        except FileNotFoundError:
            # Another running instance claimed it first.
            try:
                job_dir.rmdir()
            except OSError:
                pass
            continue
        except OSError as exc:
            try:
                job_dir.rmdir()
            except OSError:
                pass
            log(f"Gagal memindahkan {source.name}: {exc}", "yellow")
            continue
    return None, None


def release_final_reservation():
    """Remove a reservation lock if this job failed before creating its final file."""
    try:
        match = re.fullmatch(r"final(\d+)\.mp4", FINAL.name)
        if match and not FINAL.exists():
            (BASE / f".final{match.group(1)}.lock").unlink(missing_ok=True)
    except (NameError, OSError):
        pass


def process_job(input_path, job_id=None, *, resume=False, force=False, check_only=False,
                keep_temp=False, claimed=False):
    configure_job(input_path=input_path, job_id=job_id, resume=resume, claimed=claimed)
    banner("AUTO LIVETT", f"Job {JOB_ID} • Groq • Gemini • MediaCodec/libx264")
    log(f"Job ID: {JOB_ID}", "cyan")
    log(f"Input : {VIDEO}", "dim")
    log(f"Output: {FINAL}", "dim")
    log(f"Work  : {WORK}", "dim")
    check_environment()

    if check_only:
        print("Pemeriksaan environment selesai.")
        release_final_reservation()
        return

    if force:
        write_manifest(audio_ready=False, transcript_ready=False,
                       plan_ready=False, render_ready=False, final_ready=False)

    convert_audio(resume=resume, force=force)
    transcribe_audio(resume=resume, force=force)
    analyze_edit(resume=resume, force=force)
    render_preview(resume=resume, force=force)
    validate_preview()
    export_final()
    # Only delete the complete job directory after export_final has validated
    # the final output. On any processing/export error, the job folder is retained.
    if not keep_temp:
        cleanup_temporary_outputs()
        cleanup_successful_job()


def main():
    args = parse_args()
    if args.resume and not args.job_id:
        raise RuntimeError("--resume harus disertai --job-id dari pekerjaan sebelumnya.")

    # Explicit --input keeps the original one-job behavior for manual/recovery use.
    if args.input or args.resume or args.job_id:
        input_path = args.input
        if args.resume and not input_path:
            raise RuntimeError("Untuk --resume, sertakan juga --input dengan path input di folder jobs.")
        if not input_path:
            raise RuntimeError("Gunakan --input untuk menjalankan satu job manual.")
        process_job(input_path, args.job_id, resume=args.resume, force=args.force,
                    check_only=args.check, keep_temp=args.keep_temp)
        return

    # Default mode: automatically queue all root-level MP4s, preserving their names.
    banner("AUTO LIVETT • AUTO QUEUE", f"Folder input: {BASE}\nHasil akhir: final1.mp4, final2.mp4, ...")
    if args.check:
        candidates = list_pending_videos()
        if not candidates:
            raise FileNotFoundError(f"Tidak ada video MP4 di folder utama: {BASE}")
        stable = next((p for p in candidates if wait_until_stable(p)), None)
        if stable is None:
            raise RuntimeError("Video ditemukan, tetapi belum ada yang stabil. Tunggu rekaman selesai lalu coba lagi.")
        # Check-only must not move or reserve a job. Validate tools/API/library basics here.
        require_program("ffmpeg")
        load_keys_to_env()
        if not os.environ.get("GROQ_API_KEY") or (not os.environ.get("VERBOO_API_KEY") and not os.environ.get("GEMINI_API_KEY")):
            raise RuntimeError("GROQ_API_KEY dan VERBOO_API_KEY/GEMINI_API_KEY harus tersedia di environment atau data/keys.json.")
        print(f"Environment dasar OK. Video antrean pertama: {stable.name}")
        return

    processed = 0
    while True:
        input_path, job_id = claim_next_video()
        if input_path is None:
            break
        try:
            process_job(input_path, job_id, force=args.force,
                        keep_temp=args.keep_temp, claimed=True)
            processed += 1
        except Exception:
            release_final_reservation()
            raise

    if processed == 0:
        raise FileNotFoundError(
            f"Tidak ada MP4 siap diproses di folder utama: {BASE}\n"
            "Masukkan video rekaman ke folder ini, tunggu rekaman selesai, lalu jalankan lagi."
        )
    print(f"\nAntrean selesai. Total video berhasil diproses: {processed}")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nDibatalkan oleh pengguna.")
        sys.exit(130)
    except Exception as exc:
        print(f"\nERROR: {exc}", file=sys.stderr)
        sys.exit(1)
