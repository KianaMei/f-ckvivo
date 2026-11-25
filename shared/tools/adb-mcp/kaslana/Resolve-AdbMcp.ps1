# adb-mcp · kaslana 侧（远程 agent 机，Win11）部署/同步脚本
#
# 用法（管理员 PowerShell 或普通 PowerShell 均可，无需管理员）：
#   1) 首次安装：
#        powershell -ExecutionPolicy Bypass -File Resolve-AdbMcp.ps1 -Token <ADB_MCP_TOKEN> -Install
#      （Token 会存到 %USERPROFILE%\.adb-mcp\token.txt，之后无需再传）
#   2) 之后定期同步（URL 变了自动改 mcp_config.json）：
#        powershell -ExecutionPolicy Bypass -File Resolve-AdbMcp.ps1
#   3) 注册 5 分钟自动同步计划任务（当前用户，登录时启动）：
#        powershell -ExecutionPolicy Bypass -File Resolve-AdbMcp.ps1 -Token <...> -Install -Schedule
#
# 行为：
#   - 用 DoH（Google + Cloudflare JSON API 双源，nslookup 兜底）查 TXT adb-mcp-tunnel.mamba-out.com
#     → 解析 tunnel=<url>（绕开本地 DNS/代理污染，kaslana 上无需任何 DNS 配置）
#   - 读取 %USERPROFILE%\.gemini\config\mcp_config.json（Antigravity App / IDE / CLI 三端共用，无则创建），
#     合并写入 adb-phone 服务器：
#       { "serverUrl": "<tunnel-url>/mcp", "headers": { "Authorization": "Bearer <token>" } }
#     其余已有服务器条目原样保留
#   - 只在 URL/Token 变化时写文件（避免无谓抖动）
#   - 写完后提醒：在 Antigravity 侧栏 Customizations -> Installed -> MCP Servers 点 Restart 重连
#
param(
    [string]$Token = '',
    [switch]$Install,
    [switch]$Schedule
)

$ErrorActionPreference = 'Stop'
$DnsName = 'adb-mcp-tunnel.mamba-out.com'
$ConfigPath = Join-Path $env:USERPROFILE '.gemini\config\mcp_config.json'
$TokenFile = Join-Path $env:USERPROFILE '.adb-mcp\token.txt'
$RepoTokenFile = Join-Path $PSScriptRoot '..\secrets\token.txt'

# ---------- token 管理（参数 -> %USERPROFILE%\.adb-mcp -> 仓内 secrets/token.txt）----------
if ($Token) {
    New-Item -ItemType Directory -Force -Path (Split-Path $TokenFile) | Out-Null
    Set-Content -NoNewline -Path $TokenFile -Value $Token.Trim()
    $Token = $Token.Trim()
} elseif (Test-Path $TokenFile) {
    $Token = (Get-Content -Raw $TokenFile).Trim()
} elseif (Test-Path $RepoTokenFile) {
    $Token = (Get-Content -Raw $RepoTokenFile).Trim()
    if ($Install -and $Token) {
        New-Item -ItemType Directory -Force -Path (Split-Path $TokenFile) | Out-Null
        Set-Content -NoNewline -Path $TokenFile -Value $Token
    }
}
if (-not $Token) {
    Write-Host '[adb-mcp] 缺少 token：传 -Token <ADB_MCP_TOKEN>，或确保 shared/tools/adb-mcp/secrets/token.txt 存在' -ForegroundColor Red
    exit 1
}

# ---------- DoH 查 TXT（Google + Cloudflare JSON API 双源 + nslookup 兜底）----------
function Get-TunnelUrl {
    $dohEndpoints = @(
        @{ Uri = "https://dns.google/resolve?name=$DnsName&type=TXT"; Headers = @{} },
        @{ Uri = "https://cloudflare-dns.com/dns-query?name=$DnsName&type=TXT"; Headers = @{ Accept = 'application/dns-json' } }
    )
    foreach ($ep in $dohEndpoints) {
        try {
            $j = Invoke-RestMethod -Uri $ep.Uri -Headers $ep.Headers -TimeoutSec 15
            $answers = @($j.Answer | Where-Object { $_.type -eq 16 })
            foreach ($ans in $answers) {
                if ($ans.data -match 'tunnel=(https://[a-z0-9-]+\.trycloudflare\.com)') {
                    return $Matches[1]
                }
            }
        } catch { }
    }
    # 兜底：nslookup 直查（本机 DNS 若未污染也能用）
    try {
        $ns = nslookup -type=TXT $DnsName 2>$null | Out-String
        if ($ns -match 'tunnel=(https://[a-z0-9-]+\.trycloudflare\.com)') { return $Matches[1] }
    } catch { }
    return $null
}

$url = Get-TunnelUrl
if (-not $url) {
    Write-Host '[adb-mcp] 未查到 TXT 记录（机械革命侧 tunnel-sync 没跑起来？）' -ForegroundColor Red
    exit 2
}
Write-Host "[adb-mcp] 隧道 URL: $url"

# ---------- 合并 mcp_config.json ----------
$mcpUrl = "$url/mcp"
$entry = [ordered]@{
    serverUrl      = $mcpUrl
    headers        = [ordered]@{ Authorization = "Bearer $Token" }
    timeoutSeconds = 30
}

if (Test-Path $ConfigPath) {
    $raw = Get-Content -Raw $ConfigPath
    try { $cfg = $raw | ConvertFrom-Json } catch { $cfg = $null }
    if (-not $cfg -or -not $cfg.mcpServers) {
        # 结构不对时备份后重建
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
    Write-Host '[adb-mcp] mcp_config.json 已是最新，无需改动'
} else {
    $cfg.mcpServers | Add-Member -NotePropertyName 'adb-phone' -NotePropertyValue ([pscustomobject]$entry) -Force
    $out = $cfg | ConvertTo-Json -Depth 10
    Set-Content -Path $ConfigPath -Value $out -Encoding utf8
    Write-Host "[adb-mcp] 已写入 $ConfigPath"
    Write-Host '[adb-mcp] 提示：在 Antigravity (Customizations -> Installed -> MCP Servers) 对 adb-phone 点 Restart 生效' -ForegroundColor Yellow
}

# ---------- 可选：注册 5 分钟同步计划任务 ----------
if ($Schedule) {
    $taskName = 'adb-mcp-resolve'
    $scriptPath = $MyInvocation.MyCommand.Path
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' `
        -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$scriptPath`""
    $trigger = New-ScheduledTaskTrigger -Once -At (Get-Date) -RepetitionInterval (New-TimeSpan -Minutes 5)
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
    Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -Force | Out-Null
    Write-Host "[adb-mcp] 已注册计划任务 $taskName（每 5 分钟同步一次 URL，支持电池供电与唤醒补跑）"
}
