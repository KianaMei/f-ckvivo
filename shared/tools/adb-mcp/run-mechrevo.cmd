@echo off
rem adb-mcp - mechrevo side all-in-one daemon (server + tunnel + DNS TXT sync)
rem Called by Startup\adb-mcp.vbs at logon. Can also be run manually.
rem Logs: cache\adb-mcp\logs\ (server.log / cloudflared.log / tunnel-sync.log)
rem NOTE: keep this file pure ASCII (cmd.exe parses with ANSI codepage).

setlocal
set "REPO=C:\mycode\somethingsmall\fuckmyphone"
set "APP=%REPO%\shared\tools\adb-mcp"
set "CACHE=%REPO%\cache\adb-mcp"

if not defined ADB_MCP_TOKEN (
  set /p ADB_MCP_TOKEN=<"%CACHE%\secrets\token.txt"
)

cd /d "%APP%"

rem 1. keep adb server alive (local loopback only, no -a)
"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" start-server >nul 2>&1

rem 2. MCP server (skip if port 8787 already answering)
powershell -NoProfile -Command "try { $c = New-Object Net.Sockets.TcpClient('127.0.0.1', 8787); $c.Close(); exit 0 } catch { exit 1 }" >nul 2>&1
if errorlevel 1 (
  echo [adb-mcp] starting server.js ...
  start "adb-mcp-server" /min node "%APP%\server.js" >> "%CACHE%\logs\server.log" 2>&1
  timeout /t 2 /nobreak >nul
) else (
  echo [adb-mcp] server already running
)

rem 3. tunnel guard (foreground, self-restarting loop inside)
node "%APP%\tunnel-sync.js"
