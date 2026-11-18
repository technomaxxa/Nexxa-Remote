@echo off
setlocal
cd /d "%~dp0"
set "JAVA_EXE=%~dp0runtime\bin\java.exe"
if not exist "%JAVA_EXE%" (
  echo Nexxa private Java 25 runtime is not installed. Setting it up now...
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install-Bundled-Runtime.ps1"
  if errorlevel 1 (
    echo ERROR: Runtime setup failed.
    pause
    exit /b 1
  )
)
"%JAVA_EXE%" -Djava.awt.headless=true -jar "%~dp0NexxaRemote.jar" --self-test
if errorlevel 1 (
  echo Nexxa self-test FAILED.
) else (
  echo Nexxa self-test PASSED.
)
pause
endlocal
