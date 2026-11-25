@echo off
rem adb-mcp - register SYSTEM boot task via Task Scheduler (needs admin).
rem The no-elevation fallback (logon startup via adb-mcp.vbs) is already in place;
rem run this only if you want the stack alive before any user logs on.
rem Uninstall: schtasks /delete /tn adb-mcp /f
rem NOTE: keep this file pure ASCII (cmd.exe parses with ANSI codepage).

setlocal
set "REPO=C:\mycode\somethingsmall\fuckmyphone"
set "CMD=%REPO%\shared\tools\adb-mcp\run-mechrevo.cmd"

net session >nul 2>&1
if errorlevel 1 (
  echo [adb-mcp] run this script as Administrator
  exit /b 1
)

schtasks /create /tn "adb-mcp" /tr "\"%CMD%\"" /sc onstart /ru SYSTEM /rl HIGHEST /f
if errorlevel 1 ( echo [adb-mcp] register failed & exit /b 1 )

schtasks /run /tn "adb-mcp"
echo [adb-mcp] registered and started.
echo health check:  curl http://127.0.0.1:8787/healthz
echo tunnel state:  type %REPO%\cache\adb-mcp\tunnel-state.json
endlocal
