# 用 Node 探测真实服务端（解密 DPAPI 凭据 -> 环境变量传入 node，不落盘不打印）
[CmdletBinding()]
param(
    [string]$SettingsPath = "$env:APPDATA\subsonic-player\settings.json"
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Security

$cfg = Get-Content $SettingsPath -Raw -Encoding UTF8 | ConvertFrom-Json
$svc = $cfg.Services[0]
$base = $svc.LanUrl.TrimEnd('/')
$blob = [Convert]::FromBase64String($svc.Password.Substring(4))
$bytes = [System.Security.Cryptography.ProtectedData]::Unprotect($blob, $null, 'CurrentUser')
$hex = -join ($bytes | ForEach-Object { $_.ToString('x2') })

$env:SP_BASE = $base
$env:SP_AUTH = "u=$([uri]::EscapeDataString($svc.Username))&p=enc:$hex&v=1.16.1&c=sp-probe"
$out = Join-Path $PSScriptRoot '..\build\probe'
node (Join-Path $PSScriptRoot 'probe.js') $out
$env:SP_AUTH = ''
