@echo off
rem ============================================================
rem  AI Gateway monitoring stack (single-machine Windows)
rem  Prometheus :9090 + Grafana :3300 (gateway :10011 required)
rem ============================================================
setlocal
set "MON_DIR=%~dp0"
set "PROM_DIR=%MON_DIR%prometheus-2.53.1.windows-amd64"
set "GRAF_DIR=%MON_DIR%grafana-v11.1.0"

if not exist "%MON_DIR%config" (
  echo [FAIL] missing config dir: "%MON_DIR%config"
  exit /b 1
)

rem 1. deploy repo configs into binary dirs (idempotent)
copy /Y "%MON_DIR%config\prometheus.yml" "%PROM_DIR%\prometheus.yml" >nul
copy /Y "%MON_DIR%config\custom.ini" "%GRAF_DIR%\conf\custom.ini" >nul
copy /Y "%MON_DIR%config\datasource-prometheus.yaml" "%GRAF_DIR%\conf\provisioning\datasources\prometheus.yaml" >nul
copy /Y "%MON_DIR%config\dashboard-provider.yaml" "%GRAF_DIR%\conf\provisioning\dashboards\ai-gateway.yaml" >nul
if not exist "%GRAF_DIR%\conf\provisioning\dashboards\json" mkdir "%GRAF_DIR%\conf\provisioning\dashboards\json"
copy /Y "%MON_DIR%config\ai-gateway-overview.json" "%GRAF_DIR%\conf\provisioning\dashboards\json\ai-gateway-overview.json" >nul

rem 2. Prometheus (TSDB data dir inside binary dir)
start "prometheus" /min "%PROM_DIR%\prometheus.exe" --config.file="%PROM_DIR%\prometheus.yml" --storage.tsdb.path="%PROM_DIR%\data"

rem 3. Grafana (cwd must be grafana root for relative provisioning paths)
pushd "%GRAF_DIR%"
start "grafana" /min bin\grafana-server.exe
popd

echo.
echo  Prometheus : http://localhost:9090   (targets: /api/v1/targets)
echo  Grafana    : http://localhost:3300   (admin / admin)
echo  Dashboard  : AI Gateway folder, "AI Gateway Overview" (auto-provisioned)
endlocal
