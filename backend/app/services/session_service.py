"""Session management service for handling active fact-checking sessions."""

import json
import asyncio
from datetime import datetime, timedelta
from typing import Dict, Optional, List
import redis.asyncio as aioredis
from app.core.config import settings
from app.models.schemas import (
    SessionMemory, FrameBundle, SessionSettings, SessionType,
    NotificationSettings, SessionTypeConfig
)


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
        """Create a new fact-checking session."""
        
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
    
    async def get_session(self, session_id: str) -> Optional[SessionMemory]:
        """Retrieve session memory."""
        if self.redis:
            try:
                data = await self.redis.get(f"session:{session_id}")
                if data:
                    session_data = json.loads(data)
                    return SessionMemory(**session_data)
            except Exception as e:
                print(f"Redis get error: {e}")
        
        # Fallback to in-memory
        return self.sessions.get(session_id)
    
    async def update_session(self, session_id: str, session_memory: SessionMemory):
        """Update session memory."""
        await self._store_session(session_id, session_memory)
    
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
            # Check if session should expire based on settings
            if memory.settings.session_type.type == SessionType.TIME:
                if memory.settings.session_type.minutes:
                    # Check if session has exceeded time limit
                    # This would require tracking session start time
                    pass
        
        for session_id in expired_sessions:
            await self.delete_session(session_id)
    
    def should_end_session(self, session_memory: SessionMemory, frame_bundle: FrameBundle) -> bool:
        """Determine if session should end based on settings and activity."""
        settings = session_memory.settings
        
        if settings.session_type.type == SessionType.MANUAL:
            return False  # Only end manually
        
        if settings.session_type.type == SessionType.TIME:
            # Check if time limit exceeded
            if settings.session_type.minutes:
                # Calculate session duration from timeline
                if session_memory.timeline:
                    first_event = session_memory.timeline[0]
                    current_time = frame_bundle.timestamp
                    # Parse first event time and compare
                    # This would need proper time tracking implementation
                    pass
        
        if settings.session_type.type == SessionType.ACTIVITY:
            # Activity-based ending logic
            if session_memory.current_activity and session_memory.timeline:
                # Check for stable new activity ≥90s and no new fact-checkable content in last 60s
                # This would require more sophisticated analysis
                pass
        
        return False


class WebSocketManager:
    """Manages WebSocket connections for real-time communication."""
    
    def __init__(self):
        self.connections: Dict[str, any] = {}  # session_id -> websocket
    
    async def connect(self, session_id: str, websocket):
        """Register a WebSocket connection for a session."""
        self.connections[session_id] = websocket
    
    async def disconnect(self, session_id: str):
        """Remove WebSocket connection."""
        self.connections.pop(session_id, None)
    
    async def send_to_session(self, session_id: str, message: Dict[str, any]):
        """Send message to a specific session."""
        if session_id in self.connections:
            websocket = self.connections[session_id]
            try:
                await websocket.send_json(message)
            except Exception as e:
                print(f"Failed to send message to {session_id}: {e}")
                # Remove dead connection
                await self.disconnect(session_id)
    
    async def broadcast_to_all(self, message: Dict[str, any]):
        """Broadcast message to all connected sessions."""
        disconnected = []
        
        for session_id, websocket in self.connections.items():
            try:
                await websocket.send_json(message)
            except:
                disconnected.append(session_id)
        
        # Clean up dead connections
        for session_id in disconnected:
            await self.disconnect(session_id)


# Global instances
session_manager = SessionManager()
websocket_manager = WebSocketManager()
