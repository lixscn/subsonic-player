# ============================================================
#  publish-single.ps1 — 单文件(托管)部署 win-x64 到 D:\tools\SubsonicPlayer
#  用法： powershell -ExecutionPolicy Bypass -File publish-single.ps1
#  说明：
#   - .NET 托管部分打成单文件 SubsonicPlayer.exe（PublishSingleFile=true）。
#   - CEF(Chromium) 原生（libcef.dll / locales / CefGlueBrowserProcess）与 BASS 原生
#     不能打进单文件（单文件自解压会让 CEF 黑屏/崩溃），故保持为 exe 旁的外置文件。
#   - WebAssets(HTML UI) 默认为 Content 外置；如需打进单文件，加 -p:IncludeAllContentForSelfExtract=true
#     （会同时自解压 BASS 等 Content，需自行验证）。
# ============================================================
param(
    [string]$TargetDir = 'D:\tools\SubsonicPlayer',
    [string]$Tfm = 'net10.0-windows10.0.19041.0',
    [string]$Rid = 'win-x64'
)
$ErrorActionPreference = 'Stop'

$projRoot = $PSScriptRoot          # 脚本所在目录（dotnet/）
$proj   = Join-Path $projRoot 'src\SubsonicPlayer.Cef\SubsonicPlayer.Cef.csproj'

Write-Host "== 单文件(托管)打包 ($Rid) ==" -ForegroundColor Cyan

# 先停掉正在运行的应用（避免文件被锁定、覆盖失败）
Stop-Process -Name SubsonicPlayer -Force -ErrorAction SilentlyContinue
Stop-Process -Name 'Xilium.CefGlue.BrowserProcess' -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2   # 等待进程释放文件句柄

# 清空目标目录
if (Test-Path $TargetDir) { Remove-Item $TargetDir -Recurse -Force }
New-Item -ItemType Directory -Path $TargetDir -Force | Out-Null

# 托管单文件；CEF/BASS 原生与 WebAssets 外置（不进单文件）
& dotnet publish $proj -c Release -f $Tfm -r $Rid --self-contained true -o $TargetDir -nologo `
    -p:PublishSingleFile=true
if ($LASTEXITCODE -ne 0) { throw "dotnet publish 失败（exit=$LASTEXITCODE）" }

$exe = Join-Path $TargetDir 'SubsonicPlayer.exe'
if (-not (Test-Path $exe)) { throw "未找到单文件 exe：$exe" }

Write-Host "`n完成：$TargetDir\SubsonicPlayer.exe" -ForegroundColor Green
Write-Host "exe 大小： $([math]::Round((Get-Item $exe).Length/1MB,1)) MB" -ForegroundColor Green
Write-Host "目录总大小： $([math]::Round((Get-ChildItem $TargetDir -Recurse -File | Measure-Object Length -Sum).Sum/1MB,1)) MB" -ForegroundColor Green
