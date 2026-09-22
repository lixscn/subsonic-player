# =============================================================================
# Subsonic Player for Android — 离线构建脚本
#
# 本机无外网（NuGet / Gradle / Maven 全部不可达），因此不使用 Gradle：
#   aapt2 compile/link  ->  javac  ->  d8  ->  zip(classes.dex)  ->  zipalign  ->  apksigner
# 只用本机 Android SDK 命令行工具 + JDK，全程离线、零第三方依赖。
#
# 用法：
#   pwsh -File build.ps1                 # 构建 release APK（自签名）
#   pwsh -File build.ps1 -Install        # 构建并安装到已连接的设备/模拟器
#   pwsh -File build.ps1 -Clean          # 清理 build 目录
# =============================================================================
[CmdletBinding()]
param(
    [ValidateSet('debug', 'release')]
    [string]$Configuration = 'release',
    [switch]$Clean,
    [switch]$Install,
    [switch]$NoSign,
    # 安装目标设备序列号（同时连着模拟器和手机时必须指定；序列号用 adb devices 查）
    [string]$Serial = ''
)

$ErrorActionPreference = 'Continue'   # 原生工具（keytool/apksigner）会把进度写到 stderr，
                                     # PS 5.1 在 Stop 下会将其当作终止错误；改为统一显式校验 $LASTEXITCODE
Set-StrictMode -Version Latest

# ---------- 路径 ----------
$Root        = $PSScriptRoot
$AppDir      = Join-Path $Root 'app'
$ResDir      = Join-Path $AppDir 'res'
$AssetsDir   = Join-Path $AppDir 'assets'
$JavaSrcDir  = Join-Path $AppDir 'java'
$Manifest    = Join-Path $AppDir 'AndroidManifest.xml'
$BuildDir    = Join-Path $Root 'build'
# 这台机器上 build/ 里会有文件被别的进程以「只读共享」方式占住（读得到、删不掉、覆盖不了），
# javac 往 build\classes 写 class 就会报「写入时出错」、jar 也覆盖不了。清不掉就换一个目录，
# 否则整个构建卡死在一个残留文件上（根因未查明，重启后自行消失）。
Remove-Item $BuildDir -Recurse -Force -ErrorAction SilentlyContinue
if (Test-Path $BuildDir) {
    foreach ($alt in @('build2', ('build-' + (Get-Date -Format 'HHmmss')))) {
        $cand = Join-Path $Root $alt
        Remove-Item $cand -Recurse -Force -ErrorAction SilentlyContinue
        if (-not (Test-Path $cand)) {
            Write-Host "  !!  build/ 有文件被占用，改用 $alt" -ForegroundColor Yellow
            $BuildDir = $cand
            break
        }
    }
}
$GenDir      = Join-Path $BuildDir 'gen'
$ClassDir    = Join-Path $BuildDir 'classes'
$DexDir      = Join-Path $BuildDir 'dex'
$KeystoreDir = Join-Path $Root 'keystore'
$Keystore    = Join-Path $KeystoreDir 'subsonic-player.jks'

$Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME }
       elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
       else { 'D:\tools\Android\Sdk' }

$BuildTools = Join-Path $Sdk 'build-tools\34.0.0'
$AndroidJar = Join-Path $Sdk 'platforms\android-34\android.jar'
$JbrHome    = 'D:\tools\Android\AndroidStudio\jbr'
# 优先 Android Studio 自带 JBR 17（build-tools 34 的 d8/apksigner 需要 Java 11+）
$JavaHome   = if (Test-Path (Join-Path $JbrHome 'bin\javac.exe')) { $JbrHome }
              elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { $env:JAVA_HOME }
              else { throw '找不到 JDK：需要 D:\tools\Android\AndroidStudio\jbr 或设置 JAVA_HOME' }
$Javac      = Join-Path $JavaHome 'bin\javac.exe'
$Keytool    = Join-Path $JavaHome 'bin\keytool.exe'

$Aapt2      = Join-Path $BuildTools 'aapt2.exe'
$D8         = Join-Path $BuildTools 'd8.bat'
$ZipAlign   = Join-Path $BuildTools 'zipalign.exe'
$ApkSigner  = Join-Path $BuildTools 'apksigner.bat'

$VersionCode = 2
$VersionName = '0.2.0'
$MinSdk      = 29
$TargetSdk   = 34

$ApkName     = "SubsonicPlayer-$VersionName-$Configuration.apk"
$OutApk      = Join-Path $Root "dist\$ApkName"

# ---------- 前置检查 ----------
function Assert-Tool([string]$Path, [string]$What) {
    if (-not (Test-Path $Path)) { throw "缺少 $What：$Path" }
}
Assert-Tool $AndroidJar 'android.jar (platforms/android-34)'
Assert-Tool $Aapt2      'aapt2.exe (build-tools/34.0.0)'
Assert-Tool $Javac      'javac.exe (JDK 17)'
Assert-Tool $ZipAlign   'zipalign.exe'
Assert-Tool $ApkSigner  'apksigner.bat'
Assert-Tool $D8         'd8.bat'

function Step([string]$Msg) { Write-Host "`n=== $Msg ===" -ForegroundColor Cyan }
function Ok([string]$Msg)   { Write-Host "  ok  $Msg" -ForegroundColor Green }

# ---------- 清理 ----------
if ($Clean) {
    Step "清理"
    Remove-Item $BuildDir -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $Root 'dist') -Recurse -Force -ErrorAction SilentlyContinue
    Ok 'build/ 与 dist/ 已删除'
    return
}

New-Item -ItemType Directory -Force -Path $BuildDir, $GenDir, $ClassDir, $DexDir, (Join-Path $Root 'dist') | Out-Null

# ---------- 1. 资源编译 ----------
Step "1/6 aapt2 compile 资源"
$ResZip = Join-Path $BuildDir 'resources.zip'
Remove-Item $ResZip -Force -ErrorAction SilentlyContinue
& $Aapt2 compile --dir $ResDir -o $ResZip
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile 失败' }
Ok "resources.zip ($((Get-Item $ResZip).Length) 字节)"

# ---------- 2. 资源链接（生成带资源的未签名 APK + R.java） ----------
Step "2/6 aapt2 link"
$BaseApk = Join-Path $BuildDir 'base.apk'
Remove-Item $BaseApk -Force -ErrorAction SilentlyContinue
$aaptLinkArgs = @(
    'link',
    '-o', $BaseApk,
    '-I', $AndroidJar,
    '--manifest', $Manifest,
    '-R', $ResZip,
    '--java', $GenDir,
    '--min-sdk-version', $MinSdk,
    '--target-sdk-version', $TargetSdk,
    '--version-code', $VersionCode,
    '--version-name', $VersionName,
    '--auto-add-overlay'
)
if (Test-Path $AssetsDir) { $aaptLinkArgs += @('-A', $AssetsDir) }
& $Aapt2 @aaptLinkArgs
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }
Ok "base.apk ($((Get-Item $BaseApk).Length) 字节)"

# ---------- 3. javac ----------
Step "3/6 javac 编译 Java 源码"
$Sources = Get-ChildItem -Path $JavaSrcDir, $GenDir -Recurse -Filter *.java -ErrorAction SilentlyContinue
if (-not $Sources) { throw "没有找到 Java 源码：$JavaSrcDir" }
$SourceList = Join-Path $BuildDir 'sources.txt'
# 必须无 BOM，否则 javac @argfile 会把 BOM 当成非法参数（PowerShell 5.1 的 -Encoding UTF8 会写 BOM）
[System.IO.File]::WriteAllLines($SourceList, [string[]]$Sources.FullName, (New-Object System.Text.UTF8Encoding($false)))
Remove-Item "$ClassDir\*" -Recurse -Force -ErrorAction SilentlyContinue
& $Javac -encoding UTF-8 -source 8 -target 8 -nowarn -Xlint:none `
    -classpath $AndroidJar -d $ClassDir "@$SourceList" 2>&1 |
    Where-Object { $_ -notmatch 'bootstrap class path|source value 8|target value 8|警告|warning' } |
    ForEach-Object { Write-Host "  $_" }
if ($LASTEXITCODE -ne 0) { throw 'javac 编译失败' }
$ClassCount = (Get-ChildItem $ClassDir -Recurse -Filter *.class).Count
Ok "$ClassCount 个 class（$($Sources.Count) 个源文件）"

# ---------- 4. d8 转 dex ----------
Step "4/6 d8 生成 classes.dex"
Remove-Item "$DexDir\*" -Recurse -Force -ErrorAction SilentlyContinue
# 类文件可能上千，逐个当参数会触发 Windows 命令行长度上限；d8 也不接受裸目录，
# 因此先打成 jar 再交给 d8（jar 是 JDK 自带工具）。
$Jar = Join-Path $JavaHome 'bin\jar.exe'
Assert-Tool $Jar 'jar.exe (JDK)'
$ClassJar = Join-Path $BuildDir 'classes.jar'
Remove-Item $ClassJar -Force -ErrorAction SilentlyContinue
# classes.jar 偶尔会被别的进程以「只读共享」方式占着（实测：上次会话留下的句柄，
# 读得到却删不掉/覆盖不了），此时 jar 无法写它，构建会卡在 d8 之前报 AccessDenied。
# 检测到覆盖不了就换一个带时间戳的文件名，别让一个残留文件把整条构建卡死。
if (Test-Path $ClassJar) {
    $jarLocked = $false
    try { $probe = [System.IO.File]::OpenWrite($ClassJar); $probe.Close() } catch { $jarLocked = $true }
    if ($jarLocked) {
        $ClassJar = Join-Path $BuildDir ('classes-' + (Get-Date -Format 'HHmmss') + '.jar')
        Write-Host "  !!  classes.jar 被占用，改用 $(Split-Path $ClassJar -Leaf)" -ForegroundColor Yellow
    }
}
& $Jar cf $ClassJar -C $ClassDir . 2>&1 | ForEach-Object { Write-Host "  $_" }
if ($LASTEXITCODE -ne 0) { throw '打包 classes.jar 失败' }
& $D8 --release --min-api $MinSdk --lib $AndroidJar --output $DexDir $ClassJar 2>&1 |
    Where-Object { $_ -notmatch '^\s*$' } | ForEach-Object { Write-Host "  $_" }
if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }
$DexFile = Join-Path $DexDir 'classes.dex'
Assert-Tool $DexFile 'classes.dex'
Ok "classes.dex ($((Get-Item $DexFile).Length) 字节)"

# ---------- 5. 打包 APK（把 dex 塞进 base.apk） ----------
Step "5/6 打包 APK"
$UnsigndApk = Join-Path $BuildDir 'app-unsigned.apk'
Copy-Item $BaseApk $UnsigndApk -Force
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($UnsigndApk, [System.IO.Compression.ZipArchiveMode]::Update)
$SoCount = 0
$SoAbis = @()
try {
    $existing = $zip.GetEntry('classes.dex')
    if ($existing) { $existing.Delete() }
    [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, $DexFile, 'classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)

    # 原生库：app\jniLibs\<abi>\*.so -> lib\<abi>\*.so
    # （BASS 包装层 libspbass.so 与官方 libbass*.so；没有该目录时自动跳过，
    #   所以缺原生库不会影响其它功能构建。）
    $JniLibsDir = Join-Path $Root 'app\jniLibs'
    if (Test-Path $JniLibsDir) {
        foreach ($abiDir in Get-ChildItem $JniLibsDir -Directory -ErrorAction SilentlyContinue) {
            if ($abiDir.Name -eq 'include') { continue }
            $soFiles = Get-ChildItem $abiDir.FullName -Filter *.so -ErrorAction SilentlyContinue
            if (-not $soFiles) { continue }
            $SoAbis += $abiDir.Name
            foreach ($so in $soFiles) {
                $entryName = "lib/$($abiDir.Name)/$($so.Name)"
                $e = $zip.GetEntry($entryName)
                if ($e) { $e.Delete() }
                [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                    $zip, $so.FullName, $entryName, [System.IO.Compression.CompressionLevel]::Optimal)
                $SoCount++
            }
        }
    }
} finally { $zip.Dispose() }
Ok "已写入 classes.dex"
if ($SoCount -gt 0) { Ok "已写入原生库 $SoCount 个（$($SoAbis -join ', ')）" }
else { Write-Host "  --  未打包原生库（app\jniLibs 下没有 .so，BASS 功能不可用）" -ForegroundColor DarkGray }

$AlignedApk = Join-Path $BuildDir 'app-aligned.apk'
Remove-Item $AlignedApk -Force -ErrorAction SilentlyContinue
& $ZipAlign -f -p 4 $UnsigndApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }
Ok 'zipalign 完成'

# ---------- 6. 签名 ----------
Step "6/6 签名 APK"
if ($NoSign) {
    Copy-Item $AlignedApk $OutApk -Force
    Ok "未签名产物：$OutApk"
} else {
    if (-not (Test-Path $Keystore)) {
        New-Item -ItemType Directory -Force -Path $KeystoreDir | Out-Null
        Write-Host '  生成自签名密钥库 keystore/subsonic-player.jks'
        & $Keytool -genkeypair -v -keystore $Keystore -alias subsonicplayer `
            -keyalg RSA -keysize 2048 -validity 10950 `
            -storepass subsonicplayer -keypass subsonicplayer `
            -dname 'CN=Subsonic Player, OU=Android, O=lixscn, L=, ST=, C=CN' 2>&1 |
            Where-Object { $_ -notmatch '^$' } | ForEach-Object { Write-Host "  $_" }
        if ($LASTEXITCODE -ne 0) { throw 'keytool 生成密钥失败' }
    }
    Remove-Item $OutApk -Force -ErrorAction SilentlyContinue
    & $ApkSigner sign `
        --ks $Keystore --ks-key-alias subsonicplayer `
        --ks-pass pass:subsonicplayer --key-pass pass:subsonicplayer `
        --min-sdk-version $MinSdk `
        --out $OutApk $AlignedApk 2>&1 |
        Where-Object { $_ -notmatch '^$' } | ForEach-Object { Write-Host "  $_" }
    if ($LASTEXITCODE -ne 0) { throw 'apksigner 签名失败' }
    & $ApkSigner verify --min-sdk-version $MinSdk $OutApk 2>&1 |
        ForEach-Object { Write-Host "  $_" }
    if ($LASTEXITCODE -ne 0) { throw 'apksigner 校验失败' }
}

Write-Host ''
Write-Host "构建成功：$OutApk" -ForegroundColor Green
Write-Host "  大小：$([math]::Round((Get-Item $OutApk).Length / 1KB, 1)) KB"

# ---------- 安装 ----------
if ($Install) {
    Step 'adb install'
    $Adb = Join-Path $Sdk 'platform-tools\adb.exe'
    Assert-Tool $Adb 'adb.exe'
    $adbArgs = @()
    if ($Serial) { $adbArgs += @('-s', $Serial) }
    & $Adb @adbArgs install -r $OutApk 2>&1 | ForEach-Object { Write-Host "  $_" }
    if ($LASTEXITCODE -ne 0) { throw 'adb install 失败' }
    Ok '安装完成'

    if ($Configuration -eq 'debug') {
        & $Adb @adbArgs shell am start -n 'com.lixscn.subsonicplayer/.MainActivity' 2>&1 | ForEach-Object { Write-Host "  $_" }
    }
}
