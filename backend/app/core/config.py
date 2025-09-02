from pydantic_settings import BaseSettings
from typing import Optional
import os


class Settings(BaseSettings):
    """Application settings loaded from environment variables."""
    
    # AWS Configuration
    aws_region: str = "us-east-1"
    aws_access_key_id: Optional[str] = None
    aws_secret_access_key: Optional[str] = None
    aws_session_token: Optional[str] = None
    
    # Bedrock Model Configuration
    bedrock_manager_model: str = "anthropic.claude-3-5-sonnet-20241022-v2:0"
    bedrock_agent_model: str = "anthropic.claude-3-5-sonnet-20241022-v2:0"
    bedrock_embeddings_model: str = "amazon.titan-embed-text-v1"
    
    # Google Cloud Configuration
    google_cloud_project_id: Optional[str] = None
    google_application_credentials: Optional[str] = None
    
    # API Keys
    youtube_api_key: Optional[str] = None
    serpapi_api_key: Optional[str] = None
    bing_search_api_key: Optional[str] = None
    bing_search_endpoint: str = "https://api.bing.microsoft.com/v7.0/search"
    google_custom_search_engine_id: Optional[str] = None
    google_custom_search_api_key: Optional[str] = None
    
    # Database Configuration
    redis_url: str = "redis://localhost:6379/0"
    mongodb_url: str = "mongodb://localhost:27017/checkmate"
    
    # Session Configuration
    session_ttl_hours: int = 24
    max_concurrent_sessions: int = 100
    
    # Security
    secret_key: str = "your-secret-key-change-in-production"
    algorithm: str = "HS256"
    access_token_expire_minutes: int = 30
    
    # S3 Configuration
    s3_bucket_name: str = "checkmate-temp-images"
    s3_region: str = "us-east-1"
    
    # Rate Limiting
    rate_limit_per_minute: int = 60
    rate_limit_burst: int = 10
    
    # Feature Flags
    enable_audio_processing: bool = True
    enable_image_analysis: bool = True
    enable_video_analysis: bool = True
    
    # Performance Tuning
    max_ocr_text_length: int = 1200
    max_audio_chunk_seconds: int = 10
    max_image_size_mb: int = 5
    
    # Logging
    log_level: str = "INFO"
    structured_logging: bool = True
    
    class Config:
        env_file = ".env"
        case_sensitive = False


# Global settings instance
settings = Settings()


# Strictness policy thresholds
STRICTNESS_THRESHOLDS = {
    0.0: {"min_confidence": 0.90, "min_sources": 1, "tier_c_allowed": False, "color_bias": "conservative"},
    0.2: {"min_confidence": 0.80, "min_sources": 1, "tier_c_allowed": False, "color_bias": "sparing"},
    0.4: {"min_confidence": 0.75, "min_sources": 1, "tier_c_allowed": False, "color_bias": "balanced"},
    0.5: {"min_confidence": 0.70, "min_sources": 1, "tier_c_allowed": True, "color_bias": "balanced"},
    0.6: {"min_confidence": 0.65, "min_sources": 1, "tier_c_allowed": True, "color_bias": "more_yellow"},
    0.8: {"min_confidence": 0.60, "min_sources": 1, "tier_c_allowed": True, "color_bias": "proactive"},
    1.0: {"min_confidence": 0.50, "min_sources": 0, "tier_c_allowed": True, "color_bias": "aggressive"}
}

# Source trust tiers
SOURCE_TIERS = {
    "A": ["wikipedia.org", "britannica.com", "nature.com", "science.org", "nejm.org", "who.int", "cdc.gov"],
    "B": ["reuters.com", "ap.org", "bbc.com", "npr.org", "pbs.org", "factcheck.org", "snopes.com"],
    "C": ["youtube.com", "twitter.com", "facebook.com", "instagram.com", "tiktok.com"]
}