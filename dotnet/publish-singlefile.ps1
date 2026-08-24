# ============================================================
#  publish-singlefile.ps1 — 托管单文件 + CEF/BASS 原生外置
#  用法： powershell -ExecutionPolicy Bypass -File publish-singlefile.ps1
#  说明：
#   - PublishSingleFile=true：把所有托管 DLL（Avalonia、Core、WebAssets 内嵌资源）
#     打进 SubsonicPlayer.exe，exe 单文件承载全部托管代码。
#   - CEF(Chromium) 原生与 BASS 原生不能打进 exe（CEF 不能自解压，否则黑屏/闪退），
#     故仍作为 exe 旁外置文件（libcef.dll / resources.pak / CefGlueBrowserProcess / lib\bass*）。
#     产物 = SubsonicPlayer.exe + 一个原生目录。
#   - BASS 在 lib\（BassBootstrapper 启动时预加载 bass/bass_fx/bassmix）。
# ============================================================
param(
    [string]$TargetDir = 'D:\tools\SubsonicPlayer-single',
    [string]$Tfm = 'net10.0-windows10.0.19041.0',
    [string]$Rid = 'win-x64'
)
$ErrorActionPreference = 'Stop'

$projRoot = $PSScriptRoot          # 脚本所在目录（dotnet/）
$proj   = Join-Path $projRoot 'src\SubsonicPlayer.Cef\SubsonicPlayer.Cef.csproj'

Write-Host "== 托管单文件打包 ($Rid) ==" -ForegroundColor Cyan

# 先停掉正在运行的应用（避免文件被锁定、覆盖失败）
Stop-Process -Name SubsonicPlayer -Force -ErrorAction SilentlyContinue
Stop-Process -Name 'Xilium.CefGlue.BrowserProcess' -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

# 清空目标目录
if (Test-Path $TargetDir) { Remove-Item $TargetDir -Recurse -Force }
New-Item -ItemType Directory -Path $TargetDir -Force | Out-Null

# 托管单文件（PublishSingleFile=true）；不含自解压原生（IncludeNativeLibrariesForSelfExtract 不开），
# CEF/BASS 原生留在 exe 旁外置。
& dotnet publish $proj -c Release -f $Tfm -r $Rid --self-contained true -p:PublishSingleFile=true -o $TargetDir -nologo
if ($LASTEXITCODE -ne 0) { throw "dotnet publish 失败（exit=$LASTEXITCODE）" }

$exe = Join-Path $TargetDir 'SubsonicPlayer.exe'
if (-not (Test-Path $exe)) { throw "未找到 exe：$exe" }
if (-not (Test-Path "$TargetDir\libcef.dll")) { Write-Host "WARN 缺 libcef.dll（CEF 原生应外置）" -ForegroundColor Yellow }
if (-not (Test-Path "$TargetDir\lib\bass.dll")) { Write-Host "WARN 缺 lib\bass.dll（BASS 应外置）" -ForegroundColor Yellow }

# CEF locales 扁平化到根 \locales\（规范布局；Program.cs 的 ResolveLocalesDir 优先读根 locales）
$locSrc = Join-Path $TargetDir 'runtimes\win-x64\native\locales'
$locDst = Join-Path $TargetDir 'locales'
if ((Test-Path $locSrc) -and -not (Test-Path "$locDst\en-US.pak")) {
    if (Test-Path $locDst) { Remove-Item $locDst -Recurse -Force }
    New-Item -ItemType Directory -Path $locDst -Force | Out-Null
    Copy-Item "$locSrc\*" $locDst -Recurse
    Write-Host "  locales 已扁平化到 $locDst" -ForegroundColor Cyan
}
if (-not (Test-Path "$locDst\en-US.pak")) { Write-Host "WARN 缺 locales\en-US.pak" -ForegroundColor Yellow }

Write-Host "`n完成：$TargetDir\SubsonicPlayer.exe" -ForegroundColor Green
Write-Host "exe 大小： $([math]::Round((Get-Item $exe).Length/1MB,1)) MB" -ForegroundColor Green
Write-Host "目录总大小： $([math]::Round((Get-ChildItem $TargetDir -Recurse -File | Measure-Object Length -Sum).Sum/1MB,1)) MB" -ForegroundColor Green
