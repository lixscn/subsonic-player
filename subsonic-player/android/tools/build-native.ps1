# 编译 BASS 的 JNI 包装层（libspbass.so），并校验 jniLibs 里各库是否齐备。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools\build-native.ps1
#   powershell -ExecutionPolicy Bypass -File tools\build-native.ps1 -Abis arm64-v8a
#
# 前置：
#   1) Android NDK（Android Studio → SDK Manager → NDK (Side by side)，
#      或解压到 D:\tools\Android\Sdk\ndk\<版本>\）
#   2) BASS 官方 Android 包 + add-on，按 app\jniLibs\README.md 放到对应 ABI 目录
#
# 说明：本脚本不下载任何东西（开发机无外网），只做「发现 → 编译 → 校验 → 报告」。
[CmdletBinding()]
param(
    [string[]]$Abis = @('arm64-v8a'),
    [int]$ApiLevel = 29,
    [switch]$Quiet
)
$ErrorActionPreference = 'Stop'

$root    = Split-Path $PSScriptRoot -Parent           # android/
$jniDir  = Join-Path $root 'app\jni'
$libsDir = Join-Path $root 'app\jniLibs'
$sdk     = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { 'D:\tools\Android\Sdk' }

function Info($m) { if (-not $Quiet) { Write-Host $m } }
function Ok($m)   { Write-Host "  ok  $m" -ForegroundColor Green }
function Warn($m) { Write-Host "  !!  $m" -ForegroundColor Yellow }

# ---------------- 1) 找 NDK ----------------
$ndk = $null
if ($env:ANDROID_NDK_HOME -and (Test-Path $env:ANDROID_NDK_HOME)) {
    $ndk = $env:ANDROID_NDK_HOME
} else {
    $ndkRoot = Join-Path $sdk 'ndk'
    if (Test-Path $ndkRoot) {
        $cand = Get-ChildItem $ndkRoot -Directory | Sort-Object Name -Descending | Select-Object -First 1
        if ($cand) { $ndk = $cand.FullName }
    }
}
if (-not $ndk) {
    Write-Host ""
    Warn "未找到 Android NDK —— 这一步必须先准备好"
    Write-Host @"
  任选其一：
    a) Android Studio → Settings → SDK Manager → SDK Tools → 勾选 "NDK (Side by side)" → Apply
    b) 到 https://developer.android.com/ndk/downloads 下载 Windows zip，
       解压到 $sdk\ndk\<版本号>\ （保持该目录结构，本脚本会自动发现最新版）
"@
    exit 2
}
Ok "NDK: $ndk"

$clang = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe'
if (-not (Test-Path $clang)) { Warn "NDK 里没找到 clang：$clang"; exit 3 }
$sysroot = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\sysroot'
Ok "clang: $clang"

# ---------------- 2) BASS 头文件与库 ----------------
# 头文件（bass.h / bassdsd.h …）允许放在 jniLibs\include 或 jni\include
$incCandidates = @(
    (Join-Path $libsDir 'include'),
    (Join-Path $jniDir  'include')
)
$incDir = $incCandidates | Where-Object { Test-Path (Join-Path $_ 'bass.h') } | Select-Object -First 1
if (-not $incDir) {
    Warn "没找到 bass.h（BASS 官方 Android 包里带）"
    Write-Host "  请把 BASS 包里的 bass.h 等头文件放到：$(Join-Path $libsDir 'include')"
    exit 4
}
Ok "头文件: $incDir"

# add-on 头文件决定编译时打开哪些格式
$addons = [ordered]@{}
foreach ($a in @(
    @{ name = 'DSD';  header = 'bassdsd.h';  lib = 'libbassdsd.so';  flag = 'SP_HAVE_DSD' },
    @{ name = 'FLAC'; header = 'bassflac.h'; lib = 'libbassflac.so'; flag = 'SP_HAVE_FLAC' },
    @{ name = 'APE';  header = 'bassape.h';  lib = 'libbassape.so';  flag = 'SP_HAVE_APE' },
    @{ name = 'WV';   header = 'basswv.h';   lib = 'libbasswv.so';   flag = 'SP_HAVE_WV' },
    @{ name = 'OPUS'; header = 'bassopus.h'; lib = 'libbassopus.so'; flag = 'SP_HAVE_OPUS' }
)) {
    if (Test-Path (Join-Path $incDir $a.header)) { $addons[$a.name] = $a } else { Warn "没有 $($a.header) → 该格式不可用" }
}
if ($addons.Count -gt 0) { Ok ("启用的 add-on: " + ($addons.Keys -join ', ')) }

# ---------------- 3) 逐 ABI 编译 ----------------
$targets = @{
    'arm64-v8a'   = @{ triple = 'aarch64-linux-android';  cc = 'aarch64-linux-android' }
    'armeabi-v7a' = @{ triple = 'armv7a-linux-androideabi'; cc = 'armv7a-linux-androideabi' }
    'x86_64'      = @{ triple = 'x86_64-linux-android';   cc = 'x86_64-linux-android' }
}

$failed = @()
foreach ($abi in $Abis) {
    if (-not $targets.ContainsKey($abi)) { Warn "未知 ABI: $abi"; continue }
    $abiDir = Join-Path $libsDir $abi
    New-Item -ItemType Directory -Force -Path $abiDir | Out-Null

    $required = @('libbass.so')
    if ($addons.Contains('DSD')) { $required += 'libbassdsd.so' }
    $missing = @()
    foreach ($lib in $required) { if (-not (Test-Path (Join-Path $abiDir $lib))) { $missing += $lib } }
    if ($missing.Count -gt 0) {
        Warn "$abi 缺少: $($missing -join ', ')  → 请把 BASS 官方 Android 包里对应 ABI 的文件拷进来"
        $failed += "$abi ($($missing -join ', '))"
    } else {
        Ok "$abi 依赖库齐备"
    }
    foreach ($k in $addons.Keys) {
        $lib = $addons[$k].lib
        if (-not (Test-Path (Join-Path $abiDir $lib))) { Warn "$abi 缺 $lib（$k 格式将不可用）" }
    }

    $defs = @()
    foreach ($k in $addons.Keys) { $defs += "-D$($addons[$k].flag)" }
    $out = Join-Path $abiDir 'libspbass.so'
    $clangArgs = @(
        "--target=$($targets[$abi].cc)$ApiLevel",
        '-shared', '-O2', '-fPIC', '-Wall',
        "-I$incDir",
        "-I$(Join-Path $sysroot 'usr\include')",
        (Join-Path $jniDir 'sp_bass.c'),
        "-L$abiDir",
        '-lbass'
    ) + $defs + @('-o', $out)
    if ($addons.Contains('DSD')) { $clangArgs += '-lbassdsd' }
    if ($addons.Contains('FLAC')) { $clangArgs += '-lbassflac' }
    if ($addons.Contains('APE')) { $clangArgs += '-lbassape' }
    if ($addons.Contains('WV')) { $clangArgs += '-lbasswv' }
    if ($addons.Contains('OPUS')) { $clangArgs += '-lbassopus' }

    Info "编译 $abi ..."
    & $clang @clangArgs 2>&1 | ForEach-Object { "      $_" }
    if ($LASTEXITCODE -ne 0) {
        Warn "$abi 编译失败（多为缺 libbass.so 或头文件不匹配）"
        $failed += "$abi 编译失败"
    } elseif (Test-Path $out) {
        Ok "产出 $(Split-Path $out -Leaf)  $([math]::Round((Get-Item $out).Length/1KB)) KB"
    }
}

Write-Host ""
if ($failed.Count -gt 0) {
    Write-Host "未完成项：" -ForegroundColor Yellow
    $failed | ForEach-Object { Write-Host "  - $_" }
    Write-Host "`n补齐后重跑本脚本；全部就绪后执行：build.ps1 -Install" -ForegroundColor Yellow
    exit 1
}
Write-Host "全部就绪。下一步：powershell -ExecutionPolicy Bypass -File build.ps1 -Install" -ForegroundColor Green
