# ============================================================
#  publish-single.ps1 — 自包含部署 win-x64 到 D:\tools\SubsonicPlayer
#  用法： powershell -ExecutionPolicy Bypass -File publish-single.ps1
#  说明： 不要用 PublishSingleFile！CEF(Chromium) 的 libcef.dll/子进程被单文件自解压后
#         无法正确初始化 → 启动纯黑屏。这里用标准 self-contained（.NET 运行时 + 依赖 DLL 随 exe
#         同目录），CEF/BASS 原生库都在 exe 旁，可正常运行。（目录式、非单文件、非 zip）
# ============================================================
param(
    [string]$TargetDir = 'D:\tools\SubsonicPlayer',
    [string]$Tfm = 'net10.0-windows10.0.19041.0',
    [string]$Rid = 'win-x64'
)
$ErrorActionPreference = 'Stop'

$projRoot = $PSScriptRoot          # 脚本所在目录（dotnet/）
$proj   = Join-Path $projRoot 'src\SubsonicPlayer.Cef\SubsonicPlayer.Cef.csproj'

Write-Host "== 自包含打包 ($Rid) ==" -ForegroundColor Cyan

# 先停掉正在运行的应用（避免文件被锁定、覆盖失败）
Stop-Process -Name SubsonicPlayerCef -Force -ErrorAction SilentlyContinue
Stop-Process -Name 'Xilium.CefGlue.BrowserProcess' -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2   # 等待进程释放文件句柄

# 清空目标目录
if (Test-Path $TargetDir) { Remove-Item $TargetDir -Recurse -Force }
New-Item -ItemType Directory -Path $TargetDir -Force | Out-Null

# 标准自包含发布到目标目录（不单文件，原生 DLL 与 exe 同目录）
& dotnet publish $proj -c Release -f $Tfm -r $Rid --self-contained true -o $TargetDir -nologo
if ($LASTEXITCODE -ne 0) { throw "dotnet publish 失败（exit=$LASTEXITCODE）" }

$exe = Join-Path $TargetDir 'SubsonicPlayerCef.exe'
if (-not (Test-Path $exe)) { throw "未找到 exe：$exe" }

Write-Host "`n完成：$TargetDir\SubsonicPlayerCef.exe" -ForegroundColor Green
Write-Host "exe 大小： $([math]::Round((Get-Item $exe).Length/1MB,1)) MB" -ForegroundColor Green
Write-Host "目录总大小： $([math]::Round((Get-ChildItem $TargetDir -Recurse -File | Measure-Object Length -Sum).Sum/1MB,1)) MB" -ForegroundColor Green
