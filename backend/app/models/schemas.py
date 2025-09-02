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
    claims: List[Claim] = Field(default_factory=list)
    notes: str = ""
    routing: Dict[str, Any] = Field(default_factory=dict)


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


# Agent Context Models
class AgentContext(BaseModel):
    context: Dict[str, Any]
    ocr_text: str = Field(..., alias="ocrText")
    hints: Dict[str, Any] = Field(default_factory=dict)

    class Config:
        populate_by_name = True


class TextImageAgentContext(AgentContext):
    image_ref: str = Field(..., alias="imageRef")

    class Config:
        populate_by_name = True


class VideoAgentContext(AgentContext):
    transcript_delta: str = Field(..., alias="transcriptDelta")

    class Config:
        populate_by_name = True


# Manager Response Models
class ManagerResponse(BaseModel):
    updated_memory: SessionMemory = Field(..., alias="updatedMemory")
    route: Union[MediaType, Literal["none"]]
    agent_context: Optional[AgentContext] = Field(None, alias="agentContext")
    end_session: bool = Field(..., alias="endSession")
    notifications: List[NotificationPayload] = Field(default_factory=list)

    class Config:
        populate_by_name = True


# WebSocket Message Types
class WSMessageType(str, Enum):
    FRAME_BUNDLE = "frame_bundle"
    NOTIFICATION = "notification"
    SESSION_END = "session_end"
    ERROR = "error"
    PING = "ping"
    PONG = "pong"


class WSMessage(BaseModel):
    type: WSMessageType
    data: Dict[str, Any]
    timestamp: datetime = Field(default_factory=datetime.utcnow)


# Tool Function Models
class WebSearchResult(BaseModel):
    title: str
    url: str
    snippet: str
    published_date: Optional[str] = None


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