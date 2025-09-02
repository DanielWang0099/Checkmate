# Checkmate - Real-time Fact-Checking App

Checkmate is a comprehensive Android fact-checking application that runs in the background to verify information you encounter while browsing, watching videos, or consuming media on your device.

## 🎯 Project Overview

Checkmate operates as a background service that:
- **Monitors screen content** using accessibility services and screen capture
- **Extracts text** via ML Kit OCR and audio via system audio capture  
- **Detects images** using an on-device MobileNetV2 classifier
- **Fact-checks content** through LLM-powered agents using AWS Bedrock
- **Delivers notifications** with color-coded confidence levels (green/yellow/red)

## 🏗️ Architecture

### Frontend (Android - Kotlin)
- **Quick Settings Tile** for easy start/stop access
- **Jetpack Compose UI** with minimal interface
- **Foreground Service** for background operation
- **Accessibility Service** for UI tree capture
- **MediaProjection** for screen and audio capture
- **ML Kit + MobileNetV2** for on-device text/image processing

### Backend (Python - FastAPI + AWS Bedrock)
- **FastAPI WebSocket** server for real-time communication
- **Manager Agent** using Claude 3.5 Sonnet for orchestration
- **Media-specific Agents** for text, image, and video fact-checking
- **Tool Functions** for web search, reverse image search, YouTube API
- **Redis/MongoDB** for session management and caching

## 🚀 Getting Started

### Prerequisites
- **Android Studio** with Kotlin support
- **Python 3.9+** 
- **AWS Account** with Bedrock access
- **Google Cloud** account for Speech-to-Text API
- **API Keys** for YouTube, search providers

### Backend Setup

1. **Create Python virtual environment:**
```bash
cd backend
python -m venv venv
venv\Scripts\activate  # Windows
# source venv/bin/activate  # Linux/Mac
```

2. **Install dependencies:**
```bash
pip install -r requirements.txt
```

3. **Configure environment:**
```bash
cp .env.example .env
# Edit .env with your API keys and credentials
```

4. **Start the server:**
```bash
python -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

### Android Setup

1. **Open Android Studio:**
```bash
# Open the frontend directory in Android Studio
```

2. **Sync Gradle dependencies**

3. **Configure backend URL:**
- Update `app/src/main/java/com/checkmate/app/network/ApiService.kt`
- Set your backend server URL (e.g., `http://192.168.1.100:8000`)

4. **Build and run:**
- Connect device or start emulator
- Click Run or `Ctrl+F10`

## 📱 Usage

### Starting a Session
1. **Pull down Quick Settings** 
2. **Tap the Checkmate tile** to start fact-checking
3. **Configure preferences** in the app (strictness, notification settings)

### Session Types
- **Time-boxed:** 5 minutes to 3 hours
- **Manual:** Until manually stopped
- **Activity-based:** Automatically ends when switching to different content

### Strictness Levels
- **0.0-0.2:** Very conservative, only flag obvious falsehoods
- **0.4-0.5:** Balanced approach 
- **0.6-0.8:** More proactive flagging
- **1.0:** Flag any potentially misleading content

### Notifications
- **🟢 Green:** Corroborated by reliable sources
- **🟡 Yellow:** Weak or conflicting evidence  
- **🔴 Red:** Likely false or misleading
- **Expandable details** with sources and explanations

## 🛠️ API Endpoints

### REST API
- `POST /sessions` - Create new fact-checking session
- `GET /sessions/{id}` - Get session details
- `DELETE /sessions/{id}` - End session
- `GET /health` - Service health check

### WebSocket
- `ws://localhost:8000/ws/{session_id}` - Real-time communication
- Frame bundles every 2-3 seconds
- Receive notifications and session updates

## 🔧 Configuration

### Environment Variables (.env)
Key configurations needed:

```bash
# AWS Bedrock
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=your_key
AWS_SECRET_ACCESS_KEY=your_secret
BEDROCK_MANAGER_MODEL=anthropic.claude-3-5-sonnet-20241022-v2:0

# Google APIs  
GOOGLE_CLOUD_PROJECT_ID=your_project
YOUTUBE_API_KEY=your_youtube_key

# Search APIs
BING_SEARCH_API_KEY=your_bing_key
GOOGLE_CUSTOM_SEARCH_API_KEY=your_google_key

# Database
REDIS_URL=redis://localhost:6379/0
```

### Android Permissions
Required in AndroidManifest.xml:
- `BIND_ACCESSIBILITY_SERVICE`
- `FOREGROUND_SERVICE_MEDIA_PROJECTION` 
- `RECORD_AUDIO`
- `POST_NOTIFICATIONS`
- Network permissions

## 🎨 Features

### Real-time Processing
- **2-3 second cadence** for content capture
- **Adaptive sampling** based on battery and activity
- **Smart throttling** for image uploads

### AI-Powered Fact-Checking
- **Manager Agent** maintains session memory and routes content
- **Text Agent** verifies written claims with web search
- **Text+Image Agent** checks image-caption consistency  
- **Video Agent** handles YouTube, TikTok, and long-form content

### Privacy & Performance
- **Ephemeral storage** with 24-hour TTL
- **On-device processing** for sensitive operations
- **Battery optimization** with power-aware scheduling
- **Optional PII redaction** before upload

## 📊 Data Flow

1. **Screen Capture:** Accessibility tree + screenshot every 2-3s
2. **Content Extraction:** OCR text + image detection + audio transcription
3. **Manager Processing:** Update session memory, route to appropriate agent
4. **Fact-Checking:** Web search, source verification, confidence scoring
5. **Notification:** Color-coded alert with expandable details

## 🔒 Security

- **AWS STS credentials** for short-lived access
- **TLS encryption** for all API communication
- **Rate limiting** and abuse prevention
- **No persistent sensitive data storage**

## 🧪 Testing

### Backend Testing
```bash
cd backend
pytest tests/ -v
```

### Android Testing  
```bash
./gradlew test
./gradlew connectedAndroidTest
```

## 🤝 Contributing

1. Fork the repository
2. Create feature branch (`git checkout -b feature/amazing-feature`)
3. Commit changes (`git commit -m 'Add amazing feature'`)
4. Push to branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🆘 Support

For issues and questions:
- Open an issue on GitHub
- Check the [documentation](docs/)
- Review the [FAQ](docs/FAQ.md)

---

**⚠️ Disclaimer:** Checkmate is designed to assist with fact-checking but should not be considered infallible. Always verify important information through multiple authoritative sources.