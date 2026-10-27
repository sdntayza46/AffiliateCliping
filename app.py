import asyncio
import json
import os
import shutil
import time
import uuid
from pathlib import Path
from typing import Dict, Any, Optional

from fastapi import FastAPI, UploadFile, File, Form, HTTPException, BackgroundTasks
from fastapi.responses import HTMLResponse, FileResponse, StreamingResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from core.key_manager import (
    get_keys_summary, add_keys, delete_key, reset_key, reset_all_exhausted, load_keys
)
from core.processor import (
    extract_audio, transcribe_with_groq_fallback, analyze_and_plan_clips,
    render_final_video, render_final_720p, get_video_duration, OUT_W, OUT_H, FPS, RESOLUTIONS
)
from core.ffmpeg_utils import get_ffmpeg_paths

import hashlib
from datetime import datetime

BASE_DIR = Path(__file__).resolve().parent
UPLOADS_DIR = BASE_DIR / "uploads"
OUTPUTS_DIR = BASE_DIR / "outputs"
JOBS_DIR = BASE_DIR / "jobs"
STATIC_DIR = BASE_DIR / "static"
SETTINGS_FILE = BASE_DIR / "data" / "settings.json"

for d in [UPLOADS_DIR, OUTPUTS_DIR, JOBS_DIR, STATIC_DIR]:
    d.mkdir(parents=True, exist_ok=True)

app = FastAPI(title="dantez x mamank recing - Auto Clip Studio")

# In-memory job state tracking & event queues for SSE
JOBS: Dict[str, Dict[str, Any]] = {}
EVENT_QUEUES: Dict[str, list] = {}
FILE_REGISTRY: Dict[str, Path] = {}

class KeyAddRequest(BaseModel):
    provider: str
    keys: str

class FolderConfigRequest(BaseModel):
    folder: str

class DirectSelectRequest(BaseModel):
    path: str

class WatermarkRequest(BaseModel):
    watermark: str

def get_default_screenrecorder_folders() -> list:
    candidates = [
        # Android Termux standard paths
        Path("/sdcard/Movies/Screenrecords"),
        Path("/sdcard/DCIM/ScreenRecorder"),
        Path("/sdcard/Movies"),
        Path("/sdcard/DCIM/Screenshots"),
        Path("/sdcard/Download"),
        Path("/storage/emulated/0/Movies/Screenrecords"),
        Path("/storage/emulated/0/DCIM/ScreenRecorder"),
        Path("/storage/emulated/0/Download"),
        # Windows standard paths
        Path.home() / "Videos" / "Captures",
        Path.home() / "Videos",
        Path("D:/Download"),
        Path.cwd()
    ]
    existing = []
    for c in candidates:
        try:
            if c.exists() and c.is_dir():
                c_res = str(c.resolve())
                if c_res not in existing:
                    existing.append(c_res)
        except Exception:
            pass
    return existing

def load_settings() -> dict:
    defaults = get_default_screenrecorder_folders()
    default_folder = str(Path.cwd())
    if defaults:
        default_folder = defaults[0]
        for d in defaults:
            p_d = Path(d)
            try:
                if p_d.exists() and any(p_d.glob("*.mp4")):
                    default_folder = d
                    break
            except Exception:
                pass
    data = {"screenrecorder_folder": default_folder, "watermark": ""}
    if SETTINGS_FILE.exists():
        try:
            loaded = json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
            if isinstance(loaded, dict):
                if loaded.get("screenrecorder_folder"):
                    data["screenrecorder_folder"] = loaded["screenrecorder_folder"]
                if "watermark" in loaded:
                    data["watermark"] = str(loaded.get("watermark") or "").strip()
        except Exception:
            pass
    return data

def save_settings(new_settings: dict):
    SETTINGS_FILE.parent.mkdir(parents=True, exist_ok=True)
    current = {}
    if SETTINGS_FILE.exists():
        try:
            current = json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
            if not isinstance(current, dict):
                current = {}
        except Exception:
            current = {}
    current.update(new_settings)
    SETTINGS_FILE.write_text(json.dumps(current, indent=2, ensure_ascii=False), encoding="utf-8")

def scan_screenrecorder_files(folder_path: Path, max_files: int = 40) -> list:
    if not folder_path.exists() or not folder_path.is_dir():
        return []
    valid_exts = {".mp4", ".mkv", ".mov", ".ts"}
    files = []
    now = time.time()
    try:
        for f in folder_path.iterdir():
            if f.is_file() and f.suffix.lower() in valid_exts:
                try:
                    stat = f.stat()
                    diff = max(0, now - stat.st_mtime)
                    if diff < 60:
                        ago = "Baru saja"
                    elif diff < 3600:
                        ago = f"{int(diff // 60)} menit lalu"
                    elif diff < 86400:
                        ago = f"{int(diff // 3600)} jam lalu"
                    else:
                        ago = f"{int(diff // 86400)} hari lalu"

                    dt_str = datetime.fromtimestamp(stat.st_mtime).strftime("%d/%m/%Y %H:%M")
                    direct_id = f"dir_{hashlib.md5(str(f.resolve()).encode()).hexdigest()[:8]}"
                    FILE_REGISTRY[direct_id] = f.resolve()

                    files.append({
                        "id": direct_id,
                        "name": f.name,
                        "path": str(f.resolve()),
                        "size_mb": round(stat.st_size / (1024 * 1024), 1),
                        "mtime": stat.st_mtime,
                        "modified_time": dt_str,
                        "ago": ago
                    })
                except Exception:
                    continue
    except Exception:
        pass
    files.sort(key=lambda x: x["mtime"], reverse=True)
    return files[:max_files]

def resolve_video_path(upload_id_or_path: str) -> Path:
    if upload_id_or_path in FILE_REGISTRY:
        p = FILE_REGISTRY[upload_id_or_path]
        if p.exists() and p.is_file():
            return p
    candidate = Path(upload_id_or_path)
    if candidate.exists() and candidate.is_file():
        return candidate.resolve()
    up_path = UPLOADS_DIR / f"{upload_id_or_path}.mp4"
    if up_path.exists() and up_path.is_file():
        return up_path
    up_orig = UPLOADS_DIR / upload_id_or_path
    if up_orig.exists() and up_orig.is_file():
        return up_orig
    raise HTTPException(status_code=404, detail=f"File video tidak ditemukan: {upload_id_or_path}")

def emit_job_log(job_id: str, stage: str, percent: int, message: str, extra: dict = None):
    entry = {
        "job_id": job_id,
        "stage": stage,
        "percent": percent,
        "message": message,
        "time": time.strftime("%H:%M:%S"),
        "extra": extra or {}
    }
    if job_id in JOBS:
        JOBS[job_id]["stage"] = stage
        JOBS[job_id]["percent"] = percent
        JOBS[job_id]["logs"].append(entry)
        if stage == "COMPLETED":
            JOBS[job_id]["status"] = "completed"
            JOBS[job_id]["finished_at"] = time.time()
        elif stage == "ERROR":
            JOBS[job_id]["status"] = "error"
            JOBS[job_id]["error_msg"] = message

    # Push to all active SSE queues for this job
    if job_id in EVENT_QUEUES:
        for q in EVENT_QUEUES[job_id]:
            q.put_nowait(entry)

def run_job_pipeline(
    job_id: str,
    video_path: Path,
    watermark: Optional[str] = None,
    zoom: float = 1.0,
    y_offset: float = 0.0,
    duration_range: str = "30-60",
    enable_visual_ai: bool = True,
    custom_clips: Optional[list] = None,
    resolution: str = "720p"
):
    job_work = JOBS_DIR / job_id
    job_work.mkdir(parents=True, exist_ok=True)
    res_key = resolution.lower() if resolution and resolution.lower() in ["720p", "1080p"] else "720p"
    out_video = OUTPUTS_DIR / f"{job_id}_{res_key}.mp4"
    audio_mp3 = job_work / "audio.mp3"

    def log_cb(stage: str, percent: int, msg: str, extra: dict = None):
        emit_job_log(job_id, stage, percent, msg, extra)

    try:
        is_direct = not str(video_path).startswith(str(UPLOADS_DIR))
        if is_direct:
            emit_job_log(job_id, "INIT", 2, f"⚡ Mode Langsung (Zero-Copy): Mengeksekusi '{video_path.name}' langsung tanpa copy/duplikasi!")
        else:
            emit_job_log(job_id, "INIT", 2, "Menyiapkan file input dan analisis awal...")
        duration = get_video_duration(video_path)
        emit_job_log(job_id, "INIT", 5, f"Durasi video sumber: {duration:.1f} detik")

        if custom_clips and len(custom_clips) > 0:
            emit_job_log(job_id, "INIT", 10, f"Menggunakan {len(custom_clips)} klip kustom hasil penyesuaian visual...")
            clips = custom_clips
        else:
            # 1. Ekstraksi audio
            extract_audio(video_path, audio_mp3, log_cb)

            # 2. Transkripsi dengan Groq AI
            segments = transcribe_with_groq_fallback(audio_mp3, job_work, log_cb)

            # 3. Analisis & Seleksi Klip dengan Gemini AI + AI Visual Multimodal (Tuntas tanpa terpotong)
            clips = analyze_and_plan_clips(
                segments, duration, log_cb,
                duration_range=duration_range,
                video_path=video_path,
                work_dir=job_work,
                enable_visual_ai=enable_visual_ai
            )

        # Simpan metadata klip ke memori job
        JOBS[job_id]["clips"] = [
            {
                "clip_id": i,
                "start": c["start"],
                "end": c["end"],
                "duration": round(c["end"] - c["start"], 2),
                "reason": c.get("reason", "body"),
                "text": c.get("text", ""),
                "visual_status": c.get("visual_status", "Tuntas"),
                "visual_score": c.get("visual_score", 8),
                "thumb_url": f"/api/thumb/{job_id}/{i}" if (job_work / "thumbs" / f"clip_{i}.jpg").exists() else None
            }
            for i, c in enumerate(clips, 1)
        ]

        # 4. Render ke resolusi pilihan (720p atau 1080p) 30 FPS
        render_final_video(video_path, clips, out_video, log_cb, watermark=watermark, zoom=zoom, y_offset=y_offset, resolution=res_key)

        if out_video.exists() and out_video.stat().st_size > 0:
            JOBS[job_id]["output_file"] = str(out_video)
            JOBS[job_id]["final_size_mb"] = round(out_video.stat().st_size / (1024 * 1024), 2)

            # Bersihkan seluruh file sementara (keyframes, foto frame, audio parts) agar storage tetap lega
            try:
                if job_work.exists():
                    shutil.rmtree(job_work, ignore_errors=True)
            except Exception:
                pass

            log_cb("COMPLETED", 100, f"✅ Sukses! File siap didownload ({JOBS[job_id]['final_size_mb']} MB)")
            emit_job_log(job_id, "CLEANUP", 100, "🧹 Pembersihan Otomatis: Seluruh file keyframes & foto sementara telah dihapus bersih.")
        else:
            raise RuntimeError("Hasil ekspor video tidak ditemukan atau berukuran 0 byte.")

    except Exception as exc:
        import traceback
        traceback.print_exc()
        emit_job_log(job_id, "ERROR", JOBS.get(job_id, {}).get("percent", 0), f"❌ Terjadi kesalahan: {str(exc)}")

# --- API ROUTES ---

@app.get("/api/system/status")
async def system_status():
    ffmpeg_info = get_ffmpeg_paths()
    keys = load_keys()
    verboo_active = len([k for k in keys.get("verboo", []) if k.get("status") == "active"])
    groq_active = len([k for k in keys.get("groq", []) if k.get("status") == "active"])
    gemini_active = len([k for k in keys.get("gemini", []) if k.get("status") == "active"])
    return {
        "ffmpeg": ffmpeg_info["available"],
        "verboo_active": verboo_active,
        "groq_active": groq_active,
        "gemini_active": gemini_active,
        "resolution": f"{OUT_W}x{OUT_H}",
        "fps": FPS
    }

@app.get("/api/keys")
async def list_keys():
    return get_keys_summary()

@app.post("/api/keys")
async def add_api_keys(req: KeyAddRequest):
    count = add_keys(req.provider, req.keys)
    return {"status": "success", "added_count": count}

@app.delete("/api/keys/{provider}/{key_id}")
async def remove_key(provider: str, key_id: str):
    success = delete_key(provider, key_id)
    return {"status": "success" if success else "not_found"}

@app.post("/api/keys/{provider}/{key_id}/reset")
async def reset_single_key(provider: str, key_id: str):
    success = reset_key(provider, key_id)
    return {"status": "success" if success else "not_found"}

@app.post("/api/keys/reset-all")
async def reset_all_keys(provider: Optional[str] = None):
    reset_all_exhausted(provider)
    return {"status": "success"}

@app.post("/api/upload")
async def upload_video(file: UploadFile = File(...)):
    if not file.filename.lower().endswith((".mp4", ".mov", ".mkv", ".ts")):
        raise HTTPException(status_code=400, detail="Hanya file video (.mp4, .mov, .mkv) yang didukung.")
    
    upload_id = f"vid_{int(time.time())}_{uuid.uuid4().hex[:6]}"
    dest_path = UPLOADS_DIR / f"{upload_id}.mp4"
    
    with dest_path.open("wb") as buffer:
        shutil.copyfileobj(file.file, buffer)
        
    return {
        "upload_id": upload_id,
        "filename": file.filename,
        "size_mb": round(dest_path.stat().st_size / (1024 * 1024), 2),
        "is_direct": False
    }

# --- SCREEN RECORDER DIRECT ZERO-COPY ENDPOINTS ---

@app.get("/api/screenrecorder/config")
async def get_screenrecorder_config():
    settings = load_settings()
    candidates = get_default_screenrecorder_folders()
    cur_folder = settings.get("screenrecorder_folder", "")
    return {
        "current_folder": cur_folder,
        "exists": Path(cur_folder).exists() if cur_folder else False,
        "detected_folders": candidates
    }

@app.post("/api/screenrecorder/config")
async def set_screenrecorder_config(req: FolderConfigRequest):
    p = Path(req.folder.strip())
    if not p.exists() or not p.is_dir():
        raise HTTPException(status_code=400, detail="Folder tidak ditemukan di sistem.")
    save_settings({"screenrecorder_folder": str(p.resolve())})
    return {"status": "success", "folder": str(p.resolve())}

@app.get("/api/screenrecorder/files")
async def list_screenrecorder_files(folder: Optional[str] = None):
    settings = load_settings()
    target_folder = folder if folder else settings.get("screenrecorder_folder")
    if not target_folder:
        return {"folder": "", "files": []}
    p = Path(target_folder)
    files = scan_screenrecorder_files(p)
    return {
        "folder": str(p.resolve()) if p.exists() else target_folder,
        "exists": p.exists(),
        "files": files
    }

@app.post("/api/screenrecorder/select")
async def select_screenrecorder_file(req: DirectSelectRequest):
    p = Path(req.path.strip())
    if not p.exists() or not p.is_file():
        raise HTTPException(status_code=404, detail="File video tidak ditemukan di path yang ditentukan.")
    direct_id = f"dir_{hashlib.md5(str(p.resolve()).encode()).hexdigest()[:8]}"
    FILE_REGISTRY[direct_id] = p.resolve()
    stat = p.stat()
    return {
        "upload_id": direct_id,
        "filename": p.name,
        "full_path": str(p.resolve()),
        "size_mb": round(stat.stat().st_size / (1024 * 1024), 1) if hasattr(stat, 'stat') else round(stat.st_size / (1024 * 1024), 1),
        "is_direct": True
    }

@app.get("/api/find-local-file")
async def find_local_file(filename: str, size: Optional[int] = None):
    settings = load_settings()
    folders_to_check = [Path(settings.get("screenrecorder_folder", ""))]
    for c in get_default_screenrecorder_folders():
        p_c = Path(c)
        if p_c not in folders_to_check:
            folders_to_check.append(p_c)
            
    for folder in folders_to_check:
        if folder.exists() and folder.is_dir():
            target = folder / filename
            if target.exists() and target.is_file():
                direct_id = f"dir_{hashlib.md5(str(target.resolve()).encode()).hexdigest()[:8]}"
                FILE_REGISTRY[direct_id] = target.resolve()
                return {
                    "found": True,
                    "upload_id": direct_id,
                    "filename": target.name,
                    "full_path": str(target.resolve()),
                    "size_mb": round(target.stat().st_size / (1024 * 1024), 1),
                    "is_direct": True
                }
# --- WATERMARK PERSISTENCE ENDPOINTS ---

@app.get("/api/watermark")
async def get_watermark():
    settings = load_settings()
    return {"watermark": settings.get("watermark", "")}

@app.post("/api/watermark")
async def set_watermark(req: WatermarkRequest):
    wm = (req.watermark or "").strip()
    save_settings({"watermark": wm})
    return {"status": "success", "watermark": wm}

@app.delete("/api/watermark")
async def delete_watermark():
    save_settings({"watermark": ""})
    return {"status": "success", "watermark": ""}

@app.post("/api/cleanup")
async def cleanup_temp_files():
    count = 0
    try:
        for p in JOBS_DIR.iterdir():
            if p.is_dir():
                shutil.rmtree(p, ignore_errors=True)
                count += 1
    except Exception:
        pass
    return {"status": "success", "cleaned_dirs": count}

@app.delete("/api/outputs/{job_id}")
async def delete_single_output(job_id: str):
    found = False
    deleted_filename = None
    freed_mb = 0.0

    if job_id in JOBS and JOBS[job_id].get("output_file"):
        f = Path(JOBS[job_id]["output_file"])
        if f.exists():
            try:
                freed_mb = round(f.stat().st_size / (1024 * 1024), 2)
                deleted_filename = f.name
                f.unlink(missing_ok=True)
                found = True
            except Exception:
                pass
        JOBS[job_id]["output_file"] = None

    for p in OUTPUTS_DIR.glob(f"{job_id}*"):
        try:
            if p.is_file():
                freed_mb = round(p.stat().st_size / (1024 * 1024), 2)
                deleted_filename = p.name
                p.unlink(missing_ok=True)
                found = True
        except Exception:
            pass

    return {
        "status": "success" if found else "not_found",
        "deleted_filename": deleted_filename,
        "freed_mb": freed_mb
    }

@app.post("/api/outputs/cleanup")
async def cleanup_all_outputs():
    deleted_count = 0
    total_freed_mb = 0.0
    locked_count = 0
    try:
        for f in OUTPUTS_DIR.iterdir():
            if f.is_file() and f.suffix.lower() in [".mp4", ".mov", ".mkv", ".ts"]:
                try:
                    size = f.stat().st_size
                    f.unlink(missing_ok=True)
                    deleted_count += 1
                    total_freed_mb += size / (1024 * 1024)
                except PermissionError:
                    locked_count += 1
                except Exception:
                    pass
        for jid in JOBS:
            JOBS[jid]["output_file"] = None
    except Exception as e:
        return {"status": "error", "message": str(e)}

    return {
        "status": "success",
        "deleted_count": deleted_count,
        "locked_count": locked_count,
        "freed_mb": round(total_freed_mb, 1)
    }


@app.post("/api/process")
async def start_process(
    upload_id: str = Form(...),
    watermark: Optional[str] = Form(None),
    zoom: Optional[float] = Form(1.0),
    y_offset: Optional[float] = Form(0.0),
    duration_range: Optional[str] = Form("30-60"),
    enable_visual_ai: Optional[bool] = Form(True),
    resolution: Optional[str] = Form("720p"),
    background_tasks: BackgroundTasks = BackgroundTasks()
):
    video_path = resolve_video_path(upload_id)

    zoom_val = float(zoom or 1.0)
    y_offset_val = float(y_offset or 0.0)
    dur_range_val = duration_range or "30-60"
    vis_ai_val = bool(enable_visual_ai if enable_visual_ai is not None else True)
    res_val = str(resolution or "720p").lower()
    if res_val not in ["720p", "1080p"]:
        res_val = "720p"

    effective_wm = None
    if watermark is not None and watermark.strip():
        effective_wm = watermark.strip()
    else:
        saved_wm = load_settings().get("watermark", "")
        if saved_wm:
            effective_wm = saved_wm

    job_id = f"job_{int(time.time())}_{uuid.uuid4().hex[:6]}"
    JOBS[job_id] = {
        "job_id": job_id,
        "upload_id": upload_id,
        "watermark": effective_wm,
        "zoom": zoom_val,
        "y_offset": y_offset_val,
        "duration_range": dur_range_val,
        "enable_visual_ai": vis_ai_val,
        "resolution": res_val,
        "status": "processing",
        "stage": "INIT",
        "percent": 0,
        "logs": [],
        "created_at": time.time(),
        "finished_at": None,
        "output_file": None
    }
    EVENT_QUEUES[job_id] = []

    background_tasks.add_task(
        run_job_pipeline, 
        job_id, 
        video_path, 
        effective_wm,
        zoom_val,
        y_offset_val,
        dur_range_val,
        vis_ai_val,
        None,
        res_val
    )

    return {"status": "started", "job_id": job_id}

@app.post("/api/plan-clips")
async def plan_clips_preview(
    upload_id: str = Form(...),
    duration_range: Optional[str] = Form("30-60"),
    enable_visual_ai: Optional[bool] = Form(True)
):
    video_path = resolve_video_path(upload_id)

    plan_id = f"plan_{int(time.time())}_{uuid.uuid4().hex[:6]}"
    plan_work = JOBS_DIR / plan_id
    plan_work.mkdir(parents=True, exist_ok=True)
    audio_mp3 = plan_work / "audio.mp3"

    def noop_log(stage, pct, msg, extra=None):
        pass

    duration = get_video_duration(video_path)
    extract_audio(video_path, audio_mp3, noop_log)
    segments = transcribe_with_groq_fallback(audio_mp3, plan_work, noop_log)
    clips = analyze_and_plan_clips(
        segments, duration, noop_log,
        duration_range=duration_range or "30-60",
        video_path=video_path,
        work_dir=plan_work,
        enable_visual_ai=bool(enable_visual_ai if enable_visual_ai is not None else True)
    )

    formatted_clips = []
    for i, c in enumerate(clips, 1):
        has_thumb = (plan_work / "thumbs" / f"clip_{i}.jpg").exists()
        formatted_clips.append({
            "clip_id": i,
            "kind": c.get("reason", "body"),
            "start": round(float(c["start"]), 2),
            "end": round(float(c["end"]), 2),
            "duration": round(float(c["end"]) - float(c["start"]), 2),
            "text": c.get("text", ""),
            "visual_status": c.get("visual_status", "Visual & Penjelasan Tuntas"),
            "visual_score": c.get("visual_score", 8),
            "thumb_url": f"/api/thumb/{plan_id}/{i}" if has_thumb else None
        })

    return {
        "status": "success",
        "plan_id": plan_id,
        "video_duration": round(duration, 2),
        "clips": formatted_clips
    }

@app.get("/api/thumb/{job_id}/{clip_idx}")
async def get_clip_thumbnail(job_id: str, clip_idx: int):
    thumb_path = JOBS_DIR / job_id / "thumbs" / f"clip_{clip_idx}.jpg"
    if not thumb_path.exists():
        raise HTTPException(status_code=404, detail="Thumbnail tidak ditemukan.")
    return FileResponse(thumb_path, media_type="image/jpeg")

@app.get("/api/source-video/{upload_id}")
async def get_source_video(upload_id: str):
    vid_path = resolve_video_path(upload_id)
    return FileResponse(vid_path, media_type="video/mp4")

class CustomRenderRequest(BaseModel):
    upload_id: str
    clips: list
    watermark: Optional[str] = None
    zoom: Optional[float] = 1.0
    y_offset: Optional[float] = 0.0
    resolution: Optional[str] = "720p"

@app.post("/api/render-custom")
async def render_custom_clips(
    req: CustomRenderRequest,
    background_tasks: BackgroundTasks
):
    video_path = resolve_video_path(req.upload_id)
    res_val = str(req.resolution or "720p").lower()
    if res_val not in ["720p", "1080p"]:
        res_val = "720p"

    effective_wm = None
    if req.watermark is not None and req.watermark.strip():
        effective_wm = req.watermark.strip()
    else:
        saved_wm = load_settings().get("watermark", "")
        if saved_wm:
            effective_wm = saved_wm

    job_id = f"job_{int(time.time())}_{uuid.uuid4().hex[:6]}"
    JOBS[job_id] = {
        "job_id": job_id,
        "upload_id": req.upload_id,
        "watermark": effective_wm,
        "zoom": float(req.zoom or 1.0),
        "y_offset": float(req.y_offset or 0.0),
        "duration_range": "custom",
        "resolution": res_val,
        "status": "processing",
        "stage": "INIT",
        "percent": 0,
        "logs": [],
        "created_at": time.time(),
        "finished_at": None,
        "output_file": None
    }
    EVENT_QUEUES[job_id] = []

    background_tasks.add_task(
        run_job_pipeline,
        job_id,
        video_path,
        effective_wm,
        float(req.zoom or 1.0),
        float(req.y_offset or 0.0),
        "custom",
        False,
        req.clips,
        res_val
    )

    return {"status": "started", "job_id": job_id}

@app.get("/api/jobs/{job_id}")
async def get_job_state(job_id: str):
    if job_id not in JOBS:
        raise HTTPException(status_code=404, detail="Job tidak ditemukan.")
    return JOBS[job_id]

@app.get("/api/jobs/{job_id}/stream")
async def stream_job_events(job_id: str):
    if job_id not in JOBS:
        raise HTTPException(status_code=404, detail="Job tidak ditemukan.")

    q = asyncio.Queue()
    if job_id not in EVENT_QUEUES:
        EVENT_QUEUES[job_id] = []
    EVENT_QUEUES[job_id].append(q)

    # Initial replay of past logs
    for log_item in JOBS[job_id]["logs"]:
        q.put_nowait(log_item)

    async def event_generator():
        try:
            while True:
                item = await q.get()
                yield f"data: {json.dumps(item)}\n\n"
                if item.get("stage") in ["COMPLETED", "ERROR"]:
                    break
        except asyncio.CancelledError:
            pass
        finally:
            if job_id in EVENT_QUEUES and q in EVENT_QUEUES[job_id]:
                EVENT_QUEUES[job_id].remove(q)

    return StreamingResponse(event_generator(), media_type="text/event-stream")

@app.get("/api/video/{job_id}")
async def get_result_video(job_id: str, download: bool = False):
    if job_id not in JOBS or not JOBS[job_id].get("output_file"):
        raise HTTPException(status_code=404, detail="Video belum selesai atau tidak ditemukan.")
    file_path = Path(JOBS[job_id]["output_file"])
    if not file_path.exists():
        raise HTTPException(status_code=404, detail="File video fisik tidak ditemukan.")
    
    headers = {}
    if download:
        headers["Content-Disposition"] = f'attachment; filename="dantez_x_mamank_recing_{job_id}.mp4"'
    
    return FileResponse(file_path, media_type="video/mp4", headers=headers)

@app.get("/", response_class=HTMLResponse)
async def serve_index():
    index_file = STATIC_DIR / "index.html"
    if not index_file.exists():
        return HTMLResponse("<h1>Web UI sedang disiapkan...</h1>")
    return HTMLResponse(index_file.read_text(encoding="utf-8"))

app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app:app", host="0.0.0.0", port=8000, reload=True)
