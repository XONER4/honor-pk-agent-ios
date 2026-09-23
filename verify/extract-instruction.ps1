param(
  [string]$InstructionFile = 'HonorPKAgent\Core\DeepSeekClient.swift',
  [string]$OutFile = 'verify\app-instruction.txt'
)
$ErrorActionPreference = 'Stop'
$text = [System.IO.File]::ReadAllText((Resolve-Path $InstructionFile), [System.Text.Encoding]::UTF8)
# Берём ровно тот текст, который приложение отправляет в system-сообщении.
$match = [regex]::Match($text, 'static let instruction = #"""\r?\n(.*?)\r?\n\s*"""#', 'Singleline')
if (-not $match.Success) { throw 'instruction literal not found' }
$body = $match.Groups[1].Value
# Swift multiline literal: общий отступ строк убирается, у нас он 4 пробела.
$lines = $body -split "\r?\n"
$cleaned = $lines | ForEach-Object { $_ -replace '^    ', '' }
$result = ($cleaned -join "`n")
[System.IO.File]::WriteAllText((Resolve-Path -Force (Split-Path $OutFile)) .Path + '\' + (Split-Path $OutFile -Leaf), $result, (New-Object System.Text.UTF8Encoding($false)))
Write-Output ("instruction chars: " + $result.Length)
Write-Output ($result.Substring(0, [Math]::Min(200, $result.Length)))
