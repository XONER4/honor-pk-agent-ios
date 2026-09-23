param(
  [string]$KeyFile = 'HonorPKAgent\DeepSeekConfig.plist',
  [string]$CaseFile = 'verify\repro-cases.json'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$src = [System.IO.File]::ReadAllText((Resolve-Path $KeyFile), [System.Text.Encoding]::UTF8)
$m = [regex]::Match($src, '<key>APIKey</key>\s*<string>([^<]*)</string>')
if (-not $m.Success) { $m = [regex]::Match($src, '(sk-[A-Za-z0-9]+)') }
$key = $m.Groups[1].Value
$spec = [System.IO.File]::ReadAllText((Resolve-Path $CaseFile), [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$endpoint = 'https://api.deepseek.com/chat/completions'
$tmp = Join-Path $env:TEMP 'honer-repro'
Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $tmp | Out-Null

foreach ($case in $spec.cases) {
  $messages = @(@{ role = 'system'; content = $spec.instruction })
  foreach ($turn in $case.turns) { $messages += @{ role = $turn.role; content = $turn.content } }
  $body = [ordered]@{
    model = $spec.model
    messages = $messages
    thinking = @{ type = 'enabled' }
    stream = $true
  }
  if ($case.effort) { $body['reasoning_effort'] = $case.effort }
  if ($case.maxTokens) { $body['max_tokens'] = $case.maxTokens }
  if ($case.tools) { $body['tools'] = $spec.tools; $body['tool_choice'] = 'auto' }
  if ($case.temperature -ne $null) { $body['temperature'] = $case.temperature }
  if ($case.topP -ne $null) { $body['top_p'] = $case.topP }
  if ($case.prefix) {
    $messages += @{ role = 'assistant'; content = $case.prefix; prefix = $true }
    $body['messages'] = $messages
  }
  $json = $body | ConvertTo-Json -Depth 12 -Compress
  Push-Location $tmp
  [System.IO.File]::WriteAllBytes((Join-Path $tmp 'body.json'), [System.Text.Encoding]::UTF8.GetBytes($json))
  $args = @('-sS','-N','--max-time','240','-o','out.sse','-w','%{http_code}',
            '-H', "Authorization: Bearer $key",
            '-H', 'Content-Type: application/json',
            '-H', 'Accept: text/event-stream',
            '-X','POST','--data-binary','@body.json', $endpoint)
  $code = & curl.exe @args
  Pop-Location

  $raw = [System.IO.File]::ReadAllText((Join-Path $tmp 'out.sse'), [System.Text.Encoding]::UTF8)
  $content = New-Object System.Text.StringBuilder
  $reasoning = New-Object System.Text.StringBuilder
  $finish = ''
  $events = 0
  $usage = ''
  foreach ($line in ($raw -split "`n")) {
    if (-not $line.StartsWith('data:')) { continue }
    $payload = $line.Substring(5).Trim()
    if ($payload -eq '' -or $payload -eq '[DONE]') { continue }
    $events++
    try { $obj = $payload | ConvertFrom-Json } catch { continue }
    if ($obj.usage) { $usage = "completion=$($obj.usage.completion_tokens) reasoning=$($obj.usage.completion_tokens_details.reasoning_tokens)" }
    $choice = $obj.choices | Select-Object -First 1
    if (-not $choice) { continue }
    if ($choice.delta.content) { [void]$content.Append([string]$choice.delta.content) }
    if ($choice.delta.reasoning_content) { [void]$reasoning.Append([string]$choice.delta.reasoning_content) }
    if ($choice.finish_reason) { $finish = [string]$choice.finish_reason }
  }
  $c = $content.ToString()
  Write-Output ("=== {0} ===" -f $case.title)
  Write-Output ("HTTP $code | finish_reason=[$finish] | events=$events | answer=$($c.Length) chars | reasoning=$($reasoning.Length) chars | $usage")
  $preview = $c -replace "`r?`n", ' | '
  if ($preview.Length -gt 260) { $preview = $preview.Substring(0,260) + '...' }
  Write-Output ("ANSWER: " + $preview)
  Write-Output ''
}
