# =============================================================================
# 开发辅助：探测真实服务端的 Subsonic API 行为
#
# 凭据来源：桌面版 settings.json 里的 DPAPI 密文（同机同用户可解密）。
# 本脚本【不会】打印密码或任何认证参数，只打印端点与响应结构摘要。
# =============================================================================
[CmdletBinding()]
param(
    [string]$SettingsPath = "$env:APPDATA\subsonic-player\settings.json",
    [string]$Only
)
$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.Security

$cfg = Get-Content $SettingsPath -Raw -Encoding UTF8 | ConvertFrom-Json
$svc = $cfg.Services[0]
$base = $svc.LanUrl.TrimEnd('/')
$user = $svc.Username
$enc  = $svc.Password
if (-not $enc.StartsWith('enc:')) { throw '密码不是 DPAPI 密文格式' }
$blob = [Convert]::FromBase64String($enc.Substring(4))
$bytes = [System.Security.Cryptography.ProtectedData]::Unprotect($blob, $null, 'CurrentUser')
$pass = [System.Text.Encoding]::UTF8.GetString($bytes)
$hex = -join ($bytes | ForEach-Object { $_.ToString('x2') })

$common = "u=$([uri]::EscapeDataString($user))&p=enc:$hex&v=1.16.1&c=sp-probe&f=json"

function Probe([string]$Name, [string]$Query) {
    $url = "$base/rest/$Name`?$common$Query"
    try {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 25
        $sw.Stop()
        $j = $r.Content | ConvertFrom-Json
        $resp = $j.'subsonic-response'
        $ms = $sw.ElapsedMilliseconds
        Write-Host ("`n### {0}{1}  [{2}ms] status={3} v={4}" -f $Name, $Query, $ms, $resp.status, $resp.version) -ForegroundColor Cyan
        if ($resp.status -ne 'ok') {
            Write-Host ("  ERROR {0} {1}" -f $resp.error.code, $resp.error.message) -ForegroundColor Red
            return
        }
        return $resp
    } catch {
        Write-Host ("`n### {0}  FAILED: {1}" -f $Name, $_.Exception.Message) -ForegroundColor Red
        return $null
    }
}
function Keys($o) { if ($null -eq $o) { '(null)' } else { ($o.PSObject.Properties.Name) -join ',' } }

$want = { param($n) (-not $Only) -or ($n -eq $Only) }

if (& $want 'ping') { [void](Probe 'ping' '') }

if (& $want 'getArtists') {
    $r = Probe 'getArtists' ''
    if ($r) {
        $idx = $r.artists.index
        Write-Host ("  indexes={0}  ignoredArticles={1}" -f $idx.Count, $r.artists.ignoredArticles)
        Write-Host ("  index[0].name={0} artists={1}" -f $idx[0].name, $idx[0].artist.Count)
        Write-Host ("  artist keys: {0}" -f (Keys $idx[0].artist[0]))
        Write-Host ("  artist[0]: {0}" -f (($idx[0].artist[0] | ConvertTo-Json -Compress)))
    }
}

if (& $want 'getAlbumList2') {
    $r = Probe 'getAlbumList2' '&type=alphabeticalByArtist&size=3&offset=0'
    if ($r) {
        $a = $r.albumList2.album
        Write-Host ("  count={0}  album keys: {1}" -f $a.Count, (Keys $a[0]))
        Write-Host ("  album[0]: {0}" -f ($a[0] | ConvertTo-Json -Compress))
    }
}

if (& $want 'getAlbum') {
    $lst = Probe 'getAlbumList2' '&type=alphabeticalByArtist&size=1&offset=0'
    $aid = $lst.albumList2.album[0].id
    $r = Probe 'getAlbum' "&id=$aid"
    if ($r) {
        $s = $r.album.song
        Write-Host ("  album keys: {0}" -f (Keys $r.album))
        Write-Host ("  songs={0}  song keys: {1}" -f $s.Count, (Keys $s[0]))
        Write-Host ("  song[0]: {0}" -f ($s[0] | ConvertTo-Json -Compress))
    }
}

if (& $want 'getArtist') {
    $ar = Probe 'getArtists' ''
    $arid = $ar.artists.index[0].artist[0].id
    $r = Probe 'getArtist' "&id=$arid"
    if ($r) {
        Write-Host ("  artist keys: {0}" -f (Keys $r.artist))
        Write-Host ("  albums={0}  album[0]: {1}" -f $r.artist.album.Count, ($r.artist.album[0] | ConvertTo-Json -Compress))
    }
    $r2 = Probe 'getArtistInfo2' "&id=$arid"
    if ($r2) { Write-Host ("  info keys: {0}  url={1}" -f (Keys $r2.artistInfo2), $r2.artistInfo2.largeImageUrl) }
}

if (& $want 'search3') {
    $r = Probe 'search3' '&query=a&songCount=2&albumCount=2&artistCount=2'
    if ($r) {
        Write-Host ("  result keys: {0}" -f (Keys $r.searchResult3))
        Write-Host ("  artistCount={0} albumCount={1} songCount={2}" -f `
            $r.searchResult3.artist.Count, $r.searchResult3.album.Count, $r.searchResult3.song.Count)
    }
}

if (& $want 'getPlaylists') {
    $r = Probe 'getPlaylists' ''
    if ($r) { Write-Host ("  playlists={0}  keys: {1}" -f $r.playlists.playlist.Count, (Keys $r.playlists.playlist[0])) }
}

if (& $want 'getStarred2') {
    $r = Probe 'getStarred2' ''
    if ($r) { Write-Host ("  keys: {0}  songs={1} albums={2} artists={3}" -f (Keys $r.starred2), `
        @($r.starred2.song).Count, @($r.starred2.album).Count, @($r.starred2.artist).Count) }
}

if (& $want 'getGenres') {
    $r = Probe 'getGenres' ''
    if ($r) { Write-Host ("  genres={0}  genres[0]: {1}" -f @($r.genres.genre).Count, ($r.genres.genre[0] | ConvertTo-Json -Compress)) }
}

if (& $want 'getLyrics') {
    $r = Probe 'getLyrics' '&artist=%E8%94%A1%E7%90%B4&title=%E6%B8%A1%E5%8F%A3'
    if ($r) { Write-Host ("  keys: {0}" -f (Keys $r.lyrics)) }
}

if (& $want 'getCoverArt') {
    $lst = Probe 'getAlbumList2' '&type=alphabeticalByArtist&size=1&offset=0'
    $cid = $lst.albumList2.album[0].coverArt
    $url = "$base/rest/getCoverArt?$common&id=$cid&size=300"
    try {
        $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 25
        Write-Host ("`n### getCoverArt  status={0} type={1} bytes={2}" -f $r.StatusCode, $r.Headers['Content-Type'], $r.RawContentLength) -ForegroundColor Cyan
    } catch { Write-Host "getCoverArt FAILED: $($_.Exception.Message)" -ForegroundColor Red }
}

if (& $want 'stream') {
    $lst = Probe 'getAlbumList2' '&type=alphabeticalByArtist&size=1&offset=0'
    $aid = $lst.albumList2.album[0].id
    $al = Probe 'getAlbum' "&id=$aid"
    $sid = $al.album.song[0].id
    $url = "$base/rest/stream?$common&id=$sid&maxBitRate=320"
    try {
        $req = [System.Net.HttpWebRequest]::Create($url)
        $req.Timeout = 25000
        $resp = $req.GetResponse()
        Write-Host ("`n### stream  status={0} type={1} len={2}" -f [int]$resp.StatusCode, $resp.ContentType, $resp.ContentLength) -ForegroundColor Cyan
        $resp.Close()
    } catch { Write-Host "stream FAILED: $($_.Exception.Message)" -ForegroundColor Red }
}
