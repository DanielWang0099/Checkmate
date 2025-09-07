#!/usr/bin/env pwsh

# Debug Build Script for Checkmate App
# This script builds the app with maximum debugging enabled

Write-Host "🔧 Building Checkmate App with Debug Logging..." -ForegroundColor Green
Write-Host ""

# Set location to frontend directory
Set-Location "C:\Users\danie\.github\Checkmate\frontend"

Write-Host "📋 Pre-build Checklist:" -ForegroundColor Yellow
Write-Host "  ✅ PipelineDebugger added"
Write-Host "  ✅ MainActivity logging integrated"
Write-Host "  ✅ MediaProjectionService debugging added"
Write-Host "  ✅ CapturePipeline extensive logging added"
Write-Host ""

Write-Host "🧹 Cleaning previous build..." -ForegroundColor Cyan
& .\gradlew clean
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Clean failed!" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "🔨 Building debug APK with logging..." -ForegroundColor Cyan
& .\gradlew assembleDebug --stacktrace --info
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Build failed!" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "📱 Installing debug APK..." -ForegroundColor Cyan
& .\gradlew installDebug
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Installation failed!" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "✅ Debug build completed successfully!" -ForegroundColor Green
Write-Host ""
Write-Host "📊 Logging Information:" -ForegroundColor Yellow
Write-Host "  • PipelineDebugger will output to CheckmatePipeline tag"
Write-Host "  • Timber logs with PipelineDebug tag"
Write-Host "  • All pipeline stages are tracked with timing"
Write-Host "  • Errors include stack traces and context"
Write-Host ""
Write-Host "🔍 To monitor logs in real-time:" -ForegroundColor Cyan
Write-Host "  adb logcat -s CheckmatePipeline:* PipelineDebug:* MainActivity:*"
Write-Host ""
Write-Host "Or run the enhanced logcat script:"
Write-Host "  Set-Location ..\logcat-scripts"
Write-Host "  .\run.ps1"
Write-Host ""
Write-Host "📝 Key things to watch for:" -ForegroundColor Yellow
Write-Host "  🎬 MEDIA_PROJECTION stage - can cause app freeze if fails"
Write-Host "  🎤 AUDIO_CAPTURE stage - matches pcmWrite/pcmRead errors in original logcat"
Write-Host "  🌐 BACKEND_SUBMISSION stage - matches ApiService errors"
Write-Host "  📱 SCREEN_CAPTURE stage - core functionality"
Write-Host "  🔍 OCR_PROCESSING and IMAGE_CLASSIFICATION - content analysis"
Write-Host ""
Write-Host "🚀 Ready to test! Launch the app and start a fact-checking session." -ForegroundColor Green
Write-Host "The enhanced logging will show exactly where the pipeline fails." -ForegroundColor Green
