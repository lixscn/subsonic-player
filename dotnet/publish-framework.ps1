# ============================================================
#  publish-framework.ps1 — 框架依赖部署 win-x64 到 D:\tools\SubsonicPlayer
#  用法： powershell -ExecutionPolicy Bypass -File publish-framework.ps1
#  说明：
#   - 框架依赖（--self-contained false）：不打包 .NET 运行时，产物更小（~55MB，与正常工作的老版本一致）。
#   - 本机需已安装 .NET 10 Desktop 运行时（开发机有 SDK，必然有运行时）。
#   - CEF(Chromium) 原生、BASS 原生、WebAssets、托管 DLL 均在 exe 旁外置。
#   - 不设置任何 GPU 参数、不显式指定 CEF 路径 —— 回归正常工作版的启动方式。
# ============================================================
param(
    [string]$TargetDir = 'D:\tools\SubsonicPlayer',
    [string]$Tfm = 'net10.0-windows10.0.19041.0',
    [string]$Rid = 'win-x64'
)
$ErrorActionPreference = 'Stop'

$projRoot = $PSScriptRoot          # 脚本所在目录（dotnet/）
$proj   = Join-Path $projRoot 'src\SubsonicPlayer.Cef\SubsonicPlayer.Cef.csproj'

Write-Host "== 框架依赖打包 ($Rid) ==" -ForegroundColor Cyan

# 先停掉正在运行的应用（避免文件被锁定、覆盖失败）
Stop-Process -Name SubsonicPlayer -Force -ErrorAction SilentlyContinue
Stop-Process -Name 'Xilium.CefGlue.BrowserProcess' -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2   # 等待进程释放文件句柄

# 清空目标目录
if (Test-Path $TargetDir) { Remove-Item $TargetDir -Recurse -Force }
New-Item -ItemType Directory -Path $TargetDir -Force | Out-Null

# 框架依赖发布；CEF/BASS 原生、WebAssets 与托管 DLL 均在 exe 旁（可正常运行）。
& dotnet publish $proj -c Release -f $Tfm -r $Rid --self-contained false -o $TargetDir -nologo
if ($LASTEXITCODE -ne 0) { throw "dotnet publish 失败（exit=$LASTEXITCODE）" }

$exe = Join-Path $TargetDir 'SubsonicPlayer.exe'
if (-not (Test-Path $exe)) { throw "未找到 exe：$exe" }

Write-Host "`n完成：$TargetDir\SubsonicPlayer.exe" -ForegroundColor Green
Write-Host "exe 大小： $([math]::Round((Get-Item $exe).Length/1MB,1)) MB" -ForegroundColor Green
Write-Host "目录总大小： $([math]::Round((Get-ChildItem $TargetDir -Recurse -File | Measure-Object Length -Sum).Sum/1MB,1)) MB" -ForegroundColor Green
