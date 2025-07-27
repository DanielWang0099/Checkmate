# Checkmate - AI-Powered Fact Checker

A React Native mobile app with FastAPI backend for fact-checking claims using AI and LangChain.

## Project Structure

```
Checkmate/
├── backend/                    # FastAPI backend
│   ├── app/
│   │   ├── core/              # Configuration
│   │   ├── models/            # Pydantic schemas
│   │   ├── routers/           # API endpoints
│   │   └── services/          # Business logic (LangChain)
│   ├── requirements.txt
│   ├── .env.example
│   └── .env
├── CheckmateMobile/           # React Native app
│   ├── src/
│   │   ├── hooks/             # Custom React hooks
│   │   ├── screens/           # App screens
│   │   └── services/          # API services
│   └── package.json
└── README.md
```

## Backend Setup (FastAPI + LangChain + OpenAI)

1. **Navigate to backend directory:**
   ```bash
   cd backend
   ```

2. **Create and activate virtual environment:**
   ```bash
   python -m venv venv
   venv\Scripts\activate  # Windows
   # source venv/bin/activate  # macOS/Linux
   ```

3. **Install dependencies:**
   ```bash
   pip install -r requirements.txt
   ```

4. **Set up environment variables:**
   ```bash
   cp .env.example .env
   # Edit .env and add your OpenAI API key
   ```

5. **Run the backend server:**
   ```bash
   uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
   ```

The backend will be available at: `http://localhost:8000`

## Frontend Setup (React Native)

1. **Navigate to mobile app directory:**
   ```bash
   cd CheckmateMobile
   ```

2. **Install dependencies:**
   ```bash
   npm install
   ```

3. **Start Metro bundler:**
   ```bash
   npx react-native start
   ```

4. **Run on Android (in a new terminal):**
   ```bash
   npx react-native run-android
   ```

   **Prerequisites for Android:**
   - Android Studio installed
   - Android SDK configured
   - Android Virtual Device (AVD) running or physical device connected

## API Endpoints

### POST `/api/v1/fact-check`

Fact-check a claim using AI.

**Request Body:**
```json
{
  "claim": "The Earth is flat",
  "context": "Optional context for the claim"
}
```

**Response:**
```json
{
  "claim": "The Earth is flat",
  "verdict": "False",
  "confidence_score": 0.95,
  "sources": ["https://example.com/source1"],
  "explanation": "The Earth is actually spherical, as demonstrated by numerous scientific observations and experiments..."
}
```

## Features

- 🔍 AI-powered fact checking using GPT-4
- 📱 Clean React Native mobile interface
- 🚀 Fast FastAPI backend
- 🔗 LangChain integration for advanced AI workflows
- 📊 Confidence scores for fact-check results
- 📚 Source citations (when available)

## Technology Stack

**Backend:**
- FastAPI (Python web framework)
- LangChain (AI workflow orchestration)
- OpenAI GPT-4 (Language model)
- Pydantic (Data validation)
- Uvicorn (ASGI server)

**Frontend:**
- React Native 0.80+
- TypeScript
- Axios (HTTP client)
- Custom hooks for state management

## Development Notes

- The mobile app uses `10.0.2.2:8000` to connect to the backend when running on Android emulator
- CORS is enabled for all origins in development (configure for production)
- Environment variables are used for API keys and configuration

## Getting Started

1. Set up the backend first and ensure it's running on port 8000
2. Add your OpenAI API key to the backend `.env` file
3. Set up the React Native environment and run the mobile app
4. Test the fact-checking functionality through the mobile interface