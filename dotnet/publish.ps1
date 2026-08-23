<#
跨平台发布脚本（Windows / macOS / Linux）
用法：
  powershell -ExecutionPolicy Bypass -File dotnet\publish.ps1                          # 发布全部平台
  powershell -ExecutionPolicy Bypass -File dotnet\publish.ps1 -Platform linux-x64      # 仅 Linux x64
  powershell -ExecutionPolicy Bypass -File dotnet\publish.ps1 -Platform win-x64 -OutputDir D:\dist

说明：
  - BASS native：src\SubsonicPlayer.Cef\native\{win-x64,osx,linux-x64} 已按平台预置
  - CEF native：CefGlue 的 redist NuGet 包按 RuntimeIdentifier 自动注入（libcef.dll / libcef.so / libcef.dylib）
  - WebAssets：csproj 的 Content Include 自动拷到输出
#>
param(
    [string[]]$Platform = @('win-x64', 'linux-x64', 'osx-x64', 'osx-arm64'),
    [string]$OutputDir = "dist",
    [switch]$FrameworkDependent = $false
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path   # dotnet/
$proj = Join-Path $root 'src\SubsonicPlayer.Cef\SubsonicPlayer.Cef.csproj'
$outRoot = Join-Path $root $OutputDir

foreach ($rid in $Platform) {
    $tfm = if ($rid.StartsWith('win')) { 'net10.0-windows10.0.19041.0' } else { 'net10.0' }
    $out = Join-Path $outRoot $rid

    Write-Host "=== publish $rid ($tfm, framework-dependent=$FrameworkDependent) ===" -ForegroundColor Cyan
    $sc = if ($FrameworkDependent) { 'false' } else { 'true' }
    & dotnet publish $proj -c Release -f $tfm -r $rid --self-contained $sc -o $out
    if ($LASTEXITCODE -ne 0) { Write-Host "publish $rid failed" -ForegroundColor Red; continue }

    # 校验关键产物（BASS 在根目录；CEF native 在 CefGlueBrowserProcess\ 子目录）
    $bass = @('bass.dll', 'bass_fx.dll')
    if ($rid.StartsWith('linux')) { $bass = @('libbass.so', 'libbass_fx.so') }
    if ($rid.StartsWith('osx'))   { $bass = @('libbass.dylib', 'libbass_fx.dylib') }
    $cef = 'libcef.dll'
    if ($rid.StartsWith('linux')) { $cef = 'libcef.so' }
    if ($rid.StartsWith('osx'))   { $cef = 'libcef.dylib' }

    $missing = @()
    foreach ($b in $bass) { if (-not (Test-Path (Join-Path $out $b))) { $missing += $b } }
    # macOS 的 libcef.dylib 在根目录；Windows/Linux 在 CefGlueBrowserProcess\ 子目录
    $cefPath = if ($rid.StartsWith('osx')) { $cef } else { "CefGlueBrowserProcess\$cef" }
    if (-not (Test-Path (Join-Path $out $cefPath))) { $missing += $cefPath }
    if ($missing) {
        Write-Host "  WARN 缺少 native 文件: $($missing -join ', ')" -ForegroundColor Yellow
    } else {
        Write-Host "  OK   BASS/CEF native 齐全" -ForegroundColor Green
    }
    if (-not (Test-Path (Join-Path $out 'WebAssets\index.html'))) {
        Write-Host "  WARN 缺少 WebAssets" -ForegroundColor Yellow
    } else {
        Write-Host "  OK   WebAssets 齐全" -ForegroundColor Green
    }
    Write-Host "  输出: $out"
}

Write-Host "`n全部完成。输出目录: $outRoot"
