# BMChat brand cleanup — replace user-visible "Delta Chat" mentions with "BMChat"
# inside string resource / help HTML / fastlane / locale files only.
# Internal package names, the upstream core submodule, license headers, the
# AndroidManifest legacy host i.delta.chat and DeltaChat data-migration paths
# are intentionally NOT touched.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/rebrand_strings.ps1

[CmdletBinding()]
param(
    [string] $RepoRoot = (Resolve-Path "$PSScriptRoot\..").Path
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

# Ordered: URLs first (so "Delta Chat" replacement does not corrupt them),
# then word forms.
$replacements = [System.Collections.Specialized.OrderedDictionary]::new()
# Donate / fundraising links — drop entirely so we do not solicit donations
# for the upstream Delta Chat project from BMChat users.
$replacements.Add('https://delta\.chat/donate(/[A-Za-z0-9_\-]*)?', '')
$replacements.Add('https://delta\.chat/help(/[A-Za-z0-9_\-]*)?', '')
# Public Delta Chat web entry points → BMChat VPS.
$replacements.Add('https://get\.delta\.chat(/[A-Za-z0-9_\-]*)?', 'http://5.187.4.132')
# Support / issues — to our GitHub.
$replacements.Add('https://support\.delta\.chat(/[A-Za-z0-9_\-]*)?', 'https://github.com/hvkeyn/BMChat/issues')
$replacements.Add('https://github\.com/deltachat/[A-Za-z0-9_\-]+/issues(/[A-Za-z0-9_\-]*)?', 'https://github.com/hvkeyn/BMChat/issues')
$replacements.Add('https://github\.com/deltachat/[A-Za-z0-9_\-]+', 'https://github.com/hvkeyn/BMChat')
$replacements.Add('https://github\.com/deltachat', 'https://github.com/hvkeyn/BMChat')
# Translation portal / external services that we are not part of.
$replacements.Add('https://www\.transifex\.com/delta-chat/[A-Za-z0-9_\-]*', 'https://github.com/hvkeyn/BMChat')
# i.delta.chat in user-visible strings only — the AndroidManifest deeplink
# host is rewritten by a separate, scoped pass that excludes this script.
$replacements.Add('https://i\.delta\.chat', 'http://5.187.4.132/i')
$replacements.Add('i\.delta\.chat', '5.187.4.132/i')
# Apple App Store URL of upstream Delta Chat — drop the link entirely.
$replacements.Add('https://apps\.apple\.com/app/delta-chat/id[0-9]+', '')
# Brand mentions — last so URL forms above absorb the dotted variant first.
$replacements.Add('Delta\s+Chat', 'BMChat')
$replacements.Add('DeltaChat', 'BMChat')
$replacements.Add('Deltachat', 'BMChat')
$replacements.Add('delta\.chat', 'bmchat.local')
$replacements.Add('delta-chat\.de', 'bmchat.local')
# Clean-ups after the first pass:
#   "BMChat or BMChat installation" → "BMChat installation" (across languages,
#    the connector word is up to 6 chars: or/и/или/oder/ou/o/of/eller/o…).
$replacements.Add('\bBMChat\b\s+\S{1,6}\s+\bBMChat\b', 'BMChat')
#   "at " followed by a closing comment marker = link removed → trim the orphan
#   prefix so the comment still makes grammatical sense.
$replacements.Add(' at\s{2,}-->', ' -->')
$replacements.Add(' at\s{2,}<', ' <')
#   Empty markdown links produced when href becomes empty.
$replacements.Add('\[([^\]]*)\]\(\s*\)', '$1')
#   Double spaces left over from removed URLs.
$replacements.Add('([^\s])  ([^\s])', '$1 $2')

$targets = @(
    'clients\android\src\main\res\values\strings.xml'
    'clients\android\src\main\res\values-*\strings.xml'
    'clients\android\src\main\assets\help'
    'clients\android\fastlane\metadata\android'
    'clients\desktop\_locales'
    'clients\desktop\static\help'
    'clients\ios\deltachat-ios\en.lproj'
    'clients\ios\deltachat-ios\*.lproj\Localizable.strings'
    'clients\ios\deltachat-ios\*.lproj\InfoPlist.strings'
    'clients\ios\deltachat-ios\Assets\Help'
)

$fileExtensions = @('*.xml', '*.html', '*.txt', '*.strings')

$collected = @{}

foreach ($pattern in $targets) {
    $expanded = Join-Path $RepoRoot $pattern
    $items = Get-Item -Path $expanded -ErrorAction SilentlyContinue
    if (-not $items) { continue }
    foreach ($item in $items) {
        if (Test-Path $item.FullName -PathType Container) {
            foreach ($ext in $fileExtensions) {
                Get-ChildItem -Path $item.FullName -Recurse -Filter $ext -File -ErrorAction SilentlyContinue |
                    ForEach-Object { $collected[$_.FullName] = $true }
            }
        } else {
            $collected[$item.FullName] = $true
        }
    }
}

$files = @($collected.Keys)
Write-Host ("Targeting {0} files." -f $files.Count)

$changedFiles = 0
$totalSubs = 0

foreach ($f in $files) {
    if ($f -match 'deltachat-core-rust') { continue }
    if ($f -match 'libraries\\deltachat-core-rust') { continue }
    if ($f -match '\\jni\\deltachat-core-rust') { continue }

    $content = [System.IO.File]::ReadAllText($f, $utf8NoBom)
    $original = $content
    $localSubs = 0

    foreach ($key in $replacements.Keys) {
        $regex = [regex]::new($key, 'IgnoreCase')
        $matches = $regex.Matches($content)
        if ($matches.Count -gt 0) {
            $content = $regex.Replace($content, $replacements[$key])
            $localSubs += $matches.Count
        }
    }

    if ($content -ne $original) {
        [System.IO.File]::WriteAllText($f, $content, $utf8NoBom)
        $changedFiles++
        $totalSubs += $localSubs
        $rel = $f.Substring($RepoRoot.Length).TrimStart('\','/')
        Write-Host ("  [{0,4}] {1}" -f $localSubs, $rel)
    }
}

Write-Host ""
Write-Host ("Done. {0} files modified, {1} total substitutions." -f $changedFiles, $totalSubs)
