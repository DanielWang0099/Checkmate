from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from app.routers import fact_check
from app.core.config import settings

app = FastAPI(title="Checkmate API", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # Configure for production
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(fact_check.router, prefix="/api/v1")

@app.get("/")
async def root():
    return {"message": "Checkmate Fact-Checking API"}
