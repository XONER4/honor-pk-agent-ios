# ASCII-only simulation of the typing animator pacing: old behaviour vs new StreamPace.
# The logic mirrors HonorPKAgent/UI/StreamPacing.swift.

$ErrorActionPreference = 'Stop'

function Get-Multiplier {
    param([int] $Lag, [int] $Comfortable, [bool] $StreamEnded)
    $ceiling = 1.8
    if ($StreamEnded) { $ceiling = 10 }
    elseif ($Lag -gt $Comfortable * 2) { $ceiling = 10 }
    elseif ($Lag -gt $Comfortable) { $ceiling = 6 }
    elseif ($Lag * 2 -gt $Comfortable) { $ceiling = 3 }
    $value = 1.0
    if ($Lag * 2 -gt $Comfortable * 9) { $value = 10 }
    elseif ($Lag -gt $Comfortable * 2) { $value = 6 }
    elseif ($Lag -gt $Comfortable) { $value = 3 }
    elseif ($Lag * 2 -gt $Comfortable) { $value = 1.8 }
    $value = [math]::Min($value, $ceiling)
    if ($StreamEnded -and $Lag -gt $Comfortable / 2) { $value = [math]::Max($value, 3) }
    return $value
}

function Invoke-Simulation {
    param(
        [int] $Total,
        [double[]] $Arrival,
        [double] $BaseRate = 30,
        [int] $Comfortable = 320,
        [switch] $NewLogic,
        [int] $IdleFramesBeforeCatchUp = 240
    )
    $frame = 1.0 / 60.0
    $frames = $Arrival.Count
    $revealed = 0
    $carry = 0.0
    $idleFrames = 0
    $maxStep = 0
    $drainFrame = -1
    for ($i = 0; $i -lt $frames; $i++) {
        $target = [math]::Min($Total, [int]$Arrival[$i])
        $streamEnded = ($i -eq $frames - 1)
        $lag = $target - $revealed
        $take = 0
        if ($lag -gt 0) {
            if ($NewLogic) {
                $rate = [math]::Min($BaseRate * (Get-Multiplier -Lag $lag -Comfortable $Comfortable -StreamEnded $streamEnded), $BaseRate * 10)
                $carry += $rate * $frame
                $step = [int][math]::Floor($carry)
                if ($idleFrames -ge $IdleFramesBeforeCatchUp) { $step = [math]::Max($step, [int]($lag / 4)) }
                if ($step -ge 1) {
                    $take = [math]::Min($step, $lag)
                    $carry -= $take
                    if ($carry -gt 1) { $carry = 0 }
                    $idleFrames = 0
                } else {
                    $idleFrames++
                }
            } else {
                # Old behaviour: integer step never below 1 char per frame, plus the
                # first-frame stall override that dumped an eighth of the answer.
                $multiplier = Get-Multiplier -Lag $lag -Comfortable $Comfortable -StreamEnded $false
                $step = [math]::Max(1, [int][math]::Round($BaseRate * $multiplier * $frame))
                if ($revealed -eq 0) { $step = [math]::Max($step, [int]($lag / 8)) }
                $take = [math]::Min($step, $lag)
            }
        } else {
            if ($NewLogic) { $idleFrames++ }
        }
        $revealed += $take
        $maxStep = [math]::Max($maxStep, $take)
        if ($revealed -ge $Total) { $drainFrame = $i; break }
    }
    return [pscustomobject]@{
        Revealed = $revealed
        MaxStep = $maxStep
        Seconds = [math]::Round(($drainFrame + 1) * $frame, 2)
        Finished = ($revealed -ge $Total)
    }
}

function New-Arrival {
    param([int] $Total, [double] $CharsPerSecond, [int[]] $PauseFrames, [int] $Frames)
    $frame = 1.0 / 60.0
    $arrival = New-Object double[] $Frames
    $delivered = 0.0
    for ($i = 0; $i -lt $Frames; $i++) {
        $paused = $false
        foreach ($p in $PauseFrames) { if ($i -ge $p -and $i -lt ($p + 120)) { $paused = $true } }
        if (-not $paused) { $delivered += $CharsPerSecond * $frame }
        $arrival[$i] = [math]::Min($Total, $delivered)
    }
    $arrival[$Frames - 1] = $Total
    return $arrival
}

Write-Output "case                       | old: jump / finish | new: jump / finish | new leftover"
foreach ($case in @(
    @{ Name = 'short 240, fast server';  Total = 240;   Rate = 200; Pauses = @() },
    @{ Name = 'normal 900, fast server'; Total = 900;   Rate = 200; Pauses = @() },
    @{ Name = 'normal 900, 2 pauses';    Total = 900;   Rate = 80;  Pauses = @(180, 420) },
    @{ Name = 'long 4000, medium';       Total = 4000;  Rate = 90;  Pauses = @(300) },
    @{ Name = 'very long 10000, fast';   Total = 10000; Rate = 150; Pauses = @(400) }
)) {
    $frames = 4000
    $arrival = New-Arrival -Total $case.Total -CharsPerSecond $case.Rate -PauseFrames $case.Pauses -Frames $frames
    $old = Invoke-Simulation -Total $case.Total -Arrival $arrival
    $new = Invoke-Simulation -Total $case.Total -Arrival $arrival -NewLogic
    $leftover = if ($new.Finished) { 'none' } else { $case.Total - $new.Revealed }
    Write-Output ("{0,-26} | {1,4} / {2,6}    | {3,4} / {4,6}    | {5}" -f $case.Name, $old.MaxStep, $old.Seconds, $new.MaxStep, $new.Seconds, $leftover)
}

Write-Output ""
Write-Output "steady typing speed with the whole text already in the buffer:"
foreach ($total in 240, 600, 2000) {
    $frames = 3000
    $arrival = New-Object double[] $frames
    for ($i = 0; $i -lt $frames; $i++) { $arrival[$i] = $total }
    $old = Invoke-Simulation -Total $total -Arrival $arrival
    $new = Invoke-Simulation -Total $total -Arrival $arrival -NewLogic
    Write-Output ("{0,5} chars | old {1,5} chars/s | new {2,5} chars/s" -f $total, [math]::Round($total / $old.Seconds, 1), [math]::Round($total / $new.Seconds, 1))
}

Write-Output ""
Write-Output "stepped multiplier table (base 30 chars/s, stream running):"
foreach ($lag in 100, 200, 400, 700, 1400, 3000) {
    $m = Get-Multiplier -Lag $lag -Comfortable 320 -StreamEnded $false
    Write-Output ("lag {0,5} -> {1,4}x ({2} chars/s)" -f $lag, $m, [math]::Round(30 * $m, 1))
}
