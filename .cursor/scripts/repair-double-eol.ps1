# Repair EOL issues in text files:
# - CR+CRLF (0D 0D 0A) -> CRLF
# - LF / mixed -> CRLF
# - alternating blank lines ("chessboard") from double-EOL artifacts
param(
    [Parameter(Mandatory = $false)]
    [string[]]$Path = @(),
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$RemainingPaths = @(),
    [switch]$Scan,
    [switch]$WhatIf
)

$ErrorActionPreference = 'Stop'
# powershell -File binds only the first -Path value; extra paths arrive as remaining args.
if ($RemainingPaths -and $RemainingPaths.Count -gt 0) {
    $Path = @($Path) + @($RemainingPaths)
}

function Test-NeedsCrlfNormalize {
    param([string]$Raw)
    # Lone LF (not part of CRLF) or bare CR (not part of CRLF).
    return [bool]([regex]::IsMatch($Raw, '(?<!\r)\n') -or [regex]::IsMatch($Raw, '\r(?!\n)'))
}

function Test-DoubleSpacedContent {
    param(
        [string]$Raw,
        [string[]]$Lines,
        [string]$Extension
    )
    if ($Lines.Count -lt 12) { return $false }

    $doubleCrlf = ([regex]::Matches($Raw, "`r`n`r`n")).Count + ([regex]::Matches($Raw, "`r`r`n")).Count
    $nonEmpty = 0
    $badAlt = 0
    for ($i = 0; $i -lt $Lines.Count; $i++) {
        if ($Lines[$i].Trim() -ne '') { $nonEmpty++ }
    }
    for ($i = 0; $i -lt $Lines.Count - 2; $i++) {
        if ($Lines[$i].Trim() -ne '' -and $Lines[$i + 1].Trim() -eq '' -and $Lines[$i + 2].Trim() -ne '') {
            if ($Lines[$i].TrimEnd() -notmatch '[{$]$' -and $Lines[$i].Trim() -notmatch '^/\*\*?$') {
                $badAlt++
            }
        }
    }
    if ($nonEmpty -lt 8) { return $false }
    $badAltRatio = $badAlt / $nonEmpty
    $emptyRatio = ($Lines.Count - $nonEmpty) / $Lines.Count
    if ($Extension -eq '.java' -and $badAltRatio -ge 0.28 -and $emptyRatio -ge 0.22) {
        return $true
    }
    if ($doubleCrlf -ge 12 -and ($doubleCrlf / $Lines.Count) -ge 0.22 -and $badAltRatio -ge 0.18) {
        return $true
    }
    if ($doubleCrlf -ge 8 -and ($doubleCrlf / $Lines.Count) -ge 0.18 -and $badAltRatio -ge 0.22) {
        return $true
    }
    return $false
}

function Should-KeepBlankLine {
    param(
        [string]$Prev,
        [string]$Next
    )
    $p = $Prev.TrimEnd()
    $n = $Next.TrimStart()
    if ($p -match '[{}]$') { return $true }
    if ($p -match '^package\s') { return $true }
    if ($p -match '^import\s' -and $n -match '^import\s') { return $false }
    if ($p -match '^import\s' -and $n -notmatch '^import\s') { return $true }
    if ($n -match '^/\*\*') { return $true }
    if ($p -match '^\s*}\s*$' -and $n -match '^\s*(public|private|protected|class|interface|enum|@)') { return $true }
    if ($n -match '^\s*\*/') { return $false }
    # Markdown: пустая строка закрывает список — иначе абзац/заголовок «прилипает» к <li>
    $listItem = '^\s*([-*+]|\d+\.)\s'
    if ($p -match $listItem -and $n -notmatch $listItem) { return $true }
    return $false
}

function Repair-Lines {
    param([string[]]$Lines)
    $out = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt $Lines.Count; $i++) {
        $line = $Lines[$i]
        if ($line.Trim() -eq '' -and $out.Count -gt 0 -and $i + 1 -lt $Lines.Count) {
            $prev = $out[$out.Count - 1]
            $next = $Lines[$i + 1]
            if ($prev.Trim() -ne '' -and $next.Trim() -ne '' -and -not (Should-KeepBlankLine $prev $next)) {
                continue
            }
        }
        $out.Add($line)
    }
    $collapsed = New-Object System.Collections.Generic.List[string]
    foreach ($line in $out) {
        if ($line.Trim() -eq '' -and $collapsed.Count -gt 0 -and $collapsed[$collapsed.Count - 1].Trim() -eq '') {
            continue
        }
        $collapsed.Add($line)
    }
    return ,$collapsed.ToArray()
}

function Get-TargetFiles {
    param([string[]]$InputPath)
    if ($InputPath.Count -gt 0) {
        foreach ($p in $InputPath) {
            if (Test-Path -LiteralPath $p -PathType Leaf) {
                Get-Item -LiteralPath $p
            }
            elseif (Test-Path -LiteralPath $p -PathType Container) {
                Get-ChildItem -LiteralPath $p -Recurse -File |
                    Where-Object { $_.Extension -match '^\.(java|xml|properties|mdc|ps1|md|txt|launch)$' }
            }
        }
        return
    }
    $repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
    Get-ChildItem -LiteralPath (Join-Path $repo 'plugin\src') -Recurse -File -Filter '*.java'
    foreach ($extra in @('plugin\plugin.xml')) {
        $full = Join-Path $repo $extra
        if (Test-Path -LiteralPath $full -PathType Leaf) {
            Get-Item -LiteralPath $full
        }
    }
}

function Write-CrlfText {
    param(
        [string]$FilePath,
        [string[]]$Lines,
        [string]$Reason,
        [switch]$WhatIf
    )
    $text = ($Lines -join "`r`n") + "`r`n"
    if ($WhatIf) {
        Write-Host "WOULD REPAIR $FilePath ($Reason)"
        return
    }
    [System.IO.File]::WriteAllText($FilePath, $text, [System.Text.UTF8Encoding]::new($false))
    Write-Host "REPAIRED $FilePath ($Reason)"
}

$files = @(Get-TargetFiles -InputPath $Path | Sort-Object FullName -Unique)
$hits = @()

foreach ($file in $files) {
    $rawOriginal = [System.IO.File]::ReadAllText($file.FullName)
    if ($rawOriginal.Length -eq 0) { continue }
    $raw = $rawOriginal
    do {
        $prev = $raw
        $raw = $raw -replace "`r`r`n", "`r`n"
    } while ($raw -ne $prev)
    $hadCrCrLf = ($raw -ne $rawOriginal)
    $needsCrlf = Test-NeedsCrlfNormalize -Raw $raw
    $normalized = $raw -replace "`r`n", "`n" -replace "`r", "`n"
    $lines = $normalized -split "`n", -1
    if ($lines.Length -gt 0 -and $lines[$lines.Length - 1] -eq '') {
        $lines = $lines[0..($lines.Length - 2)]
    }
    $doubleSpaced = $false
    if ($file.Extension -eq '.java') {
        $doubleSpaced = Test-DoubleSpacedContent -Raw $raw -Lines $lines -Extension $file.Extension
    }
    if (-not $hadCrCrLf -and -not $doubleSpaced -and -not $needsCrlf) { continue }
    $hits += $file
    if ($Scan) {
        $tags = @()
        if ($hadCrCrLf) { $tags += 'CR+CRLF' }
        if ($needsCrlf) { $tags += 'LF/mixed' }
        if ($doubleSpaced) { $tags += 'double-spaced' }
        Write-Host ("SUSPECT {0} ({1})" -f $file.FullName, ($tags -join ', '))
        continue
    }

    if ($doubleSpaced) {
        $repaired = $lines
        do {
            $before = $repaired.Count
            $repaired = Repair-Lines -Lines $repaired
        } while ($repaired.Count -lt $before)
        $rawRepaired = ($repaired -join "`r`n") + "`r`n"
        while ((Test-DoubleSpacedContent -Raw $rawRepaired -Lines $repaired -Extension $file.Extension)) {
            $before = $repaired.Count
            $repaired = Repair-Lines -Lines $repaired
            if ($repaired.Count -ge $before) { break }
            $rawRepaired = ($repaired -join "`r`n") + "`r`n"
        }
        $reason = "$($lines.Count) -> $($repaired.Count) lines"
        if ($hadCrCrLf -or $needsCrlf) { $reason = "EOL+spacing; $reason" }
        Write-CrlfText -FilePath $file.FullName -Lines $repaired -Reason $reason -WhatIf:$WhatIf
        continue
    }

    $reasons = @()
    if ($hadCrCrLf) { $reasons += 'CR+CRLF -> CRLF' }
    if ($needsCrlf) { $reasons += 'LF/mixed -> CRLF' }
    if ($reasons.Count -eq 0) { $reasons += 'normalize CRLF' }
    Write-CrlfText -FilePath $file.FullName -Lines $lines -Reason ($reasons -join '; ') -WhatIf:$WhatIf
}

if ($Scan) {
    if ($hits.Count -eq 0) {
        Write-Host 'No EOL issues found.'
    }
}

exit 0
