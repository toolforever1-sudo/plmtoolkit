@echo off
REM =====================================================================
REM  PLM Toolkit watchdog — restarts the JVM if it exits for any reason
REM  (clean exit, crash, OOM trigger from -XX:+ExitOnOutOfMemoryError).
REM
REM  How to stop:
REM    1) run stop.bat (kills the running JVM)
REM    2) AND create the sentinel file D:\plm-toolkit\STOP   (touch it)
REM       — without the sentinel, this loop will respawn the JVM
REM
REM  This is a quick-and-simple wrapper. For production-grade supervision,
REM  install as a Windows Service via NSSM (see nssm-install.txt).
REM =====================================================================

REM ---- Pin the working directory ----
REM The JVM uses relative paths for ./data/feedback-queue.json and friends, so
REM the cwd MUST be D:\plm-toolkit no matter how the watchdog was launched. If
REM a future deploy.bat or a manual restart starts the watchdog from system32
REM (e.g. a fresh elevated cmd), inheriting that cwd would make the JVM look
REM for the queue under C:\Windows\system32\data\ — empty file, lost tickets.
REM Caught after that bit users on 2026-05-13. Pin it here as belt-and-braces.
cd /d D:\plm-toolkit

if not exist "D:\plm-toolkit\heapdumps" mkdir "D:\plm-toolkit\heapdumps"
if not exist "D:\plm-toolkit\logs"      mkdir "D:\plm-toolkit\logs"

REM Prune OOM heap dumps older than 14 days — each is 8-10 GB and they were
REM silently piling up (~82 GB by Jul 2026). Anything worth analyzing gets
REM looked at within days of the crash; after that it's just disk pressure.
forfiles /P "D:\plm-toolkit\heapdumps" /M *.hprof /D -14 /C "cmd /c del @path" 2>nul

REM Clear any prior STOP sentinel so this run actually loops.
if exist "D:\plm-toolkit\STOP" del /Q "D:\plm-toolkit\STOP"

set JAVA_BIN=D:\java\jdk17\bin\java
set JAR=D:\plm-toolkit\plm-field-tracker-1.0.1.jar
set CONFIG=D:/plm-toolkit/config/application.properties
set APP_LOG=D:\plm-toolkit\logs\plm-toolkit.log
set WATCHDOG_LOG=D:\plm-toolkit\logs\watchdog.log
set REJECTION_QUERY_TIMEOUT_SEC=1800

:loop
REM Belt-and-braces: re-check STOP at the top of every iteration. The post-JVM
REM check below covers most cases, but if a duplicate watchdog window is alive
REM when deploy.bat runs, this top-of-loop check lets the orphan exit on its
REM own next pass even if deploy.bat's PowerShell kill missed it.
if exist "D:\plm-toolkit\STOP" (
    echo [%date% %time%] STOP sentinel detected at top of loop — exiting. >> "%WATCHDOG_LOG%"
    goto end
)
echo [%date% %time%] starting JVM >> "%WATCHDOG_LOG%"

REM UseStringDeduplication: the 786K-row item cache repeats the same column
REM values millions of times — G1 dedups the backing char arrays for free.
REM InitiatingHeapOccupancyPercent=30: start concurrent GC cycles earlier so
REM old-gen garbage stops accumulating to 85% between big report allocations
REM (live set is only ~2.1 GB of the 6 GB heap).
"%JAVA_BIN%" ^
  -Xmx6g ^
  -XX:+UseG1GC ^
  -XX:+UseStringDeduplication ^
  -XX:InitiatingHeapOccupancyPercent=30 ^
  -XX:+ExitOnOutOfMemoryError ^
  -XX:+HeapDumpOnOutOfMemoryError ^
  -XX:HeapDumpPath=D:\plm-toolkit\heapdumps ^
  -Dfile.encoding=UTF-8 ^
  -jar "%JAR%" ^
  --spring.config.additional-location=%CONFIG% ^
  >> "%APP_LOG%" 2>&1

set EXITCODE=%ERRORLEVEL%
echo [%date% %time%] JVM exited with code %EXITCODE% >> "%WATCHDOG_LOG%"

REM Honor the STOP sentinel so we don't fight the operator.
if exist "D:\plm-toolkit\STOP" (
    echo [%date% %time%] STOP sentinel present — watchdog exiting cleanly. >> "%WATCHDOG_LOG%"
    del /Q "D:\plm-toolkit\STOP"
    goto end
)

REM Backoff so we don't tight-loop if the JVM dies in the first second.
echo [%date% %time%] respawning in 5 seconds... >> "%WATCHDOG_LOG%"
timeout /t 5 /nobreak >nul
goto loop

:end
echo Watchdog stopped.
