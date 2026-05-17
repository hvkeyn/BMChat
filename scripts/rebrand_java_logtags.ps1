# Replace string literals "DeltaChat" / "DeltaChat:..." inside Java/Kotlin
# files only.  This is a deliberately narrow rewrite: it touches Log.x() tags,
# WakeLock names and channel-id literals, but never identifiers, imports, or
# package paths (those would break compilation and upstream merges).

[CmdletBinding()]
param(
    [string] $RepoRoot = (Resolve-Path "$PSScriptRoot\..").Path
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

$javaRoots = @(
    Join-Path $RepoRoot 'clients\android\src\main\java'
    Join-Path $RepoRoot 'clients\android\src\main\kotlin'
)

$files = @()
foreach ($root in $javaRoots) {
    if (Test-Path $root) {
        $files += Get-ChildItem -Path $root -Recurse -Include *.java,*.kt -File
    }
}

# Match the literal "DeltaChat" or "DeltaChat:something" inside a string and
# rewrite the brand part only.  We use a quoted-substring regex so identifiers
# like BaseDeltaChat or DeltaChatAccount are never touched.
$pattern = '"DeltaChat(?<suffix>:[^"]*)?"'

$changedFiles = 0
$totalSubs = 0

foreach ($f in $files) {
    $content = [System.IO.File]::ReadAllText($f.FullName, $utf8NoBom)
    $original = $content

    $regex = [regex]::new($pattern)
    $matches = $regex.Matches($content)
    if ($matches.Count -gt 0) {
        $content = $regex.Replace($content, {
            param($m)
            $suffix = $m.Groups['suffix'].Value
            return '"BMChat' + $suffix + '"'
        })
        if ($content -ne $original) {
            [System.IO.File]::WriteAllText($f.FullName, $content, $utf8NoBom)
            $changedFiles++
            $totalSubs += $matches.Count
            $rel = $f.FullName.Substring($RepoRoot.Length).TrimStart('\','/')
            Write-Host ("  [{0,3}] {1}" -f $matches.Count, $rel)
        }
    }
}

Write-Host ""
Write-Host ("Done. {0} files modified, {1} substitutions." -f $changedFiles, $totalSubs)
