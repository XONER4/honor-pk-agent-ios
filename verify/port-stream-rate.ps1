# ASCII-only simulation of StreamText.advance() reveal pacing.
# Compares the old integer step (min 1 char per frame) with the new carried remainder.

$ErrorActionPreference = 'Stop'

function Measure-RevealRate {
    param(
        [int] $TargetCount,
        [double] $BaseRate,
        [double] $FrameSeconds = (1.0 / 60.0),
        [int] $Frames = 600,
        [switch] $Carry
    )
    $revealed = 0
    $lastGrowth = 0.0
    $carryValue = 0.0
    $settledAt = -1
    for ($frame = 1; $frame -le $Frames; $frame++) {
        $now = $frame * $FrameSeconds
        $elapsed = $FrameSeconds
        $remaining = $TargetCount - $revealed
        $stalled = ($now - $lastGrowth) -gt 2.0
        if ($remaining -le 0) { break }
        $rate = $BaseRate
        if ($remaining -gt 320 * 4) { $rate = $BaseRate * 6 }
        elseif ($remaining -gt 320 * 2) { $rate = $BaseRate * 3 }
        elseif ($remaining -gt 320) { $rate = $BaseRate * 1.8 }
        if ($Carry) {
            $carryValue += $rate * $elapsed
            $step = [int][math]::Floor($carryValue)
            if ($step -lt 1) { continue }
            if ($stalled) { $step = [math]::Max($step, [int]($remaining / 8)) }
            $take = [math]::Min($step, $remaining)
            $carryValue -= $take
        } else {
            $step = [math]::Max(1, [int][math]::Round($rate * $elapsed))
            if ($stalled) { $step = [math]::Max($step, [int]($remaining / 8)) }
            $take = [math]::Min($step, $remaining)
        }
        $revealed += $take
        $lastGrowth = $now
        if ($revealed -ge $TargetCount) { $settledAt = $now; break }
    }
    $seconds = if ($settledAt -gt 0) { $settledAt } else { $Frames * $FrameSeconds }
    return [pscustomobject]@{
        Target = $TargetCount
        Seconds = [math]::Round($seconds, 2)
        Rate = [math]::Round($TargetCount / $seconds, 1)
    }
}

Write-Output "target | old cps | new cps | new seconds"
foreach ($target in 240, 600, 1500, 4000) {
    $old = Measure-RevealRate -TargetCount $target -BaseRate 30
    $new = Measure-RevealRate -TargetCount $target -BaseRate 30 -Carry
    Write-Output ("{0,6} | {1,7} | {2,7} | {3}" -f $target, $old.Rate, $new.Rate, $new.Seconds)
}

Write-Output ""
Write-Output "reasoning stream (42 cps target):"
foreach ($target in 600, 2000) {
    $old = Measure-RevealRate -TargetCount $target -BaseRate 42
    $new = Measure-RevealRate -TargetCount $target -BaseRate 42 -Carry
    Write-Output ("{0,6} | {1,7} | {2,7} | {3}" -f $target, $old.Rate, $new.Rate, $new.Seconds)
}
