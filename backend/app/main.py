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
from pydantic import ValidationError

from app.core.config import settings
from app.models.schemas import (
    FrameBundle, SessionMemory, WSMessage, WSMessageType,
    NotificationPayload, SessionSettings, ErrorResponse, ErrorType, 
    ErrorSeverity, ValidationErrorResponse, ValidationErrorField, WebSocketErrorMessage,
    SessionControlMessage, SessionStatusMessage, HeartbeatMessage
)
from app.services.session_service import session_manager, websocket_manager
from app.services.bedrock_service import orchestrator
from app.services.redis_starter import redis_starter
from app.services.error_recovery_service import error_recovery_service


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
    
    # Try to start Redis automatically
    redis_starter.ensure_redis_available()
    
    await session_manager.initialize()
    await error_recovery_service.initialize()
    
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
    
    # Optionally stop Redis container (uncomment if you want auto-cleanup)
    # redis_starter.stop_redis_container()


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
    
    except ValidationError as ve:
        logger.error(f"Validation error creating session: {ve}")
        field_errors = [
            ValidationErrorField(
                field=str(error["loc"][-1]) if error["loc"] else "unknown",
                message=error["msg"],
                invalid_value=error.get("input")
            )
            for error in ve.errors()
        ]
        validation_error = ValidationErrorResponse(
            severity=ErrorSeverity.LOW,
            message="Invalid session settings provided",
            field_errors=field_errors
        )
        raise HTTPException(status_code=422, detail=validation_error.model_dump(by_alias=True))
    
    except Exception as e:
        logger.error(f"Failed to create session: {e}")
        error_response = ErrorResponse(
            error_type=ErrorType.INTERNAL_ERROR,
            severity=ErrorSeverity.HIGH,
            message="Failed to create session",
            details=str(e)
        )
        raise HTTPException(status_code=500, detail=error_response.model_dump(by_alias=True))


@app.get("/sessions/{session_id}")
async def get_session(session_id: str):
    """Get session information."""
    result = await session_manager.get_session(session_id)
    if not result.success:
        error_response = result.error
        if error_response.error_type == ErrorType.SESSION_NOT_FOUND:
            raise HTTPException(status_code=404, detail=error_response.model_dump(by_alias=True))
        else:
            raise HTTPException(status_code=500, detail=error_response.model_dump(by_alias=True))
    
    return {
        "session_id": session_id,
        "memory": result.data.model_dump(by_alias=True),
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
    """Enhanced WebSocket endpoint with proper message routing."""
    await websocket.accept()
    await websocket_manager.connect(session_id, websocket)
    
    logger.info(f"WebSocket connected for session {session_id}")
    
    try:
        while True:
            # Receive message from client
            data = await websocket.receive_json()
            
            # Validate basic message structure
            try:
                message = WSMessage(**data)
            except ValidationError as ve:
                error_response = ErrorResponse(
                    error_type=ErrorType.VALIDATION_ERROR,
                    severity=ErrorSeverity.LOW,
                    message="Invalid message format",
                    details=str(ve)
                )
                error_msg = WebSocketErrorMessage(data=error_response)
                await websocket.send_json(error_msg.model_dump(by_alias=True))
                continue
            
            # Route message based on type
            await route_websocket_message(session_id, message, websocket)
    
    except WebSocketDisconnect:
        logger.info(f"WebSocket disconnected for session {session_id}")
    except Exception as e:
        logger.error(f"WebSocket error for session {session_id}: {e}")
    finally:
        await websocket_manager.disconnect(session_id)


async def route_websocket_message(session_id: str, message: WSMessage, websocket: WebSocket):
    """Route WebSocket messages to appropriate handlers."""
    try:
        if message.type == WSMessageType.PING:
            await handle_ping(session_id, websocket)
        
        elif message.type == WSMessageType.HEARTBEAT:
            await handle_heartbeat(session_id, websocket)
        
        elif message.type == WSMessageType.FRAME_BUNDLE:
            await process_frame_bundle(session_id, message.data, websocket)
        
        elif message.type == WSMessageType.SESSION_START:
            await handle_session_start(session_id, message.data, websocket)
        
        elif message.type == WSMessageType.SESSION_STOP:
            await handle_session_stop(session_id, websocket)
        
        elif message.type == WSMessageType.SESSION_STATUS:
            await handle_session_status_request(session_id, websocket)
        
        else:
            # Unknown message type
            error_response = ErrorResponse(
                error_type=ErrorType.VALIDATION_ERROR,
                severity=ErrorSeverity.LOW,
                message=f"Unknown message type: {message.type}"
            )
            error_msg = WebSocketErrorMessage(data=error_response)
            await websocket.send_json(error_msg.model_dump(by_alias=True))
    
    except Exception as e:
        logger.error(f"Message routing error for session {session_id}: {e}")
        error_response = ErrorResponse(
            error_type=ErrorType.INTERNAL_ERROR,
            severity=ErrorSeverity.HIGH,
            message="Message processing failed",
            details=str(e)
        )
        error_msg = WebSocketErrorMessage(data=error_response)
        await websocket.send_json(error_msg.model_dump(by_alias=True))


async def handle_ping(session_id: str, websocket: WebSocket):
    """Handle PING messages with PONG response."""
    pong_message = {
        "type": WSMessageType.PONG.value,
        "data": {"timestamp": datetime.utcnow().isoformat()},
        "timestamp": datetime.utcnow().isoformat()
    }
    await websocket.send_json(pong_message)


async def handle_heartbeat(session_id: str, websocket: WebSocket):
    """Handle heartbeat messages and update connection health."""
    websocket_manager.update_heartbeat(session_id)
    
    # Send heartbeat acknowledgment
    heartbeat_response = HeartbeatMessage(
        data={"status": "acknowledged", "connections": websocket_manager.get_connection_count()}
    )
    await websocket.send_json(heartbeat_response.model_dump(by_alias=True))


async def handle_session_start(session_id: str, session_data: Dict[str, Any], websocket: WebSocket):
    """Handle session start requests from client."""
    try:
        # Create session if it doesn't exist
        result = await session_manager.get_session(session_id)
        if result.success:
            # Session already exists
            status_message = SessionStatusMessage(
                data={
                    "status": "already_active",
                    "session_id": session_id,
                    "message": "Session is already active"
                }
            )
        else:
            # Create new session
            session_memory = await session_manager.create_session(session_id, session_data)
            status_message = SessionStatusMessage(
                data={
                    "status": "started",
                    "session_id": session_id,
                    "settings": session_memory.settings.model_dump(by_alias=True)
                }
            )
        
        await websocket.send_json(status_message.model_dump(by_alias=True))
        logger.info(f"Session start handled for {session_id}")
    
    except Exception as e:
        logger.error(f"Session start error for {session_id}: {e}")
        error_response = ErrorResponse(
            error_type=ErrorType.INTERNAL_ERROR,
            severity=ErrorSeverity.HIGH,
            message="Failed to start session",
            details=str(e)
        )
        error_msg = WebSocketErrorMessage(data=error_response)
        await websocket.send_json(error_msg.model_dump(by_alias=True))


async def handle_session_stop(session_id: str, websocket: WebSocket):
    """Handle session stop requests from client."""
    try:
        # Delete session
        await session_manager.delete_session(session_id)
        
        # Send session end message
        session_end_message = {
            "type": WSMessageType.SESSION_END.value,
            "data": {"reason": "Client requested stop"},
            "timestamp": datetime.utcnow().isoformat()
        }
        await websocket.send_json(session_end_message)
        
        logger.info(f"Session stop handled for {session_id}")
    
    except Exception as e:
        logger.error(f"Session stop error for {session_id}: {e}")
        error_response = ErrorResponse(
            error_type=ErrorType.INTERNAL_ERROR,
            severity=ErrorSeverity.MEDIUM,
            message="Failed to stop session cleanly",
            details=str(e)
        )
        error_msg = WebSocketErrorMessage(data=error_response)
        await websocket.send_json(error_msg.model_dump(by_alias=True))


async def handle_session_status_request(session_id: str, websocket: WebSocket):
    """Handle session status requests."""
    try:
        result = await session_manager.get_session(session_id)
        
        if result.success:
            status_data = {
                "status": "active",
                "session_id": session_id,
                "memory": result.data.model_dump(by_alias=True),
                "connection_health": {
                    "connected_at": websocket_manager.connections[session_id].connected_at.isoformat() if session_id in websocket_manager.connections else None,
                    "last_heartbeat": websocket_manager.connections[session_id].last_heartbeat.isoformat() if session_id in websocket_manager.connections else None
                }
            }
        else:
            status_data = {
                "status": "not_found",
                "session_id": session_id,
                "error": result.error.model_dump(by_alias=True) if result.error else None
            }
        
        status_message = SessionStatusMessage(data=status_data)
        await websocket.send_json(status_message.model_dump(by_alias=True))
    
    except Exception as e:
        logger.error(f"Session status error for {session_id}: {e}")
        error_response = ErrorResponse(
            error_type=ErrorType.INTERNAL_ERROR,
            severity=ErrorSeverity.MEDIUM,
            message="Failed to get session status",
            details=str(e)
        )
        error_msg = WebSocketErrorMessage(data=error_response)
        await websocket.send_json(error_msg.model_dump(by_alias=True))


async def process_frame_bundle(session_id: str, data: Dict[str, Any], websocket: WebSocket):
    """Process an incoming frame bundle."""
    try:
        # Parse frame bundle with validation error handling
        try:
            frame_bundle = FrameBundle(**data)
        except ValidationError as ve:
            error_response = ErrorResponse(
                error_type=ErrorType.VALIDATION_ERROR,
                severity=ErrorSeverity.LOW,
                message="Invalid frame bundle format",
                details=str(ve)
            )
            error_msg = WebSocketErrorMessage(data=error_response)
            await websocket.send_json(error_msg.model_dump(by_alias=True))
            return
        
        # Get session memory
        result = await session_manager.get_session(session_id)
        if not result.success:
            error_msg = WebSocketErrorMessage(data=result.error)
            await websocket.send_json(error_msg.model_dump(by_alias=True))
            return
        
        session_memory = result.data
        
        # Process through agent orchestrator
        manager_response = await orchestrator.process_frame(session_memory, frame_bundle)
        
        # Update session memory
        update_result = await session_manager.update_session(session_id, manager_response.updated_memory)
        if not update_result.success:
            # Log error but don't fail the whole operation
            logger.error(f"Failed to update session {session_id}: {update_result.error}")
        
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
        error_response = ErrorResponse(
            error_type=ErrorType.INTERNAL_ERROR,
            severity=ErrorSeverity.HIGH,
            message="Frame processing failed",
            details=str(e)
        )
        error_msg = WebSocketErrorMessage(data=error_response)
        await websocket.send_json(error_msg.model_dump(by_alias=True))


@app.post("/sessions/{session_id}/upload-image")
async def upload_image(session_id: str, file: bytes):
    """Upload an image for a specific session."""
    try:
        from app.services.s3_service import s3_service
        from datetime import datetime
        
        # Upload to S3
        s3_url = await s3_service.upload_image(
            image_data=file,
            session_id=session_id,
            frame_timestamp=datetime.utcnow()
        )
        
        if s3_url:
            return {"image_url": s3_url}
        else:
            raise HTTPException(status_code=500, detail="Failed to upload image")
    
    except Exception as e:
        logger.error(f"Image upload error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


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


# Error Recovery Endpoints
@app.get("/system/health")
async def get_system_health():
    """Get comprehensive system health status."""
    from app.services.error_recovery_service import error_recovery_service
    
    try:
        health = await error_recovery_service.get_system_health()
        return health.model_dump(by_alias=True)
    except Exception as e:
        logger.error(f"Health check error: {e}")
        raise HTTPException(status_code=500, detail="Health check failed")


@app.post("/sessions/{session_id}/recovery/retry")
async def retry_session_operation(
    session_id: str,
    operation_data: Dict[str, Any]
):
    """Manually trigger retry for a failed session operation."""
    from app.services.error_recovery_service import error_recovery_service, RetryConfig
    
    try:
        operation = operation_data.get("operation")
        if not operation:
            raise HTTPException(status_code=400, detail="Operation parameter required")
        
        # Create retry configuration from request
        retry_config = RetryConfig(
            max_attempts=operation_data.get("maxAttempts", 3),
            base_delay=operation_data.get("baseDelay", 1.0),
            max_delay=operation_data.get("maxDelay", 60.0)
        )
        
        # Define operation callable based on operation type
        if operation == "get_session":
            async def operation_callable():
                result = await session_manager.get_session(session_id)
                if not result.success:
                    raise Exception(result.error.message)
                return result.data
        elif operation == "reconnect_websocket":
            async def operation_callable():
                await websocket_manager.reconnect_session(session_id)
                return {"status": "reconnected"}
        else:
            raise HTTPException(status_code=400, detail=f"Unsupported operation: {operation}")
        
        # Execute retry with recovery
        result = await error_recovery_service.retry_operation(
            operation_callable,
            operation,
            session_id,
            retry_config
        )
        
        if isinstance(result, Exception):
            raise result
        
        return {
            "success": True,
            "message": f"Operation {operation} completed successfully",
            "result": result
        }
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Manual retry error: {e}")
        error_response = await error_recovery_service.handle_error_with_recovery(
            e, f"manual_retry_{operation}", session_id, auto_recover=False
        )
        
        return JSONResponse(
            status_code=500,
            content={
                "success": False,
                "error": error_response.model_dump(by_alias=True)
            }
        )


@app.post("/sessions/{session_id}/recovery/execute-action")
async def execute_recovery_action(
    session_id: str,
    action_data: Dict[str, Any]
):
    """Execute a specific recovery action for a session."""
    from app.services.error_recovery_service import error_recovery_service, OperationContext, RecoveryAction
    
    try:
        # Parse recovery action from request
        action = RecoveryAction(**action_data)
        
        # Create operation context
        context = OperationContext(
            operation_id=f"manual_recovery_{session_id}",
            session_id=session_id,
            attempt_count=1
        )
        
        # Execute the recovery action
        success = await error_recovery_service.execute_recovery_action(action, context)
        
        return {
            "success": success,
            "message": f"Recovery action {action.action_type} {'completed' if success else 'failed'}",
            "action": action.model_dump(by_alias=True)
        }
        
    except ValidationError as e:
        raise HTTPException(status_code=400, detail=f"Invalid action data: {e}")
    except Exception as e:
        logger.error(f"Recovery action execution error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/system/circuit-breakers")
async def get_circuit_breaker_status():
    """Get status of all circuit breakers."""
    from app.services.error_recovery_service import error_recovery_service
    
    try:
        circuit_breakers = {}
        for operation, breaker in error_recovery_service.circuit_breakers.items():
            circuit_breakers[operation] = {
                "state": breaker.state,
                "failure_count": breaker.failure_count,
                "last_failure_time": breaker.last_failure_time.isoformat() if breaker.last_failure_time else None,
                "next_attempt_time": breaker.next_attempt_time.isoformat() if breaker.next_attempt_time else None,
                "failure_threshold": breaker.failure_threshold
            }
        
        return {
            "circuit_breakers": circuit_breakers,
            "total_breakers": len(circuit_breakers)
        }
        
    except Exception as e:
        logger.error(f"Circuit breaker status error: {e}")
        raise HTTPException(status_code=500, detail="Failed to get circuit breaker status")


@app.post("/system/circuit-breakers/{operation}/reset")
async def reset_circuit_breaker(operation: str):
    """Reset a specific circuit breaker."""
    from app.services.error_recovery_service import error_recovery_service
    
    try:
        if operation in error_recovery_service.circuit_breakers:
            error_recovery_service._reset_circuit_breaker(operation)
            return {
                "success": True,
                "message": f"Circuit breaker for {operation} has been reset"
            }
        else:
            raise HTTPException(status_code=404, detail=f"Circuit breaker for {operation} not found")
            
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Circuit breaker reset error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


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