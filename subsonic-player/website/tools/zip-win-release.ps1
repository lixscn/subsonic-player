# 把 Windows 单文件发布目录打成站点下载用的 zip
#
#   - 条目名统一用正斜杠（zip 规范）；用 Compress-Archive 在 Windows 上打出来的 zip
#     条目名是反斜杠，Linux/macOS 解压会生成带反斜杠的怪文件名
#   - 跳过运行时产生的缓存目录（DawnCache / GPUCache / Cache / Code Cache）
#   - 只打包内容，不套一层目录：解压后 SubsonicPlayer.exe 直接就在当前目录
#
# 用法：
#   pwsh -File tools\zip-win-release.ps1 -Source ..\dotnet\dist\single-win-x64 `
#        -Out downloads\SubsonicPlayer-win-x64.zip
param(
    [Parameter(Mandatory = $true)][string]$Source,
    [Parameter(Mandatory = $true)][string]$Out
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$Source = (Resolve-Path $Source).Path
if (Test-Path $Out) { Remove-Item $Out -Force }
$outDir = Split-Path $Out -Parent
if ($outDir -and -not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }

$skipDir = @('DawnCache', 'GPUCache', 'Cache', 'Code Cache', 'blob_storage', 'logs')
$files = Get-ChildItem $Source -Recurse -File | Where-Object {
    $rel = $_.FullName.Substring($Source.Length + 1)
    $parts = $rel -split '\\'
    -not ($parts | Where-Object { $skipDir -contains $_ })
}
Write-Host ("packing {0} files from {1}" -f $files.Count, $Source)

$zip = [System.IO.Compression.ZipFile]::Open($Out, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($f in $files) {
        $rel = $f.FullName.Substring($Source.Length + 1).Replace('\', '/')
        [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, $f.FullName, $rel, [System.IO.Compression.CompressionLevel]::Optimal)
    }
} finally { $zip.Dispose() }

$zi = Get-Item $Out
Write-Host ("done: {0}" -f $zi.FullName)
Write-Host ("size: {0:N0} bytes / {1:N1} MB" -f $zi.Length, ($zi.Length / 1MB))
Write-Host ("sha256: {0}" -f (Get-FileHash $Out -Algorithm SHA256).Hash)
