@echo off
REM =====================================================================
REM  deploy.bat — atomic JAR deploy for PLM Toolkit on Windows
REM
REM  Usage:
REM    deploy.bat ^<path-to-new-jar^>             (graceful — DEFAULT)
REM    deploy.bat --force ^<path-to-new-jar^>     (skip the 2-min warning)
REM
REM  Graceful (default) — PT-56 fix:
REM    POSTs to the toolkit's localhost-only /api/maintenance/schedule-from-deploy.
REM    The SERVER decides what to do based on who's logged in right now:
REM      • Only deploy admins (Vikas / Krati / app.maintenance.allowed-users)
REM        or nobody active → returns shouldWait=false. We proceed
REM        IMMEDIATELY with no banner / email / wait.
REM      • Any other (non-admin) user active → server fires a 5-minute
REM        in-app banner + email to those users and returns
REM        shouldWait=true, waitSeconds=300. We sleep ~310s so the JVM has
REM        cleanly System.exit'd before we touch any files.
REM
REM  Why graceful is the default: deployed mid-feedback once with no
REM  warning (PT-56), Jimmy lost his form data. When real users are on
REM  the box we owe them 5 minutes. When nobody is, we don't waste them.
REM
REM  --force: skip the server check entirely. Use for genuine emergencies
REM  (security patch, JVM is wedged, etc.) where any wait is unacceptable.
REM
REM  What it does (in order):
REM    0. (graceful only) Fire 2-min warning + sleep 130s
REM    1. Validate the staged JAR (exists, looks reasonable in size)
REM    1b. PARITY GATE: staged JAR must contain every .class the LIVE jar has
REM        (else HALT before touching anything — catches stale/wrong builds that
REM        would revert a feature). Skipped on first deploy / with --allow-class-loss.
REM    2. Touch STOP sentinel so the watchdog will exit instead of respawn
REM    3. Kill the running JVM (calls stop.bat, falls back to taskkill)
REM    4. Wait for port 8090 to free up (up to 30s)
REM    5. Back up the live JAR with a timestamp
REM    6. Copy the staged JAR into place (this updates the JAR's mtime,
REM       which is the signal MaintenanceService.init() uses to auto-exit
REM       maintenance mode on the next boot)
REM    7. Delete the STOP sentinel
REM    8. Re-launch the watchdog (detached, minimized window)
REM    9. Poll HTTP until the app answers (up to 60s)
REM
REM  Why a STOP sentinel during the swap: without it, the watchdog might
REM  respawn the OLD JAR in the 5-second gap between killing the JVM and
REM  finishing the file copy. Pinning the watchdog with STOP guarantees
REM  the new JAR is in place before the next java.exe starts.
REM
REM  Exit codes:
REM    0  = success
REM    1  = runtime failure (copy failed, app didn't come up, parity gate HALT)
REM    2  = bad input (missing argument, staged JAR not found)
REM =====================================================================

REM ---- Self-elevate if not running as administrator ----
REM Without this, a deploy from a non-elevated cmd silently fails its
REM cross-user process kills. Stop-Process / taskkill cannot terminate
REM processes owned by another domain user without the unfiltered admin
REM token, even when both users are in the local Administrators group
REM (UAC token-splitting). Symptom seen 2026-05-13: Krati ran deploy.bat
REM from her own non-elevated cmd, our stop step silently no-op'd against
REM Vikas's watchdog + JVM, a second watchdog launched in parallel, both
REM kept respawning JVMs that died on port collision -> prod outage.
REM
REM `net session` is the cheapest "am I elevated?" probe -- it requires
REM admin and returns errorlevel >= 1 if not. On failure we re-launch
REM ourselves via PowerShell -Verb RunAs, which triggers the UAC prompt.
REM `cmd /k` keeps the new elevated window open after the deploy so the
REM operator can read the result and any warnings before closing.
net session >nul 2>&1
if errorlevel 1 (
    echo [deploy] not running as administrator
    echo [deploy] requesting elevation via UAC...
    powershell -NoProfile -Command "Start-Process cmd -ArgumentList '/k', '""%~f0"" %*' -Verb RunAs"
    exit /b 0
)

REM ---- Pin the working directory ----
REM UAC elevation drops the new cmd into C:\Windows\system32 regardless of where
REM deploy.bat was invoked from. Without this cd, the watchdog cmd launched via
REM `start` later inherits system32 as its cwd, so the JVM's relative paths
REM (./data/feedback-queue.json, ./data/ecn-report/, ./logs/) all resolve under
REM system32 — JVM starts with an empty queue, can't find ecn templates, and
REM writes log files to C:\Windows\system32\. Caused PT-60-era data-loss scare
REM 2026-05-13. Hardcode the install root so the elevated window can't drift.
cd /d D:\plm-toolkit

setlocal enableextensions enabledelayedexpansion

set BASE=D:\plm-toolkit
set LIVE_JAR=%BASE%\plm-field-tracker-1.0.1.jar
set STOP_FILE=%BASE%\STOP
set WATCHDOG=%BASE%\run-loop.bat
set STOP_SCRIPT=%BASE%\stop.bat
set DEPLOY_LOG=%BASE%\logs\deploy.log
set HEALTH_URL=http://localhost:8090/

if not exist "%BASE%\logs"    mkdir "%BASE%\logs"
if not exist "%BASE%\backups" mkdir "%BASE%\backups"

REM ---- Parse flags (any order, before the jar path) ----
set MODE=graceful
set PARITY=enforce
:parse_flags
if /I "%~1"=="--force" ( set "MODE=force" & shift & goto parse_flags )
if /I "%~1"=="--allow-class-loss" ( set "PARITY=skip" & shift & goto parse_flags )

if "%~1"=="" (
    echo [error] usage: deploy.bat [--force] [--allow-class-loss] ^<path-to-new-jar^>
    echo example: deploy.bat %BASE%\staging\plm-field-tracker-1.0.1.jar
    echo --force            skips the 2-min maintenance warning — emergencies only.
    echo --allow-class-loss skips the parity gate — only if you INTEND to drop classes.
    exit /b 2
)
set STAGED_JAR=%~1

REM ---- 0. Graceful path: ask the server whether to wait ----
REM One PowerShell call does the whole thing — POSTs to the endpoint, parses
REM the JSON response, decides whether to sleep, and sleeps inline if needed.
REM Keeps cmd out of JSON parsing entirely (cmd's quote-nesting inside
REM for/f-in-command is unreliable and was the root cause of the
REM "active was unexpected at this time" error in the first cut).
REM
REM Server returns:
REM   shouldWait=false → no non-admin users active; we proceed immediately.
REM   shouldWait=true  → real users present; server fired the in-app banner
REM     + email to them. We then POLL /api/maintenance/deploy-ready every 15s
REM     until the (possibly user-EXTENDED) maintenance window elapses, instead
REM     of a fixed sleep. End users can click "+5 / +10 min" in the banner to
REM     push the deadline back; the server caps the total (app.maintenance.
REM     max-extend-minutes, default 20), and we add a safety ceiling so a deploy
REM     never hangs forever. Only after deploy-ready=true do we touch any files.
if /I "%MODE%"=="graceful" (
    echo [deploy] checking for active non-admin users...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "try { $r = Invoke-WebRequest -Uri 'http://localhost:8090/api/maintenance/schedule-from-deploy' -Method POST -Body '{}' -ContentType 'application/json' -UseBasicParsing -TimeoutSec 8; $d = $r.Content | ConvertFrom-Json; if ($d.shouldWait) { $names = if ($d.activeUsers) { ($d.activeUsers | ForEach-Object { $n = if ($_.displayName) { $_.displayName } else { $_.username }; $u = if ($_.username) { ' (' + $_.username + ')' } else { '' }; $n + $u }) -join ', ' } else { '(names unavailable)' }; Write-Host ('[deploy] ' + $d.activeUserCount + ' non-admin user(s) active: ' + $names); Write-Host '[deploy] banner+email fired; waiting for the maintenance window (users may extend it)'; $base = [int]$d.waitSeconds; $ceiling = $base + 1500; $elapsed = 0; while ($true) { Start-Sleep -Seconds 15; $elapsed += 15; try { $dr = (Invoke-WebRequest -Uri 'http://localhost:8090/api/maintenance/deploy-ready' -UseBasicParsing -TimeoutSec 8).Content | ConvertFrom-Json } catch { if ($elapsed -ge $base) { Write-Host '[deploy] deploy-ready unavailable (older build?) - fixed wait elapsed, proceeding'; break } else { Write-Host ('[deploy] deploy-ready unavailable - falling back to fixed wait (' + $elapsed + '/' + $base + 's)'); continue } }; if ($dr.ready) { Write-Host '[deploy] maintenance window elapsed - proceeding'; break }; if ($elapsed -ge $ceiling) { Write-Host ('[warn ] safety ceiling ' + $ceiling + 's hit while waiting - proceeding anyway'); break }; $ext = if ($dr.totalExtendedMinutes) { ' (+' + $dr.totalExtendedMinutes + ' min extended by users)' } else { '' }; Write-Host ('[deploy] ' + $dr.remainingSeconds + 's left' + $ext) } } else { Write-Host '[deploy] no real users active - proceeding immediately' } } catch { Write-Host ('[warn ] could not reach JVM (already down?): ' + $_.Exception.Message + ' - proceeding') }"
) else (
    echo [deploy] --force - skipping maintenance check
)

REM ---- 1. Validate the staged JAR ----
if not exist "%STAGED_JAR%" (
    echo [error] staged JAR not found: %STAGED_JAR%
    exit /b 2
)
for %%A in ("%STAGED_JAR%") do set STAGED_SIZE=%%~zA
if %STAGED_SIZE% LSS 1000000 (
    echo [error] staged JAR is suspiciously small: %STAGED_SIZE% bytes — aborting.
    exit /b 2
)

REM ---- 1b. Parity gate — staged JAR must contain every class the LIVE JAR has ----
REM Prevents the "stale/wrong build silently reverts a feature" outage seen
REM 2026-06-25: a jar grafted onto an OLD baseline was missing the entire
REM Meeting Mode feature (MeetingController, MeetingStorageService, ...);
REM deploying it reverted prod's IT Enhancements. This compares the .class
REM entries of the staged jar against the currently-LIVE jar (read-only — the
REM JVM is still running at this point, so a HALT means zero downtime). If the
REM staged jar is MISSING any class the live jar has, we abort before touching
REM anything. New classes in staged are fine. Skipped on the first deploy
REM (no live jar) and with --allow-class-loss (intentional class removal).
REM Runs BEFORE the STOP sentinel / JVM kill so a halt leaves prod fully up.
if /I "%PARITY%"=="skip" (
    echo [warn ] --allow-class-loss set — skipping parity gate
    goto parity_done
)
if not exist "%LIVE_JAR%" (
    echo [deploy] no live JAR yet — skipping parity gate ^(first deploy^)
    goto parity_done
)
echo [deploy] parity gate: staged JAR must contain every class in the live JAR
powershell -NoProfile -ExecutionPolicy Bypass -Command "try { Add-Type -AssemblyName System.IO.Compression.FileSystem; $live=[IO.Compression.ZipFile]::OpenRead('%LIVE_JAR%'); $staged=[IO.Compression.ZipFile]::OpenRead('%STAGED_JAR%'); $ls=$live.Entries | Where-Object { $_.FullName.EndsWith('.class') } | ForEach-Object { $_.FullName }; $ss=New-Object 'System.Collections.Generic.HashSet[string]'; $staged.Entries | Where-Object { $_.FullName.EndsWith('.class') } | ForEach-Object { [void]$ss.Add($_.FullName) }; $miss=@($ls | Where-Object { -not $ss.Contains($_) }); $live.Dispose(); $staged.Dispose(); if ($miss.Count -gt 0) { Write-Host ('[error] PARITY FAIL: staged JAR is missing ' + $miss.Count + ' class(es) the live JAR has:'); $miss | Select-Object -First 25 | ForEach-Object { Write-Host ('         - ' + $_) }; if ($miss.Count -gt 25) { Write-Host ('         ... and ' + ($miss.Count - 25) + ' more') }; exit 3 } else { Write-Host ('[deploy] parity OK — staged contains all ' + $ls.Count + ' live classes'); exit 0 } } catch { Write-Host ('[warn ] parity gate could not run (' + $_.Exception.Message + ') — proceeding'); exit 0 }"
if errorlevel 3 (
    echo [error] =====================================================================
    echo [error] PARITY GATE HALTED THE DEPLOY — nothing was changed, app is still up.
    echo [error] The staged JAR is missing classes the LIVE jar has — almost always a
    echo [error] stale or wrong build that would REVERT a feature in prod.
    echo [error] Fix: rebuild from the branch that has ALL current features and re-stage.
    echo [error] If you truly intend to drop those classes, re-run with --allow-class-loss.
    echo [error] =====================================================================
    exit /b 1
)
:parity_done

REM Locale-portable timestamp via PowerShell
for /f "delims=" %%T in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set TS=%%T

echo [%TS%] starting deploy: %STAGED_JAR% -^> %LIVE_JAR% (size=%STAGED_SIZE%)
echo [%TS%] starting deploy: %STAGED_JAR% -^> %LIVE_JAR% (size=%STAGED_SIZE%) >> "%DEPLOY_LOG%"

REM ---- 2. Pin the watchdog so it doesn't respawn the old JAR ----
echo [deploy] creating STOP sentinel
echo deploy in progress > "%STOP_FILE%"

REM ---- 2b. Kill any existing watchdog cmd processes ----
REM Without this, an old "PLM-Toolkit-watchdog" window survives the deploy
REM and respawns its own JVM in parallel with the new one. Symptom: two
REM interleaved "starting JVM"/"JVM exited with code 0" lines every 5 s in
REM watchdog.log, and the losing JVM spams "The process cannot access the
REM file because it is being used by another process" because the winning
REM JVM owns port 8090 / has plm-toolkit.log open exclusively.
echo [deploy] killing any existing watchdog cmd processes
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='cmd.exe'\" | Where-Object { $_.CommandLine -like '*run-loop.bat*' } | ForEach-Object { Write-Host ('  killing watchdog PID ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"

REM ---- 3. Kill the running JVM ----
echo [deploy] stopping JVM
if exist "%STOP_SCRIPT%" (
    call "%STOP_SCRIPT%"
) else (
    echo [deploy] no stop.bat — killing java.exe by image name
    taskkill /F /IM java.exe >nul 2>&1
)

REM ---- 4. Wait for port 8090 to free up ----
echo [deploy] waiting for port 8090 to free up
set /a TRIES=0
:wait_down
netstat -ano | findstr ":8090 " | findstr "LISTENING" >nul
if errorlevel 1 goto port_free
set /a TRIES+=1
if %TRIES% GEQ 30 (
    echo [error] JVM still listening on 8090 after 30s — aborting before swap.
    echo [error] STOP sentinel left in place; resolve manually then delete %STOP_FILE%
    exit /b 1
)
timeout /t 1 /nobreak >nul
goto wait_down
:port_free
echo [deploy] port is free

REM ---- 4b. PT-92 belt-and-braces: kill any orphan plm-field-tracker JVMs ----
REM
REM Server-side fix (MaintenanceController.scheduleFromDeploy) now skips
REM arming the System.exit timer for deploy.bat's path, so orphan JVMs
REM shouldn't spawn during the sleep window. But if an older JVM is on the
REM box that DOES still arm the timer (e.g. we're rolling forward and the
REM currently-running JAR predates PT-92), the orphan JVM may still be
REM around. This step nukes any java.exe whose command line references the
REM JAR path, regardless of port-binding state — catches the JVM even if
REM it hasn't yet bound to 8090. Leaves plm-agile-service.jar on 8081
REM untouched because its JAR name is different.
REM
REM Then sleeps 3s so Windows has time to fully release file handles
REM (plm-toolkit.log especially) before the new watchdog tries to open
REM them for append. Without this, the new watchdog logs
REM   "The process cannot access the file because it is being used by
REM   another process"
REM and retries every 5s until the handle drops — a 20-60s recovery hole.
echo [deploy] checking for orphan plm-field-tracker java.exe processes
powershell -NoProfile -Command "$found = @(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*plm-field-tracker-*.jar*' }); if ($found.Count -gt 0) { Write-Host ('[deploy] found ' + $found.Count + ' orphan JVM(s) — killing'); $found | ForEach-Object { Write-Host ('  killing orphan JVM PID ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue } } else { Write-Host '[deploy] no orphan JVMs found' }"
echo [deploy] sleeping 3s for file-handle release
timeout /t 3 /nobreak >nul

REM ---- 5. Back up the live JAR ----
if exist "%LIVE_JAR%" (
    echo [deploy] backing up live JAR to backups\plm-field-tracker-1.0.1.jar.%TS%
    copy /Y "%LIVE_JAR%" "%BASE%\backups\plm-field-tracker-1.0.1.jar.%TS%" >nul
    if errorlevel 1 (
        echo [warn] backup copy failed — continuing anyway
    )
)

REM ---- 6. Swap in the new JAR (this updates mtime → triggers maint-mode exit on boot) ----
echo [deploy] copying %STAGED_JAR% -^> %LIVE_JAR%
copy /Y "%STAGED_JAR%" "%LIVE_JAR%" >nul
if errorlevel 1 (
    echo [error] copy failed — STOP sentinel left in place, watchdog stopped.
    echo [error] resolve manually then delete %STOP_FILE% and re-launch %WATCHDOG%
    exit /b 1
)

REM ---- 6b. Rotate logs (they grow unbounded — plm-toolkit.log / watchdog.log
REM  had reached 30-90 MB before this step existed). The old JVM + watchdog
REM  handles are released by now (step 4 kill + the 3s file-handle sleep), so the
REM  files can be moved aside; the new watchdog (step 8) recreates them fresh.
REM  agile-service.log is intentionally left alone — it's owned by the separate
REM  port-8081 service that this deploy does not stop.
echo [deploy] rotating logs to logs\archive
if not exist "%BASE%\logs\archive" mkdir "%BASE%\logs\archive"
for %%L in (plm-toolkit.log watchdog.log) do (
    if exist "%BASE%\logs\%%L" (
        echo [deploy]   archiving %%L
        move /Y "%BASE%\logs\%%L" "%BASE%\logs\archive\%%~nL.%TS%.log" >nul
    )
)

REM ---- 7. Release the watchdog ----
REM (The watchdog itself also deletes STOP on its exit path, so this is belt-and-braces.)
echo [deploy] removing STOP sentinel
if exist "%STOP_FILE%" del /Q "%STOP_FILE%"

REM ---- 8. Re-launch the watchdog (detached, minimized) ----
if exist "%WATCHDOG%" (
    echo [deploy] launching watchdog: %WATCHDOG%
    start "PLM-Toolkit-watchdog" /MIN cmd /c "%WATCHDOG%"
) else (
    echo [warn] watchdog %WATCHDOG% not found — start it manually.
    echo [warn] (if running as a Windows service via NSSM, the service should respawn the JVM automatically)
)

REM ---- 9. Wait for HTTP up ----
REM Use PowerShell instead of curl — curl.exe isn't on the PATH on every
REM Windows server (Server 2016 sometimes lacks it; Server 2019+ usually has
REM it but not always for the service account). PowerShell's Invoke-WebRequest
REM ships with every supported Windows. The try/catch returns the status code
REM even on 4xx/5xx (which Invoke-WebRequest treats as exceptions); we accept
REM 200/302/401 as "alive" (login page redirect counts).
echo [deploy] waiting for %HEALTH_URL% to respond
set /a TRIES=0
:wait_up
for /f %%C in ('powershell -NoProfile -Command "try { (Invoke-WebRequest -Uri '%HEALTH_URL%' -UseBasicParsing -TimeoutSec 3).StatusCode } catch { try { $_.Exception.Response.StatusCode.value__ } catch { 0 } }"') do set CODE=%%C
if "%CODE%"=="200" goto up
if "%CODE%"=="302" goto up
if "%CODE%"=="401" goto up
set /a TRIES+=1
if %TRIES% GEQ 60 (
    REM Last-resort check: is *anything* listening on port 8090? If yes, the
    REM JVM is up but the HTTP probe is unhappy — log a warning instead of
    REM treating the deploy as failed.
    netstat -ano | findstr ":8090 " | findstr "LISTENING" >nul
    if errorlevel 1 (
        echo [error] app didn't come up within 60s — last HTTP: %CODE%
        echo [error] check %BASE%\logs\plm-toolkit.log and %BASE%\logs\watchdog.log
        exit /b 1
    ) else (
        echo [warn ] HTTP probe never returned 200/302/401 ^(last: %CODE%^), but port 8090 IS listening.
        echo [warn ] Deploy probably succeeded — verify manually at %HEALTH_URL%
        goto up
    )
)
timeout /t 1 /nobreak >nul
goto wait_up

:up
for /f "delims=" %%T in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set TS_END=%%T
echo [%TS_END%] [ok] deploy complete — HTTP %CODE% — %HEALTH_URL%
echo [%TS_END%] [ok] deploy complete — HTTP %CODE% — %HEALTH_URL% >> "%DEPLOY_LOG%"

REM ---- 10. Compress + prune archived logs. Runs only AFTER the app is confirmed
REM  up (never on a failed deploy, and never in the recovery path) so it can't
REM  delay uptime. Zips each rotated *.log to reclaim disk (an 87 MB watchdog.log
REM  compresses to a few MB), removes the raw .log, then deletes any archive
REM  entry older than 14 days. Fully wrapped so a hiccup here never fails deploy.
echo [deploy] compressing + pruning old log archives
powershell -NoProfile -Command "$a='%BASE%\logs\archive'; if (Test-Path $a) { Get-ChildItem $a -Filter '*.log' -ErrorAction SilentlyContinue | ForEach-Object { try { Compress-Archive -Path $_.FullName -DestinationPath ($_.FullName + '.zip') -Force; Remove-Item $_.FullName -Force } catch {} }; Get-ChildItem $a -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-14) } | Remove-Item -Force -Recurse -ErrorAction SilentlyContinue }"

exit /b 0
