@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Unregister-Nexxa-Link-Handler.ps1"
pause
endlocal
