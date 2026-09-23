# Swift sanity check: brace balance and glued declarations. ASCII only (PS 5.1 encoding safe).
# Run from the project root: powershell -File check-swift.ps1
$ErrorActionPreference = 'Continue'
$root = Join-Path $PSScriptRoot 'HonorPKAgent'
$problems = 0

function Test-Balance([string]$path) {
    $src = [System.IO.File]::ReadAllText($path)
    $depth = 0; $i = 0; $n = $src.Length
    $inString = [char]0; $inLine = $false; $inBlock = $false; $esc = $false
    while ($i -lt $n) {
        $c = $src[$i]
        $nxt = if ($i + 1 -lt $n) { $src[$i + 1] } else { [char]0 }
        if ($inLine) { if ($c -eq "`n") { $inLine = $false } }
        elseif ($inBlock) { if ($c -eq '*' -and $nxt -eq '/') { $inBlock = $false; $i++ } }
        elseif ($inString -ne [char]0) {
            if ($esc) { $esc = $false }
            elseif ($c -eq '\') { $esc = $true }
            elseif ($c -eq $inString) { $inString = [char]0 }
        } else {
            if ($c -eq '/' -and $nxt -eq '/') { $inLine = $true; $i++ }
            elseif ($c -eq '/' -and $nxt -eq '*') { $inBlock = $true; $i++ }
            elseif ($c -eq '"' -or $c -eq "'") { $inString = $c }
            elseif ($c -eq '{') { $depth++ }
            elseif ($c -eq '}') { $depth--; if ($depth -lt 0) { return 'extra closing brace' } }
        }
        $i++
    }
    if ($depth -ne 0) { return "unbalanced braces: $depth" }
    return $null
}

Get-ChildItem $root -Recurse -Filter '*.swift' | ForEach-Object {
    $issue = Test-Balance $_.FullName
    if ($issue) { Write-Output "PROBLEM: $($_.Name) - $issue"; $script:problems++ }
    $lines = [System.IO.File]::ReadAllLines($_.FullName)
    for ($k = 0; $k -lt $lines.Count; $k++) {
        if ($lines[$k] -match '\)\s{2,}(func|private func|var|let|struct|enum|extension)\b') {
            Write-Output "GLUED: $($_.Name):$($k + 1)"
            $script:problems++
        }
        if ($lines[$k] -match '///.*\s{2,}func\s') {
            Write-Output "GLUED COMMENT: $($_.Name):$($k + 1)"
            $script:problems++
        }
        # property named 'body' inside a View conflicts with View.body (Scene.body is fine)
        if ($lines[$k] -match '^\s+(let|var)\s+body\s*:' -and
            $lines[$k] -notmatch 'var\s+body\s*:\s*some\s+(View|Scene)') {
            Write-Output "RESERVED NAME: $($_.Name):$($k + 1)"
            $script:problems++
        }
        # glued struct header and first member on one line
        if ($lines[$k] -match '^\s*(private\s+)?struct\s+\w+\s*:\s*View\s*\{\s+\S') {
            Write-Output "GLUED STRUCT: $($_.Name):$($k + 1)"
            $script:problems++
        }
    }
}

if ($problems -eq 0) { Write-Output 'OK: all Swift files passed' } else { Write-Output "PROBLEMS: $problems" }
