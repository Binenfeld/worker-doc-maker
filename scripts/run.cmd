@echo off
rem Double-click to run WorkerDocMaker (same as: powershell -ExecutionPolicy Bypass -File scripts\run.ps1).
rem The window stays open at the end so the result can be read. The exit code is the program's (1 = failed).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1" %*
set rc=%errorlevel%
echo.
if %rc% neq 0 (echo Something went wrong - see the message above.) else (echo Done.)
pause
exit /b %rc%
