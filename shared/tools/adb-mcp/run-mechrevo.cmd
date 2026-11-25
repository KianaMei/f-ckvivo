@echo off
rem adb-mcp - manual / scheduled start (idempotent, ZERO console windows):
rem   powershell (hidden) -> node daemon.js (hidden) -> server.js / tunnel-sync.js / cloudflared (hidden)
rem Logon autostart uses the same daemon via Startup\adb-mcp.vbs.
rem Status: curl http://127.0.0.1:8787/healthz  |  type cache\adb-mcp\daemon-state.json
rem NOTE: keep this file pure ASCII (cmd.exe parses with ANSI codepage).

setlocal
set "DAEMON=%~dp0daemon.js"
set "NODE_EXE=node.exe"
where node.exe >nul 2>&1
if errorlevel 1 (
  if exist "C:\Program Files\nodejs\node.exe" set "NODE_EXE=C:\Program Files\nodejs\node.exe"
)

powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -Command ^
  "Start-Process -FilePath $env:NODE_EXE -ArgumentList ('\"{0}\"' -f $env:DAEMON) -WindowStyle Hidden"

echo [adb-mcp] daemon launched (hidden, idempotent). Check: curl http://127.0.0.1:8787/healthz
endlocal
