# 把真实服务端的原始响应落盘（UTF-8），供人工核对 JSON 结构。
# 不打印任何认证参数；密码仅在内存中使用。
[CmdletBinding()]
param(
    [string]$SettingsPath = "$env:APPDATA\subsonic-player\settings.json",
    [string]$OutDir = "$PSScriptRoot\..\build\probe"
)
$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.Security

$cfg = Get-Content $SettingsPath -Raw -Encoding UTF8 | ConvertFrom-Json
$svc = $cfg.Services[0]
$base = $svc.LanUrl.TrimEnd('/')
$blob = [Convert]::FromBase64String($svc.Password.Substring(4))
$bytes = [System.Security.Cryptography.ProtectedData]::Unprotect($blob, $null, 'CurrentUser')
$hex = -join ($bytes | ForEach-Object { $_.ToString('x2') })
$auth = "u=$([uri]::EscapeDataString($svc.Username))&p=enc:$hex&v=1.16.1&c=sp-probe"

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Dump([string]$File, [string]$Endpoint, [string]$Query = '') {
    $url = "$base/rest/$Endpoint`?$auth&f=json$Query"
    try {
        $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 30
        # 原始字节按 UTF-8 落盘，避免控制台代码页破坏中文
        [System.IO.File]::WriteAllBytes((Join-Path $OutDir $File), $r.RawContentStream.ToArray())
        Write-Host ("  {0,-28} <- {1} ({2} bytes)" -f $File, $Endpoint, $r.RawContentLength)
    } catch {
        Write-Host ("  {0,-28} FAILED {1}" -f $File, $_.Exception.Message)
    }
}

Write-Host "dump 到 $OutDir"
Dump 'ping.json'          'ping'
Dump 'artists.json'       'getArtists'
Dump 'albumlist2.json'    'getAlbumList2' '&type=alphabeticalByArtist&size=25&offset=0'
Dump 'album.json'         'getAlbum'      '&id=2804'
Dump 'artist.json'        'getArtist'     '&id=1865'
Dump 'search3.json'       'search3'       '&query=%E7%88%B1&songCount=20&albumCount=20&artistCount=20'
Dump 'starred.json'       'getStarred2'
Dump 'genres.json'        'getGenres'
Dump 'playlists.json'     'getPlaylists'
Dump 'songsbygenre.json'  'getSongsByGenre' '&genre=%E6%9C%AA%E7%9F%A5&count=20'
Dump 'random.json'        'getAlbumList2' '&type=random&size=8'
Dump 'recent.json'        'getAlbumList2' '&type=recent&size=8'
Dump 'frequent.json'      'getAlbumList2' '&type=frequent&size=8'
Dump 'newest.json'        'getAlbumList2' '&type=newest&size=8'
Dump 'bookmarks.json'     'getBookmarks'
Dump 'playqueue.json'     'getPlayQueue'
Dump 'lyricsbysong.json'  'getLyricsBySongId' '&id='
Write-Host 'done'
