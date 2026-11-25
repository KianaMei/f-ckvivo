# adb-mcp · kaslana 侧（远程 agent 机，Win11）一次性部署脚本
#
# 机械革命走 Cloudflare 命名隧道，地址固定为 https://adb-mcp.mamba-out.com，
# 所以这里只需要写一次 mcp_config.json，不再需要 DNS TXT 发现，也不再需要定时计划任务。
#
# 用法（普通 PowerShell，无需管理员）：
#   powershell -ExecutionPolicy Bypass -File Resolve-AdbMcp.ps1            # 从仓内 secrets/token.txt 读 token
#   powershell -ExecutionPolicy Bypass -File Resolve-AdbMcp.ps1 -Token <ADB_MCP_TOKEN>
#
# 行为：
#   - 合并写入 %USERPROFILE%\.gemini\config\mcp_config.json 的 adb-phone 条目（其它条目原样保留）
#   - 顺手删除旧版遗留的 adb-mcp-resolve 计划任务（quick tunnel 时代每 5 分钟同步 URL 用的）
#
param(
    [string]$Token = '',
    [string]$PublicUrl = 'https://adb-mcp.mamba-out.com',
    # 兼容旧调用方式；已无作用
    [switch]$Install,
    [switch]$Schedule
)

$ErrorActionPreference = 'Stop'
$ConfigPath = Join-Path $env:USERPROFILE '.gemini\config\mcp_config.json'
$TokenFile = Join-Path $env:USERPROFILE '.adb-mcp\token.txt'
$RepoTokenFile = Join-Path $PSScriptRoot '..\secrets\token.txt'

# ---------- token（参数 -> 仓内 secrets/token.txt -> %USERPROFILE%\.adb-mcp）----------
if (-not $Token -and (Test-Path $RepoTokenFile)) { $Token = (Get-Content -Raw $RepoTokenFile).Trim() }
if (-not $Token -and (Test-Path $TokenFile)) { $Token = (Get-Content -Raw $TokenFile).Trim() }
$Token = $Token.Trim()
if (-not $Token) {
    Write-Host '[adb-mcp] 缺少 token：传 -Token <ADB_MCP_TOKEN>，或确保 shared/tools/adb-mcp/secrets/token.txt 存在' -ForegroundColor Red
    exit 1
}

# ---------- 合并 mcp_config.json ----------
$mcpUrl = "$($PublicUrl.TrimEnd('/'))/mcp"
$entry = [ordered]@{
    serverUrl      = $mcpUrl
    headers        = [ordered]@{ Authorization = "Bearer $Token" }
    timeoutSeconds = 30
}

if (Test-Path $ConfigPath) {
    $raw = Get-Content -Raw $ConfigPath
    try { $cfg = $raw | ConvertFrom-Json } catch { $cfg = $null }
    if (-not $cfg -or -not $cfg.mcpServers) {
        Copy-Item $ConfigPath "$ConfigPath.bak-$(Get-Date -Format yyyyMMdd-HHmmss)"
        $cfg = [pscustomobject]@{ mcpServers = [pscustomobject]@{} }
    }
} else {
    New-Item -ItemType Directory -Force -Path (Split-Path $ConfigPath) | Out-Null
    $cfg = [pscustomobject]@{ mcpServers = [pscustomobject]@{} }
}

$existing = $cfg.mcpServers.PSObject.Properties['adb-phone']
$sameUrl = $existing -and $existing.Value.PSObject.Properties['serverUrl'] -and ($existing.Value.serverUrl -eq $mcpUrl)
$sameAuth = $existing -and $existing.Value.headers -and ($existing.Value.headers.Authorization -eq "Bearer $Token")
if ($sameUrl -and $sameAuth) {
    Write-Host "[adb-mcp] mcp_config.json 已指向 $mcpUrl，无需改动"
} else {
    $cfg.mcpServers | Add-Member -NotePropertyName 'adb-phone' -NotePropertyValue ([pscustomobject]$entry) -Force
    $cfg | ConvertTo-Json -Depth 10 | Set-Content -Path $ConfigPath -Encoding utf8
    Write-Host "[adb-mcp] 已写入 $ConfigPath → $mcpUrl"
    Write-Host '[adb-mcp] 在 Antigravity (Customizations -> Installed -> MCP Servers) 对 adb-phone 点 Restart 生效' -ForegroundColor Yellow
}

# ---------- 清理旧版定时同步任务 ----------
if (Get-ScheduledTask -TaskName 'adb-mcp-resolve' -ErrorAction SilentlyContinue) {
    Unregister-ScheduledTask -TaskName 'adb-mcp-resolve' -Confirm:$false
    Write-Host '[adb-mcp] 已删除旧计划任务 adb-mcp-resolve（固定域名后不再需要）'
}
