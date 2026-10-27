@echo off
title dantez x mamank recing - Studio
echo ========================================================
echo        DANTEZ X MAMANK RECING - AUTO CLIP STUDIO
echo ========================================================
echo Server sedang berjalan di: http://localhost:8000
echo Tekan Ctrl+C di terminal ini untuk mematikan server.
echo ========================================================
set VERBOO_API_KEY=vbk_pro_dc22f73b35587571924cbbf2425cb5a8dc061084
start http://localhost:8000
py -m uvicorn app:app --host 0.0.0.0 --port 8000 || python -m uvicorn app:app --host 0.0.0.0 --port 8000
pause
