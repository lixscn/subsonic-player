# 裁出手机截图底部区域并放大 2 倍，便于看清底部导航栏的排版问题
Add-Type -AssemblyName System.Drawing
$src = $args[0]
$dst = $args[1]
$cropH = [int]$args[2]

$img = [System.Drawing.Image]::FromFile($src)
$w = $img.Width
$h = $img.Height
$y = $h - $cropH
$crop = New-Object System.Drawing.Bitmap($w, $cropH)
$g = [System.Drawing.Graphics]::FromImage($crop)
$g.DrawImage($img, (New-Object System.Drawing.Rectangle(0, 0, $w, $cropH)),
             (New-Object System.Drawing.Rectangle(0, $y, $w, $cropH)),
             [System.Drawing.GraphicsUnit]::Pixel)
$g.Dispose()

$scale = 2
$big = New-Object System.Drawing.Bitmap(($w * $scale), ($cropH * $scale))
$g2 = [System.Drawing.Graphics]::FromImage($big)
$g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$g2.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$g2.DrawImage($crop, 0, 0, ($w * $scale), ($cropH * $scale))
$g2.Dispose()
$big.Save($dst, [System.Drawing.Imaging.ImageFormat]::Png)
$big.Dispose(); $crop.Dispose(); $img.Dispose()
Write-Host "已输出 $dst （原图 ${w}x${h}，裁剪底部 ${cropH}px，放大 ${scale}x）"
