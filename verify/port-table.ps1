# ASCII-only harness for the table detection port.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$utf8 = [System.Text.Encoding]::UTF8
. (Join-Path $here 'port-table-lib.ps1')
$samples = [System.IO.File]::ReadAllText((Join-Path $here 'table-samples.json'), $utf8) | ConvertFrom-Json

$fail = 0
foreach ($sample in $samples) {
    $blocks = Get-Blocks $sample.input
    $kinds = @($blocks | ForEach-Object { $_.Kind })
    $hasTable = $kinds -contains 'table'
    $text = (@($blocks | ForEach-Object { $_.Text }) -join "`n")
    $ok = $true
    if ($hasTable -ne [bool]$sample.expectTable) { $ok = $false }
    foreach ($needle in $sample.expectText) { if (-not $text.Contains($needle)) { $ok = $false } }
    if (-not $ok) { $fail++ }
    Write-Output ("{0} {1} (table={2}, kinds={3})" -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $sample.name, $hasTable, ($kinds -join ','))
    if (-not $ok) {
        Write-Output ("   expected table={0}, text~{1}" -f $sample.expectTable, ($sample.expectText -join ' + '))
        Write-Output ("   got text=[{0}]" -f $text)
    }
}
Write-Output ("--- {0} sample(s), {1} failure(s)" -f $samples.Count, $fail)
if ($fail -gt 0) { exit 1 }
