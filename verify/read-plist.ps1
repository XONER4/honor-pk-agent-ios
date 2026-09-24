# Minimal binary-plist reader: enough to verify Info.plist values inside the IPA.
param(
  [Parameter(Mandatory = $true)][string] $Path
)

$ErrorActionPreference = 'Stop'
$bytes = [System.IO.File]::ReadAllBytes($Path)
if ([System.Text.Encoding]::ASCII.GetString($bytes, 0, 8) -ne 'bplist00') { throw 'not a binary plist' }

$offsetSize = $bytes[$bytes.Length - 26]
$refSize = $bytes[$bytes.Length - 25]
$count = 0
for ($i = 0; $i -lt 8; $i++) { $count = ($count -shl 8) -bor $bytes[$bytes.Length - 24 + $i] }
$tableOffset = 0
for ($i = 0; $i -lt 8; $i++) { $tableOffset = ($tableOffset -shl 8) -bor $bytes[$bytes.Length - 8 + $i] }

function Read-Int([int] $offset, [int] $size) {
  $value = 0
  for ($i = 0; $i -lt $size; $i++) { $value = ($value -shl 8) -bor $bytes[$offset + $i] }
  return $value
}

function Get-Object([int] $index) {
  $offset = Read-Int ($tableOffset + $index * $offsetSize) $offsetSize
  $marker = $bytes[$offset]
  $type = $marker -shr 4
  $info = $marker -band 0x0F
  switch ($type) {
    0 {
      if ($info -eq 8) { return [bool]$false }
      if ($info -eq 9) { return [bool]$true }
      return 0
    }
    1 {
      $length = 1 -shl $info
      return Read-Int ($offset + 1) $length
    }
    2 { return $null }
    3 { return $null }
    4 {
      $length = $info
      if ($info -eq 0x0F) { $length = Read-Int ($offset + 1) 1; $offset++ }
      return ([System.Text.Encoding]::UTF8.GetString($bytes, $offset + 1, $length))
    }
    5 {
      $length = Read-Int ($offset + 1) $info
      return ([System.Text.Encoding]::ASCII.GetString($bytes, $offset + 1 + $info, $length))
    }
    6 {
      $length = Read-Int ($offset + 1) $info
      return ([System.Text.Encoding]::UTF8.GetString($bytes, $offset + 1 + $info, $length))
    }
    8 {
      $length = $info
      if ($info -eq 0x0F) { $length = Read-Int ($offset + 1) 1; $offset++ }
      return Read-Int ($offset + 1) $length
    }
    10 {
      $length = Read-Int ($offset + 1) $info
      $items = @()
      for ($i = 0; $i -lt $length; $i++) { $items += Get-Object (Read-Int ($offset + 1 + $info + $i * $refSize) $refSize) }
      return $items
    }
    13 {
      $length = Read-Int ($offset + 1) $info
      $map = [ordered]@{}
      for ($i = 0; $i -lt $length; $i++) {
        $keyIndex = Read-Int ($offset + 1 + $info + $i * $refSize) $refSize
        $valueIndex = Read-Int ($offset + 1 + $info + ($length + $i) * $refSize) $refSize
        $map[(Get-Object $keyIndex)] = Get-Object $valueIndex
      }
      return $map
    }
    default { return $null }
  }
}

$root = Get-Object 0
foreach ($key in @('CFBundleShortVersionString', 'CFBundleVersion', 'CFBundleIdentifier', 'CFBundleDisplayName', 'MinimumOSVersion')) {
  $value = if ($root.Contains($key)) { $root[$key] } else { 'missing' }
  Write-Output ("{0} = {1}" -f $key, $value)
}
