from fastapi import APIRouter, HTTPException
from app.models.schemas import FactCheckRequest, FactCheckResponse
from app.services.langchain_service import FactCheckingService

router = APIRouter(tags=["fact-check"])
fact_checker = FactCheckingService()

@router.post("/fact-check", response_model=FactCheckResponse)
async def check_fact(request: FactCheckRequest):
    try:
        result = await fact_checker.check_fact(request.claim, request.context)
        
        return FactCheckResponse(
            claim=request.claim,
            verdict=result["verdict"],
            confidence_score=result["confidence_score"],
            sources=result["sources"],
            explanation=result["explanation"]
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
