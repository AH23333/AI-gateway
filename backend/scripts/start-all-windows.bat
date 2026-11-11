@echo off
REM ============================================================================
REM  AI Gateway modular monolith - Windows one-click startup script
REM
REM  Usage:
REM    start-all-windows.bat            normal start (deps: MySQL/Redis)
REM    start-all-windows.bat -init      first deploy: create db + seed, then start
REM    start-all-windows.bat -build     force rebuild fat jar, then start
REM
REM  Env vars (optional, local defaults):
REM    JAVA_HOME   JDK 21 path        (default D:\Java\jdk\jdk-21.0.6)
REM    MYSQL_HOME  MySQL bin dir      (default C:\Program Files\MySQL\MySQL Server 8.0\bin)
REM    REDIS_HOME  redis-server dir   (default auto-detect / unzip zip)
REM    DB_USER/DB_PASSWORD/DB_HOST/DB_NAME  db conn (root/233233qq/localhost/ai_gateway)
REM    JWT_SECRET  prod secret >= 32 bytes (default built-in dev secret)
REM ============================================================================
setlocal enabledelayedexpansion
title AI Gateway starter

set "BACKEND_DIR=%~dp0.."
set "APP_JAR=%BACKEND_DIR%\ai-gateway-app\target\ai-gateway-app-1.0.0.jar"
set "INIT_FLAG=%1"

REM ---------- JDK detect ----------
if not defined JAVA_HOME (
    if exist "D:\Java\jdk\jdk-21.0.6\bin\java.exe" set "JAVA_HOME=D:\Java\jdk\jdk-21.0.6"
)
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [FAIL] JDK 21 not found. Set JAVA_HOME or install to D:\Java\jdk\jdk-21.0.6
    goto :fail
)
echo [1/6] JDK      : %JAVA_HOME%

REM ---------- MySQL check ----------
call :check_port 3306
if %errorlevel% neq 0 (
    echo [FAIL] MySQL 3306 not listening. Start it first: net start MySQL80
    goto :fail
)
echo [2/6] MySQL    : localhost:3306 ready

REM ---------- Redis detect (auto-start if not running) ----------
call :check_port 6379
if %errorlevel% neq 0 (
    call :start_redis
    if !errorlevel! neq 0 (
        echo [FAIL] Redis 6379 not listening and auto-start failed
        goto :fail
    )
    ping -n 3 127.0.0.1 >nul
)
echo [3/6] Redis    : localhost:6379 ready
echo [4/6] Upstream : cloud OpenAI-compatible endpoint (GLM, see sql\seed.sql)

REM ---------- Build (missing jar or -build forced) ----------
if "%INIT_FLAG%"=="-build" set "NEED_BUILD=1"
if not exist "%APP_JAR%" set "NEED_BUILD=1"
if defined NEED_BUILD (
    echo [5/6] Building fat jar ...
    rem Same CALL gotcha as build-release.bat: cmd CALL does not search
    rem PATH for a quoted relative name (silent RC=1) - always absolute.
    set "MVN_CMD=D:\Java\Maven\apache-maven-3.9.11\bin\mvn.cmd"
    if not exist "!MVN_CMD!" (
        for /f "delims=" %%i in ('where mvn.cmd 2^>nul') do set "MVN_CMD=%%i"
    )
    if not exist "!MVN_CMD!" (
        echo [FAIL] Maven not found. Install it or set PATH.
        goto :fail
    )
    pushd "%BACKEND_DIR%"
    call "!MVN_CMD!" -q clean package -DskipTests
    set "BUILD_RC=!errorlevel!"
    popd
    if !BUILD_RC! neq 0 (
        echo [FAIL] Build failed. Run build-release.bat for full Maven output.
        goto :fail
    )
) else (
    echo [5/6] Build     : jar exists, use -build to force rebuild
)

REM ---------- First-time db init (-init) ----------
if "%INIT_FLAG%"=="-init" (
    echo Running sql\init.sql + sql\seed.sql - enter MySQL root password when asked ...
    set "MYSQL_EXE=D:\MySQL\MySQL Server 8.0\bin\mysql.exe"
    "!MYSQL_EXE!" -uroot -p -e "source %BACKEND_DIR%\sql\init.sql"
    "!MYSQL_EXE!" -uroot -p -e "source %BACKEND_DIR%\sql\seed.sql"
    echo Db init done - check errors above if any
)

REM ---------- Start app ----------
echo [6/6] Starting AI Gateway (port 10011) ...
if not defined DB_HOST set "DB_HOST=localhost"
if not defined DB_NAME set "DB_NAME=ai_gateway"
if not defined DB_USER set "DB_USER=root"
REM local dev password; override DB_PASSWORD env var for other machines
if not defined DB_PASSWORD set "DB_PASSWORD=233233qq"
if not defined JWT_SECRET set "JWT_SECRET=ai-gateway-local-dev-secret-key-32bytes!!"

start "ai-gateway-app" /min cmd /c ""%JAVA_HOME%\bin\java.exe" -jar "%APP_JAR%""

REM ---------- Wait for port ----------
set /a WAIT=0
:wait_loop
ping -n 3 127.0.0.1 >nul
call :check_port 10011
if %errorlevel% equ 0 goto :ready
set /a WAIT+=2
if %WAIT% lss 60 goto :wait_loop
echo [FAIL] Port 10011 not ready in 60s. Check ai-gateway-app window.
goto :fail

:ready
echo.
echo ============================================================
echo   AI Gateway started
echo   Chat API  : http://localhost:10011/v1/chat/completions
echo   Admin API : http://localhost:10011/admin/dashboard
echo   Swagger   : http://localhost:10011/swagger-ui.html
echo   Accounts  : admin/admin123  demo/demo123
echo   Stop      : scripts\stop-all-windows.bat
echo ============================================================
endlocal
exit /b 0

REM ---------- sub: check_port - 0 if listening, 1 if not ----------
:check_port
set "PORT_NO=%~1"
netstat -ano | findstr /r /c:":%PORT_NO% .*LISTENING" >nul 2>nul
exit /b %errorlevel%

REM ---------- sub: start_redis - auto-detect, 0 ok / 1 fail ----------
:start_redis
if not defined REDIS_HOME (
    if exist "%~dp0..\redis\redis-server.exe" set "REDIS_HOME=%~dp0..\redis"
)
if not exist "%REDIS_HOME%\redis-server.exe" exit /b 1
echo Starting redis-server in background (%REDIS_HOME%) ...
start "redis-server" /min cmd /c ""%REDIS_HOME%\redis-server.exe" --port 6379"
exit /b 0

:fail
echo.
echo Startup aborted. Troubleshooting:
echo   1. MySQL not started : net start MySQL80
echo   2. Redis manual start : redis-server.exe --port 6379
echo   3. Upstream 401       : check t_model.api_key in sql\seed.sql
echo   4. Port in use        : netstat -ano ^| findstr :10011
endlocal
exit /b 1
