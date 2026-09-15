# 核查：站点公开文件 / APK / Windows 包里是否含账号密码
# 密码只在内存中用于匹配，任何情况下都不打印
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Security

$cfg = Get-Content "$env:APPDATA\subsonic-player\settings.json" -Raw -Encoding UTF8 | ConvertFrom-Json
$svc = $cfg.Services[0]
$blob = [Convert]::FromBase64String($svc.Password.Substring(4))
$bytes = [System.Security.Cryptography.ProtectedData]::Unprotect($blob, $null, 'CurrentUser')
$pass = [System.Text.Encoding]::UTF8.GetString($bytes)
$user = $svc.Username
$hex = -join ($bytes | ForEach-Object { $_.ToString('x2') })
$b64frag = $svc.Password.Substring(20, 24)   # DPAPI 密文片段，用于检测是否泄露密文

$w = "D:\work_space\DeepSeekHarness\music-play\subsonic-player\website"
$apk = "D:\work_space\DeepSeekHarness\music-play\subsonic-player\android\dist\SubsonicPlayer-0.1.0-release.apk"
$zip = "$w\dist\SubsonicPlayer-win-x64.zip"

function Scan-Text([string]$path, [string]$label) {
    $files = Get-ChildItem $path -Recurse -File | Where-Object { $_.Extension -match '\.(html|css|js|xml|txt|json|svg|md)$' }
    $hits = @()
    foreach ($f in $files) {
        $t = Get-Content -Raw -Encoding UTF8 $f.FullName -ErrorAction SilentlyContinue
        if (-not $t) { continue }
        if ($t.Contains($pass)) { $hits += "$($f.Name):明文密码" }
        if ($t.Contains($user)) { $hits += "$($f.Name):用户名" }
        if ($t.Contains($hex)) { $hits += "$($f.Name):密码hex" }
        if ($t.Contains($b64frag)) { $hits += "$($f.Name):DPAPI密文" }
        if ($t -match 'p=enc|"p"\s*:|password\s*[:=]') { $hits += "$($f.Name):可疑认证字段" }
        if ($t -match '192\.168\.0\.220|100\.64\.120\.220') { $hits += "$($f.Name):内网/Tailscale地址" }
    }
    Write-Host "`n[$label] 扫描 $($files.Count) 个文本文件"
    if ($hits.Count -eq 0) { Write-Host "  ✓ 未发现任何凭据" -ForegroundColor Green }
    else { $hits | ForEach-Object { Write-Host "  ⚠ $_" -ForegroundColor Yellow } }
}

function Scan-Binary([string]$path, [string]$label, [string[]]$names) {
    Write-Host "`n[$label] 二进制扫描：$([math]::Round((Get-Item $path).Length/1MB,1)) MB"
    $fs = [System.IO.File]::OpenRead($path)
    try {
        $bufSize = 1MB
        $buf = New-Object byte[] $bufSize
        $overlap = 64
        $carry = New-Object byte[] 0
        $found = @{}
        $patterns = @{ '明文密码' = [System.Text.Encoding]::UTF8.GetBytes($pass)
                       '用户名'   = [System.Text.Encoding]::UTF8.GetBytes($user)
                       '密码hex'  = [System.Text.Encoding]::ASCII.GetBytes($hex) }
        $n = 0
        while (($n = $fs.Read($buf, 0, $bufSize)) -gt 0) {
            $chunk = New-Object byte[] ($carry.Length + $n)
            [Array]::Copy($carry, 0, $chunk, 0, $carry.Length)
            [Array]::Copy($buf, 0, $chunk, $carry.Length, $n)
            foreach ($k in $patterns.Keys) {
                if ($found[$k]) { continue }
                $pat = $patterns[$k]
                for ($i = 0; $i -le $chunk.Length - $pat.Length; $i++) {
                    $ok = $true
                    for ($j = 0; $j -lt $pat.Length; $j++) { if ($chunk[$i + $j] -ne $pat[$j]) { $ok = $false; break } }
                    if ($ok) { $found[$k] = $true; break }
                }
            }
            $keep = [Math]::Min($overlap, $chunk.Length)
            $carry = New-Object byte[] $keep
            [Array]::Copy($chunk, $chunk.Length - $keep, $carry, 0, $keep)
        }
        if ($found.Count -eq 0) { Write-Host "  ✓ 未发现密码/用户名" -ForegroundColor Green }
        else { $found.Keys | ForEach-Object { Write-Host "  ⚠ 命中：$_" -ForegroundColor Yellow } }
    } finally { $fs.Dispose() }
}

Scan-Text $w "站点源文件（含未部署的 README）"
Scan-Text "$w\deploy" "实际部署到服务器的文件集"
Scan-Text "$w\downloads" "downloads 目录"

Write-Host "`n[$('Android APK')] 检查 zip 内条目"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$za = [System.IO.Compression.ZipFile]::OpenRead($apk)
$za.Entries | Where-Object { $_.FullName -match 'settings|sp_|\.db|\.json|\.log' } | ForEach-Object { "  条目: $($_.FullName)" }
$za.Dispose()
Scan-Binary $apk "Android APK（含内部配置？）"

Write-Host "`n[Windows 包] 可疑条目列表"
$za2 = [System.IO.Compression.ZipFile]::OpenRead($zip)
$sus = $za2.Entries | Where-Object { $_.FullName -match 'settings\.json|\.db$|\.log$|cache/(Cookies|Local Storage)|Login Data|secret' }
if ($sus) { $sus | ForEach-Object { "  ⚠ 条目: $($_.FullName)" } } else { Write-Host "  ✓ 无 settings.json / 数据库 / 缓存凭据类文件" -ForegroundColor Green }
$cnt = ($za2.Entries | Measure-Object).Count
"[Windows 包] 条目总数: $cnt"
$za2.Dispose()
Scan-Binary $zip "Windows 包（275MB 全量）"
