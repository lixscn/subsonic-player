# ============================================================
#  publish-singlefile.ps1 — 托管单文件 + CEF/BASS 原生外置
#  用法： powershell -ExecutionPolicy Bypass -File publish-singlefile.ps1
#  说明：
#   - PublishSingleFile=true：把应用自身托管 DLL 打进 SubsonicPlayer.exe（单文件承载应用代码）。
#   - CEF(Chromium) 原生与 BASS 原生不能打进 exe（CEF 不能自解压，否则黑屏/闪退），
#     故作为 exe 旁外置文件（libcef.dll / resources.pak / locales / CefGlueBrowserProcess / lib\bass*）。
#   - 关键：单文件会把 CEF 子进程（CefGlueBrowserProcess\Xilium.CefGlue.BrowserProcess.exe）
#     所需的托管 dll（System.* / Xilium.CefGlue.* 等）全部打进主 exe，导致子进程缺依赖起不来
#     （症状：GPU process isn't usable / exit 0x80004005）。因此发布后需用一次「非单文件自包含发布」
#     取出完整的 CefGlueBrowserProcess 目录补回，让子进程能独立加载。
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
$stage  = Join-Path $projRoot 'dist\stage-ns'   # 非单文件发布暂存（取子进程完整依赖用）

Write-Host "== 托管单文件打包 ($Rid) ==" -ForegroundColor Cyan

# 先停掉正在运行的应用（避免文件被锁定、覆盖失败）
Stop-Process -Name SubsonicPlayer -Force -ErrorAction SilentlyContinue
Stop-Process -Name 'Xilium.CefGlue.BrowserProcess' -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

# 清空目标目录
if (Test-Path $TargetDir) { Remove-Item $TargetDir -Recurse -Force }
New-Item -ItemType Directory -Path $TargetDir -Force | Out-Null

# 1) 托管单文件（PublishSingleFile=true）；不含自解压原生，CEF/BASS 原生留在 exe 旁外置。
& dotnet publish $proj -c Release -f $Tfm -r $Rid --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=false -p:IncludeAllContentForSelfExtract=false -o $TargetDir -nologo
if ($LASTEXITCODE -ne 0) { throw "单文件发布失败（exit=$LASTEXITCODE）" }

$exe = Join-Path $TargetDir 'SubsonicPlayer.exe'
if (-not (Test-Path $exe)) { throw "未找到 exe：$exe" }
if (-not (Test-Path "$TargetDir\libcef.dll")) { Write-Host "WARN 缺 libcef.dll（CEF 原生应外置）" -ForegroundColor Yellow }
if (-not (Test-Path "$TargetDir\lib\bass.dll")) { Write-Host "WARN 缺 lib\bass.dll（BASS 应外置）" -ForegroundColor Yellow }

# 2) 取子进程完整依赖：非单文件自包含发布到暂存，拷贝完整 CefGlueBrowserProcess 目录回目标。
Write-Host "== 构建子进程依赖（非单文件暂存）==" -ForegroundColor Cyan
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Path $stage -Force | Out-Null
& dotnet publish $proj -c Release -f $Tfm -r $Rid --self-contained true -o $stage -nologo
if ($LASTEXITCODE -ne 0) { Write-Host "WARN 非单文件发布失败，子进程可能缺依赖" -ForegroundColor Yellow }

$bpSrc = Join-Path $stage 'CefGlueBrowserProcess'
$bpDst = Join-Path $TargetDir 'CefGlueBrowserProcess'
if ((Test-Path $bpSrc) -and (Test-Path $bpDst)) {
    Remove-Item $bpDst -Recurse -Force
    Copy-Item $bpSrc $bpDst -Recurse
    Write-Host "  CefGlueBrowserProcess 子进程依赖已补齐" -ForegroundColor Green
} else {
    Write-Host "WARN 未找到子进程目录（$bpSrc 或 $bpDst），子进程可能无法启动" -ForegroundColor Yellow
}
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }

# 把 CefGlue 库也外置到根（与子进程目录一致；主进程单文件自带，根上是冗余但无害）
$cefglueSrc = Join-Path $env:USERPROFILE '.nuget\packages\cefglue.common\120.6099.211\lib\net8.0'
foreach ($f in @('Xilium.CefGlue.dll', 'Xilium.CefGlue.Common.dll', 'Xilium.CefGlue.Common.Shared.dll')) {
    $s = Join-Path $cefglueSrc $f
    if (Test-Path $s) { Copy-Item $s (Join-Path $TargetDir $f) -Force }
}

# 3) CEF locales 扁平化到根 \locales\（Program.cs 的 ResolveLocalesDir 优先读根 locales）
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
