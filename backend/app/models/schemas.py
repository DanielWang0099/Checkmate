from pydantic import BaseModel, Field
from typing import Optional, List, Dict, Any, Literal, Union
from datetime import datetime
from enum import Enum


class MediaType(str, Enum):
    TEXT = "text"
    TEXT_IMAGE = "text+image"
    SHORT_VIDEO = "short-video"
    LONG_VIDEO = "long-video"
    AUDIO = "audio"


class SessionType(str, Enum):
    TIME = "TIME"
    MANUAL = "MANUAL"
    ACTIVITY = "ACTIVITY"


class SourceTier(str, Enum):
    A = "A"
    B = "B"
    C = "C"


class ClaimLabel(str, Enum):
    SUPPORTED = "supported"
    CONTESTED = "contested"
    MISLEADING = "misleading"
    FALSE = "false"
    UNCERTAIN = "uncertain"


class NotificationColor(str, Enum):
    GREEN = "green"
    YELLOW = "yellow"
    RED = "red"


# 8.1 TreeSummary (client → server)
class MediaHints(BaseModel):
    has_text: bool = Field(default=True, alias="hasText")
    has_image: bool = Field(default=False, alias="hasImage")
    has_video: bool = Field(default=False, alias="hasVideo")

    class Config:
        populate_by_name = True


class TreeNode(BaseModel):
    role: str = Field(..., description="UI element role (heading, byline, caption, etc.)")
    text: str = Field(..., description="Text content of the element")


class TreeSummary(BaseModel):
    app_package: str = Field(..., alias="appPackage")
    app_readable_name: str = Field(..., alias="appReadableName")
    media_hints: MediaHints = Field(..., alias="mediaHints")
    top_nodes: List[TreeNode] = Field(..., alias="topNodes")
    url_or_channel_guess: Optional[str] = Field(None, alias="urlOrChannelGuess")
    publisher_guess: Optional[str] = Field(None, alias="publisherGuess")
    topic_guesses: List[str] = Field(default_factory=list, alias="topicGuesses")
    confidence: float = Field(..., ge=0.0, le=1.0)

    class Config:
        populate_by_name = True


# 8.2 FrameBundle (client → server, every tick)
class DeviceHints(BaseModel):
    battery: float = Field(..., ge=0.0, le=1.0, description="Battery level as fraction")
    power_saver: bool = Field(..., alias="powerSaver", description="Power saver mode enabled")

    class Config:
        populate_by_name = True


class FrameBundle(BaseModel):
    session_id: str = Field(..., alias="sessionId")
    timestamp: datetime
    tree_summary: TreeSummary = Field(..., alias="treeSummary")
    ocr_text: str = Field(..., alias="ocrText")
    has_image: bool = Field(..., alias="hasImage")
    image_ref: Optional[str] = Field(None, alias="imageRef")
    audio_transcript_delta: str = Field(..., alias="audioTranscriptDelta")
    device_hints: DeviceHints = Field(..., alias="deviceHints")

    class Config:
        populate_by_name = True


# 8.3 SessionMemory (manager-owned, persisted)
class SessionTypeConfig(BaseModel):
    type: SessionType
    minutes: Optional[int] = None


class NotificationSettings(BaseModel):
    details: bool = True
    links: bool = True


class SessionSettings(BaseModel):
    session_type: SessionTypeConfig = Field(..., alias="sessionType")
    strictness: float = Field(..., ge=0.0, le=1.0)
    notify: NotificationSettings

    class Config:
        populate_by_name = True


class TimelineEvent(BaseModel):
    t: str = Field(..., description="Time in mm:ss format")
    event: str = Field(..., description="Description of what happened")


class CurrentActivity(BaseModel):
    id: str
    app: str
    media: MediaType
    desc: str = Field(..., alias="description")

    class Config:
        populate_by_name = True


class PastContent(BaseModel):
    app: str
    media: MediaType
    desc: str = Field(..., alias="description")
    has_video: bool = Field(..., alias="hasVideo")
    has_audio: bool = Field(..., alias="hasAudio")
    publisher: Optional[str] = None
    topic: Optional[str] = None
    context_notes: str = Field(..., alias="contextNotes")

    class Config:
        populate_by_name = True


class Source(BaseModel):
    url: str
    tier: SourceTier
    title: Optional[str] = None
    direct_quote_match: bool = Field(default=False, alias="directQuoteMatch")

    class Config:
        populate_by_name = True


class LastClaimChecked(BaseModel):
    claim: str
    status: ClaimLabel
    sources: List[Source] = Field(default_factory=list)


class SessionMemory(BaseModel):
    settings: SessionSettings
    timeline: List[TimelineEvent] = Field(default_factory=list)
    current_activity: Optional[CurrentActivity] = Field(None, alias="currentActivity")
    past_contents: Dict[str, PastContent] = Field(default_factory=dict, alias="pastContents")
    last_claims_checked: List[LastClaimChecked] = Field(default_factory=list, alias="lastClaimsChecked")

    class Config:
        populate_by_name = True


# 8.4 FactCheckResult (media agent → manager)
class Claim(BaseModel):
    text: str
    label: ClaimLabel
    confidence: float = Field(..., ge=0.0, le=1.0)
    severity: float = Field(..., ge=0.0, le=1.0)
    sources: List[Source] = Field(default_factory=list)


class FactCheckResult(BaseModel):
    """Result from media agent fact-checking analysis."""
    claims: List[Claim] = Field(default_factory=list)
    notes: str = ""
    summary: str = ""  # ✅ CRITICAL FIX: Added missing summary field used by orchestrator
    sources: List[Source] = Field(default_factory=list)  # ✅ CRITICAL FIX: Added missing sources field used by orchestrator
    # REMOVED: routing field - not used by agents, was causing confusion


# 8.5 NotificationPayload (manager → client)
class NotificationPayload(BaseModel):
    color: NotificationColor
    short_text: str = Field(..., alias="shortText")
    details: Optional[str] = None
    sources: List[Source] = Field(default_factory=list)
    confidence: float = Field(..., ge=0.0, le=1.0)
    severity: float = Field(..., ge=0.0, le=1.0)
    should_notify: bool = Field(..., alias="shouldNotify")

    class Config:
        populate_by_name = True


# Agent Context Models (Polymorphic) - FIXED WITH DISCRIMINATOR
from typing import Literal

class AgentContext(BaseModel):
    """Base agent context for text-only fact-checking."""
    context_type: Literal["text"] = Field(default="text", alias="contextType")
    context: Dict[str, Any]
    ocr_text: str = Field(..., alias="ocrText")
    hints: Dict[str, Any] = Field(default_factory=dict)
    
    class Config:
        populate_by_name = True


class TextImageAgentContext(AgentContext):
    """Agent context for text+image fact-checking."""
    context_type: Literal["text_image"] = Field(default="text_image", alias="contextType")
    image_ref: str = Field(..., alias="imageRef")
    
    class Config:
        populate_by_name = True


class VideoAgentContext(AgentContext):
    """Agent context for video fact-checking (both short and long video)."""
    context_type: Literal["video"] = Field(default="video", alias="contextType")
    transcript_delta: str = Field(..., alias="transcriptDelta")
    
    class Config:
        populate_by_name = True


# Fixed Union type with discriminator for proper polymorphic handling
from pydantic import Field, BaseModel
from typing import Union, Annotated

AgentContextUnion = Annotated[
    Union[
        TextImageAgentContext,
        VideoAgentContext, 
        AgentContext
    ],
    Field(discriminator='context_type')
]


# Error Response Models
class ErrorType(str, Enum):
    VALIDATION_ERROR = "validation_error"
    SESSION_NOT_FOUND = "session_not_found"
    SERVICE_UNAVAILABLE = "service_unavailable"
    TOOL_EXECUTION_ERROR = "tool_execution_error"
    RATE_LIMIT_EXCEEDED = "rate_limit_exceeded"
    NETWORK_ERROR = "network_error"
    INTERNAL_ERROR = "internal_error"
    PRIVACY_BLOCK = "privacy_block"


class ErrorSeverity(str, Enum):
    LOW = "low"          # User can continue, fallback available
    MEDIUM = "medium"    # Feature degraded but session continues
    HIGH = "high"        # Session should be paused/stopped
    CRITICAL = "critical" # Immediate termination required


class ErrorResponse(BaseModel):
    """Standard error response for API endpoints."""
    error_type: ErrorType = Field(..., alias="errorType")
    severity: ErrorSeverity
    message: str
    details: Optional[str] = None
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    correlation_id: Optional[str] = Field(None, alias="correlationId")
    retry_after: Optional[int] = Field(None, alias="retryAfter")  # seconds
    
    class Config:
        populate_by_name = True


class ValidationErrorField(BaseModel):
    """Individual field validation error."""
    field: str
    message: str
    invalid_value: Optional[Any] = Field(None, alias="invalidValue")
    
    class Config:
        populate_by_name = True


class ValidationErrorResponse(ErrorResponse):
    """Validation error with field-specific details."""
    error_type: Literal[ErrorType.VALIDATION_ERROR] = Field(default=ErrorType.VALIDATION_ERROR, alias="errorType")
    field_errors: List[ValidationErrorField] = Field(..., alias="fieldErrors")
    
    class Config:
        populate_by_name = True


# Enhanced Error Recovery Models
class ErrorContext(BaseModel):
    """Additional context for error diagnosis and recovery."""
    operation: str = Field(..., description="Operation that failed")
    session_id: Optional[str] = Field(None, alias="sessionId", description="Associated session ID")
    retry_count: int = Field(0, alias="retryCount", description="Number of retry attempts made")
    last_success_time: Optional[datetime] = Field(None, alias="lastSuccessTime", description="Last successful operation time")
    device_state: Optional[Dict[str, Any]] = Field(None, alias="deviceState", description="Device state at error time")
    network_state: Optional[str] = Field(None, alias="networkState", description="Network connectivity state")
    
    class Config:
        populate_by_name = True

class RecoveryAction(BaseModel):
    """Suggested recovery action for an error."""
    action_type: str = Field(..., alias="actionType", description="Type of recovery action")
    description: str = Field(..., description="Human-readable description")
    auto_executable: bool = Field(False, alias="autoExecutable", description="Can be executed automatically")
    priority: int = Field(1, description="Priority level (1-5, 5 being highest)")
    estimated_duration: Optional[int] = Field(None, alias="estimatedDuration", description="Estimated time in seconds")
    
    class Config:
        populate_by_name = True

class EnhancedErrorResponse(ErrorResponse):
    """Enhanced error response with recovery context."""
    context: Optional[ErrorContext] = None
    recovery_actions: List[RecoveryAction] = Field(default_factory=list, alias="recoveryActions")
    user_message: Optional[str] = Field(None, alias="userMessage", description="User-friendly error message")
    
    class Config:
        populate_by_name = True
    
    @classmethod
    def create_with_recovery(
        cls,
        error_type: ErrorType,
        severity: ErrorSeverity,
        message: str,
        operation: str,
        session_id: Optional[str] = None,
        recovery_actions: Optional[List[RecoveryAction]] = None,
        user_message: Optional[str] = None,
        retry_count: int = 0
    ) -> "EnhancedErrorResponse":
        """Create error response with recovery context."""
        context = ErrorContext(
            operation=operation,
            session_id=session_id,
            retry_count=retry_count,
            last_success_time=None
        )
        
        return cls(
            error_type=error_type,
            severity=severity,
            message=message,
            context=context,
            recovery_actions=recovery_actions or cls._generate_recovery_actions(error_type, operation),
            user_message=user_message or cls._generate_user_message(error_type, operation)
        )
    
    @staticmethod
    def _generate_recovery_actions(error_type: ErrorType, operation: str) -> List[RecoveryAction]:
        """Generate default recovery actions for error types."""
        actions = []
        
        if error_type == ErrorType.NETWORK_ERROR:
            actions.extend([
                RecoveryAction(
                    action_type="retry_with_backoff",
                    description="Retry the operation with exponential backoff",
                    auto_executable=True,
                    priority=5,
                    estimated_duration=30
                ),
                RecoveryAction(
                    action_type="check_connectivity",
                    description="Check network connectivity",
                    auto_executable=True,
                    priority=4,
                    estimated_duration=5
                ),
                RecoveryAction(
                    action_type="switch_to_offline_mode",
                    description="Switch to offline mode",
                    auto_executable=True,
                    priority=2,
                    estimated_duration=1
                )
            ])
        elif error_type == ErrorType.AUTHENTICATION_ERROR:
            actions.extend([
                RecoveryAction(
                    action_type="refresh_credentials",
                    description="Refresh authentication credentials",
                    auto_executable=True,
                    priority=5,
                    estimated_duration=10
                ),
                RecoveryAction(
                    action_type="re_authenticate",
                    description="Re-authenticate with the service",
                    auto_executable=False,
                    priority=4,
                    estimated_duration=60
                )
            ])
        elif error_type == ErrorType.RATE_LIMIT_ERROR:
            actions.append(
                RecoveryAction(
                    action_type="exponential_backoff",
                    description="Wait and retry with exponential backoff",
                    auto_executable=True,
                    priority=5,
                    estimated_duration=120
                )
            )
        elif error_type == ErrorType.SERVICE_UNAVAILABLE:
            actions.extend([
                RecoveryAction(
                    action_type="health_check",
                    description="Check service health",
                    auto_executable=True,
                    priority=5,
                    estimated_duration=10
                ),
                RecoveryAction(
                    action_type="fallback_service",
                    description="Switch to fallback service",
                    auto_executable=True,
                    priority=3,
                    estimated_duration=5
                )
            ])
        
        # Default action for all error types
        if not actions:
            actions.append(
                RecoveryAction(
                    action_type="manual_retry",
                    description="Manually retry the operation",
                    auto_executable=False,
                    priority=3,
                    estimated_duration=30
                )
            )
        
        return actions
    
    @staticmethod
    def _generate_user_message(error_type: ErrorType, operation: str) -> str:
        """Generate user-friendly error messages."""
        messages = {
            ErrorType.NETWORK_ERROR: f"Connection issue during {operation}. Please check your internet connection.",
            ErrorType.AUTHENTICATION_ERROR: "Authentication failed. Please check your credentials.",
            ErrorType.VALIDATION_ERROR: f"Invalid data provided for {operation}. Please check your input.",
            ErrorType.RESOURCE_NOT_FOUND: f"Requested resource not found during {operation}.",
            ErrorType.RATE_LIMIT_ERROR: "Too many requests. Please wait a moment and try again.",
            ErrorType.INTERNAL_ERROR: f"Internal error during {operation}. Our team has been notified.",
            ErrorType.SERVICE_UNAVAILABLE: "Service temporarily unavailable. We're working to restore it.",
            ErrorType.PERMISSION_ERROR: f"Permission denied for {operation}. Please check your access rights."
        }
        return messages.get(error_type, f"An error occurred during {operation}. Please try again.")

class ServiceDegradationMode(str, Enum):
    """Service degradation modes for graceful failure handling."""
    FULL_SERVICE = "full_service"
    REDUCED_FEATURES = "reduced_features"
    OFFLINE_MODE = "offline_mode"
    EMERGENCY_MODE = "emergency_mode"

class SystemHealth(BaseModel):
    """System health status for monitoring and recovery."""
    service_mode: ServiceDegradationMode = Field(default=ServiceDegradationMode.FULL_SERVICE, alias="serviceMode")
    redis_available: bool = Field(True, alias="redisAvailable")
    bedrock_available: bool = Field(True, alias="bedrockAvailable")
    search_available: bool = Field(True, alias="searchAvailable")
    last_health_check: datetime = Field(default_factory=datetime.utcnow, alias="lastHealthCheck")
    error_rate: float = Field(0.0, ge=0.0, le=1.0, alias="errorRate")
    recovery_in_progress: bool = Field(False, alias="recoveryInProgress")
    
    class Config:
        populate_by_name = True


class SessionOperationResult(BaseModel):
    """Result wrapper for session operations with error handling."""
    success: bool
    data: Optional[Any] = None
    error: Optional[ErrorResponse] = None
    
    @classmethod
    def success_result(cls, data: Any = None) -> "SessionOperationResult":
        return cls(success=True, data=data)
    
    @classmethod
    def error_result(cls, error: ErrorResponse) -> "SessionOperationResult":
        return cls(success=False, error=error)


# Manager Response Models
class ManagerResponse(BaseModel):
    updated_memory: SessionMemory = Field(..., alias="updatedMemory")
    route: Union[MediaType, Literal["none"]]
    agent_context: Optional[AgentContextUnion] = Field(None, alias="agentContext")
    end_session: bool = Field(..., alias="endSession")
    notifications: List[NotificationPayload] = Field(default_factory=list)

    class Config:
        populate_by_name = True


# WebSocket Message Types
class WSMessageType(str, Enum):
    FRAME_BUNDLE = "frame_bundle"
    NOTIFICATION = "notification"
    SESSION_END = "session_end"
    SESSION_START = "session_start"
    SESSION_STOP = "session_stop"
    SESSION_STATUS = "session_status"
    ERROR = "error"
    PING = "ping"
    PONG = "pong"
    HEARTBEAT = "heartbeat"


class WSMessage(BaseModel):
    type: WSMessageType
    data: Dict[str, Any]
    timestamp: datetime = Field(default_factory=datetime.utcnow)


class WebSocketErrorMessage(BaseModel):
    """WebSocket error message following WSMessage structure."""
    type: Literal[WSMessageType.ERROR] = WSMessageType.ERROR
    data: ErrorResponse
    timestamp: datetime = Field(default_factory=datetime.utcnow)


class SessionControlMessage(BaseModel):
    """Message for session start/stop control."""
    type: Union[
        Literal[WSMessageType.SESSION_START], 
        Literal[WSMessageType.SESSION_STOP]
    ]
    data: Dict[str, Any] = Field(default_factory=dict)  # Settings for start, empty for stop
    timestamp: datetime = Field(default_factory=datetime.utcnow)


class SessionStatusMessage(BaseModel):
    """Message for session status updates."""
    type: Literal[WSMessageType.SESSION_STATUS] = WSMessageType.SESSION_STATUS
    data: Dict[str, Any]  # Contains session state, memory summary, etc.
    timestamp: datetime = Field(default_factory=datetime.utcnow)


class HeartbeatMessage(BaseModel):
    """Heartbeat message for connection health monitoring."""
    type: Literal[WSMessageType.HEARTBEAT] = WSMessageType.HEARTBEAT
    data: Dict[str, Any] = Field(default_factory=lambda: {"status": "alive"})
    timestamp: datetime = Field(default_factory=datetime.utcnow)


# Tool Function Models
class WebSearchResult(BaseModel):
    title: str
    url: str
    snippet: str
    published_date: Optional[str] = None
    tier: Optional[str] = Field(default="B", description="Source reliability tier (A/B/C)")


class ReverseImageSearchResult(BaseModel):
    similar_images: List[str] = Field(default_factory=list)
    best_guess: Optional[str] = None
    matching_pages: List[WebSearchResult] = Field(default_factory=list)


class YouTubeMetadata(BaseModel):
    title: str
    channel_name: str = Field(..., alias="channelName")
    channel_id: str = Field(..., alias="channelId")
    description: str
    tags: List[str] = Field(default_factory=list)
    category: Optional[str] = None
    published_at: Optional[datetime] = Field(None, alias="publishedAt")
    view_count: Optional[int] = Field(None, alias="viewCount")
    like_count: Optional[int] = Field(None, alias="likeCount")

    class Config:
        populate_by_name = True