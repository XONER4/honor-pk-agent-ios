# ASCII-only debug of the table detection port.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $here 'port-table-lib.ps1')

$utf8 = [System.Text.Encoding]::UTF8
$samples = [System.IO.File]::ReadAllText((Join-Path $here 'table-samples.json'), $utf8) | ConvertFrom-Json
$input = [string]$samples[0].input
$lines = @($input -split "`n")
for ($i = 0; $i -lt $lines.Count; $i++) {
    Write-Output ("line {0} = [{1}] sep={2} isRow={3}" -f $i, $lines[$i], (Get-Separator $lines[$i]), (Test-TableRow $lines[$i]))
}
Write-Output ("alignment of line1 with header line0: " + ((Get-AlignmentRow $lines[1] $lines[0]) -join ','))
Write-Output ("isTableHeader line0: " + (Test-TableHeader $lines[0]))
$cursor = 4
Write-Output ("tableContinues from 4: " + (Test-TableContinues -lines $lines -index $cursor))
Write-Output ("blocks: " + ((Get-Blocks $input | ForEach-Object { $_.Kind }) -join ','))
