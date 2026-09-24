# ASCII-only port of the Markdown table detection in StreamText.swift, used to check
# why a still-streaming table is (or is not) drawn as a table. Samples live in JSON (UTF-8).

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$utf8 = [System.Text.Encoding]::UTF8
$samples = [System.IO.File]::ReadAllText((Join-Path $here 'table-samples.json'), $utf8) | ConvertFrom-Json

function Get-Separator([string] $line) {
    $pipe = $line.IndexOf('|')
    $plus = $line.IndexOf('+')
    if ($pipe -lt 0 -and $plus -lt 0) { return $null }
    if ($pipe -ge 0 -and $plus -lt 0) { return '|' }
    if ($pipe -lt 0 -and $plus -ge 0) { return '+' }
    if ($pipe -lt $plus) { return '|' }
    return '+'
}

function Split-Row([string] $line) {
    $value = $line.Trim()
    $marker = Get-Separator $value
    if (-not $marker) { $marker = '|' }
    if ($value.StartsWith($marker)) { $value = $value.Substring(1) }
    if ($value.EndsWith($marker) -and -not $value.EndsWith('\' + $marker)) { $value = $value.Substring(0, $value.Length - 1) }
    $cells = New-Object System.Collections.ArrayList
    $current = ''
    $index = 0
    while ($index -lt $value.Length) {
        $character = $value[$index]
        if ($character -eq '\' -and ($index + 1) -lt $value.Length) {
            $next = $value[$index + 1]
            if ($next -eq $marker) { $current += $marker } else { $current += $character; $current += $next }
            $index += 2
            continue
        }
        if ($character -eq $marker) { [void]$cells.Add($current); $current = ''; $index++; continue }
        $current += $character
        $index++
    }
    [void]$cells.Add($current)
    return @($cells | ForEach-Object { $_.Trim() })
}

function Test-HasText([string] $cell) {
    $value = $cell.Replace(' ', '')
    if ($value.Length -eq 0) { return $false }
    foreach ($ch in $value.ToCharArray()) { if ($ch -ne '-' -and $ch -ne ':' -and $ch -ne '=') { return $true } }
    return $false
}

function Test-TableRow([string] $line) {
    $trimmed = $line.Trim()
    if ($trimmed.Length -eq 0) { return $false }
    return ($null -ne (Get-Separator $trimmed))
}

function Get-AlignmentRow([string] $line, [string] $header) {
    $trimmed = $line.Trim()
    if (-not (Get-Separator $trimmed)) { return $null }
    if (-not $trimmed.Contains('-')) { return $null }
    $cells = Split-Row $trimmed
    $headerCells = @(Split-Row $header)
    $headerHasText = $false
    foreach ($cell in $headerCells) { if (Test-HasText $cell) { $headerHasText = $true } }
    if ($cells.Count -lt 2 -and -not $headerHasText) { return $null }
    $result = @()
    foreach ($cell in $cells) {
        $dashes = $cell.Replace(' ', '')
        if ($dashes.Length -lt 1) { return $null }
        foreach ($ch in $dashes.ToCharArray()) { if ($ch -ne '-' -and $ch -ne ':' -and $ch -ne '=') { return $null } }
        $left = $dashes.StartsWith(':')
        $right = $dashes.EndsWith(':')
        if ($left -and $right) { $result += 'center' } elseif ($right) { $result += 'trailing' } else { $result += 'leading' }
    }
    return ,@($result)
}

function Test-TableHeader([string] $line) {
    if (-not (Get-Separator $line)) { return $false }
    $hasText = $false
    foreach ($cell in (Split-Row $line)) { if (Test-HasText $cell) { $hasText = $true } }
    if (-not $hasText) { return $false }
    return ($null -eq (Get-AlignmentRow $line $line))
}

function Test-TableContinues([string[]] $lines, [int] $index) {
    if ($index -ge $lines.Count) { return $false }
    $next = $lines[$index].Trim()
    if (Test-TableRow $next) { return $true }
    return ($next.StartsWith('|') -or $next.StartsWith('+'))
}

function Get-Blocks([string] $source) {
    $blocks = New-Object System.Collections.ArrayList
    # Swift splits on any newline; this port only needs \n.
    $lines = @($source -split "`n")
    $index = 0
    while ($index -lt $lines.Count) {
        $line = $lines[$index]
        $trimmed = $line.Trim()
        if (($index + 1) -lt $lines.Count) {
            $alignments = Get-AlignmentRow $lines[$index + 1] $trimmed
        } else {
            $alignments = $null
        }
        if ($alignments -and (Test-TableHeader $trimmed)) {
            $headers = Split-Row $trimmed
            $rows = New-Object System.Collections.ArrayList
            $cursor = $index + 2
            while ($cursor -lt $lines.Count -and (Test-TableRow $lines[$cursor])) {
                $cells = Split-Row $lines[$cursor].Trim()
                $hasText = $false
                foreach ($cell in $cells) { if (Test-HasText $cell) { $hasText = $true } }
                if ($hasText) { [void]$rows.Add($cells) }
                $cursor++
            }
            if ($rows.Count -gt 0) {
                if (Test-TableContinues -lines $lines -index $cursor) {
                    $raw = @($lines[$index], $lines[$index + 1]) + @($lines[$cursor..($lines.Count - 1)])
                    [void]$blocks.Add([pscustomobject]@{ Kind = 'paragraph'; Text = ($raw -join "`n") })
                    $index = $lines.Count
                    continue
                }
                [void]$blocks.Add([pscustomobject]@{ Kind = 'table'; Text = ''; Rows = $rows.Count })
                $index = $cursor
                continue
            }
        }
        $index++
    }
    return $blocks
}
