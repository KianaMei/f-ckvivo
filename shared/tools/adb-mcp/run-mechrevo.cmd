@echo off
rem adb-mcp - manual start. ZERO console windows:
rem   powershell (hidden) -> node daemon.js (hidden) -> server.js / tunnel-sync.js / cloudflared (hidden)
rem Logon autostart uses the same daemon via Startup\adb-mcp.vbs.
rem Status: curl http://127.0.0.1:8787/healthz  |  type cache\adb-mcp\daemon-state.json
rem NOTE: keep this file pure ASCII (cmd.exe parses with ANSI codepage).

powershell -NoProfile -WindowStyle Hidden -Command ^
  "Start-Process -FilePath 'C:\Program Files\nodejs\node.exe' -ArgumentList '\"C:\mycode\somethingsmall\fuckmyphone\shared\tools\adb-mcp\daemon.js\"' -WindowStyle Hidden"

echo [adb-mcp] daemon launched (hidden). Check: curl http://127.0.0.1:8787/healthz
