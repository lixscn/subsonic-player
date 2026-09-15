# 为落地页准备图片：裁剪 + 缩放 + 转 JPEG（用 System.Drawing，无需第三方库）
Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot      # website/
$assetDir = Join-Path $root 'assets'
New-Item -ItemType Directory -Force -Path $assetDir | Out-Null

function Convert-Image {
    param(
        [string]$Src, [string]$Dst,
        [int]$MaxW, [int]$MaxH,
        [string]$CropTop = 'none',   # none | phone（裁掉安卓状态栏）
        [int]$Quality = 84
    )
    if (-not (Test-Path $Src)) { Write-Host "  跳过（不存在）: $Src"; return }
    $img = [System.Drawing.Image]::FromFile($Src)
    try {
        $srcRect = New-Object System.Drawing.Rectangle(0, 0, $img.Width, $img.Height)
        if ($CropTop -eq 'phone') {
            # 安卓截图 1080x2400：裁掉顶部状态栏与底部手势区，留内容
            $top = [int]($img.Height * 0.03)
            $h = $img.Height - $top - [int]($img.Height * 0.02)
            $srcRect = New-Object System.Drawing.Rectangle(0, $top, $img.Width, $h)
        }
        $ratio = [Math]::Min($MaxW / $srcRect.Width, $MaxH / $srcRect.Height)
        if ($ratio -gt 1) { $ratio = 1 }
        $w = [int]($srcRect.Width * $ratio); $h = [int]($srcRect.Height * $ratio)

        $bmp = New-Object System.Drawing.Bitmap($w, $h)
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $g.DrawImage($img, (New-Object System.Drawing.Rectangle(0, 0, $w, $h)), $srcRect, [System.Drawing.GraphicsUnit]::Pixel)
        $g.Dispose()

        $codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
        $ps = New-Object System.Drawing.Imaging.EncoderParameters(1)
        $ps.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter([System.Drawing.Imaging.Encoder]::Quality, [long]$Quality)
        $bmp.Save($Dst, $codec, $ps)
        $bmp.Dispose()
        Write-Host ("  {0}  ->  {1}  ({2}x{3}, {4} KB)" -f (Split-Path $Src -Leaf), (Split-Path $Dst -Leaf), $w, $h, [math]::Round((Get-Item $Dst).Length / 1KB))
    } finally {
        $img.Dispose()
    }
}

$ab = Join-Path (Split-Path $root -Parent) 'android\build'   # subsonic-player/android/build

Write-Host "生成落地页图片："
Convert-Image -Src (Join-Path $assetDir 'desktop-raw.png') -Dst (Join-Path $assetDir 'desktop.jpg') -MaxW 1600 -MaxH 900 -Quality 86
Convert-Image -Src (Join-Path $ab 'nav3.png')   -Dst (Join-Path $assetDir 'android-home.jpg')   -MaxW 420 -MaxH 900 -CropTop phone
Convert-Image -Src (Join-Path $ab 's3.png')     -Dst (Join-Path $assetDir 'android-albums.jpg') -MaxW 420 -MaxH 900 -CropTop phone
Convert-Image -Src (Join-Path $ab 'a3.png')     -Dst (Join-Path $assetDir 'android-player.jpg') -MaxW 420 -MaxH 900 -CropTop phone
Convert-Image -Src (Join-Path $ab 'a5.png')     -Dst (Join-Path $assetDir 'android-settings.jpg') -MaxW 420 -MaxH 900 -CropTop phone

# 社交分享封面：用桌面图缩到 1200x630
Convert-Image -Src (Join-Path $assetDir 'desktop-raw.png') -Dst (Join-Path $assetDir 'og-cover.jpg') -MaxW 1200 -MaxH 630 -Quality 86
Write-Host "完成"
