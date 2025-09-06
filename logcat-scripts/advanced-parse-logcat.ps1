# Advanced Smart Logcat Parser
# Intelligently groups repetitive messages and shows counts
param(
    [string]$InputFile = "C:\Users\danie\.github\Checkmate\logcat-scripts\checkmate-logcat.txt",
    [string]$OutputFile = "C:\Users\danie\.github\Checkmate\logcat-scripts\checkmate-logcat-filtered.txt",
    [int]$MinRepeatCount = 3,
    [switch]$ShowAppOnly = $false
)

function Get-LogSignature {
    param([string]$line)
    
    # Parse Android logcat format: MM-DD HH:MM:SS.mmm LEVEL/TAG(PID): MESSAGE
    if ($line -match '^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+([VDIWEF])/([^(]+)\(\s*(\d+)\):\s*(.*)$') {
        $timestamp = $matches[1]
        $level = $matches[2]
        $tag = $matches[3].Trim()
        $processId = $matches[4]
        $message = $matches[5].Trim()
        
        # Create normalized signature for grouping (remove variable parts)
        $normalizedMessage = $message
        
        # Remove common variable parts that change between identical messages
        $normalizedMessage = $normalizedMessage -replace '\b\d{6,}\b', 'ID'  # Replace long numbers like IDs
        $normalizedMessage = $normalizedMessage -replace '\b0x[a-fA-F0-9]+\b', 'ADDR'  # Replace hex addresses
        $normalizedMessage = $normalizedMessage -replace '\(-?\d+\)', '(NUM)'  # Replace numbers in parentheses
        $normalizedMessage = $normalizedMessage -replace ':\s*-?\d+\b', ': NUM'  # Replace numbers after colons
        
        return @{
            Timestamp = $timestamp
            Level = $level
            Tag = $tag
            ProcessId = $processId
            Message = $message
            Original = $line
            Signature = "$level/$tag`: $normalizedMessage"
            IsApp = ($tag -match 'checkmate|ApiService|SessionManager|WebSocket|CheckmateService' -or $processId -eq '8630')
        }
    }
    
    # Handle non-standard lines
    return @{
        Timestamp = ""
        Level = ""
        Tag = ""
        ProcessId = ""
        Message = $line.Trim()
        Original = $line
        Signature = $line.Trim()
        IsApp = $false
    }
}

function Format-GroupedLines {
    param(
        [array]$group
    )
    
    if ($group.Count -eq 1) {
        return $group[0].Original
    }
    
    $first = $group[0]
    $last = $group[-1]
    
    if ($first.Timestamp -and $last.Timestamp -and $first.Timestamp -ne $last.Timestamp) {
        $timeRange = "$($first.Timestamp) to $($last.Timestamp)"
    } else {
        $timeRange = $first.Timestamp
    }
    
    if ($first.Level) {
        return "$timeRange $($first.Level)/$($first.Tag)($($first.ProcessId)): $($first.Message) [REPEATED $($group.Count) times]"
    } else {
        return "$timeRange $($first.Message) [REPEATED $($group.Count) times]"
    }
}

Write-Host "Advanced Smart Logcat Parser"
Write-Host "Input: $InputFile"
Write-Host "Output: $OutputFile"
Write-Host "Minimum repeat count: $MinRepeatCount"
if ($ShowAppOnly) { Write-Host "Mode: App logs only" }

if (-not (Test-Path $InputFile)) {
    Write-Error "Input file '$InputFile' not found!"
    exit 1
}

$lines = Get-Content $InputFile
$totalLines = $lines.Count
Write-Host "Processing $totalLines lines..."

# Parse all lines
$parsedLines = @()
foreach ($line in $lines) {
    $parsed = Get-LogSignature $line
    if (-not $ShowAppOnly -or $parsed.IsApp -or $parsed.Level -eq "") {
        $parsedLines += $parsed
    }
}

if ($ShowAppOnly) {
    Write-Host "Filtered to $($parsedLines.Count) app-related lines"
}

# Group consecutive lines with same signature
$result = @()
$currentGroup = @()
$currentSignature = ""

foreach ($parsed in $parsedLines) {
    if ($parsed.Signature -eq $currentSignature -and $currentSignature -ne "") {
        $currentGroup += $parsed
    } else {
        # Process previous group
        if ($currentGroup.Count -gt 0) {
            if ($currentGroup.Count -ge $MinRepeatCount) {
                $result += Format-GroupedLines $currentGroup
            } else {
                foreach ($item in $currentGroup) {
                    $result += $item.Original
                }
            }
        }
        
        # Start new group
        $currentGroup = @($parsed)
        $currentSignature = $parsed.Signature
    }
}

# Process final group
if ($currentGroup.Count -gt 0) {
    if ($currentGroup.Count -ge $MinRepeatCount) {
        $result += Format-GroupedLines $currentGroup
    } else {
        foreach ($item in $currentGroup) {
            $result += $item.Original
        }
    }
}

# Save results
$result | Out-File -FilePath $OutputFile -Encoding UTF8

$outputLines = $result.Count
$reductionPercent = [math]::Round((($totalLines - $outputLines) / $totalLines) * 100, 1)

Write-Host ""
Write-Host "=== RESULTS ==="
Write-Host "Original lines: $totalLines"
Write-Host "Filtered lines: $outputLines"
Write-Host "Reduction: $reductionPercent%"
Write-Host "Output saved to: $OutputFile"

# Show preview
Write-Host ""
Write-Host "=== PREVIEW (first 25 lines) ==="
$result | Select-Object -First 25 | ForEach-Object { 
    if ($_ -match 'REPEATED \d+ times') {
        Write-Host $_ -ForegroundColor Yellow
    } elseif ($_ -match 'checkmate|ApiService|SessionManager|WebSocket|CheckmateService') {
        Write-Host $_ -ForegroundColor Cyan
    } else {
        Write-Host $_
    }
}

if ($result.Count -gt 25) {
    Write-Host "... ($($result.Count - 25) more lines in output file)" -ForegroundColor DarkGray
}
