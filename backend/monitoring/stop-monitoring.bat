@echo off
REM kill both grafana parent (grafana.exe, owns :3300) and its console child
taskkill /IM grafana.exe /F >nul 2>&1
taskkill /IM grafana-server.exe /F >nul 2>&1
taskkill /IM prometheus.exe /F >nul 2>&1
echo monitoring stack stopped (prometheus :9090 / grafana :3300)
