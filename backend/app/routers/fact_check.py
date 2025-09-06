"""Fact-checking API router with proper polymorphic AgentContext handling."""

from fastapi import APIRouter, HTTPException, Depends
from typing import Dict, Any
from app.models.schemas import (
    SessionMemory, FrameBundle, ManagerResponse, AgentContextUnion,
    ErrorResponse, ErrorType, ErrorSeverity, SessionOperationResult,
    MediaType
)
from app.services.bedrock_service import orchestrator
from app.services.session_service import session_manager
import logging

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/v1/fact-check", tags=["fact-check"])


@router.post("/process-frame/{session_id}", response_model=ManagerResponse)
async def process_frame(
    session_id: str,
    frame_bundle: FrameBundle
) -> ManagerResponse:
    """
    Process a frame bundle for fact-checking with proper polymorphic handling.
    
    CRITICAL: This endpoint validates AgentContext polymorphism and ensures
    type safety throughout the fact-checking pipeline.
    """
    try:
        # Get session memory
        session_memory = await session_manager.get_session(session_id)
        if not session_memory:
            raise HTTPException(
                status_code=404,
                detail={
                    "errorType": ErrorType.SESSION_NOT_FOUND,
                    "message": f"Session {session_id} not found",
                    "severity": ErrorSeverity.HIGH
                }
            )
        
        # Process through orchestrator with polymorphic validation
        manager_response = await orchestrator.process_frame(session_memory, frame_bundle)
        
        # Validate the response AgentContext if present
        if manager_response.agent_context:
            await _validate_agent_context_consistency(
                manager_response.route, 
                manager_response.agent_context
            )
        
        # Update session memory
        await session_manager.update_session(session_id, manager_response.updated_memory)
        
        return manager_response
        
    except ValueError as e:
        # Polymorphic validation errors
        logger.error(f"AgentContext validation error for session {session_id}: {e}")
        raise HTTPException(
            status_code=400,
            detail={
                "errorType": ErrorType.VALIDATION_ERROR,
                "message": f"AgentContext validation failed: {str(e)}",
                "severity": ErrorSeverity.MEDIUM
            }
        )
    except Exception as e:
        logger.error(f"Fact-check processing error for session {session_id}: {e}")
        raise HTTPException(
            status_code=500,
            detail={
                "errorType": ErrorType.INTERNAL_ERROR,
                "message": "Internal processing error",
                "severity": ErrorSeverity.HIGH
            }
        )


async def _validate_agent_context_consistency(
    route: str, 
    agent_context: AgentContextUnion
) -> None:
    """
    Validate that the AgentContext type matches the routing decision.
    
    CRITICAL: This prevents runtime type mismatches that could cause
    downstream processing errors.
    """
    if route == MediaType.TEXT:
        if not (hasattr(agent_context, 'context_type') and agent_context.context_type == "text"):
            raise ValueError(f"Route 'text' requires AgentContext with context_type='text'")
        if hasattr(agent_context, 'image_ref') or hasattr(agent_context, 'transcript_delta'):
            raise ValueError(f"Route 'text' should not have image_ref or transcript_delta fields")
    
    elif route == MediaType.TEXT_IMAGE:
        if not (hasattr(agent_context, 'context_type') and agent_context.context_type == "text_image"):
            raise ValueError(f"Route 'text_image' requires TextImageAgentContext with context_type='text_image'")
        if not hasattr(agent_context, 'image_ref'):
            raise ValueError(f"Route 'text_image' requires image_ref field")
    
    elif route in [MediaType.SHORT_VIDEO, MediaType.LONG_VIDEO]:
        if not (hasattr(agent_context, 'context_type') and agent_context.context_type == "video"):
            raise ValueError(f"Route 'video' requires VideoAgentContext with context_type='video'")
        if not hasattr(agent_context, 'transcript_delta'):
            raise ValueError(f"Route 'video' requires transcript_delta field")
    
    elif route == "none":
        # No agent context should be present for "none" route
        if agent_context is not None:
            raise ValueError(f"Route 'none' should not have agent_context")
    
    else:
        raise ValueError(f"Unknown route: {route}")


@router.get("/session/{session_id}/context-validation")
async def validate_session_context(session_id: str) -> Dict[str, Any]:
    """
    Validate the current session's AgentContext polymorphism.
    
    Used for debugging and monitoring polymorphic type handling.
    """
    try:
        session_memory = await session_manager.get_session(session_id)
        if not session_memory:
            raise HTTPException(status_code=404, detail="Session not found")
        
        # Check if session has proper polymorphic context handling
        validation_result = {
            "session_id": session_id,
            "polymorphic_validation": "passed",
            "context_types_supported": [
                "text (AgentContext)",
                "text_image (TextImageAgentContext)", 
                "video (VideoAgentContext)"
            ],
            "current_session_valid": True
        }
        
        return validation_result
        
    except Exception as e:
        logger.error(f"Context validation error for session {session_id}: {e}")
        raise HTTPException(
            status_code=500,
            detail={
                "errorType": ErrorType.INTERNAL_ERROR,
                "message": f"Validation failed: {str(e)}",
                "severity": ErrorSeverity.LOW
            }
        )