param(
  [string]$KeyFile = 'HonorPKAgent\DeepSeekConfig.plist',
  [string]$CaseFile = 'verify\live-requests.json'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$src = [System.IO.File]::ReadAllText((Resolve-Path $KeyFile), [System.Text.Encoding]::UTF8)
$m = [regex]::Match($src, '<key>APIKey</key>\s*<string>([^<]*)</string>')
if (-not $m.Success) { $m = [regex]::Match($src, '(sk-[A-Za-z0-9]+)') }
if (-not $m.Success) { throw 'API key not found' }
$key = $m.Groups[1].Value

$spec = [System.IO.File]::ReadAllText((Resolve-Path $CaseFile), [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$endpoint = 'https://api.deepseek.com/chat/completions'
$tmp = Join-Path $env:TEMP 'honer-live'
Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $tmp | Out-Null

function Measure-Text {
  param([string]$Text)
  $letters = 0; $cyr = 0
  foreach ($ch in $Text.ToCharArray()) {
    if ([char]::IsLetter($ch)) {
      $letters++
      $code = [int][char]$ch
      if ($code -ge 0x0400 -and $code -le 0x04FF) { $cyr++ }
    }
  }
  $share = 0
  if ($letters -gt 0) { $share = [math]::Round(100 * $cyr / $letters) }
  return [pscustomobject]@{ Letters = $letters; Cyrillic = $cyr; Share = $share }
}

$summary = @()

foreach ($case in $spec.cases) {
  $messages = @(
    @{ role = 'system'; content = $spec.instruction },
    @{ role = 'user'; content = $case.question }
  )
  $body = [ordered]@{
    model = 'deepseek-flash'
    messages = $messages
    thinking = @{ type = 'enabled' }
    reasoning_effort = 'high'
    stream = $true
  }
  if ($case.old) {
    $body['max_tokens'] = 16384
    $body['tools'] = $spec.tools
    $body['tool_choice'] = 'auto'
  }
  $json = $body | ConvertTo-Json -Depth 12 -Compress
  # curl gets simple relative names: PowerShell 5.1 breaks on absolute paths with spaces.
  Push-Location $tmp
  [System.IO.File]::WriteAllBytes((Join-Path $tmp 'body.json'), [System.Text.Encoding]::UTF8.GetBytes($json))
  $args = @('-sS','-N','--max-time','240','-o','out.sse','-w','%{http_code}',
            '-H', "Authorization: Bearer $key",
            '-H', 'Content-Type: application/json',
            '-H', 'Accept: text/event-stream',
            '-X','POST','--data-binary','@body.json',
            $endpoint)
  $code = & curl.exe @args
  Pop-Location

  $out = Join-Path $tmp 'out.sse'
  Copy-Item $out (Join-Path $tmp ($case.id + '.sse')) -Force
  $raw = [System.IO.File]::ReadAllText($out, [System.Text.Encoding]::UTF8)
  $content = New-Object System.Text.StringBuilder
  $reasoning = New-Object System.Text.StringBuilder
  $finish = ''
  $toolChunks = 0
  foreach ($line in ($raw -split "`n")) {
    if (-not $line.StartsWith('data:')) { continue }
    $payload = $line.Substring(5).Trim()
    if ($payload -eq '' -or $payload -eq '[DONE]') { continue }
    try { $obj = $payload | ConvertFrom-Json } catch { continue }
    $choice = $obj.choices | Select-Object -First 1
    if (-not $choice) { continue }
    if ($choice.delta.content) { [void]$content.Append([string]$choice.delta.content) }
    if ($choice.delta.reasoning_content) { [void]$reasoning.Append([string]$choice.delta.reasoning_content) }
    if ($choice.delta.tool_calls) { $toolChunks += @($choice.delta.tool_calls).Count }
    if ($choice.finish_reason) { $finish = [string]$choice.finish_reason }
  }
  $cText = $content.ToString()
  $rText = $reasoning.ToString()
  $cm = Measure-Text $cText
  $rm = Measure-Text $rText

  $preview = $cText -replace "`r?`n", ' | '
  if ($preview.Length -gt 400) { $preview = $preview.Substring(0, 400) + '...' }

  Write-Output ('=== ' + $case.title + ' ===')
  Write-Output ("HTTP $code | finish_reason=$finish | answer chars=$($cText.Length) | answer cyrillic=$($cm.Share)% | reasoning chars=$($rText.Length) | reasoning cyrillic=$($rm.Share)% | tool_call chunks=$toolChunks")
  Write-Output ("ANSWER: " + $preview)
  Write-Output ''

  $summary += [pscustomobject]@{
    Case = $case.id
    Title = $case.title
    Http = $code
    Finish = $finish
    AnswerChars = $cText.Length
    AnswerCyrillic = $cm.Share
    ReasoningChars = $rText.Length
    ReasoningCyrillic = $rm.Share
    ToolChunks = $toolChunks
    Answer = $cText
    Reasoning = $rText
  }
}

Write-Output '=== SUMMARY TABLE ==='
$summary | Select-Object Case, Http, Finish, AnswerChars, AnswerCyrillic, ReasoningChars, ReasoningCyrillic, ToolChunks | Format-Table -AutoSize

$report = Join-Path $tmp 'report.json'
$summary | ConvertTo-Json -Depth 6 | Set-Content -Path $report -Encoding UTF8
Write-Output ("report: " + $report)
