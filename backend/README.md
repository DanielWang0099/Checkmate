# Checkmate — Backend

This folder contains the FastAPI backend for Checkmate: a small fact-checking API that calls OpenAI via LangChain.

This README documents how to run the backend locally (Windows PowerShell), the expected environment, the public API, and common troubleshooting steps.

## Quick summary
- Framework: FastAPI
- LLM integration: LangChain (uses `langchain-openai` / `ChatOpenAI`)
- Model configured (by default): `gpt-4-turbo-preview`
- Entrypoint: `app.main:app` (Uvicorn)

## Files of interest
- `app/main.py` — FastAPI app, CORS middleware, router registration.
- `app/core/config.py` — pydantic `Settings` (loads `.env`) and exposes `settings`.
- `app/models/schemas.py` — Pydantic request/response models (`FactCheckRequest`, `FactCheckResponse`).
- `app/routers/fact_check.py` — API router for `/api/v1/fact-check`.
- `app/services/langchain_service.py` — LLM wrapper that queries the model and returns a structured JSON result.
- `requirements.txt` — pinned Python dependencies used for the project.
- `start_server.bat` — convenience script to activate the venv and start the server on Windows (CMD).
- `test_backend.py` — a minimal script that exercises the root endpoint and the fact-check endpoint.
- `.env.example` — example file showing `OPENAI_API_KEY` variable.

## Contract (API)
- POST /api/v1/fact-check
  - Request JSON: `{ "claim": "...", "context": "optional context" }`
  - Response JSON (schema `FactCheckResponse`):
    - `claim`: string
    - `verdict`: string — one of: `True`, `False`, `Partially True`, `Inconclusive`
    - `confidence_score`: float (0.0 - 1.0)
    - `sources`: list[string]
    - `explanation`: string

## Prerequisites
- Python 3.10+ installed and on PATH
- A valid OpenAI API key with access to the configured model (if using `gpt-4-turbo-*` you must have access)

## Local setup (Windows PowerShell)
1. Open PowerShell in the `backend` folder.
2. Create a virtual environment (if not created):

```powershell
python -m venv venv
```

3. Activate the venv (PowerShell):

```powershell
.\venv\Scripts\Activate.ps1
```

4. Install dependencies:

```powershell
pip install -r requirements.txt
```

5. Provide your OpenAI key. Either create a file named `.env` in the `backend` folder with:

```text
OPENAI_API_KEY=sk-................
```

The project loads `.env` via `python-dotenv` in `app/core/config.py`. Alternatively set the environment variable in PowerShell:

```powershell
$env:OPENAI_API_KEY = 'sk-...'
```

6. Start the server (development):

```powershell
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

Or use the provided `start_server.bat` from a CMD prompt (it activates the venv and runs uvicorn):

```cmd
start_server.bat
```

The API will be available at: `http://localhost:8000` and the fact-check endpoint at `http://localhost:8000/api/v1/fact-check`.

## Example request (curl)

```bash
curl -X POST "http://localhost:8000/api/v1/fact-check" \
  -H "Content-Type: application/json" \
  -d '{"claim": "The Earth is flat", "context": "short test"}'
```

## Running the quick smoke test
With the server running, run:

```powershell
python test_backend.py
```

This will call the root endpoint and the `/api/v1/fact-check` endpoint and print results.

## Design notes & implementation details
- `app/services/langchain_service.py` builds a prompt and sends it to `ChatOpenAI` via LangChain. The service expects the LLM to return a JSON object and attempts to extract JSON using a regex fallback.
- If the LLM response cannot be parsed as JSON, the service returns a safe fallback with `verdict: "Inconclusive"`, `confidence_score: 0.5` and the raw response placed in `explanation`.
- `app/core/config.py` uses `pydantic_settings.BaseSettings` and `python-dotenv` to read `OPENAI_API_KEY` from `.env`.

## Security & production notes
- Do NOT commit `.env` or your OpenAI key into source control. Use the provided `.env.example` and add `.env` to your `.gitignore`.
- CORS is currently configured with `allow_origins=["*"]` in `app/main.py`. Restrict this in production to the domains you control.
- The default model in `langchain_service.py` is `gpt-4-turbo-preview`. Ensure your API key has permission and be aware of usage costs and rate limits.

## Troubleshooting
- If imports fail in your editor, make sure VS Code / your linter is using the `backend/venv` interpreter.
- If you see authentication errors from OpenAI, confirm `OPENAI_API_KEY` is set and valid.
- If you want to remove the virtual environment from git (if it was accidentally tracked):

```bash
git rm -r --cached backend/venv/
echo "backend/venv/" >> .gitignore
git add .gitignore
git commit -m "chore: ignore backend virtualenv"
```

## Edge cases and limitations
- The service relies on the LLM to produce well-formed JSON; while there's a regex fallback, parsing may still fail for complex responses. Consider using structured tools (function calling or JSON schema validation) for more robust results.
- The simple fact-checker does not perform external web scraping or database lookups — it depends entirely on the LLM's knowledge and reasoning. For authoritative sourcing add retrieval (search + citation) before calling the LLM.

## Verified checks
- The repository includes `test_backend.py` for quick smoke testing.
- The application imports have been validated via `python -c "import app.main; print('App imports successfully')"` during development.

## Next steps (suggested)
- Add a richer retrieval layer (search or cached sources) to provide verifiable citations.
- Add unit tests and CI to run the smoke test and validate response shape (happy path + JSON parse fallback).

---

If you want, I can also add a `backend/README.md` badge, a small unit test, or commit the README into the repository for you.
