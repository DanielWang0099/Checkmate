"""Checkmate Backend - FastAPI application for real-time fact-checking."""

import asyncio
import json
import logging
from contextlib import asynccontextmanager
from datetime import datetime
from typing import Dict, Any
import uuid

from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException, BackgroundTasks
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.core.config import settings
from app.models.schemas import (
    FrameBundle, SessionMemory, WSMessage, WSMessageType,
    NotificationPayload, SessionSettings
)
from app.services.session_service import session_manager, websocket_manager
from app.services.bedrock_service import orchestrator


# Configure logging
logging.basicConfig(
    level=getattr(logging, settings.log_level.upper()),
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan manager."""
    # Startup
    logger.info("Starting Checkmate backend...")
    await session_manager.initialize()
    
    # Background task for session cleanup
    cleanup_task = asyncio.create_task(periodic_cleanup())
    
    yield
    
    # Shutdown
    logger.info("Shutting down Checkmate backend...")
    cleanup_task.cancel()
    try:
        await cleanup_task
    except asyncio.CancelledError:
        pass


async def periodic_cleanup():
    """Periodic cleanup of expired sessions."""
    while True:
        try:
            await session_manager.cleanup_expired_sessions()
            await asyncio.sleep(300)  # Run every 5 minutes
        except asyncio.CancelledError:
            break
        except Exception as e:
            logger.error(f"Cleanup error: {e}")
            await asyncio.sleep(60)


# Create FastAPI app
app = FastAPI(
    title="Checkmate API",
    description="Real-time fact-checking backend service",
    version="1.0.0",
    lifespan=lifespan
)

# Add CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # Configure appropriately for production
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/")
async def root():
    """Health check endpoint."""
    return {"message": "Checkmate API is running", "version": "1.0.0"}


@app.get("/health")
async def health_check():
    """Detailed health check."""
    return {
        "status": "healthy",
        "timestamp": datetime.utcnow().isoformat(),
        "version": "1.0.0",
        "services": {
            "redis": session_manager.redis is not None,
            "bedrock": orchestrator.bedrock.client is not None
        }
    }


@app.post("/sessions")
async def create_session(session_settings: Dict[str, Any]):
    """Create a new fact-checking session."""
    try:
        session_id = str(uuid.uuid4())
        session_memory = await session_manager.create_session(session_id, session_settings)
        
        logger.info(f"Created session {session_id}")
        
        return {
            "session_id": session_id,
            "settings": session_memory.settings.model_dump(by_alias=True),
            "created_at": datetime.utcnow().isoformat()
        }
    
    except Exception as e:
        logger.error(f"Failed to create session: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/sessions/{session_id}")
async def get_session(session_id: str):
    """Get session information."""
    session_memory = await session_manager.get_session(session_id)
    if not session_memory:
        raise HTTPException(status_code=404, detail="Session not found")
    
    return {
        "session_id": session_id,
        "memory": session_memory.model_dump(by_alias=True),
        "active": True
    }


@app.delete("/sessions/{session_id}")
async def delete_session(session_id: str):
    """Delete a session."""
    await session_manager.delete_session(session_id)
    await websocket_manager.disconnect(session_id)
    
    logger.info(f"Deleted session {session_id}")
    
    return {"message": "Session deleted"}


@app.get("/sessions")
async def list_sessions():
    """List all active sessions."""
    session_ids = await session_manager.list_active_sessions()
    return {"sessions": session_ids, "count": len(session_ids)}


@app.websocket("/ws/{session_id}")
async def websocket_endpoint(websocket: WebSocket, session_id: str):
    """WebSocket endpoint for real-time fact-checking."""
    await websocket.accept()
    await websocket_manager.connect(session_id, websocket)
    
    logger.info(f"WebSocket connected for session {session_id}")
    
    try:
        while True:
            # Receive message from client
            data = await websocket.receive_json()
            message = WSMessage(**data)
            
            if message.type == WSMessageType.PING:
                await websocket.send_json({
                    "type": WSMessageType.PONG.value,
                    "data": {"timestamp": datetime.utcnow().isoformat()},
                    "timestamp": datetime.utcnow().isoformat()
                })
                continue
            
            if message.type == WSMessageType.FRAME_BUNDLE:
                await process_frame_bundle(session_id, message.data, websocket)
    
    except WebSocketDisconnect:
        logger.info(f"WebSocket disconnected for session {session_id}")
    except Exception as e:
        logger.error(f"WebSocket error for session {session_id}: {e}")
    finally:
        await websocket_manager.disconnect(session_id)


async def process_frame_bundle(session_id: str, data: Dict[str, Any], websocket: WebSocket):
    """Process an incoming frame bundle."""
    try:
        # Parse frame bundle
        frame_bundle = FrameBundle(**data)
        
        # Get session memory
        session_memory = await session_manager.get_session(session_id)
        if not session_memory:
            await websocket.send_json({
                "type": WSMessageType.ERROR.value,
                "data": {"error": "Session not found"},
                "timestamp": datetime.utcnow().isoformat()
            })
            return
        
        # Process through agent orchestrator
        manager_response = await orchestrator.process_frame(session_memory, frame_bundle)
        
        # Update session memory
        await session_manager.update_session(session_id, manager_response.updated_memory)
        
        # Send notifications if any
        for notification in manager_response.notifications:
            if notification.should_notify:
                await websocket.send_json({
                    "type": WSMessageType.NOTIFICATION.value,
                    "data": notification.model_dump(by_alias=True),
                    "timestamp": datetime.utcnow().isoformat()
                })
        
        # Check if session should end
        if manager_response.end_session:
            await websocket.send_json({
                "type": WSMessageType.SESSION_END.value,
                "data": {"reason": "Activity-based session end"},
                "timestamp": datetime.utcnow().isoformat()
            })
            await session_manager.delete_session(session_id)
        
        logger.debug(f"Processed frame for session {session_id}, route: {manager_response.route}")
    
    except Exception as e:
        logger.error(f"Frame processing error for session {session_id}: {e}")
        await websocket.send_json({
            "type": WSMessageType.ERROR.value,
            "data": {"error": str(e)},
            "timestamp": datetime.utcnow().isoformat()
        })


@app.post("/sessions/{session_id}/test-notification")
async def test_notification(session_id: str, notification_data: Dict[str, Any]):
    """Test endpoint for sending notifications (development only)."""
    if not settings.log_level.upper() == "DEBUG":
        raise HTTPException(status_code=403, detail="Test endpoint only available in debug mode")
    
    try:
        notification = NotificationPayload(**notification_data)
        await websocket_manager.send_to_session(session_id, {
            "type": WSMessageType.NOTIFICATION.value,
            "data": notification.model_dump(by_alias=True),
            "timestamp": datetime.utcnow().isoformat()
        })
        
        return {"message": "Test notification sent"}
    
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@app.exception_handler(Exception)
async def global_exception_handler(request, exc):
    """Global exception handler."""
    logger.error(f"Unhandled exception: {exc}")
    return JSONResponse(
        status_code=500,
        content={"error": "Internal server error", "detail": str(exc)}
    )


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=8000,
        reload=True,
        log_level=settings.log_level.lower()
    )