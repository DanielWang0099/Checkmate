# Ultimate Install & Log Script with filtering options
# Works from any directory - automatically finds the Checkmate project
param(
    [string]$LogFile = "checkmate-logcat.txt",
    [switch]$AllLogs,  # Capture all logs without filtering
    [switch]$AppOnly   # Only capture our app logs
)

# Function to find the Checkmate project root
function Find-CheckmateProject {
    $currentPath = Get-Location
    $searchPath = $currentPath
    
    # Look for Checkmate project markers going up the directory tree
    while ($searchPath -and $searchPath -ne (Split-Path $searchPath -Parent)) {
        $frontendPath = Join-Path $searchPath "frontend"
        $gradlewPath = Join-Path $frontendPath "gradlew"
        $manifestPath = Join-Path $frontendPath "app\src\main\AndroidManifest.xml"
        
        # Check if this looks like the Checkmate project
        if ((Test-Path $gradlewPath) -and (Test-Path $manifestPath)) {
            $manifestContent = Get-Content $manifestPath -Raw
            if ($manifestContent -like "*com.checkmate.app*") {
                return $searchPath
            }
        }
        
        $searchPath = Split-Path $searchPath -Parent
    }
    
    # If not found, check if we're already in frontend directory
    if ((Test-Path "gradlew") -and (Test-Path "app\src\main\AndroidManifest.xml")) {
        $manifestContent = Get-Content "app\src\main\AndroidManifest.xml" -Raw
        if ($manifestContent -like "*com.checkmate.app*") {
            return Split-Path $currentPath -Parent
        }
    }
    
    throw "Could not find Checkmate project. Please run from within the project directory."
}

# Auto-detect project structure
try {
    $ProjectDir = Find-CheckmateProject
    $FrontendDir = Join-Path $ProjectDir "frontend"
    $ScriptsDir = $PSScriptRoot  # Directory where this script is located
    $FullLogPath = Join-Path $ScriptsDir $LogFile  # Keep log with the script
    
    Write-Host "=== Ultimate Checkmate Install & Log ===" -ForegroundColor Cyan
    Write-Host "Project found: $ProjectDir" -ForegroundColor Gray
    Write-Host "Frontend dir: $FrontendDir" -ForegroundColor Gray
    Write-Host "Scripts dir: $ScriptsDir" -ForegroundColor Gray
    Write-Host "Log file: $FullLogPath" -ForegroundColor Gray
    Write-Host ""
    
    # Verify frontend directory exists
    if (-not (Test-Path $FrontendDir)) {
        throw "Frontend directory not found: $FrontendDir"
    }
    
    # Verify gradlew exists
    $gradlewPath = Join-Path $FrontendDir "gradlew"
    if (-not (Test-Path $gradlewPath)) {
        throw "gradlew not found: $gradlewPath"
    }
    
} catch {
    Write-Host "Error: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Make sure you're running this from within the Checkmate project directory tree." -ForegroundColor Yellow
    exit 1
}

# Change to frontend directory
Set-Location $FrontendDir

# Remove previous log file
if (Test-Path $FullLogPath) {
    Remove-Item $FullLogPath -Force
    Write-Host "Removed previous log file" -ForegroundColor Gray
}

# Clear logcat buffer
Write-Host "Clearing logcat buffer..." -ForegroundColor Yellow
adb logcat -c

# Install APK
Write-Host "Installing debug APK..." -ForegroundColor Green
$installResult = & .\gradlew installDebug

if ($LASTEXITCODE -eq 0) {
    Write-Host "APK installed successfully!" -ForegroundColor Green
    
    # Launch the app
    Write-Host "Launching MainActivity..." -ForegroundColor Green
    $launchResult = adb shell am start -n com.checkmate.app.debug/com.checkmate.app.ui.MainActivity
    Write-Host "Launch result: $launchResult" -ForegroundColor Gray
    
    Write-Host "Starting logcat capture..." -ForegroundColor Yellow
    
    # Determine filter based on parameters
    $filterArgs = @("-v", "time")
    
    if ($AppOnly) {
        $filterArgs += @("com.checkmate.app.debug:V", "*:S")
        Write-Host "Filter: App logs only" -ForegroundColor Cyan
    } elseif ($AllLogs) {
        Write-Host "Filter: All logs (unfiltered)" -ForegroundColor Cyan
    } else {
        # Default: App + important system logs
        $filterArgs += @("com.checkmate.app.debug:V", "AndroidRuntime:E", "System.err:W", "*:E")
        Write-Host "Filter: App + important system logs" -ForegroundColor Cyan
    }
    
    Write-Host "Press Ctrl+C to stop logging and save file" -ForegroundColor Yellow
    Write-Host "==========================================" -ForegroundColor Gray
    
    try {
        # Start logcat with chosen filtering
        & adb logcat @filterArgs | Tee-Object -FilePath $FullLogPath
    } catch {
        Write-Host "`nLogcat stopped" -ForegroundColor Yellow
    }
    
    # Show final results
    Write-Host "`n=== Logcat Capture Completed ===" -ForegroundColor Green
    if (Test-Path $FullLogPath) {
        $fileInfo = Get-Item $FullLogPath
        $lineCount = (Get-Content $FullLogPath | Measure-Object -Line).Lines
        Write-Host "Log saved: $FullLogPath" -ForegroundColor Green
        Write-Host "File size: $($fileInfo.Length) bytes, $lineCount lines" -ForegroundColor Cyan
        
        # Show any crashes or important events
        $importantLogs = Get-Content $FullLogPath | Select-String "FATAL|SecurityException|checkmate.*crash"
        if ($importantLogs) {
            Write-Host "`nImportant events found:" -ForegroundColor Yellow
            $importantLogs | Select-Object -Last 3 | ForEach-Object { 
                Write-Host "  $_" -ForegroundColor Red 
            }
        } else {
            Write-Host "No critical errors found in logs" -ForegroundColor Green
        }
    }
} else {
    Write-Host "Failed to install APK! Exit code: $LASTEXITCODE" -ForegroundColor Red
}

Write-Host "`nScript completed. Log file: $FullLogPath" -ForegroundColor Cyan
