# Live check: how the model answers when web search is off or returned nothing.
# ASCII-only source; Russian text lives in JSON/UTF-8 files.

param(
  [string]$KeyFile = 'HonorPKAgent\DeepSeekConfig.plist',
  [string]$CaseFile = 'verify\search-off-cases.json',
  [string]$InstructionFile = 'verify\app-instruction.txt'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$utf8 = [System.Text.Encoding]::UTF8
function Read-Text([string] $path) { [System.IO.File]::ReadAllText((Resolve-Path $path), $utf8) }

$key = ([regex]::Match((Read-Text $KeyFile), '(sk-[A-Za-z0-9]+)')).Groups[1].Value
if (-not $key) { throw 'no api key' }
$instruction = Read-Text $InstructionFile
$spec = (Read-Text $CaseFile) | ConvertFrom-Json
$endpoint = 'https://api.deepseek.com/chat/completions'
$tmp = Join-Path $env:TEMP 'honer-search-off'
Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $tmp | Out-Null

$fail = 0
foreach ($case in $spec.cases) {
  $body = [ordered]@{
    model = 'deepseek-flash'
    messages = @(@{ role = 'system'; content = $instruction },
                 @{ role = 'system'; content = [string]$case.system },
                 @{ role = 'user'; content = [string]$case.user })
    thinking = @{ type = 'disabled' }
    stream = $false
  }
  Push-Location $tmp
  [System.IO.File]::WriteAllBytes((Join-Path $tmp 'body.json'), $utf8.GetBytes(($body | ConvertTo-Json -Depth 12 -Compress)))
  $raw = & curl.exe -sS --max-time 180 -H "Authorization: Bearer $key" -H 'Content-Type: application/json' `
    -X POST --data-binary '@body.json' $endpoint
  Pop-Location
  $answer = ''
  try {
    $o = $raw | ConvertFrom-Json
    if ($o.error) { throw $o.error.message }
    $answer = [string]$o.choices[0].message.content
  } catch {
    Write-Output ("FAIL {0}: service error {1}" -f $case.name, $_)
    $fail++
    continue
  }
  $preview = ($answer -replace "`r?`n", ' | ')
  if ($preview.Length -gt 260) { $preview = $preview.Substring(0, 260) + '...' }
  $missing = @()
  # Cases about fresh data must mention the search toggle so the user knows the fix.
  $needSearch = $true
  if ($null -ne $case.explainSearch) { $needSearch = [bool]$case.explainSearch }
  if ($needSearch) {
    foreach ($needle in $spec.mustExplainSearch) { if ($answer -notmatch ('(?i)' + [regex]::Escape($needle))) { $missing += $needle } }
  }
  # ...and it must offer something useful instead of a dead end.
  $offered = $false
  foreach ($needle in $spec.mustOfferAlternative) { if ($answer -match ('(?i)' + [regex]::Escape($needle))) { $offered = $true } }
  if (-not $offered) { $missing += 'alternative-offer' }
  # It must not invent today's weather.
  $bad = @()
  foreach ($needle in $spec.mustNotFabricate) { if ($answer -match ('(?i)' + [regex]::Escape($needle))) { $bad += $needle } }
  $ok = ($missing.Count -eq 0) -and ($bad.Count -eq 0)
  if (-not $ok) { $fail++ }
  Write-Output ("{0} {1}" -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $case.name)
  Write-Output ('   answer: ' + $preview)
  if ($missing.Count -gt 0) { Write-Output ('   missing: ' + ($missing -join ', ')) }
  if ($bad.Count -gt 0) { Write-Output ('   fabricated content: ' + ($bad -join ', ')) }
}
Write-Output ''
Write-Output ("--- {0} case(s), {1} failure(s)" -f $spec.cases.Count, $fail)
if ($fail -gt 0) { exit 1 }
