# ASCII-only port of ChatStore.strippingToolAnnouncements for behavioural checks.
# All non-ASCII data lives in strip-samples.json / strip-words.json (UTF-8).

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$utf8 = [System.Text.Encoding]::UTF8
$words = [System.IO.File]::ReadAllText((Join-Path $here 'strip-words.json'), $utf8) | ConvertFrom-Json
$samples = [System.IO.File]::ReadAllText((Join-Path $here 'strip-samples.json'), $utf8) | ConvertFrom-Json

$verbs = @($words.verbs)
$nouns = @($words.nouns)
$markers = @($words.markers)
$splitter = [System.Text.RegularExpressions.Regex]::new([string]$words.pattern)

function Test-Announcement([string] $sentence) {
    if ([string]::IsNullOrWhiteSpace($sentence)) { return $false }
    if ($sentence.Length -ge 220) { return $false }
    $lowered = $sentence.ToLowerInvariant()
    foreach ($digit in [char[]]'0123456789') { if ($lowered.Contains($digit)) { return $false } }
    $hasVerb = $false
    foreach ($verb in $verbs) { if ($lowered.Contains($verb)) { $hasVerb = $true; break } }
    if (-not $hasVerb) { return $false }
    foreach ($noun in $nouns) { if ($lowered.Contains($noun)) { return $true } }
    foreach ($marker in $markers) { if ($lowered.Contains($marker)) { return $true } }
    return $false
}

function Get-Stripped([string] $text) {
    $value = $text.Trim()
    if ($value.Length -eq 0) { return $value }
    for ($i = 0; $i -lt 5; $i++) {
        $match = $splitter.Match($value)
        if (-not $match.Success) { break }
        $first = $match.Value.Trim()
        if (-not (Test-Announcement $first)) { break }
        $rest = $value.Substring($match.Index + $match.Length).Trim()
        if ($rest.Length -lt 12) { return '' }
        $value = $rest
    }
    return $value
}

$fail = 0
foreach ($sample in $samples) {
    $got = Get-Stripped $sample.input
    $ok = ($got -eq $sample.expected)
    if (-not $ok) { $fail++ }
    $label = if ($ok) { 'PASS' } else { 'FAIL' }
    Write-Output ("{0} {1}" -f $label, $sample.name)
    if (-not $ok) {
        Write-Output ("   expected: [{0}]" -f $sample.expected)
        Write-Output ("   got:      [{0}]" -f $got)
    }
}
Write-Output ("--- {0} sample(s), {1} failure(s)" -f $samples.Count, $fail)
if ($fail -gt 0) { exit 1 }
