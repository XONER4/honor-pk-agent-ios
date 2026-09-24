# Live check of the full tool chain: list_chats -> read_chat -> answer.
# ASCII-only source; all Russian text lives in verify/app-instruction.txt and verify/tools-chain.json.

param(
  [string]$KeyFile = 'HonorPKAgent\DeepSeekConfig.plist',
  [string]$ChainFile = 'verify\tools-chain.json',
  [string]$InstructionFile = 'verify\app-instruction.txt',
  [string]$ToolsFile = 'verify\tools-live.json'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$utf8 = [System.Text.Encoding]::UTF8

function Read-Text([string] $path) { [System.IO.File]::ReadAllText((Resolve-Path $path), $utf8) }

$key = ([regex]::Match((Read-Text $KeyFile), '(sk-[A-Za-z0-9]+)')).Groups[1].Value
if (-not $key) { throw 'no api key' }
$instruction = Read-Text $InstructionFile
$chain = (Read-Text $ChainFile) | ConvertFrom-Json
$spec = (Read-Text $ToolsFile) | ConvertFrom-Json
$endpoint = 'https://api.deepseek.com/chat/completions'
$tmp = Join-Path $env:TEMP 'honer-tools-chain'
Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $tmp | Out-Null

function Invoke-Turn {
  param([hashtable]$Body, [string]$Name)
  Push-Location $tmp
  [System.IO.File]::WriteAllBytes((Join-Path $tmp 'body.json'), $utf8.GetBytes(($Body | ConvertTo-Json -Depth 16 -Compress)))
  $code = & curl.exe -sS -N --max-time 240 -o ($Name + '.sse') -w '%{http_code}' `
    -H "Authorization: Bearer $key" -H 'Content-Type: application/json' -H 'Accept: text/event-stream' `
    -X POST --data-binary '@body.json' $endpoint
  Pop-Location
  $raw = Read-Text (Join-Path $tmp ($Name + '.sse'))
  $content = New-Object System.Text.StringBuilder
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
  return [pscustomobject]@{ Http = $code; Finish = $finish; Content = $content.ToString(); Calls = $calls; Error = $errorText }
}

function New-Assistant([pscustomobject] $result) {
  $assistant = [ordered]@{ role = 'assistant'; content = $result.Content; reasoning_content = '' }
  if ($result.Calls.Count -gt 0) {
    $list = @()
    foreach ($k in $result.Calls.Keys) {
      $list += [ordered]@{ id = $result.Calls[$k].id; type = 'function'
                           function = @{ name = $result.Calls[$k].name; arguments = $result.Calls[$k].arguments } }
    }
    $assistant['tool_calls'] = $list
  }
  return $assistant
}

$messages = @(@{ role = 'system'; content = $instruction },
              @{ role = 'user'; content = $chain.user })
$nudge = 'Use the received data and give the final answer to the user in Russian. Do not call this tool again.'

function Get-ToolResult([string] $name, [string] $arguments) {
  switch ($name) {
    'list_chats' { return [string]$chain.listResult }
    'read_chat' {
      $number = ''
      $m = [regex]::Match($arguments, '"number"\s*:\s*(\d+)')
      if ($m.Success) { $number = $m.Groups[1].Value }
      if ($number -and $chain.chats.$number) { return [string]$chain.readResult }
      return ('Chat number ' + $number + ' not found. Call list_chats first.')
    }
    default { return 'Tool is not available in this check.' }
  }
}

$final = ''
for ($round = 1; $round -le 4; $round++) {
  $body = [ordered]@{ model = 'deepseek-flash'; messages = $messages; thinking = @{ type = 'enabled' }
                      reasoning_effort = 'high'; stream = $true; tools = $spec.tools; tool_choice = 'auto' }
  $r = Invoke-Turn -Body $body -Name ('round' + $round)
  Write-Output ("ROUND {0}: HTTP {1} | finish={2} | content={3} chars | calls={4}" -f $round, $r.Http, $r.Finish, $r.Content.Length, $r.Calls.Count)
  if ($r.Error) { Write-Output ('  SERVICE ERROR: ' + $r.Error); break }
  if ($r.Calls.Count -eq 0) {
    $final = $r.Content
    Write-Output ('  answer: ' + (($final -replace "`r?`n", ' | ').Substring(0, [math]::Min(300, $final.Length))))
    break
  }
  $messages += (New-Assistant $r)
  foreach ($k in $r.Calls.Keys) {
    $name = $r.Calls[$k].name
    $arguments = $r.Calls[$k].arguments
    Write-Output ("  call: {0} {1}" -f $name, $arguments)
    $toolText = Get-ToolResult -name $name -arguments $arguments
    $messages += @{ role = 'tool'; tool_call_id = $r.Calls[$k].id; content = $toolText }
  }
  $messages += @{ role = 'user'; content = $nudge }
}

Write-Output ''
if (-not $final) {
  Write-Output 'RESULT: FAIL - the model produced no final answer'
  exit 1
}
$missing = @()
foreach ($needle in $chain.expect) { if ($final -notmatch [regex]::Escape($needle)) { $missing += $needle } }
if ($missing.Count -gt 0) {
  Write-Output ('RESULT: FAIL - the answer is missing: ' + ($missing -join ', '))
  exit 1
}
Write-Output 'RESULT: PASS - the model read the other chat and used its content in the answer'
