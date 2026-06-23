@echo off
setlocal
cd /d "%~dp0"
REM Pull ops_logs to pulled_logs\ (telemetry, events, sky_disturbances, crash_episodes).
REM Past-day files: verify local size matches phone before delete; re-pull if not.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0synclogs.ps1" %*
set ERR=%ERRORLEVEL%
if %ERR% neq 0 (
    echo.
    if %ERR% equ 1 (
        echo SYNClogs aborted - phone not available.
    ) else if %ERR% equ 2 (
        echo SYNClogs aborted - another sync is already running.
    ) else (
        echo SYNClogs failed ^(exit %ERR%^).
    )
    echo.
)
exit /b %ERR%
