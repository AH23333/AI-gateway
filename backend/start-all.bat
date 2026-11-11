@echo off
rem ============================================================
rem  AI Gateway - one-click FULL STACK (Windows)
rem  Location: backend\start-all.bat  (frontend is sibling ..\frontend)
rem
rem  Starts:  Backend :10011 + Frontend :3000 + Monitoring (:9090 / :3300)
rem  Needs :  MySQL80 service running  (net start MySQL80)
rem  Inside:  JDK detect / Redis auto-start / build-if-missing
rem
rem  Usage:
rem    start-all.bat               start stack + open browser
rem    start-all.bat -nobrowser    start stack, do not open browser
rem ============================================================
setlocal enabledelayedexpansion
title AI Gateway - full stack
set "BACKEND_DIR=%~dp0"
set "FRONTEND_DIR=%BACKEND_DIR%..\frontend"

echo ============================================================
echo   AI Gateway full stack starting ...
echo ============================================================

rem ---------- [1/4] preflight: MySQL ----------
call :check_port 3306
if errorlevel 1 (
    echo [FAIL] MySQL :3306 not listening. Run: net start MySQL80
    goto :fail
)
echo [1/4] MySQL    : localhost:3306 ready

rem ---------- [2/4] backend (Redis auto-start / build / wait inside) ----------
echo [2/4] Backend  : launching scripts\start-all-windows.bat ...
call "%BACKEND_DIR%scripts\start-all-windows.bat"
if errorlevel 1 goto :fail

rem ---------- [3/4] frontend (Vite dev :3000) ----------
call :check_port 3000
if not errorlevel 1 (
    echo [3/4] Frontend : :3000 already listening, skip
    goto :fe_done
)
if not exist "%FRONTEND_DIR%\node_modules" (
    echo [FAIL] frontend\node_modules missing. Run: cd frontend ^&^& npm install
    goto :fail
)
echo [3/4] Frontend : starting Vite dev server on :3000 ...
pushd "%FRONTEND_DIR%"
start "ai-gateway-frontend" /min cmd /c "npm run dev"
popd
set /a FE_WAIT=0
:fe_wait
ping -n 2 127.0.0.1 >nul
call :check_port 3000
if not errorlevel 1 goto :fe_done
set /a FE_WAIT+=1
if !FE_WAIT! lss 30 goto :fe_wait
echo [WARN] Frontend :3000 not ready in 30s - check the ai-gateway-frontend window
:fe_done

rem ---------- [4/4] monitoring (Prometheus :9090 + Grafana :3300) ----------
echo [4/4] Monitoring : launching monitoring\start-monitoring.bat ...
call "%BACKEND_DIR%monitoring\start-monitoring.bat"
if errorlevel 1 goto :fail

echo.
echo ============================================================
echo   AI Gateway full stack UP
echo   Frontend   : http://localhost:3000    admin/admin123  demo/demo123
echo   Grafana    : http://localhost:3300    admin/admin  (AI Gateway Overview)
echo   Prometheus : http://localhost:9090    (targets: /api/v1/targets)
echo   Swagger    : http://localhost:10011/swagger-ui/index.html
echo   Stop       : stop-all.bat
echo ============================================================
if /i not "%~1"=="-nobrowser" start "" http://localhost:3000
endlocal
exit /b 0

:fail
echo.
echo Startup aborted - see messages above.
endlocal
exit /b 1

rem ---------- sub: check_port - errorlevel 0 if listening ----------
:check_port
netstat -ano | findstr /r /c:":%~1 .*LISTENING" >nul 2>nul
exit /b %errorlevel%
