# Checkmate Complete Setup Guide

This comprehensive guide will walk you through setting up the complete Checkmate fact-checking system from scratch, including backend API, Android application, and all required services.

## 📋 System Requirements

### Development Machine
- **OS**: Windows 10+, macOS 12+, or Linux Ubuntu 20.04+
- **RAM**: 8GB minimum, 16GB recommended
- **Storage**: 10GB free space
- **Internet**: Stable broadband connection
- **Docker Desktop**: Recommended for Redis (alternatively install Redis natively)

### Required Software
- **Python 3.11+**
- **Android Studio** (latest stable)
- **Docker Desktop** (recommended for Redis)
- **Git**

### Required Accounts & Services
- **AWS Account** with Bedrock access
- **Google Cloud Platform** account
- **GitHub** account (for cloning)

## 🎯 Quick Start (30 minutes)

### Phase 1: Clone & Environment Setup (5 minutes)

```bash
# 1. Clone the repository
git clone https://github.com/DanielWang0099/Checkmate.git
cd Checkmate

# 2. Create Python virtual environment
cd backend
python -m venv checkmate-env

# Windows
checkmate-env\Scripts\activate

# macOS/Linux  
source checkmate-env/bin/activate

# 3. Install Python dependencies
pip install -r requirements.txt
```

### Phase 2: API Configuration (10 minutes)

```bash
# 1. Copy environment template
cp .env.example .env

# 2. Edit .env file with your credentials
nano .env  # or use your preferred editor
```

Required API keys to obtain:
1. **AWS Credentials** → [AWS Console](https://console.aws.amazon.com/)
2. **Google Custom Search** → [Google CSE](https://cse.google.com/)
3. **YouTube API Key** → [Google Cloud Console](https://console.cloud.google.com/)

### Phase 3: Backend Services (10 minutes)

```bash
# 1. Install Docker Desktop (recommended)
# Download from: https://www.docker.com/products/docker-desktop/
# Start Docker Desktop application

# 2. Start the backend (Redis will auto-start!)
python -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000

# Alternative: Manual Redis setup
# Windows (via Chocolatey)
choco install redis-64
redis-server

# macOS
brew install redis
brew services start redis

# Ubuntu/Debian
sudo apt update && sudo apt install redis-server
sudo systemctl start redis
```

### Phase 4: Android Setup (5 minutes)

```bash
# 1. Open Android Studio
cd ../frontend
# Open this directory in Android Studio

# 2. Build and install
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

✅ **You're ready!** Open the Checkmate app and start fact-checking.

## 🔧 Detailed Setup Instructions

## 1. 🐍 Backend Setup

### Step 1.1: Python Environment

```bash
# Verify Python version (3.11+ required)
python --version

# If Python 3.11+ not available, install:
# Windows: Download from python.org
# macOS: brew install python@3.11
# Ubuntu: sudo apt install python3.11 python3.11-venv

# Create isolated environment
cd backend
python -m venv checkmate-env
source checkmate-env/bin/activate  # Linux/macOS
# checkmate-env\Scripts\activate    # Windows

# Upgrade pip and install dependencies
pip install --upgrade pip
pip install -r requirements.txt
```

### Step 1.2: AWS Bedrock Configuration

#### Option A: AWS CLI Setup (Recommended)
```bash
# Install AWS CLI
pip install awscli

# Configure credentials
aws configure
# AWS Access Key ID: [Your Access Key]
# AWS Secret Access Key: [Your Secret Key] 
# Default region: us-east-1
# Default output format: json

# Test Bedrock access
aws bedrock list-foundation-models --region us-east-1
```

#### Option B: Manual Environment Variables
```bash
# Add to .env file
AWS_ACCESS_KEY_ID=AKIA...
AWS_SECRET_ACCESS_KEY=abcd...
AWS_REGION=us-east-1
```

#### Enable Claude 3.5 Sonnet Access
1. Go to [AWS Bedrock Console](https://console.aws.amazon.com/bedrock/)
2. Navigate to "Model access" in the left sidebar
3. Click "Request model access"
4. Find "Claude 3.5 Sonnet v2" and click "Request access"
5. Wait for approval (usually instant)

#### Verify Bedrock Setup
```bash
python -c "
import boto3
try:
    client = boto3.client('bedrock-runtime', region_name='us-east-1')
    response = client.list_foundation_models()
    print('✅ Bedrock access verified!')
except Exception as e:
    print(f'❌ Bedrock access failed: {e}')
"
```

### Step 1.3: Google APIs Setup

#### Google Custom Search Engine
1. **Go to [Google Custom Search](https://cse.google.com/)**
2. **Click "Add"** to create new search engine
3. **Sites to search**: Leave empty or add "*" to search entire web
4. **Name**: "Checkmate Fact Checker"
5. **Click "Create"**
6. **Go to Control Panel** → Copy the "Search engine ID"

#### Google Cloud API Setup
1. **Go to [Google Cloud Console](https://console.cloud.google.com/)**
2. **Create new project** or select existing
3. **Enable APIs**:
   - Custom Search JSON API
   - YouTube Data API v3
4. **Create Credentials**:
   - Go to "Credentials" → "Create Credentials" → "API Key"
   - Copy the API key
   - Optionally restrict to specific APIs

#### Test Google APIs
```bash
# Test Custom Search
curl "https://www.googleapis.com/customsearch/v1?key=YOUR_API_KEY&cx=YOUR_CSE_ID&q=test"

# Test YouTube API  
curl "https://www.googleapis.com/youtube/v3/search?part=snippet&q=test&key=YOUR_API_KEY"
```

### Step 1.4: Complete .env Configuration

```env
# AWS Configuration
AWS_ACCESS_KEY_ID=your_aws_access_key_id
AWS_SECRET_ACCESS_KEY=your_aws_secret_access_key
AWS_REGION=us-east-1

# Bedrock Configuration
BEDROCK_MODEL_ID=anthropic.claude-3-5-sonnet-20241022-v2:0
BEDROCK_MAX_TOKENS=4096
BEDROCK_TEMPERATURE=0.1

# Google APIs
GOOGLE_CSE_ID=your_google_custom_search_engine_id
GOOGLE_API_KEY=your_google_api_key
YOUTUBE_API_KEY=your_youtube_data_api_key

# Redis Configuration
REDIS_URL=redis://localhost:6379
REDIS_PASSWORD=
REDIS_DB=0

# Application Settings
DEBUG=true
LOG_LEVEL=INFO
HOST=0.0.0.0
PORT=8000
CORS_ORIGINS=http://localhost:3000,http://127.0.0.1:3000

# Session Configuration
SESSION_TIMEOUT=3600
MAX_CONCURRENT_SESSIONS=100
CLEANUP_INTERVAL=300
```

### Step 1.5: Redis Installation & Setup

#### 🐳 Option A: Docker (Recommended)
```bash
# 1. Install Docker Desktop
# Download from: https://www.docker.com/products/docker-desktop/

# 2. Start Docker Desktop application

# 3. Run Redis container
docker run -d --name checkmate-redis -p 6379:6379 redis:7-alpine

# 4. Verify Redis is running
docker exec -it checkmate-redis redis-cli ping
# Should return: PONG

# 5. (Optional) Use Docker Compose
cd backend
docker-compose up -d redis
```

**✨ Automatic Startup**: The backend will automatically detect and start Redis if Docker is available!

#### Option B: Native Installation

##### Windows
```bash
# Option 1: Chocolatey
choco install redis-64

# Option 2: Download installer
# https://github.com/microsoftarchive/redis/releases

# Start Redis
redis-server
```

##### macOS
```bash
# Install via Homebrew
brew install redis

# Start Redis
brew services start redis

# Or start manually
redis-server
```

##### Linux (Ubuntu/Debian)
```bash
# Install Redis
sudo apt update
sudo apt install redis-server

# Start Redis service
sudo systemctl start redis
sudo systemctl enable redis

# Test Redis
redis-cli ping
# Should return: PONG
```

### Step 1.6: Start Backend

```bash
# Activate environment
source checkmate-env/bin/activate  # Linux/macOS
# checkmate-env\Scripts\activate    # Windows

# Start development server
python -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000

# Verify server is running
curl http://localhost:8000/health
# Should return: {"status": "ok"}

# Access API documentation
# Open: http://localhost:8000/docs
```

## 2. 📱 Android Frontend Setup

### Step 2.1: Android Studio Installation

1. **Download Android Studio** from [developer.android.com](https://developer.android.com/studio)
2. **Install with default settings**
3. **Open Android Studio**
4. **Install required SDKs**:
   - Android SDK Platform 34 (Android 14)
   - Android SDK Build-Tools 34.0.0
   - Android SDK Platform-Tools

### Step 2.2: Project Setup

```bash
# Navigate to frontend directory
cd frontend

# Open in Android Studio
# File → Open → Select 'frontend' folder

# Wait for Gradle sync to complete
# This may take 5-10 minutes on first run
```

### Step 2.3: Configuration

#### Update Backend URL
Edit `app/src/main/java/com/checkmate/app/data/AppConfig.kt`:

```kotlin
object AppConfig {
    // For Android Emulator
    const val BASE_URL = "http://10.0.2.2:8000"
    
    // For Physical Device (replace with your computer's IP)
    // const val BASE_URL = "http://192.168.1.100:8000"
    
    const val WEBSOCKET_URL = "ws://10.0.2.2:8000/ws"
    // const val WEBSOCKET_URL = "ws://192.168.1.100:8000/ws"
}
```

#### Find Your Computer's IP (for physical devices)
```bash
# Windows
ipconfig | findstr IPv4

# macOS/Linux
ifconfig | grep inet

# Use the IP address in your local network (usually 192.168.x.x)
```

### Step 2.4: Build & Install

#### Using Android Studio
1. **Connect device** or start emulator
2. **Click "Run" button** (green play icon)
3. **Select target device**
4. **Wait for build and installation**

#### Using Command Line
```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk

# Check if device is connected
adb devices
```

### Step 2.5: Permissions Setup

After installing the app:

1. **Open Checkmate app**
2. **Grant permissions when prompted**:
   - ✅ Microphone access
   - ✅ System alert window
   - ✅ Accessibility service
3. **Enable Accessibility Service**:
   - Settings → Accessibility → Checkmate → Enable
4. **Allow overlay permission**:
   - Settings → Apps → Checkmate → Display over other apps → Allow

## 3. 🧪 Testing & Verification

### Backend Testing

```bash
# Test health endpoint
curl http://localhost:8000/health

# Test WebSocket connection
# Use a WebSocket client or browser console:
const ws = new WebSocket('ws://localhost:8000/ws/test-session');
ws.onopen = () => console.log('Connected');
ws.onmessage = (msg) => console.log('Received:', msg.data);
```

### Frontend Testing

```bash
# Run unit tests
./gradlew test

# Check app logs
adb logcat -s Checkmate

# Test fact-checking
# 1. Open the app
# 2. Start a session
# 3. Open a news app
# 4. Look for fact-check notifications
```

### End-to-End Testing

1. **Start backend server**
2. **Install and open Android app**
3. **Grant all permissions**
4. **Start fact-checking session**
5. **Open a browser/news app**
6. **Look for real-time fact-check notifications**

## 4. 🚀 Production Deployment

### Backend Production

#### Using Docker
```bash
# Build Docker image
docker build -t checkmate-backend .

# Run with environment file
docker run -d \
  --name checkmate-backend \
  -p 8000:8000 \
  --env-file .env \
  checkmate-backend
```

#### Using Docker Compose
```yaml
# docker-compose.yml
version: '3.8'
services:
  backend:
    build: .
    ports:
      - "8000:8000"
    env_file:
      - .env
    depends_on:
      - redis
    restart: unless-stopped

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data
    restart: unless-stopped

volumes:
  redis_data:
```

```bash
# Start all services
docker-compose up -d

# Check status
docker-compose ps
```

### Android Production

```bash
# Build release APK
./gradlew assembleRelease

# Sign APK (if keystore configured)
./gradlew assembleRelease

# APK location
ls -la app/build/outputs/apk/release/
```

## 5. 🐛 Troubleshooting

### Common Backend Issues

#### AWS Bedrock Access Denied
```bash
# Check IAM permissions
aws iam get-user

# Verify Bedrock service availability in region
aws bedrock list-foundation-models --region us-east-1

# Common fix: Switch to us-east-1 region
```

#### Redis Connection Failed
```bash
# Check Redis status
redis-cli ping

# Check Redis logs
# Linux: sudo journalctl -u redis
# macOS: brew services list
# Windows: Check Redis service in Services panel

# Test connection
python -c "
import redis
r = redis.Redis(host='localhost', port=6379, db=0)
print(r.ping())
"
```

#### Google API Errors
```bash
# Test API key
curl "https://www.googleapis.com/customsearch/v1?key=YOUR_KEY&cx=YOUR_CSE&q=test"

# Common issues:
# - API key not enabled for Custom Search
# - CSE ID incorrect
# - Billing not enabled for project
```

### Common Android Issues

#### Build Failures
```bash
# Clean and rebuild
./gradlew clean
./gradlew assembleDebug

# Check Gradle version
./gradlew --version

# Clear Gradle cache
rm -rf ~/.gradle/caches
```

#### Network Connection Issues
- Verify backend URL in AppConfig.kt
- Check firewall settings
- For emulator: Use `10.0.2.2` instead of `localhost`
- For device: Use computer's IP address

#### Permission Issues
- Manually grant permissions in device Settings
- Enable accessibility service
- Allow overlay permissions
- Check microphone access

### Performance Issues

#### High Memory Usage
```bash
# Monitor backend memory
ps aux | grep python

# Monitor Android app
adb shell dumpsys meminfo com.checkmate.app
```

#### High CPU Usage
- Check Redis memory settings
- Monitor AWS Bedrock API calls
- Optimize image processing frequency

## 6. 📊 Monitoring & Maintenance

### Backend Monitoring

```bash
# Check logs
tail -f checkmate.log

# Monitor Redis
redis-cli info memory

# Check AWS usage
aws cloudwatch get-metric-statistics \
  --namespace AWS/Bedrock \
  --metric-name Invocations \
  --start-time 2024-01-01T00:00:00Z \
  --end-time 2024-01-02T00:00:00Z \
  --period 3600 \
  --statistics Sum
```

### Android Monitoring

```bash
# Monitor app performance
adb shell dumpsys activity com.checkmate.app

# Check battery usage
adb shell dumpsys batterystats com.checkmate.app

# Monitor network usage
adb shell dumpsys netstats
```

## 7. 🔧 Advanced Configuration

### Custom ML Models

```bash
# Add custom TensorFlow Lite model
cp your_model.tflite frontend/app/src/main/assets/models/

# Update model configuration
# Edit: app/src/main/java/com/checkmate/app/ml/ImageClassifier.kt
```

### Backend Scaling

```yaml
# Kubernetes deployment
apiVersion: apps/v1
kind: Deployment
metadata:
  name: checkmate-backend
spec:
  replicas: 3
  selector:
    matchLabels:
      app: checkmate-backend
  template:
    spec:
      containers:
      - name: backend
        image: checkmate-backend:latest
        ports:
        - containerPort: 8000
        env:
        - name: REDIS_URL
          value: "redis://redis-service:6379"
```

### Load Balancing

```nginx
# nginx.conf
upstream checkmate_backend {
    server backend1:8000;
    server backend2:8000;
    server backend3:8000;
}

server {
    listen 80;
    location / {
        proxy_pass http://checkmate_backend;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

## 8. 🛡️ Security Considerations

### Backend Security
- Use HTTPS in production
- Implement rate limiting
- Add authentication if needed
- Secure API keys in environment variables
- Regular dependency updates

### Android Security
- Enable ProGuard for release builds
- Validate all network responses
- Implement certificate pinning
- Handle sensitive data securely
- Regular security updates

## 9. 📚 Additional Resources

### Documentation Links
- [AWS Bedrock Documentation](https://docs.aws.amazon.com/bedrock/)
- [Google Custom Search API](https://developers.google.com/custom-search/v1/overview)
- [Android Services Guide](https://developer.android.com/guide/components/services)
- [FastAPI Documentation](https://fastapi.tiangolo.com/)
- [Redis Documentation](https://redis.io/documentation)

### Community & Support
- GitHub Issues: [Checkmate Issues](https://github.com/DanielWang0099/Checkmate/issues)
- Discord: [Checkmate Community](https://discord.gg/checkmate)
- Email: support@checkmate-app.com

---

🎉 **Congratulations!** You now have a fully functional Checkmate fact-checking system. Start fact-checking and help combat misinformation!
