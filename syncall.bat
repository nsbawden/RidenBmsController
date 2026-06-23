@echo off
setlocal
cd /d "%~dp0"
REM Pull logs, then build sky + crash HTML charts (skips stale charts).
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0syncall.ps1" %*
set ERR=%ERRORLEVEL%
if %ERR% neq 0 (
    echo.
    if %ERR% equ 1 (
        echo SYNCALL aborted - phone not available or sync failed.
    ) else if %ERR% equ 2 (
        echo SYNCALL aborted - another sync is already running.
    ) else (
        echo SYNCALL failed ^(exit %ERR%^).
    )
    echo.
)
exit /b %ERR%
