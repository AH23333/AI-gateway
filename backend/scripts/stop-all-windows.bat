@echo off
REM ============================================================================
REM  AI Gateway modular monolith - Windows stop script
REM
REM  Usage: stop-all-windows.bat
REM  Behavior:
REM    1. Stop AI Gateway (java process listening on port 10011)
REM    2. Redis / MySQL are resident services, not stopped by default
REM ============================================================================
setlocal enabledelayedexpansion
title AI Gateway stopper

echo [1/2] Stopping AI Gateway (:10011) ...
call :kill_by_port 10011

echo [2/2] Redis(:6379) / MySQL(:3306) are resident services, not stopped.
echo        Manual stop if needed:
echo          Redis : taskkill /IM redis-server.exe /F
echo          MySQL : net stop MySQL80
echo.
echo Done.
endlocal
exit /b 0

REM ---------- sub: kill_by_port - kill listeners on port, tolerate none ----------
:kill_by_port
set "PORT_NO=%~1"
set "FOUND=0"
for /f "tokens=5" %%P in ('netstat -ano ^| findstr /r /c:":%PORT_NO% .*LISTENING"') do (
    if not "%%P"=="0" (
        echo        Killing PID %%P on port %PORT_NO%
        taskkill /PID %%P /F >nul 2>nul
        set "FOUND=1"
    )
)
if "!FOUND!"=="0" echo        Port %PORT_NO% has no listener, skip
exit /b 0
