#!/usr/bin/env pwsh

# Enhanced Logcat Monitor for Checkmate Pipeline Debugging
# This script provides real-time monitoring of the Checkmate app with enhanced filtering

param(
    [switch]$PipelineOnly,
    [switch]$ErrorsOnly,
    [switch]$SaveToFile,
    [string]$OutputFile = "checkmate-debug-$(Get-Date -Format 'yyyy-MM-dd-HH-mm-ss').txt"
)

Write-Host "🔍 Checkmate Pipeline Debug Monitor" -ForegroundColor Green
Write-Host "====================================" -ForegroundColor Green
Write-Host ""

if ($PipelineOnly) {
    Write-Host "📊 Monitoring Pipeline stages only..." -ForegroundColor Cyan
    $filter = "CheckmatePipeline:* PipelineDebug:*"
} elseif ($ErrorsOnly) {
    Write-Host "❌ Monitoring Errors only..." -ForegroundColor Red
    $filter = "*:E CheckmatePipeline:* PipelineDebug:*"
} else {
    Write-Host "📱 Monitoring All Checkmate activity..." -ForegroundColor Yellow
    $filter = "CheckmatePipeline:* PipelineDebug:* MainActivity:* SessionManager:* ApiService:* MediaProjectionService:* CapturePipeline:* *:E"
}

Write-Host "Filter: $filter" -ForegroundColor Gray
Write-Host ""

# Clear logcat buffer first
Write-Host "🧹 Clearing logcat buffer..." -ForegroundColor Cyan
adb logcat -c

Write-Host ""
Write-Host "📊 Starting real-time monitoring..." -ForegroundColor Green
Write-Host "Press Ctrl+C to stop" -ForegroundColor Gray
Write-Host ""
Write-Host "🔑 Key Indicators to Watch:" -ForegroundColor Yellow
Write-Host "  🚀 ===== PIPELINE EXECUTION START ===== - Pipeline begins"
Write-Host "  🎬 MEDIA_PROJECTION: Starting screen capture - Critical stage"
Write-Host "  🎤 AUDIO_CAPTURE: Starting audio capture - May fail with pcm errors"
Write-Host "  📱 SCREEN_CAPTURE: Capturing screen content - Core functionality"
Write-Host "  🌐 BACKEND_SUBMISSION: Sending data to backend - API communication"
Write-Host "  ❌ ERROR/FAILED messages - Points of failure"
Write-Host "  🏁 ===== PIPELINE EXECUTION END ===== - Pipeline completion"
Write-Host ""

if ($SaveToFile) {
    Write-Host "💾 Saving output to: $OutputFile" -ForegroundColor Cyan
    Write-Host ""
    
    # Start logcat with filter and save to file
    if ($PipelineOnly) {
        adb logcat -s $filter | Tee-Object -FilePath $OutputFile
    } else {
        adb logcat -s $filter | ForEach-Object {
            $line = $_
            $timestamp = Get-Date -Format "HH:mm:ss.fff"
            
            # Color coding for different types
            if ($line -match "ERROR|FAILED|❌") {
                Write-Host "[$timestamp] " -NoNewline -ForegroundColor Gray
                Write-Host $line -ForegroundColor Red
            } elseif ($line -match "WARNING|⚠️") {
                Write-Host "[$timestamp] " -NoNewline -ForegroundColor Gray
                Write-Host $line -ForegroundColor Yellow
            } elseif ($line -match "PIPELINE EXECUTION START|🚀") {
                Write-Host "[$timestamp] " -NoNewline -ForegroundColor Gray
                Write-Host $line -ForegroundColor Green -BackgroundColor DarkGreen
            } elseif ($line -match "PIPELINE EXECUTION END|🏁") {
                Write-Host "[$timestamp] " -NoNewline -ForegroundColor Gray
                Write-Host $line -ForegroundColor Green -BackgroundColor DarkGreen
            } elseif ($line -match "STAGE_START|STAGE_END") {
                Write-Host "[$timestamp] " -NoNewline -ForegroundColor Gray
                Write-Host $line -ForegroundColor Cyan
            } elseif ($line -match "SUCCESS|✅") {
                Write-Host "[$timestamp] " -NoNewline -ForegroundColor Gray
                Write-Host $line -ForegroundColor Green
            } else {
                Write-Host "[$timestamp] " -NoNewline -ForegroundColor Gray
                Write-Host $line -ForegroundColor White
            }
            
            # Also save to file
            "[$timestamp] $line" | Out-File -FilePath $OutputFile -Append
        }
    }
} else {
    # Just display with color coding
    adb logcat -s $filter | ForEach-Object {
        $line = $_
        $timestamp = Get-Date -Format "HH:mm:ss.fff"
        
        # Color coding for different types
        if ($line -match "ERROR|FAILED|❌|E/") {
            Write-Host "[$timestamp] " -NoNewline -ForegroundColor Gray
            Write-Host $line -ForegroundColor Red
        } elseif ($line -match "WARNING|⚠️|W/") {
            Write-Host "[$timestamp] " -NoNewline -ForegroundColor Gray
            Write-Host $line -ForegroundColor Yellow
        } elseif ($line -match "PIPELINE EXECUTION START|🚀") {
            Write-Host "[$timestamp] " -NoNewline -ForegroundColor Gray
            Write-Host $line -ForegroundColor Green -BackgroundColor DarkGreen
        } elseif ($line -match "PIPELINE EXECUTION END|🏁") {
            Write-Host "[$timestamp] " -NoNewline -ForegroundColor Gray
            Write-Host $line -ForegroundColor Green -BackgroundColor DarkGreen
        } elseif ($line -match "STAGE_START|STAGE_END|🎬|🎤|📱|🌐|🔍") {
            Write-Host "[$timestamp] " -NoNewline -ForegroundColor Gray
            Write-Host $line -ForegroundColor Cyan
        } elseif ($line -match "SUCCESS|✅") {
            Write-Host "[$timestamp] " -NoNewline -ForegroundColor Gray
            Write-Host $line -ForegroundColor Green
        } elseif ($line -match "CheckmatePipeline|PipelineDebug") {
            Write-Host "[$timestamp] " -NoNewline -ForegroundColor Gray
            Write-Host $line -ForegroundColor White
        } else {
            Write-Host "[$timestamp] " -NoNewline -ForegroundColor Gray
            Write-Host $line -ForegroundColor Gray
        }
    }
}

Write-Host ""
Write-Host "🔍 Monitoring ended." -ForegroundColor Green

if ($SaveToFile) {
    Write-Host "💾 Log saved to: $OutputFile" -ForegroundColor Cyan
}
