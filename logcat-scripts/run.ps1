# Simple alias script - just run ".\run.ps1" to install and log
# This script can be run from anywhere within the Checkmate project

# Run the ultimate install log script
& "$PSScriptRoot\ultimate-install-log.ps1" @args

# After logging is complete, run the advanced logcat parser to filter repetitive messages
Write-Host ""
Write-Host "=== Running Advanced Logcat Parser ==="
& "$PSScriptRoot\advanced-parse-logcat.ps1"
Write-Host "Advanced parsing completed successfully!" -ForegroundColor Green
