@echo off
setlocal
cd /d "%~dp0"
echo This creates a second ZIP with Nexxa and its private Java 25 runtime.
echo The Temurin download is about 56 MB; the Oracle fallback is about 206 MB.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Build Offline Full Bundle.ps1"
if errorlevel 1 (
  echo.
  echo Could not create the full bundle. See the error above.
  pause
  exit /b 1
)
echo.
echo Full offline bundle created in the parent folder.
pause
endlocal
