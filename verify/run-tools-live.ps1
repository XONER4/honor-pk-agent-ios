param(
  [string]$KeyFile = 'HonorPKAgent\DeepSeekConfig.plist',
  [string]$CaseFile = 'verify\tools-live.json'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$src = [System.IO.File]::ReadAllText((Resolve-Path $KeyFile), [System.Text.Encoding]::UTF8)
$key = ([regex]::Match($src, '(sk-[A-Za-z0-9]+)')).Groups[1].Value
$spec = [System.IO.File]::ReadAllText((Resolve-Path $CaseFile), [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$endpoint = 'https://api.deepseek.com/chat/completions'
$tmp = Join-Path $env:TEMP 'honer-tools-live'
Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $tmp | Out-Null

function Invoke-Turn {
  param([hashtable]$Body, [string]$Name)
  Push-Location $tmp
  [System.IO.File]::WriteAllBytes((Join-Path $tmp 'body.json'), [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 14 -Compress)))
  $code = & curl.exe -sS -N --max-time 200 -o ($Name + '.sse') -w '%{http_code}' `
    -H "Authorization: Bearer $key" -H 'Content-Type: application/json' -H 'Accept: text/event-stream' `
    -X POST --data-binary '@body.json' $endpoint
  Pop-Location
  $raw = [System.IO.File]::ReadAllText((Join-Path $tmp ($Name + '.sse')), [System.Text.Encoding]::UTF8)
  $content = New-Object System.Text.StringBuilder
  $reasoning = New-Object System.Text.StringBuilder
  $finish = ''
  $calls = @{}
  $errorText = ''
  foreach ($line in ($raw -split "`n")) {
    if (-not $line.StartsWith('data:')) { continue }
    $p = $line.Substring(5).Trim()
    if ($p -eq '' -or $p -eq '[DONE]') { continue }
    try { $o = $p | ConvertFrom-Json } catch { continue }
    if ($o.error) { $errorText = $o.error.message }
    $c = $o.choices | Select-Object -First 1
    if (-not $c) { continue }
    if ($c.delta.content) { [void]$content.Append([string]$c.delta.content) }
    if ($c.delta.reasoning_content) { [void]$reasoning.Append([string]$c.delta.reasoning_content) }
    if ($c.delta.tool_calls) {
      foreach ($call in $c.delta.tool_calls) {
        $idx = '0'
        if ($null -ne $call.index) { $idx = [string]$call.index }
        if (-not $calls.ContainsKey($idx)) { $calls[$idx] = [ordered]@{ id = ''; name = ''; arguments = '' } }
        if ($call.id) { $calls[$idx].id = [string]$call.id }
        if ($call.function.name) { $calls[$idx].name = [string]$call.function.name }
        if ($call.function.arguments) { $calls[$idx].arguments += [string]$call.function.arguments }
      }
    }
    if ($c.finish_reason) { $finish = [string]$c.finish_reason }
  }
  return [pscustomobject]@{
    Http = $code; Finish = $finish; Content = $content.ToString(); Reasoning = $reasoning.ToString()
    Calls = $calls; Error = $errorText
  }
}

$messages = @(@{ role = 'system'; content = $spec.instruction })
foreach ($turn in $spec.turns) { $messages += @{ role = $turn.role; content = $turn.content } }

Write-Output '=== TURN 1: request with tools ==='
$body1 = [ordered]@{ model = 'deepseek-flash'; messages = $messages; thinking = @{ type = 'enabled' }; reasoning_effort = 'high'
                     stream = $true; tools = $spec.tools; tool_choice = 'auto' }
$r1 = Invoke-Turn -Body $body1 -Name 'turn1'
Write-Output ("HTTP {0} | finish={1} | content={2} chars | reasoning={3} chars | tool_calls={4}" -f `
  $r1.Http, $r1.Finish, $r1.Content.Length, $r1.Reasoning.Length, $r1.Calls.Count)
foreach ($k in $r1.Calls.Keys) {
  Write-Output ("  call {0}: {1} args={2}" -f $k, $r1.Calls[$k].name, $r1.Calls[$k].arguments)
}
if ($r1.Error) { Write-Output ("  SERVICE ERROR: " + $r1.Error) }

$toolBlock = [string]$spec.toolResult

Write-Output ''
Write-Output '=== TURN 2: tool result via tool role, plus a nudge to answer ==='
$assistant = [ordered]@{ role = 'assistant'; content = $r1.Content; reasoning_content = $r1.Reasoning }
if ($r1.Calls.Count -gt 0) {
  $list = @()
  foreach ($k in $r1.Calls.Keys) {
    $list += [ordered]@{ id = $r1.Calls[$k].id; type = 'function'
                         function = @{ name = $r1.Calls[$k].name; arguments = $r1.Calls[$k].arguments } }
  }
  $assistant['tool_calls'] = $list
}
$messages2 = $messages + $assistant
if ($r1.Calls.Count -gt 0) {
  foreach ($k in $r1.Calls.Keys) {
    $messages2 += @{ role = 'tool'; tool_call_id = $r1.Calls[$k].id; content = $toolBlock }
  }
  $messages2 += @{ role = 'user'; content = 'Use the received data and give the final answer to the user. Do not call this tool again.' }
} else {
  $messages2 += @{ role = 'user'; content = ('Tool result: ' + $toolBlock + ' Use it in the answer.') }
}
$body2 = [ordered]@{ model = 'deepseek-flash'; messages = $messages2; thinking = @{ type = 'enabled' }; reasoning_effort = 'high'
                     stream = $true }
$r2 = Invoke-Turn -Body $body2 -Name 'turn2'
Write-Output ("HTTP {0} | finish={1} | content={2} chars | reasoning={3} chars" -f $r2.Http, $r2.Finish, $r2.Content.Length, $r2.Reasoning.Length)
$preview = ($r2.Content -replace "`r?`n", ' | ')
if ($preview.Length -gt 240) { $preview = $preview.Substring(0, 240) + '...' }
Write-Output ("  answer: " + $preview)
if ($r2.Error) { Write-Output ("  SERVICE ERROR: " + $r2.Error) }

Write-Output ''
Write-Output '=== TURN 3: tool result as a USER message (previous broken behaviour) ==='
$assistantWrong = [ordered]@{ role = 'assistant'; content = $r1.Content; reasoning_content = $r1.Reasoning }
if ($r1.Calls.Count -gt 0) { $assistantWrong['tool_calls'] = $assistant['tool_calls'] }
$messages3 = $messages + $assistantWrong + @{ role = 'user'; content = ('Tool result: ' + $toolBlock + ' Use it in the answer.') }
$body3 = [ordered]@{ model = 'deepseek-flash'; messages = $messages3; thinking = @{ type = 'enabled' }; reasoning_effort = 'high'
                     stream = $true }
$r3 = Invoke-Turn -Body $body3 -Name 'turn3'
Write-Output ("HTTP {0} | finish={1} | content={2} chars" -f $r3.Http, $r3.Finish, $r3.Content.Length)
if ($r3.Error) { Write-Output ("  SERVICE ERROR: " + $r3.Error) }

Write-Output ''
Write-Output '=== TURN 4: tool message but reasoning_content omitted ==='
$assistantNoReason = [ordered]@{ role = 'assistant'; content = $r1.Content }
if ($r1.Calls.Count -gt 0) { $assistantNoReason['tool_calls'] = $assistant['tool_calls'] }
$messages4 = $messages + $assistantNoReason
foreach ($k in $r1.Calls.Keys) { $messages4 += @{ role = 'tool'; tool_call_id = $r1.Calls[$k].id; content = $toolBlock } }
$body4 = [ordered]@{ model = 'deepseek-flash'; messages = $messages4; thinking = @{ type = 'enabled' }; reasoning_effort = 'high'
                     stream = $true }
$r4 = Invoke-Turn -Body $body4 -Name 'turn4'
Write-Output ("HTTP {0} | finish={1} | content={2} chars" -f $r4.Http, $r4.Finish, $r4.Content.Length)
if ($r4.Error) { Write-Output ("  SERVICE ERROR: " + $r4.Error) }
