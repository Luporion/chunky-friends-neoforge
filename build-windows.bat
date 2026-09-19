@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo Pruefe Java-Version...
where java >nul 2>nul
if errorlevel 1 (
  echo.
  echo FEHLER: Java wurde nicht gefunden.
  echo Fuer Minecraft 1.21.1 / NeoForge wird Java 21 benoetigt.
  echo Installieren z.B. mit: winget install EclipseAdoptium.Temurin.21.JDK
  pause
  exit /b 1
)

for /f "tokens=3" %%V in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VERSION_RAW=%%~V"
set "JAVA_VERSION_RAW=%JAVA_VERSION_RAW:\"=%"
for /f "tokens=1 delims=." %%M in ("%JAVA_VERSION_RAW%") do set "JAVA_MAJOR=%%M"

if not "%JAVA_MAJOR%"=="21" (
  echo.
  echo FEHLER: Dieser Build braucht Java 21, aktuell wird Java %JAVA_VERSION_RAW% verwendet.
  echo.
  java -version
  echo.
  echo Pruefe PATH / JAVA_HOME oder oeffne nach der Java-21-Installation ein neues Terminal.
  pause
  exit /b 1
)

echo Java %JAVA_VERSION_RAW% OK.
echo.
call gradlew.bat clean build
if errorlevel 1 (
  echo.
  echo Build fehlgeschlagen. Siehe Ausgabe oben.
  pause
  exit /b 1
)

echo.
echo Build erfolgreich. JAR liegt unter build\libs\
explorer build\libs
pause
