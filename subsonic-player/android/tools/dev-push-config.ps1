# 开发用：把桌面版的服务器配置（DPAPI 密文）解密后写成 server.json 推到设备上，
# 应用启动时会自动导入并删除该文件。全程不打印密码。
[CmdletBinding()]
param(
    [string]$SettingsPath = "$env:APPDATA\subsonic-player\settings.json",
    [string]$Serial = "",
    # 外网地址：留空则用桌面版 settings.json 里的外网地址（不在此硬编码任何真实域名/IP）
    [string]$Wan = "",
    # 内网地址：留空则用桌面版 settings.json 里的内网地址
    [string]$Lan = "",
    # 只保留一个地址（强制走域名，不在家也能用；用于验证域名链路）
    [switch]$DomainOnly,
    [switch]$NoLaunch
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Security

$Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'D:\tools\Android\Sdk' }
$Adb = Join-Path $Sdk 'platform-tools\adb.exe'
$Pkg = 'com.lixscn.subsonicplayer'

$cfg = Get-Content $SettingsPath -Raw -Encoding UTF8 | ConvertFrom-Json
$svc = $cfg.Services[0]
$blob = [Convert]::FromBase64String($svc.Password.Substring(4))
$bytes = [System.Security.Cryptography.ProtectedData]::Unprotect($blob, $null, 'CurrentUser')
$pass = [System.Text.Encoding]::UTF8.GetString($bytes)

$lanUrl = if ($Lan) { $Lan } else { $svc.LanUrl }
$wan = if ($Wan) { $Wan } else { $svc.WanUrl }
$wanUrl = if ($DomainOnly) { "" } else { $wan }
if ($DomainOnly) { $lanUrl = $wan }   # 只留一个地址：内网地址也填它

$json = [ordered]@{
    name     = $svc.Name
    lanUrl   = $lanUrl
    wanUrl   = $wanUrl
    username = $svc.Username
    password = $pass
} | ConvertTo-Json -Compress

$tmp = Join-Path $env:TEMP 'sp-server.json'
[System.IO.File]::WriteAllText($tmp, $json, (New-Object System.Text.UTF8Encoding($false)))

$adbArgs = @()
if ($Serial) { $adbArgs += @('-s', $Serial) }

& $Adb @adbArgs shell "mkdir -p /sdcard/Android/data/$Pkg/files" 2>&1 | Out-Null
& $Adb @adbArgs push $tmp "/sdcard/Android/data/$Pkg/files/server.json" 2>&1 | Select-Object -Last 1
Remove-Item $tmp -Force

Write-Host "已推送配置：$($svc.Name) / 内网=$lanUrl / 外网=$(if ($wanUrl) { $wanUrl } else { '(未设置)' }) / 用户 $($svc.Username)（密码未回显）"

if (-not $NoLaunch) {
    & $Adb @adbArgs shell am force-stop $Pkg 2>&1 | Out-Null
    & $Adb @adbArgs shell am start -n "$Pkg/.MainActivity" 2>&1 | Select-Object -First 2
}
