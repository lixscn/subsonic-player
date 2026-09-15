# Verify that CJK string literals in BOM-less .cs files are read as UTF-8 by Roslyn
# and stored verbatim (UTF-16) in the built assembly.
# ASCII-only on purpose: Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI, so CJK here would break parsing.
$dotnet = Join-Path (Split-Path -Parent $PSScriptRoot) 'dotnet'
$src = Join-Path $dotnet 'src/SubsonicPlayer.Cef/Services/CefPageDataProvider.cs'
$dll = Join-Path $dotnet 'src/SubsonicPlayer.Cef/bin/Debug/net10.0-windows10.0.19041.0/SubsonicPlayer.dll'

$text = [System.IO.File]::ReadAllText($src, [System.Text.Encoding]::UTF8)
$literals = [regex]::Matches($text, '"([^"\r\n]*[\u4e00-\u9fff][^"\r\n]*)"') |
    ForEach-Object { $_.Groups[1].Value } |
    Where-Object { $_ -notmatch '\{' } |
    Select-Object -Unique
"candidates: $($literals.Count)"

$bytes = [System.IO.File]::ReadAllBytes($dll)
$hits = 0
$miss = @()
foreach ($lit in $literals) {
    $needle = [System.Text.Encoding]::Unicode.GetBytes($lit)
    $found = $false
    for ($i = 0; $i -le $bytes.Length - $needle.Length; $i++) {
        if ($bytes[$i] -eq $needle[0]) {
            $ok = $true
            for ($j = 1; $j -lt $needle.Length; $j++) {
                if ($bytes[$i + $j] -ne $needle[$j]) { $ok = $false; break }
            }
            if ($ok) { $found = $true; break }
        }
    }
    if ($found) { $hits++ } else { $miss += $lit }
}
"found verbatim in DLL: $hits / $($literals.Count)"
if ($miss.Count) {
    "MISSING:"
    $miss | ForEach-Object { "  [$_]" }
}
