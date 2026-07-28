@echo off
setlocal enabledelayedexpansion
REM =====================================================================
REM  promote-agile.bat  -  manually promote the staging agile-service jar
REM  to live, with a backup of the current live jar. Observable: prints
REM  every step and PAUSEs at the end so the window stays open.
REM
REM  Drive-agnostic: derives all paths from this script's own folder
REM  (%~dp0), so the same file works on QA (F:) and prod (D:).
REM
REM  The agile-service JVM must be STOPPED first (stop-agile.bat) - Windows
REM  locks the running jar and the copy will fail while it is up.
REM
REM  Usage:
REM    promote-agile.bat            - interactive, PAUSEs at the end
REM    promote-agile.bat nopause    - no pause (run-agile-loop calls it this way)
REM =====================================================================

echo ============================================================
echo  Promote staging agile-service jar to live
echo ============================================================

set "LIVE_DIR=%~dp0"
set "LIVE=%LIVE_DIR%plm-agile-service-1.0.0.jar"
set "STAGE=%LIVE_DIR%staging\plm-agile-service-1.0.0.jar"
set "BACKUPS=%LIVE_DIR%backups"

echo Folder : %LIVE_DIR%
echo Live   : %LIVE%
echo Stage  : %STAGE%
echo.

if not exist "%STAGE%" (
    echo [SKIP] No staging jar at "%STAGE%" - nothing to promote.
    goto done
)
if not exist "%LIVE%" (
    echo [INFO] No live jar present - will copy staging straight in.
    goto docopy
)

echo Live  timestamp/size:
powershell -NoProfile -Command "$f=Get-Item '%LIVE%'; '   {0}   {1} bytes' -f $f.LastWriteTime,$f.Length"
echo Stage timestamp/size:
powershell -NoProfile -Command "$f=Get-Item '%STAGE%'; '   {0}   {1} bytes' -f $f.LastWriteTime,$f.Length"
echo.

set "CMP="
for /f "usebackq delims=" %%R in (`powershell -NoProfile -Command "if((Get-Item '%STAGE%').LastWriteTime -gt (Get-Item '%LIVE%').LastWriteTime){'NEWER'}else{'NOTNEWER'}"`) do set "CMP=%%R"
echo Staging is %CMP% than live.
if /I not "%CMP%"=="NEWER" (
    echo [SKIP] Live jar is already current - no promotion needed.
    goto done
)

:docopy
if not exist "%BACKUPS%" mkdir "%BACKUPS%"
set "STAMP=%date%_%time%"
set "STAMP=!STAMP:/=-!"
set "STAMP=!STAMP::=-!"
set "STAMP=!STAMP: =-!"
set "STAMP=!STAMP:.=-!"
set "STAMP=!STAMP:,=-!"
if exist "%LIVE%" (
    echo Backing up live jar -^> %BACKUPS%\plm-agile-service-1.0.0.jar.bak.!STAMP!
    copy /Y "%LIVE%" "%BACKUPS%\plm-agile-service-1.0.0.jar.bak.!STAMP!" >nul
    if errorlevel 1 echo [WARN] backup copy reported an error.
)
echo Copying staging -^> live ...
copy /Y "%STAGE%" "%LIVE%"
if errorlevel 1 (
    echo.
    echo *** COPY FAILED. The live jar is most likely still LOCKED by a
    echo *** running agile-service JVM. Stop it first ^(stop-agile.bat, or
    echo *** close the watchdog window^) and run this again.
) else (
    echo.
    echo *** PROMOTION COMPLETE. New live jar is in place.
    powershell -NoProfile -Command "$f=Get-Item '%LIVE%'; 'New live: {0}   {1} bytes' -f $f.LastWriteTime,$f.Length"
)

:done
echo.
echo ============================================================
if /I not "%~1"=="nopause" pause
endlocal
