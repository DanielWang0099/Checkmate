# Start Checkmate Development Environment
# This script starts Redis and the Python backend automatically

Write-Host "🚀 Starting Checkmate Development Environment..." -ForegroundColor Green

# Check if Docker is running
try {
    docker info > $null 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Docker not running"
    }
} catch {
    Write-Host "❌ Docker is not running. Please start Docker Desktop first." -ForegroundColor Red
    exit 1
}

# Start Redis in background
Write-Host "🔧 Starting Redis..." -ForegroundColor Yellow
$redisContainer = docker run -d --name checkmate-redis -p 6379:6379 redis:7-alpine

if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ Redis started successfully!" -ForegroundColor Green
} else {
    # Try to remove existing container and restart
    docker rm -f checkmate-redis > $null 2>&1
    $redisContainer = docker run -d --name checkmate-redis -p 6379:6379 redis:7-alpine
    Write-Host "✅ Redis restarted successfully!" -ForegroundColor Green
}

# Wait a moment for Redis to be ready
Start-Sleep -Seconds 2

# Activate virtual environment and start backend
Write-Host "🐍 Starting Python backend..." -ForegroundColor Yellow
if (Test-Path ".venv\Scripts\Activate.ps1") {
    & .venv\Scripts\Activate.ps1
    Write-Host "✅ Virtual environment activated!" -ForegroundColor Green
    
    # Start the backend
    Write-Host "🌐 Starting FastAPI server on http://localhost:8000..." -ForegroundColor Cyan
    python -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
} else {
    Write-Host "❌ Virtual environment not found. Please run: python -m venv .venv" -ForegroundColor Red
    exit 1
}

# Cleanup function (this won't run if CTRL+C is used)
Write-Host "🧹 Stopping Redis container..." -ForegroundColor Yellow
docker stop checkmate-redis
docker rm checkmate-redis
