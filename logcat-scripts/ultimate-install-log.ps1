# === Checkmate: Install/Launch + Logcat + End-of-Run De-dup (keeps original behavior) ===
# - Preserves your previous functionality (install, launch, capture with filters).
# - Adds a final pass that removes repetitive lines and writes <LogFile>.filtered.txt
#   (use -InPlace to overwrite the original).
#
# Usage examples:
#   .\run.ps1                                   # same as before + writes filtered copy
#   .\run.ps1 -NoInstall -NoLaunch              # skip install/launch, just log + filter
#   .\run.ps1 -AppOnly                          # your old filter mode (app-focused)
#   .\run.ps1 -AllLogs                          # your old unfiltered mode
#   .\run.ps1 -MaxPerMessage 5 -WriteSummary    # keep up to 5 repeats and write summary
#   .\run.ps1 -InPlace                          # replace original log with filtered output

param(
  [string]$LogFile = "checkmate-logcat.txt",
  [switch]$AllLogs,       # Capture all logs without filtering
  [switch]$AppOnly,       # Only capture our app logs
  [switch]$NoInstall,     # Skip gradlew installDebug
  [switch]$NoLaunch       # Skip launching MainActivity
)

# ---------- Original helper: find project ----------
function Find-CheckmateProject {
  $currentPath = Get-Location
  $searchPath = $currentPath
  while ($searchPath -and $searchPath -ne (Split-Path $searchPath -Parent)) {
    $frontendPath = Join-Path $searchPath "frontend"
    $gradlewPath = Join-Path $frontendPath "gradlew"
    $manifestPath = Join-Path $frontendPath "app\src\main\AndroidManifest.xml"
    if ((Test-Path $gradlewPath) -and (Test-Path $manifestPath)) {
      $manifestContent = Get-Content $manifestPath -Raw
      if ($manifestContent -like "*com.checkmate.app*") { return $searchPath }
    }
    $searchPath = Split-Path $searchPath -Parent
  }
  if ((Test-Path "gradlew") -and (Test-Path "app\src\main\AndroidManifest.xml")) {
    $manifestContent = Get-Content "app\src\main\AndroidManifest.xml" -Raw
    if ($manifestContent -like "*com.checkmate.app*") { return Split-Path $currentPath -Parent }
  }
  throw "Could not find Checkmate project. Please run from within the project directory."
}

# ---------- Startup ----------
try {
  $ProjectDir = Find-CheckmateProject
  $FrontendDir = Join-Path $ProjectDir "frontend"
  $ScriptsDir = $PSScriptRoot
  $FullLogPath = Join-Path $ScriptsDir $LogFile

  Write-Host "=== Ultimate Checkmate Install & Log (with end-of-run de-dup) ===" -ForegroundColor Cyan
  Write-Host "Project found: $ProjectDir" -ForegroundColor Gray
  Write-Host "Frontend dir: $FrontendDir" -ForegroundColor Gray
  Write-Host "Scripts dir:  $ScriptsDir" -ForegroundColor Gray
  Write-Host "Log file:     $FullLogPath" -ForegroundColor Gray
  Write-Host ""

  if (-not (Test-Path $FrontendDir)) { throw "Frontend directory not found: $FrontendDir" }
  $gradlewPath = Join-Path $FrontendDir "gradlew"
  if (-not (Test-Path $gradlewPath)) { throw "gradlew not found: $gradlewPath" }

} catch {
  Write-Host "Error: $($_.Exception.Message)" -ForegroundColor Red
  Write-Host "Make sure you're running this from within the Checkmate project directory tree." -ForegroundColor Yellow
  exit 1
}

# ---------- Switch to frontend; clear old log; clear buffer ----------
Set-Location $FrontendDir

if (Test-Path $FullLogPath) {
  Remove-Item $FullLogPath -Force
  Write-Host "Removed previous log file" -ForegroundColor Gray
}

Write-Host "Clearing logcat buffer..." -ForegroundColor Yellow
adb logcat -c | Out-Null

# ---------- Install & Launch (same as before; now optional via flags) ----------
if (-not $NoInstall) {
  Write-Host "Installing debug APK..." -ForegroundColor Green
  & .\gradlew installDebug
  if ($LASTEXITCODE -ne 0) {
    Write-Host "Failed to install APK! Exit code: $LASTEXITCODE" -ForegroundColor Red
    # Continue anyway if you prefer; else uncomment next line to stop:
    # exit 1
  } else {
    Write-Host "APK installed successfully!" -ForegroundColor Green
  }
} else {
  Write-Host "Skipping install (per -NoInstall)" -ForegroundColor Yellow
}

if (-not $NoLaunch) {
  Write-Host "Launching MainActivity..." -ForegroundColor Green
  $launchResult = adb shell am start -n com.checkmate.app.debug/com.checkmate.app.ui.MainActivity
  Write-Host "Launch result: $launchResult" -ForegroundColor Gray
} else {
  Write-Host "Skipping launch (per -NoLaunch)" -ForegroundColor Yellow
}

# ---------- Build your original filter args ----------
Write-Host "Starting logcat capture..." -ForegroundColor Yellow
$filterArgs = @("-v","time")

if ($AppOnly) {
  $filterArgs += @("com.checkmate.app.debug:V","*:S")
  Write-Host "Filter: App logs only" -ForegroundColor Cyan
} elseif ($AllLogs) {
  Write-Host "Filter: All logs (unfiltered)" -ForegroundColor Cyan
} else {
  # Default: App + important system logs (same balance you had)
  $filterArgs += @("com.checkmate.app.debug:V","AndroidRuntime:E","System.err:W","*:E")
  Write-Host "Filter: App + important system logs" -ForegroundColor Cyan
}

Write-Host "Press 'Q' key to stop logging and save file" -ForegroundColor Yellow
Write-Host "==========================================" -ForegroundColor Gray

# ---------- Live capture with Q key to stop ----------
$logcatJob = Start-Job -ScriptBlock {
    param($filterArgs, $FullLogPath)
    & adb logcat @filterArgs | Tee-Object -FilePath $FullLogPath
} -ArgumentList $filterArgs, $FullLogPath

Write-Host "Logcat started. Press 'Q' to stop..." -ForegroundColor Green
do {
    Start-Sleep -Milliseconds 100
    if ([Console]::KeyAvailable) {
        $key = [Console]::ReadKey($true)
        if ($key.Key -eq 'Q' -or $key.Key -eq 'q') {
            Write-Host "`nStopping logcat..." -ForegroundColor Yellow
            break
        }
    }
} while ($logcatJob.State -eq 'Running')

# Stop the logcat job
Stop-Job $logcatJob -PassThru | Remove-Job

# ---------- Show quick summary (unchanged) ----------
Write-Host "`n=== Logcat Capture Completed ===" -ForegroundColor Green
if (Test-Path $FullLogPath) {
  $fileInfo = Get-Item $FullLogPath
  $lineCount = (Get-Content $FullLogPath | Measure-Object -Line).Lines
  Write-Host "Log saved: $FullLogPath" -ForegroundColor Green
  Write-Host "File size: $($fileInfo.Length) bytes, $lineCount lines" -ForegroundColor Cyan

  $importantLogs = Get-Content $FullLogPath | Select-String "FATAL|SecurityException|checkmate.*crash"
  if ($importantLogs) {
    Write-Host "`nImportant events found:" -ForegroundColor Yellow
    $importantLogs | Select-Object -Last 3 | ForEach-Object { Write-Host " $_" -ForegroundColor Red }
  } else {
    Write-Host "No critical errors found in logs" -ForegroundColor Green
  }
}

# ---------- DISABLED: Built-in de-dup (using advanced parser instead) ----------
# The advanced-parse-logcat.ps1 script will handle filtering after this completes
Write-Host "`n=== Logcat capture completed - ready for advanced parsing ===" -ForegroundColor Green

Write-Host "`nScript completed. Log file: $FullLogPath" -ForegroundColor Cyan
