@echo off
echo Stopping all PLM Toolkit services...
REM Sentinels first so run-loop.bat (toolkit) and run-agile-loop.bat
REM (agile-service) won't respawn anything.
echo. > "D:\plm-toolkit\STOP"
echo. > "D:\plm-toolkit\STOP_AGILE"
REM Kill any watchdog cmd windows so they can't respawn a new JVM after we
REM kill this one. Match by command line containing run-loop.bat (toolkit),
REM run-agile-loop.bat (new agile watchdog), or run-agile.bat (legacy
REM one-shot launcher, still around for ad-hoc starts).
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='cmd.exe'\" | Where-Object { $_.CommandLine -like '*run-loop.bat*' -or $_.CommandLine -like '*run-agile-loop.bat*' -or $_.CommandLine -like '*run-agile.bat*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"
for /f "tokens=1" %%i in ('jps -l ^| findstr plm-field-tracker') do taskkill /PID %%i /F
for /f "tokens=1" %%i in ('jps -l ^| findstr plm-agile-service') do taskkill /PID %%i /F
echo Done.
