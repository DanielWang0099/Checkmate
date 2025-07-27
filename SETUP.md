Check if an AVD or physical device is connected.

# Checkmate Setup Guide

## Prerequisites

Before running the project, make sure you have:

### 1. Backend Prerequisites
- Python 3.8+ installed
- OpenAI API key

### 2. React Native Prerequisites
- Node.js 16+ installed
- Android Studio with Android SDK
- Java Development Kit (JDK) 17

## Setup Instructions

### Backend Setup

1. Navigate to backend directory:
   ```bash
   cd backend
   ```

2. Create and activate virtual environment:
   ```bash
   python -m venv venv
   venv\Scripts\activate  # Windows
   ```

3. Install dependencies:
   ```bash
   pip install -r requirements.txt
   ```

4. Copy environment file and add your OpenAI API key:
   ```bash
   copy .env.example .env
   # Edit .env file and add: OPENAI_API_KEY=your_actual_api_key_here
   ```

5. Start the backend server:
   ```bash
   .\start_server.bat
   ```
   Or manually:
   ```bash
   uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
   ```

The backend will be available at: http://localhost:8000

### Android Studio Setup

1. Download and install Android Studio from: https://developer.android.com/studio
2. Open Android Studio and install:
   - Android SDK Platform 33 (or latest)
   - Android SDK Build-Tools
   - Android Virtual Device (AVD) - create an emulator
3. Set up environment variables:
   - ANDROID_HOME: Path to your Android SDK (usually `C:\Users\[username]\AppData\Local\Android\Sdk`)
   - Add to PATH: `%ANDROID_HOME%\platform-tools` and `%ANDROID_HOME%\tools`

### React Native Setup

1. Navigate to mobile app directory:
   ```bash
   cd CheckmateMobile
   ```

2. Install dependencies:
   ```bash
   npm install
   ```

3. Start Metro bundler:
   ```bash
   npx react-native start
   ```

4. In a new terminal, run on Android:
   ```bash
   npx react-native run-android
   ```

## Quick Start Scripts

### Start Backend
Run `backend/start_server.bat`

### Start React Native
1. Run `CheckmateMobile/start_metro.bat`
2. Run `CheckmateMobile/run_android.bat`

## Testing the App

1. Make sure backend is running on port 8000
2. Start the React Native app on Android emulator
3. Enter a claim to fact-check in the app
4. The app will communicate with the backend to get AI-powered fact-check results

## Troubleshooting

### Android SDK Not Found
- Install Android Studio
- Set ANDROID_HOME environment variable
- Restart your terminal/command prompt

### Backend Connection Issues
- Ensure backend is running on http://localhost:8000
- Check if Windows Firewall is blocking the connection
- Verify the API endpoint in `CheckmateMobile/src/services/api.ts`

### OpenAI API Issues
- Verify your OpenAI API key is correct in backend/.env
- Ensure you have sufficient OpenAI credits
- Check OpenAI API status at https://status.openai.com
