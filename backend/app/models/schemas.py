from pydantic import BaseModel
from typing import Optional

class FactCheckRequest(BaseModel):
    claim: str
    context: Optional[str] = None

class FactCheckResponse(BaseModel):
    claim: str
    verdict: str  # "True", "False", "Partially True", "Inconclusive"
    confidence_score: float
    sources: list[str]
    explanation: str
