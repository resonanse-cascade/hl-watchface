@echo off
REM Build the watch face APK, and install it if a watch is connected over adb.
REM
REM   build.bat              build only
REM   build.bat --install    build, then install to the connected watch
REM
REM Run setup_assets.py first if you want the Half-Life artwork; the face builds
REM and runs fine without it.

setlocal enabledelayedexpansion
cd /d "%~dp0"

REM Android Studio ships a JDK, and most people have no system Java. Find one.
if "%JAVA_HOME%"=="" (
  for %%D in (
    "%ProgramFiles%\Android\Android Studio\jbr"
    "%ProgramFiles%\Android\Android Studio\jre"
    "%LOCALAPPDATA%\Programs\Android Studio\jbr"
  ) do (
    if exist "%%~D\bin\java.exe" (
      set "JAVA_HOME=%%~D"
      goto :found_java
    )
  )
  where java >nul 2>nul
  if errorlevel 1 (
    echo ERROR: No Java found. Install Android Studio, or set JAVA_HOME.
    exit /b 1
  )
)
:found_java

REM The SDK path is machine-specific, so it is not committed. Derive it if missing.
if not exist local.properties (
  set "SDK=%ANDROID_HOME%"
  if "!SDK!"=="" set "SDK=%ANDROID_SDK_ROOT%"
  if "!SDK!"=="" set "SDK=%LOCALAPPDATA%\Android\Sdk"
  if not exist "!SDK!" (
    echo ERROR: Android SDK not found. Set ANDROID_HOME, or install Android Studio.
    exit /b 1
  )
  REM Gradle wants forward slashes here even on Windows.
  set "SDKESC=!SDK:\=/!"
  echo sdk.dir=!SDKESC!> local.properties
  echo Wrote local.properties -^> !SDK!
)

if exist "app\src\main\res\drawable\hl_egon.png" (
  echo Half-Life artwork: present
) else (
  echo Half-Life artwork: none ^(face will use fallbacks; see setup_assets.py^)
)

call gradlew.bat assembleDebug
if errorlevel 1 exit /b 1

set "APK=app\build\outputs\apk\debug\app-debug.apk"
echo.
echo Built: %APK%

if /i "%~1"=="--install" (
  for /f "tokens=1" %%A in ('adb devices ^| findstr /r /c:"device$"') do (
    set "DEV=%%A"
    goto :got_device
  )
  echo No watch connected over adb. See the README for pairing.
  exit /b 1
  :got_device
  echo Installing to !DEV! ...
  adb -s !DEV! install -r "%APK%"
  echo Done. On the watch: long-press the face -^> Customize.
)
endlocal
