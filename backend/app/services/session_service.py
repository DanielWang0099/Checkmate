"""Session management service for handling active fact-checking sessions."""

import json
import asyncio
from datetime import datetime, timedelta
from typing import Dict, Optional, List
import redis.asyncio as aioredis
from app.core.config import settings
from app.models.schemas import (
    SessionMemory, FrameBundle, SessionSettings, SessionType,
    NotificationSettings, SessionTypeConfig, ErrorResponse, ErrorType, 
    ErrorSeverity, SessionOperationResult, WSMessageType, HeartbeatMessage,
    EnhancedErrorResponse
)
from .error_recovery_service import error_recovery_service, RetryConfig


class SessionManager:
    """Manages active fact-checking sessions."""
    
    def __init__(self):
        self.redis = None
        self.sessions: Dict[str, SessionMemory] = {}  # In-memory fallback
    
    async def initialize(self):
        """Initialize Redis connection."""
        try:
            self.redis = aioredis.from_url(settings.redis_url)
            await self.redis.ping()
            print("Redis connection established")
        except Exception as e:
            print(f"Redis connection failed, using in-memory storage: {e}")
            self.redis = None
    
    async def create_session(
        self,
        session_id: str,
        settings_data: Dict[str, any]
    ) -> SessionMemory:
        """Create a new fact-checking session with error recovery."""
        
        async def _create_session_operation():
            # Parse settings
            session_settings = SessionSettings(
                sessionType=SessionTypeConfig(**settings_data.get("sessionType", {"type": "MANUAL"})),
                strictness=settings_data.get("strictness", 0.5),
                notify=NotificationSettings(**settings_data.get("notify", {"details": True, "links": True}))
            )
            
            # Create session memory
            session_memory = SessionMemory(
                settings=session_settings,
                timeline=[],
                currentActivity=None,
                pastContents={},
                lastClaimsChecked=[]
            )
            
            # Store session
            await self._store_session(session_id, session_memory)
            
            return session_memory
        
        # Use error recovery service for robust session creation
        retry_config = RetryConfig(max_attempts=3, base_delay=1.0)
        result = await error_recovery_service.retry_operation(
            _create_session_operation,
            "create_session",
            session_id,
            retry_config
        )
        
        if isinstance(result, EnhancedErrorResponse):
            # If all retries failed, still attempt to create session in memory
            session_settings = SessionSettings(
                sessionType=SessionTypeConfig(**settings_data.get("sessionType", {"type": "MANUAL"})),
                strictness=settings_data.get("strictness", 0.5),
                notify=NotificationSettings(**settings_data.get("notify", {"details": True, "links": True}))
            )
            
            session_memory = SessionMemory(
                settings=session_settings,
                timeline=[],
                currentActivity=None,
                pastContents={},
                lastClaimsChecked=[]
            )
            
            # Store in memory as fallback
            self.sessions[session_id] = session_memory
            return session_memory
        
        return result
    
    async def get_session(self, session_id: str) -> SessionOperationResult:
        """Retrieve session memory with error recovery."""
        
        async def _get_session_operation():
            if self.redis:
                data = await self.redis.get(f"session:{session_id}")
                if data:
                    session_data = json.loads(data)
                    session_memory = SessionMemory(**session_data)
                    return session_memory
                else:
                    # Try fallback to in-memory
                    session_memory = self.sessions.get(session_id)
                    if session_memory:
                        return session_memory
                    else:
                        raise Exception(f"Session {session_id} not found")
            else:
                # Use in-memory storage
                session_memory = self.sessions.get(session_id)
                if session_memory:
                    return session_memory
                else:
                    raise Exception(f"Session {session_id} not found")
        
        try:
            result = await error_recovery_service.retry_operation(
                _get_session_operation,
                "get_session",
                session_id,
                RetryConfig(max_attempts=2, base_delay=0.5)
            )
            
            if isinstance(result, EnhancedErrorResponse):
                return SessionOperationResult.error_result(
                    ErrorResponse(
                        error_type=ErrorType.SESSION_NOT_FOUND,
                        severity=ErrorSeverity.MEDIUM,
                        message=f"Session {session_id} not found after recovery attempts"
                    )
                )
            
            return SessionOperationResult.success_result(result)
            
        except Exception as e:
            error_response = await error_recovery_service.handle_error_with_recovery(
                e, "get_session", session_id
            )
            return SessionOperationResult.error_result(
                ErrorResponse(
                    error_type=error_response.error_type,
                    severity=error_response.severity,
                    message=error_response.message
                )
            )
    
    async def update_session(self, session_id: str, session_memory: SessionMemory) -> SessionOperationResult:
        """Update session memory with proper error handling."""
        try:
            await self._store_session(session_id, session_memory)
            return SessionOperationResult.success_result()
        except Exception as e:
            return SessionOperationResult.error_result(
                ErrorResponse(
                    error_type=ErrorType.SERVICE_UNAVAILABLE,
                    severity=ErrorSeverity.HIGH,
                    message=f"Failed to update session {session_id}",
                    details=str(e)
                )
            )
    
    async def _store_session(self, session_id: str, session_memory: SessionMemory):
        """Store session memory in Redis or in-memory."""
        if self.redis:
            try:
                data = session_memory.model_dump_json(by_alias=True)
                await self.redis.setex(
                    f"session:{session_id}",
                    timedelta(hours=settings.session_ttl_hours),
                    data
                )
                return
            except Exception as e:
                print(f"Redis store error: {e}")
        
        # Fallback to in-memory
        self.sessions[session_id] = session_memory
    
    async def delete_session(self, session_id: str):
        """Delete a session."""
        if self.redis:
            try:
                await self.redis.delete(f"session:{session_id}")
            except:
                pass
        
        self.sessions.pop(session_id, None)
    
    async def list_active_sessions(self) -> List[str]:
        """List all active session IDs."""
        if self.redis:
            try:
                keys = await self.redis.keys("session:*")
                return [key.decode().replace("session:", "") for key in keys]
            except:
                pass
        
        return list(self.sessions.keys())
    
    async def cleanup_expired_sessions(self):
        """Clean up expired sessions (for in-memory storage)."""
        # Redis handles TTL automatically, this is for in-memory cleanup
        current_time = datetime.utcnow()
        expired_sessions = []
        
        for session_id, memory in self.sessions.items():
            try:
                # Check if session should expire based on settings
                if memory.settings.sessionType.type == SessionType.TIME:
                    if memory.settings.sessionType.minutes:
                        # Calculate session duration from timeline
                        if memory.timeline:
                            first_event_time = memory.timeline[0].get("timestamp")
                            if first_event_time:
                                # Parse timestamp and check if expired
                                session_duration = (current_time - first_event_time).total_seconds() / 60
                                if session_duration > memory.settings.sessionType.minutes:
                                    expired_sessions.append(session_id)
                
                # Check for inactive sessions (no activity for 24 hours)
                if memory.timeline:
                    last_activity = memory.timeline[-1].get("timestamp", current_time)
                    inactive_hours = (current_time - last_activity).total_seconds() / 3600
                    if inactive_hours > 24:  # 24 hours of inactivity
                        expired_sessions.append(session_id)
                        
            except Exception as e:
                print(f"Error checking session expiry for {session_id}: {e}")
                # Add problematic sessions to expired list
                expired_sessions.append(session_id)
        
        # Clean up expired sessions
        for session_id in expired_sessions:
            await self.delete_session(session_id)
            print(f"Cleaned up expired session: {session_id}")

    def should_end_session(self, session_memory: SessionMemory, frame_bundle: FrameBundle) -> bool:
        """Determine if session should end based on settings and activity."""
        settings = session_memory.settings
        
        if settings.sessionType.type == SessionType.MANUAL:
            return False  # Only end manually
        
        if settings.sessionType.type == SessionType.TIME:
            # Check if time limit exceeded
            if settings.sessionType.minutes and session_memory.timeline:
                try:
                    # Get first timeline entry timestamp
                    first_event = session_memory.timeline[0]
                    start_time = first_event.get("timestamp")
                    
                    if start_time:
                        current_time = frame_bundle.timestamp
                        # Calculate session duration in minutes
                        duration_minutes = (current_time - start_time).total_seconds() / 60
                        
                        if duration_minutes >= settings.sessionType.minutes:
                            print(f"Session time limit reached: {duration_minutes:.1f}/{settings.sessionType.minutes} minutes")
                            return True
                            
                except Exception as e:
                    print(f"Error checking time limit: {e}")
        
        if settings.sessionType.type == SessionType.ACTIVITY:
            # Activity-based ending logic
            if session_memory.currentActivity and session_memory.timeline:
                try:
                    current_time = frame_bundle.timestamp
                    
                    # Check for stable new activity ≥90s and no new fact-checkable content in last 60s
                    recent_timeline = [
                        event for event in session_memory.timeline[-10:]  # Last 10 events
                        if (current_time - event.get("timestamp", current_time)).total_seconds() <= 90
                    ]
                    
                    # Check if current activity is stable (same for ≥90 seconds)
                    current_app = frame_bundle.treeSummary.appPackage
                    if session_memory.currentActivity.get("app") != current_app:
                        # Activity changed, update current activity
                        session_memory.currentActivity = {
                            "app": current_app,
                            "start_time": current_time,
                            "fact_checkable_content": False
                        }
                        return False
                    
                    # Check if current activity has been stable for ≥90 seconds
                    activity_start = session_memory.currentActivity.get("start_time")
                    if activity_start:
                        activity_duration = (current_time - activity_start).total_seconds()
                        
                        if activity_duration >= 90:
                            # Check if there's been fact-checkable content in last 60 seconds
                            has_recent_content = any(
                                event.get("has_fact_checkable_content", False)
                                for event in recent_timeline
                                if (current_time - event.get("timestamp", current_time)).total_seconds() <= 60
                            )
                            
                            if not has_recent_content:
                                print(f"Activity-based session end: stable activity for {activity_duration:.1f}s, no fact-checkable content in 60s")
                                return True
                                
                except Exception as e:
                    print(f"Error checking activity-based ending: {e}")
        
        return False


class WebSocketConnection:
    """Represents a WebSocket connection with health monitoring."""
    
    def __init__(self, websocket, session_id: str):
        self.websocket = websocket
        self.session_id = session_id
        self.connected_at = datetime.utcnow()
        self.last_heartbeat = datetime.utcnow()
        self.is_alive = True
        
    async def send_json(self, message: Dict[str, any]):
        """Send JSON message with error handling."""
        try:
            await self.websocket.send_json(message)
            return True
        except Exception as e:
            print(f"Failed to send message to {self.session_id}: {e}")
            self.is_alive = False
            return False
    
    def update_heartbeat(self):
        """Update last heartbeat timestamp."""
        self.last_heartbeat = datetime.utcnow()
    
    def is_stale(self, timeout_seconds: int = 30) -> bool:
        """Check if connection is stale (no heartbeat for timeout period)."""
        return (datetime.utcnow() - self.last_heartbeat).total_seconds() > timeout_seconds


class WebSocketManager:
    """Enhanced WebSocket manager with connection health monitoring."""
    
    def __init__(self):
        self.connections: Dict[str, WebSocketConnection] = {}
        self.heartbeat_interval = 10  # seconds
        self.connection_timeout = 30  # seconds
        self._heartbeat_task = None
    
    async def start_heartbeat_monitor(self):
        """Start the heartbeat monitoring task."""
        if self._heartbeat_task is None:
            self._heartbeat_task = asyncio.create_task(self._heartbeat_loop())
    
    async def stop_heartbeat_monitor(self):
        """Stop the heartbeat monitoring task."""
        if self._heartbeat_task:
            self._heartbeat_task.cancel()
            try:
                await self._heartbeat_task
            except asyncio.CancelledError:
                pass
            self._heartbeat_task = None
    
    async def _heartbeat_loop(self):
        """Background task for connection health monitoring."""
        while True:
            try:
                await asyncio.sleep(self.heartbeat_interval)
                await self._check_connections()
                await self._send_heartbeats()
            except asyncio.CancelledError:
                break
            except Exception as e:
                print(f"Heartbeat loop error: {e}")
    
    async def _check_connections(self):
        """Check and cleanup stale connections."""
        stale_sessions = []
        
        for session_id, connection in self.connections.items():
            if not connection.is_alive or connection.is_stale(self.connection_timeout):
                stale_sessions.append(session_id)
        
        for session_id in stale_sessions:
            await self.disconnect(session_id)
            print(f"Cleaned up stale connection for session {session_id}")
    
    async def _send_heartbeats(self):
        """Send heartbeat messages to all connections."""
        heartbeat_msg = HeartbeatMessage()
        
        for session_id, connection in list(self.connections.items()):
            success = await connection.send_json(heartbeat_msg.model_dump(by_alias=True))
            if not success:
                await self.disconnect(session_id)
    
    async def connect(self, session_id: str, websocket):
        """Register a WebSocket connection for a session."""
        connection = WebSocketConnection(websocket, session_id)
        self.connections[session_id] = connection
        
        # Start heartbeat monitor if this is the first connection
        if len(self.connections) == 1:
            await self.start_heartbeat_monitor()
        
        print(f"WebSocket connected for session {session_id}")
    
    async def disconnect(self, session_id: str):
        """Remove WebSocket connection."""
        if session_id in self.connections:
            del self.connections[session_id]
            print(f"WebSocket disconnected for session {session_id}")
        
        # Stop heartbeat monitor if no connections remain
        if len(self.connections) == 0:
            await self.stop_heartbeat_monitor()
    
    def update_heartbeat(self, session_id: str):
        """Update heartbeat for a specific session."""
        if session_id in self.connections:
            self.connections[session_id].update_heartbeat()
    
    async def send_to_session(self, session_id: str, message: Dict[str, any]):
        """Send message to a specific session."""
        if session_id in self.connections:
            connection = self.connections[session_id]
            success = await connection.send_json(message)
            if not success:
                await self.disconnect(session_id)
            return success
        return False
    
    async def broadcast_to_all(self, message: Dict[str, any]):
        """Broadcast message to all connected sessions."""
        disconnected = []
        
        for session_id, connection in self.connections.items():
            success = await connection.send_json(message)
            if not success:
                disconnected.append(session_id)
        
        # Clean up dead connections
        for session_id in disconnected:
            await self.disconnect(session_id)
    
    def get_connection_count(self) -> int:
        """Get number of active connections."""
        return len(self.connections)
    
    def get_session_ids(self) -> List[str]:
        """Get list of connected session IDs."""
        return list(self.connections.keys())


# Global instances
session_manager = SessionManager()
websocket_manager = WebSocketManager()
