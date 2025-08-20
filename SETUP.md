Backend setup — Checkmate

This file contains only the backend setup steps for the Checkmate FastAPI service.

## Prerequisites
- Python 3.10+ installed and on your PATH
- An OpenAI API key (add it to `.env` or set it in your session)

## Quick backend setup (PowerShell)

1. Open PowerShell and change to the backend folder:

```powershell
cd backend
```

2. Create a virtual environment (if you haven't yet):

```powershell
python -m venv venv
```

3. Activate the virtual environment (PowerShell):

```powershell
.\venv\Scripts\Activate.ps1
```

If activation fails due to execution policy restrictions, allow it for the current session and retry:

```powershell
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope Process
.\venv\Scripts\Activate.ps1
```

4. Install Python dependencies:

```powershell
pip install -r requirements.txt
```

5. Provide your OpenAI API key.

- Option A — copy the example `.env` and edit it:

```powershell
Copy-Item .env.example .env
# open backend\.env in an editor and set OPENAI_API_KEY=sk-...
```

- Option B — set the key for your current PowerShell session (temporary):

```powershell
$env:OPENAI_API_KEY = 'sk-...'
```

6. Start the development server:

```powershell
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

Or, from a CMD prompt you can use the provided script:

```cmd
start_server.bat
```

The API will be available at: http://localhost:8000
The fact-check endpoint is: http://localhost:8000/api/v1/fact-check

## Quick smoke test
With the server running, run:

```powershell
python test_backend.py
```

The script will call the root endpoint and the `/api/v1/fact-check` endpoint and print a short result.

## Git hygiene (important)
- Do NOT commit your `.env` or the `venv` directory. If these were accidentally tracked, remove them and update `.gitignore`:

```powershell
# from the repository root
echo "backend/venv/" >> .gitignore
echo "backend/.env" >> .gitignore
git rm -r --cached backend/venv/
git rm --cached backend/.env
git add .gitignore
git commit -m "chore: ignore backend venv and .env"
```

## Notes & troubleshooting
- If you get OpenAI authentication errors, confirm `OPENAI_API_KEY` is set in `.env` or the environment and that the key has the required model access.
- If your editor (e.g., VS Code) shows unresolved imports, ensure the workspace interpreter is set to `backend/venv` so linters resolve installed packages.
- The backend service expects the LLM to return JSON; malformed LLM responses are handled with a safe fallback (`Inconclusive`, confidence 0.5).

