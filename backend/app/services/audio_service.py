"""Audio transcription service using Google Cloud Speech-to-Text."""

import asyncio
import io
import json
import wave
from typing import Optional, List, Dict, Any
from datetime import datetime, timedelta

try:
    from google.cloud import speech
    from google.oauth2 import service_account
except ImportError:
    speech = None
    service_account = None

from app.core.config import settings


class AudioTranscriptionService:
    """Service for real-time audio transcription."""
    
    def __init__(self):
        self.client = None
        self.streaming_config = None
        self._initialize_client()
    
    def _initialize_client(self):
        """Initialize Google Cloud Speech-to-Text client."""
        if not speech:
            print("Google Cloud Speech library not available")
            return
        
        try:
            # Initialize client with credentials
            if settings.google_application_credentials:
                credentials = service_account.Credentials.from_service_account_file(
                    settings.google_application_credentials
                )
                self.client = speech.SpeechClient(credentials=credentials)
            else:
                # Try default credentials
                self.client = speech.SpeechClient()
            
            # Configure streaming recognition
            config = speech.RecognitionConfig(
                encoding=speech.RecognitionConfig.AudioEncoding.LINEAR16,
                sample_rate_hertz=16000,
                language_code="en-US",
                enable_automatic_punctuation=True,
                enable_word_time_offsets=True,
                model="latest_long"  # Best for longer utterances
            )
            
            self.streaming_config = speech.StreamingRecognitionConfig(
                config=config,
                interim_results=True,
                single_utterance=False
            )
            
        except Exception as e:
            print(f"Failed to initialize Google Cloud Speech client: {e}")
            self.client = None
    
    async def transcribe_audio_chunk(
        self, 
        audio_data: bytes, 
        sample_rate: int = 16000
    ) -> Optional[str]:
        """
        Transcribe a single audio chunk.
        
        Args:
            audio_data: Raw audio bytes (LINEAR16 format)
            sample_rate: Audio sample rate in Hz
            
        Returns:
            Transcribed text or None if failed
        """
        if not self.client:
            return None
        
        try:
            # Configure recognition for this chunk
            config = speech.RecognitionConfig(
                encoding=speech.RecognitionConfig.AudioEncoding.LINEAR16,
                sample_rate_hertz=sample_rate,
                language_code="en-US",
                enable_automatic_punctuation=True
            )
            
            # Create recognition request
            audio = speech.RecognitionAudio(content=audio_data)
            
            # Perform synchronous recognition
            response = await asyncio.to_thread(
                self.client.recognize,
                config=config,
                audio=audio
            )
            
            # Extract transcript
            if response.results:
                transcript = ""
                for result in response.results:
                    if result.alternatives:
                        transcript += result.alternatives[0].transcript + " "
                
                return transcript.strip()
            
            return None
        
        except Exception as e:
            print(f"Audio transcription failed: {e}")
            return None
    
    async def transcribe_audio_file(self, file_path: str) -> Optional[str]:
        """Transcribe an audio file."""
        if not self.client:
            return None
        
        try:
            # Read audio file
            with open(file_path, 'rb') as audio_file:
                content = audio_file.read()
            
            # Get audio format info (basic WAV parsing)
            sample_rate = self._get_wav_sample_rate(content)
            
            return await self.transcribe_audio_chunk(content, sample_rate)
        
        except Exception as e:
            print(f"Failed to transcribe audio file {file_path}: {e}")
            return None
    
    def _get_wav_sample_rate(self, wav_data: bytes) -> int:
        """Extract sample rate from WAV file header."""
        try:
            # Parse WAV header to get sample rate
            if len(wav_data) < 44:
                return 16000  # Default
            
            # Sample rate is at bytes 24-27 in WAV header
            sample_rate = int.from_bytes(wav_data[24:28], byteorder='little')
            return sample_rate if sample_rate > 0 else 16000
        
        except Exception:
            return 16000  # Default fallback
    
    async def start_streaming_recognition(self, audio_generator):
        """Start streaming recognition (for real-time transcription)."""
        if not self.client or not self.streaming_config:
            return
        
        try:
            # Create streaming recognition request
            requests = (speech.StreamingRecognizeRequest(audio_content=chunk)
                       for chunk in audio_generator)
            
            # Start streaming recognition
            responses = self.client.streaming_recognize(
                self.streaming_config, 
                requests
            )
            
            # Process responses
            async for response in responses:
                if not response.results:
                    continue
                
                result = response.results[0]
                if not result.alternatives:
                    continue
                
                transcript = result.alternatives[0].transcript
                
                # Yield interim and final results
                yield {
                    'transcript': transcript,
                    'is_final': result.is_final,
                    'confidence': result.alternatives[0].confidence if result.is_final else 0.0,
                    'timestamp': datetime.utcnow().isoformat()
                }
        
        except Exception as e:
            print(f"Streaming recognition failed: {e}")
    
    def is_available(self) -> bool:
        """Check if transcription service is available."""
        return self.client is not None


class AudioBuffer:
    """Buffer for managing audio chunks and extracting deltas."""
    
    def __init__(self, max_duration_seconds: int = 30):
        self.max_duration = max_duration_seconds
        self.chunks: List[Dict[str, Any]] = []
        self.last_transcript = ""
    
    def add_chunk(self, audio_data: bytes, timestamp: datetime):
        """Add an audio chunk to the buffer."""
        self.chunks.append({
            'data': audio_data,
            'timestamp': timestamp,
            'transcribed': False,
            'transcript': ''
        })
        
        # Remove old chunks
        cutoff_time = timestamp - timedelta(seconds=self.max_duration)
        self.chunks = [chunk for chunk in self.chunks 
                      if chunk['timestamp'] > cutoff_time]
    
    async def get_transcript_delta(
        self, 
        transcription_service: AudioTranscriptionService
    ) -> str:
        """Get new transcript text since last call."""
        if not transcription_service.is_available():
            return ""
        
        # Transcribe any untranscribed chunks
        for chunk in self.chunks:
            if not chunk['transcribed']:
                transcript = await transcription_service.transcribe_audio_chunk(
                    chunk['data']
                )
                chunk['transcript'] = transcript or ""
                chunk['transcribed'] = True
        
        # Combine all transcripts
        full_transcript = " ".join(
            chunk['transcript'] for chunk in self.chunks 
            if chunk['transcript']
        ).strip()
        
        # Calculate delta
        if full_transcript.startswith(self.last_transcript):
            delta = full_transcript[len(self.last_transcript):].strip()
        else:
            # Transcript changed significantly, return recent portion
            delta = full_transcript
        
        # Update last transcript
        self.last_transcript = full_transcript
        
        return delta
    
    def clear(self):
        """Clear the buffer."""
        self.chunks.clear()
        self.last_transcript = ""


# Global transcription service instance
transcription_service = AudioTranscriptionService()
