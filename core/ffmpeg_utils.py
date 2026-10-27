import os
import shutil
import subprocess
from pathlib import Path

def find_ffmpeg_bin():
    """
    Find ffmpeg executable.
    Checks system PATH, common winget/local paths, D:\ffmpeg-bin, etc.
    """
    # 1. System PATH
    ffmpeg = shutil.which("ffmpeg")
    ffprobe = shutil.which("ffprobe")
    if ffmpeg and ffprobe:
        return Path(ffmpeg), Path(ffprobe)

    # 2. Check Local AppData winget / Gyan.FFmpeg / Chocolatey / Scoop
    candidates = [
        Path(os.environ.get("LOCALAPPDATA", "")) / "Microsoft" / "WinGet" / "Packages",
        Path("C:/Program Files/ffmpeg/bin"),
        Path("C:/ffmpeg/bin"),
        Path("D:/ffmpeg-bin"),
        Path("D:/ffmpeg/bin")
    ]

    for base in candidates:
        if base.exists():
            ffmpegs = list(base.glob("**/ffmpeg.exe"))
            ffprobes = list(base.glob("**/ffprobe.exe"))
            if ffmpegs and ffprobes:
                # Add to PATH so subprocess finds it easily
                bin_dir = str(ffmpegs[0].parent)
                if bin_dir not in os.environ.get("PATH", ""):
                    os.environ["PATH"] = bin_dir + os.pathsep + os.environ.get("PATH", "")
                return ffmpegs[0], ffprobes[0]

    return None, None

def get_ffmpeg_paths():
    ffmpeg, ffprobe = find_ffmpeg_bin()
    return {
        "ffmpeg": str(ffmpeg) if ffmpeg else "ffmpeg",
        "ffprobe": str(ffprobe) if ffprobe else "ffprobe",
        "available": ffmpeg is not None and ffprobe is not None
    }
