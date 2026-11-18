@echo off
setlocal
cd /d "%~dp0"
echo ============================================================
echo  Nexxa Remote 0.7.5 - Source build
echo ============================================================
where javac.exe >nul 2>nul
if errorlevel 1 (
  echo ERROR: javac was not found.
  echo Install a JDK 21 or newer. A JRE is not enough to compile.
  echo https://adoptium.net/temurin/releases/?version=21
  pause
  exit /b 1
)
if exist build rmdir /s /q build
mkdir build
javac --release 21 -encoding UTF-8 -Xlint:all -d build NexxaRemote.java
if errorlevel 1 (
  echo.
  echo ERROR: Java compilation failed.
  pause
  exit /b 1
)
>manifest.mf echo Manifest-Version: 1.0
>>manifest.mf echo Main-Class: NexxaRemote
>>manifest.mf echo Implementation-Title: Nexxa Remote
>>manifest.mf echo Implementation-Version: 0.7.5
jar --create --file NexxaRemote.jar --manifest manifest.mf -C build .
if errorlevel 1 (
  echo.
  echo ERROR: JAR packaging failed.
  pause
  exit /b 1
)
echo.
echo Build successful: NexxaRemote.jar
pause
endlocal
