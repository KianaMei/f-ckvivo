param(
    [ValidateSet('preview', 'restart')][string]$Mode = 'preview',
    [string]$Serial = '10AG632QLS002CF',
    [int]$TargetUid = 10113,
    [int]$UserId = 0,
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
$assetPath = Join-Path $projectPath 'src/main/assets/restart_scope.sh'
$uptimeValue = ((& $AdbPath -s $Serial shell cat /proc/uptime) -join '').Split(' ')[0]
if ($LASTEXITCODE -ne 0) { throw 'Could not read device uptime' }
$deadline = [long][Math]::Floor([double]::Parse($uptimeValue,
        [Globalization.CultureInfo]::InvariantCulture)) + 35
$scriptBody = [IO.File]::ReadAllText($assetPath).Replace([string][char]13, '')
$startInfo = [Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = $AdbPath
$startInfo.UseShellExecute = $false
$startInfo.CreateNoWindow = $true
$startInfo.RedirectStandardInput = $true
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
$remoteCommand = "su -c '/system/bin/sh -s -- $Mode $TargetUid $UserId $deadline'"
foreach ($argument in @('-s', $Serial, 'shell', '-T', $remoteCommand)) {
    $startInfo.ArgumentList.Add($argument)
}
$process = [Diagnostics.Process]::Start($startInfo)
$outputTask = $process.StandardOutput.ReadToEndAsync()
$errorTask = $process.StandardError.ReadToEndAsync()
$process.StandardInput.Write($scriptBody)
$process.StandardInput.Close()
if (-not $process.WaitForExit(45000)) {
    $process.Kill()
    throw 'Helper timed out; inspect the device before considering any further action'
}
Write-Output ($outputTask.GetAwaiter().GetResult() + $errorTask.GetAwaiter().GetResult())
if ($process.ExitCode -ne 0) { throw "Scope helper failed with exit code $($process.ExitCode)" }
