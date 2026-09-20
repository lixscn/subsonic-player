# 耗电基线测量：抓本 App 的 batterystats/netstats 计数，算「每小时播放耗多少 mAh」
#
# 用法（在一段播放前后各跑一次，或直接让它自己跑一段）：
#   powershell -File tools\measure-power.ps1 -Snapshot before
#   ...（听 30 分钟）...
#   powershell -File tools\measure-power.ps1 -Snapshot after
#   powershell -File tools\measure-power.ps1 -Diff
#
# 数据都在 %TEMP%\sp-power\ 下，before/after 两个 json 相减即为这一段的耗电与流量。
param(
    [Parameter(Mandatory = $true)][ValidateSet('before', 'after', 'diff', 'show')][string]$Snapshot,
    [string]$Serial = 'v4bi6d8xrggm45vw',
    [string]$Package = 'com.lixscn.subsonicplayer'
)
$ErrorActionPreference = 'Stop'
$adb = 'D:\tools\Android\Sdk\platform-tools\adb.exe'
$dir = Join-Path $env:TEMP 'sp-power'
New-Item -ItemType Directory -Force -Path $dir | Out-Null

function Get-Stats {
    # 取 UID：cmd package list packages -U 输出 "package:xxx uid:10279"（MIUI 的 dumpsys package 不一定有 userId 行）
    $pkgOut = & $adb -s $Serial shell "cmd package list packages -U $Package" 2>$null
    $uid = '10279'
    if ($pkgOut -match 'uid:(\d+)') { $uid = $matches[1] }
    $bs = & $adb -s $Serial shell "dumpsys batterystats --charged 2>/dev/null" 2>$null
    # 找到本 App 的 UID 行（形如 "UID u0a279: 216 fg: ..."）
    # batterystats 里本机 App 的 UID 记作 "u0a<uid-10000>"（实测 10279 → u0a279）
    $uidToken = "u0a" + ([int]$uid - 10000)
    $line = ($bs -split "`n" | Where-Object { $_ -match "UID\s+$uidToken\b" } | Select-Object -First 1)
    $m = [regex]::Match($line, 'UID\s+\S+:\s+([\d.]+)')
    $total = if ($m.Success) { [double]$m.Groups[1].Value } else { 0 }
    function Pick([string]$key) {
        $mm = [regex]::Match($line, "$key=([\d.]+)")
        if ($mm.Success) { return [double]$mm.Groups[1].Value } else { return 0 }
    }
    function PickDur([string]$key) {
        $mm = [regex]::Match($line, "$key=\S*\s*\(([^)]*)\)")
        if ($mm.Success) { return $mm.Groups[1].Value } else { return '' }
    }
    # 流量：netstats detail 里，uid 行的下一行起是分桶行，字节字段是 rb=/tb=
    # （形如 `st=1789812000 rb=5355291 rp=3851 tb=128147 tp=2302`；注意别用 grep -m1 之类会提前退出的管道）
    $ns = & $adb -s $Serial shell "dumpsys netstats detail 2>/dev/null" 2>$null
    $rx = 0; $tx = 0; $mine = $false
    foreach ($l in ($ns -split "`n")) {
        if ($l -match 'ident=\[') { $mine = ($l -match "uid=$uid\s") ; continue }
        if (-not $mine) { continue }
        $r = [regex]::Match($l, '\brb=(\d+)'); if ($r.Success) { $rx += [int64]$r.Groups[1].Value }
        $t = [regex]::Match($l, '\btb=(\d+)'); if ($t.Success) { $tx += [int64]$t.Groups[1].Value }
    }
    $bat = & $adb -s $Serial shell "dumpsys battery | grep -E 'level:'" 2>$null
    $level = [int]((($bat -split "`n")[0] -replace '\D', ''))
    $play = & $adb -s $Serial shell "grep -c 'BASS 播放' /sdcard/Android/data/$Package/files/logs/playback.log" 2>$null
    [ordered]@{
        at          = (Get-Date).ToString('s')
        uid         = $uid
        total_mAh   = $total
        radio_mAh   = (Pick 'mobile_radio')
        radio_dur   = (PickDur 'mobile_radio')
        cpu_mAh     = (Pick 'cpu')
        cpu_dur     = (PickDur 'cpu')
        wakelock_mAh = (Pick 'wakelock')
        audio_dur   = (PickDur 'audio')
        rx_MB       = [math]::Round($rx / 1MB, 1)
        tx_MB       = [math]::Round($tx / 1MB, 2)
        battery_pct = $level
        play_starts = [int]($play | Select-Object -First 1)
    }
}

if ($Snapshot -eq 'diff') {
    $b = Get-Content (Join-Path $dir 'before.json') -Raw | ConvertFrom-Json
    $a = Get-Content (Join-Path $dir 'after.json') -Raw | ConvertFrom-Json
    $mins = ([datetime]$a.at - [datetime]$b.at).TotalMinutes
    "测量区间: $([math]::Round($mins,1)) 分钟"
    "总耗电   : {0:N1} mAh  →  {1:N1} mAh/小时" -f ($a.total_mAh - $b.total_mAh), (($a.total_mAh - $b.total_mAh) / $mins * 60)
    "蜂窝射频 : {0:N1} mAh  →  {1:N1} mAh/小时" -f ($a.radio_mAh - $b.radio_mAh), (($a.radio_mAh - $b.radio_mAh) / $mins * 60)
    "CPU      : {0:N2} mAh" -f ($a.cpu_mAh - $b.cpu_mAh)
    "唤醒锁   : {0:N2} mAh" -f ($a.wakelock_mAh - $b.wakelock_mAh)
    "下载     : {0:N1} MB" -f ($a.rx_MB - $b.rx_MB)
    "电量     : {0}% → {1}%" -f $b.battery_pct, $a.battery_pct
    "起播次数 : {0}" -f ($a.play_starts - $b.play_starts)
    exit 0
}
$s = Get-Stats
$s | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $dir "$Snapshot.json")
"[{0}] {1}" -f $Snapshot, ($s | ConvertTo-Json -Compress)
