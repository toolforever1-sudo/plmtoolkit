@echo off
REM Stops just the agile-service (port 8081). Leaves the toolkit running.
REM
REM Sets the STOP_AGILE sentinel so run-agile-loop.bat won't respawn the JVM,
REM kills any agile-watchdog cmd windows so they can't beat the kill, then
REM kills the JVM by port-holder (same pattern as stop.bat for the toolkit).
echo. > "D:\plm-toolkit\STOP_AGILE"
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='cmd.exe'\" | Where-Object { $_.CommandLine -like '*run-agile-loop.bat*' -or $_.CommandLine -like '*run-agile.bat*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"
REM Identify the JVM by who is holding port 8081. netstat is always on PATH,
REM `jps` may not be when running from an elevated cmd with stripped PATH.
REM Targets only port 8081 so the toolkit on 8090 is left alone.
for /f "tokens=5" %%i in ('netstat -ano ^| findstr ":8081 " ^| findstr LISTENING') do taskkill /F /PID %%i
