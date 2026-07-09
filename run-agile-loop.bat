@echo off
REM =====================================================================
REM  plm-agile-service watchdog — restarts the JVM if it exits for any
REM  reason (clean exit, crash, OOM trigger, host log-out, etc.).
REM
REM  Matches run-loop.bat for the toolkit. Without this wrapper,
REM  run-agile.bat is a single-shot launcher: when the JVM dies, port
REM  8081 stops listening and every per-user write-back / IMS Review
REM  cascade silently fails until somebody notices and re-runs it
REM  manually. That gap stranded Bibi's DRR-0018775 for ~22 hours on
REM  2026-06-04/05 — agile-service started 6/4 15:43, died sometime
REM  overnight, stayed down till 6/5 13:35 when Vikas manually restarted.
REM
REM  How to stop:
REM    1) run stop-agile.bat (kills the running JVM by port 8081)
REM    2) AND create the sentinel file D:\plm-toolkit\STOP_AGILE
REM       — without the sentinel, this loop will respawn the JVM
REM    3) Or use stop-all.bat which also sets D:\plm-toolkit\STOP and
REM       kills the toolkit at the same time.
REM
REM  Watchdog log: D:\plm-toolkit\logs\watchdog-agile.log
REM  App log:      D:\plm-toolkit\logs\agile-service.log
REM =====================================================================

REM ---- Pin the working directory ----
REM agile-service uses relative paths for ./cache/admin-list-cache.ser and
REM friends, so the cwd MUST be D:\plm-toolkit regardless of how the
REM watchdog was launched. Same lesson as run-loop.bat (caught 2026-05-13
REM when the toolkit's watchdog inherited C:\Windows\system32 from a UAC
REM elevation and lost its on-disk queue).
cd /d D:\plm-toolkit

if not exist "D:\plm-toolkit\heapdumps" mkdir "D:\plm-toolkit\heapdumps"
if not exist "D:\plm-toolkit\logs"      mkdir "D:\plm-toolkit\logs"

REM Clear any prior STOP_AGILE sentinel so this run actually loops.
if exist "D:\plm-toolkit\STOP_AGILE" del /Q "D:\plm-toolkit\STOP_AGILE"

set JAVA_BIN=java
set JAR=D:\plm-toolkit\plm-agile-service-1.0.0.jar
set CONFIG=D:/plm-toolkit/config/agile-service.properties
set APP_LOG=D:\plm-toolkit\logs\agile-service.log
set WATCHDOG_LOG=D:\plm-toolkit\logs\watchdog-agile.log

:loop
REM Belt-and-braces: re-check STOP/STOP_AGILE at the top of every iteration.
REM The post-JVM check below covers most cases, but if a duplicate watchdog
REM window is alive when a deploy/stop runs, this top-of-loop check lets the
REM orphan exit on its own next pass even if the PowerShell kill missed it.
REM Honors BOTH the global STOP (so stop-all.bat takes everything down) and
REM the agile-specific STOP_AGILE (so we can bounce just this service).
if exist "D:\plm-toolkit\STOP" (
    echo [%date% %time%] global STOP sentinel — exiting. >> "%WATCHDOG_LOG%"
    goto end
)
if exist "D:\plm-toolkit\STOP_AGILE" (
    echo [%date% %time%] STOP_AGILE sentinel — exiting. >> "%WATCHDOG_LOG%"
    goto end
)
echo [%date% %time%] starting agile-service JVM >> "%WATCHDOG_LOG%"

REM Memory: agile-service holds the long-lived SDK session, the admin-list
REM cache, and the per-user / write-back machinery. 2g is plenty (observed
REM steady-state ~600-800 MB on prod). ExitOnOutOfMemoryError so the
REM watchdog respawns instead of leaving a wedged JVM eating RAM. Heap
REM dump path shared with the toolkit so post-mortems live in one place.
"%JAVA_BIN%" ^
  -Xmx2g ^
  -XX:+UseG1GC ^
  -XX:+ExitOnOutOfMemoryError ^
  -XX:+HeapDumpOnOutOfMemoryError ^
  -XX:HeapDumpPath=D:\plm-toolkit\heapdumps ^
  -Dfile.encoding=UTF-8 ^
  -jar "%JAR%" ^
  --spring.config.additional-location=%CONFIG% ^
  >> "%APP_LOG%" 2>&1

set EXITCODE=%ERRORLEVEL%
echo [%date% %time%] agile-service JVM exited with code %EXITCODE% >> "%WATCHDOG_LOG%"

REM Honor the STOP sentinels after the JVM exits so the operator can take
REM us down between iterations.
if exist "D:\plm-toolkit\STOP" (
    echo [%date% %time%] global STOP present — watchdog exiting cleanly. >> "%WATCHDOG_LOG%"
    goto end
)
if exist "D:\plm-toolkit\STOP_AGILE" (
    echo [%date% %time%] STOP_AGILE present — watchdog exiting cleanly. >> "%WATCHDOG_LOG%"
    del /Q "D:\plm-toolkit\STOP_AGILE"
    goto end
)

REM Backoff so we don't tight-loop if the JVM dies in the first second.
echo [%date% %time%] respawning agile-service in 5 seconds... >> "%WATCHDOG_LOG%"
timeout /t 5 /nobreak >nul
goto loop

:end
echo Agile-service watchdog stopped.
