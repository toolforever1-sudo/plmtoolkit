@echo off
REM Sets the STOP sentinel first so run-loop.bat won't respawn the JVM,
REM kills any watchdog cmd windows so they can't respawn a new JVM after we
REM kill this one, then kills the running plm-field-tracker process.
echo. > "D:\plm-toolkit\STOP"
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='cmd.exe'\" | Where-Object { $_.CommandLine -like '*run-loop.bat*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"
REM Identify the JVM by who is holding port 8090, NOT by `jps -l | findstr ...`.
REM When deploy.bat self-elevates via UAC, the new cmd inherits a stripped PATH
REM that often omits the JDK's bin\ — so `jps` resolves to "command not found",
REM the for/f produces no PIDs, the JVM never gets killed, and the deploy
REM aborts at the 30s "port still in use" timeout (seen 2026-05-26). netstat is
REM always on PATH and binds the kill to the actual port-holder, which also
REM catches an orphaned/wedged JVM whose name has drifted (PT-58-style).
REM Targets only port 8090 so plm-agile-service.jar on 8081 is left alone.
for /f "tokens=5" %%i in ('netstat -ano ^| findstr ":8090 " ^| findstr LISTENING') do taskkill /F /PID %%i
