@echo off
setlocal
cd /d "%~dp0"
REM Graph sky_disturbances CSVs in pulled_logs (all stale/missing, or pass YYYY-MM-DD).
REM Output: pulled_logs\YYYY-MM-DD_sky_disturbances.html
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0graph_sky_logs.ps1" %*
exit /b %ERRORLEVEL%
