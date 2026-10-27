@echo off
echo ========================================================
echo   Installing Dantez Cliping v1.2.9 (Disjoint Timestamps)
echo ========================================================
"C:\Users\thinkpad\AppData\Local\Android\Sdk\platform-tools\adb.exe" wait-for-device
echo HP terdeteksi! Menginstall APK...
"C:\Users\thinkpad\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r -d "%~dp0DantezCliping_v1.2.9.apk"
"C:\Users\thinkpad\AppData\Local\Android\Sdk\platform-tools\adb.exe" push "%~dp0DantezCliping_v1.2.9.apk" "/sdcard/Download/DantezCliping_v1.2.9.apk"
echo Menjalankan aplikasi...
"C:\Users\thinkpad\AppData\Local\Android\Sdk\platform-tools\adb.exe" shell am start -n com.dantez.autoclip/.ui.MainActivity
echo Selesai!
pause
