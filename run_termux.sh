#!/data/data/com.termux/files/usr/bin/bash
echo "========================================================"
echo "       DANTEZ X MAMANK RECING - AUTO CLIP STUDIO"
echo "========================================================"
echo "Memulai server di Android Termux..."
echo "Buka browser di: http://127.0.0.1:8000"
echo "========================================================"
export VERBOO_API_KEY="vbk_pro_dc22f73b35587571924cbbf2425cb5a8dc061084"
python -m uvicorn app:app --host 0.0.0.0 --port 8000
