# ASCII-only debug of the finished-table sample.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $here 'port-table-lib.ps1')
$utf8 = [System.Text.Encoding]::UTF8
$samples = [System.IO.File]::ReadAllText((Join-Path $here 'table-samples.json'), $utf8) | ConvertFrom-Json
$source = [string]$samples[3].input
Write-Output ("name = " + $samples[3].name)
Write-Output ("source ends with newline: " + $source.EndsWith("`n"))
$lines = @($source -split "`n")
Write-Output ("lines = " + $lines.Count)
for ($i = 0; $i -lt $lines.Count; $i++) {
    Write-Output ("  [{0}] isRow={1} text=[{2}]" -f $i, (Test-TableRow $lines[$i].Trim()), $lines[$i])
}
Write-Output ("header ok: " + (Test-TableHeader $lines[0].Trim()))
$align = Get-AlignmentRow $lines[1] $lines[0].Trim()
Write-Output ("alignment: " + ($align -join ','))
Write-Output ("continues from 4: " + (Test-TableContinues -lines $lines -index 4 -source $source))
