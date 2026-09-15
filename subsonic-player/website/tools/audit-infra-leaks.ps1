# 上线/提交前的泄漏面自查：扫描工作区里哪些文件含服务器地址、域名、账号等可识别信息。
#
# 设计原则：**脚本里不硬编码任何真实地址/域名**（否则脚本自身就是泄漏源）。
# 具体值从桌面版 settings.json 读取；其余类别用通用正则识别（内网段、公网 IPv4 等）。
# 只输出「文件 + 类别」，不打印具体值。
[CmdletBinding()]
param(
    [string]$Root = (Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent),
    [string]$SettingsPath = "$env:APPDATA\subsonic-player\settings.json"
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Security

# ---------- 从本地配置派生「具体值」（不落盘、不打印） ----------
$dynamic = @{}
if (Test-Path $SettingsPath) {
    $cfg = Get-Content $SettingsPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $svc = $cfg.Services[0]
    $dynamic['内网地址'] = ([uri]$svc.LanUrl).Host
    $dynamic['外网地址'] = ([uri]$svc.WanUrl).Host
    $dynamic['账号名']   = $svc.Username
    if ($svc.Password -like 'enc:*') {
        try {
            $blob = [Convert]::FromBase64String($svc.Password.Substring(4))
            $bytes = [System.Security.Cryptography.ProtectedData]::Unprotect($blob, $null, 'CurrentUser')
            $dynamic['密码hex'] = -join ($bytes | ForEach-Object { $_.ToString('x2') })
            $pw = [System.Text.Encoding]::UTF8.GetString($bytes)
            if ($pw.Length -ge 6) { $dynamic['明文密码'] = $pw }
        } catch { }
    }
}

# ---------- 通用正则：不需要知道具体值也能识别的类别 ----------
$regexes = [ordered]@{
    '公网IPv4'    = '\b(?!10\.|127\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.|100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.)(\d{1,3}\.){3}\d{1,3}\b'
    '内网网段'    = '\b192\.168\.\d{1,3}\.\d{1,3}\b|\b10\.\d{1,3}\.\d{1,3}\.\d{1,3}\b'
    'Tailscale段' = '\b100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.\d{1,3}\.\d{1,3}\b'
}

$skip = '\\(\.git|bin|obj|node_modules|dist|stage-ns|\.gradle)\\|\\build\\'
$files = Get-ChildItem $Root -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch $skip -and $_.Length -lt 8MB -and
                   $_.Extension -match '\.(cs|js|json|md|html|css|xml|txt|ps1|java|slnx|yml|yaml|sh|mjs|bat|gitignore)$' }

$hits = @{}
function Note([string]$cat, [string]$file) {
    if (-not $script:hits.ContainsKey($cat)) { $script:hits[$cat] = New-Object System.Collections.Generic.HashSet[string] }
    [void]$script:hits[$cat].Add($file)
}

foreach ($f in $files) {
    if ($f.Name -eq 'audit-infra-leaks.ps1') { continue }
    $t = Get-Content -Raw -Encoding UTF8 $f.FullName -ErrorAction SilentlyContinue
    if (-not $t) { continue }
    $rel = $f.FullName.Replace("$Root\", '')
    foreach ($k in $dynamic.Keys) {
        $v = $dynamic[$k]
        if ($v -and $v.Length -gt 2 -and $t.Contains($v)) { Note $k $rel }
    }
    foreach ($k in $regexes.Keys) {
        if ([regex]::IsMatch($t, $regexes[$k])) { Note $k $rel }
    }
}

Write-Host "扫描 $($files.Count) 个文本文件（根：$Root）`n"
if ($hits.Count -eq 0) { Write-Host "OK 未发现服务器地址/域名/账号等可识别信息" -ForegroundColor Green; exit 0 }
foreach ($k in $hits.Keys) {
    $list = $hits[$k] | Sort-Object
    Write-Host "[$k] $($list.Count) 个文件" -ForegroundColor Yellow
    $list | ForEach-Object { Write-Host "    $_" }
}
Write-Host "`n提示：站点页面里的官方域名（站点自身地址）属公开信息，可保留；其余建议改为占位符。" -ForegroundColor DarkGray