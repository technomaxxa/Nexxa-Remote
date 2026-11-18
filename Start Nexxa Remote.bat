@echo off
setlocal
cd /d "%~dp0"
set "APP_DIR=%~dp0"
set "JAVA_EXE=%APP_DIR%runtime\bin\javaw.exe"

if not exist "%JAVA_EXE%" (
  echo Nexxa private Java 25 runtime is not installed yet.
  echo The installer downloads and verifies Temurin first, then uses Oracle as fallback.
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%APP_DIR%Install-Bundled-Runtime.ps1"
  if errorlevel 1 (
    echo.
    echo ERROR: Private Java runtime setup failed.
    pause
    exit /b 1
  )
)

if not exist "%JAVA_EXE%" (
  echo ERROR: runtime\bin\javaw.exe was not found after setup.
  pause
  exit /b 1
)

start "" "%JAVA_EXE%" -Dfile.encoding=UTF-8 -jar "%APP_DIR%NexxaRemote.jar" %*
endlocal
