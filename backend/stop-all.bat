@echo off
rem ============================================================
rem  AI Gateway - stop FULL STACK (Windows)
rem
rem  Stops :  Frontend :3000 / Gateway :10011 / Prometheus :9090
rem           Grafana :3300 / Redis :6379
rem  Keeps :  MySQL80 (Windows service, resident)
rem  Safe  :  kills by listening port (PID + child tree), so
rem           java/node of OTHER projects are never touched.
rem ============================================================
setlocal enabledelayedexpansion
title AI Gateway - stop full stack

echo [1/5] Frontend   :3000 ...
call :kill_by_port 3000
echo [2/5] Gateway    :10011 ...
call :kill_by_port 10011
echo [3/5] Prometheus :9090 ...
call :kill_by_port 9090
taskkill /IM prometheus.exe /F >nul 2>&1
echo [4/5] Grafana    :3300 ...
call :kill_by_port 3300
taskkill /IM grafana.exe /F >nul 2>&1
taskkill /IM grafana-server.exe /F >nul 2>&1
echo [5/5] Redis      :6379 ...
call :kill_by_port 6379

echo.
echo Verifying ports released ...
ping -n 3 127.0.0.1 >nul
set "LEAK=0"
for %%P in (3000 10011 9090 3300 6379) do (
    call :check_port %%P
    if not errorlevel 1 (
        echo   [WARN] port %%P still listening, killing again ...
        call :kill_by_port %%P
        set "LEAK=1"
    )
)
if "!LEAK!"=="1" (
    ping -n 3 127.0.0.1 >nul
    for %%P in (3000 10011 9090 3300 6379) do (
        call :check_port %%P
        if not errorlevel 1 echo   [FAIL] port %%P STILL listening - manual: netstat -ano ^| findstr :%%P
    )
) else (
    echo   all stack ports released.
)
echo.
echo Done. MySQL80 left running (Windows service).
endlocal
exit /b 0

rem ---------- sub: check_port - errorlevel 0 if listening ----------
:check_port
netstat -ano | findstr /r /c:":%~1 .*LISTENING" >nul 2>nul
exit /b %errorlevel%

rem ---------- sub: kill_by_port - kill PID trees listening on port ----------
:kill_by_port
set "PORT_NO=%~1"
set "KILLED=0"
set "LAST_PID="
for /f "tokens=5" %%P in ('netstat -ano ^| findstr /r /c:":%PORT_NO% .*LISTENING"') do (
    if not "%%P"=="0" (
        if not "%%P"=="!LAST_PID!" (
            echo        killing PID %%P on port %PORT_NO%
            taskkill /PID %%P /T /F >nul 2>&1
            set "LAST_PID=%%P"
            set "KILLED=1"
        )
    )
)
if "!KILLED!"=="0" echo        port %PORT_NO% has no listener, skip
exit /b 0
