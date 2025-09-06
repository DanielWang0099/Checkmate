"""S3 service for handling image uploads and temporary storage."""

import asyncio
import io
import uuid
import hashlib
import mimetypes
from datetime import datetime, timedelta
from typing import Optional, Dict, Any
from urllib.parse import urlparse

try:
    import boto3
    from botocore.exceptions import ClientError, NoCredentialsError
except ImportError:
    boto3 = None
    ClientError = Exception
    NoCredentialsError = Exception

from app.core.config import settings
import logging

logger = logging.getLogger(__name__)


class S3ServiceError(Exception):
    """Custom exception for S3 service errors."""
    pass


class S3Service:
    """Service for managing S3 uploads and downloads."""
    
    def __init__(self):
        self.s3_client = None
        self.bucket_name = settings.s3_bucket_name  # ✅ CRITICAL FIX: Use correct setting name
        self._initialize_client()
    
    def _initialize_client(self):
        """Initialize S3 client with credentials."""
        if not boto3:
            print("boto3 not available, S3 functionality disabled")
            return
        
        try:
            self.s3_client = boto3.client(
                's3',
                region_name=settings.s3_region,
                aws_access_key_id=settings.aws_access_key_id,
                aws_secret_access_key=settings.aws_secret_access_key,
                aws_session_token=settings.aws_session_token
            )
            
            # Test credentials by listing bucket (if it exists)
            try:
                self.s3_client.head_bucket(Bucket=self.bucket_name)
            except ClientError as e:
                error_code = e.response['Error']['Code']
                if error_code == '404':
                    print(f"S3 bucket {self.bucket_name} not found")
                elif error_code == '403':
                    print(f"No access to S3 bucket {self.bucket_name}")
                else:
                    print(f"S3 bucket check failed: {e}")
        
        except NoCredentialsError:
            print("AWS credentials not found, S3 functionality disabled")
            self.s3_client = None
        except Exception as e:
            print(f"Failed to initialize S3 client: {e}")
            self.s3_client = None
    
    async def upload_image(
        self, 
        image_data: bytes, 
        session_id: str, 
        frame_timestamp: datetime
    ) -> Optional[str]:
        """
        Upload image to S3 with 24-hour lifecycle.
        
        Args:
            image_data: Raw image bytes
            session_id: Session identifier for organizing uploads
            frame_timestamp: Timestamp of the frame for unique naming
            
        Returns:
            S3 URL of uploaded image or None if failed
        """
        if not self.s3_client:
            return None
        
        try:
            # Generate unique key
            timestamp_str = frame_timestamp.strftime("%Y%m%d_%H%M%S_%f")
            unique_id = str(uuid.uuid4())[:8]
            key = f"sessions/{session_id}/frames/{timestamp_str}_{unique_id}.png"
            
            # Upload with 24-hour expiration
            extra_args = {
                'ContentType': 'image/png',
                'ServerSideEncryption': 'AES256',
                'Metadata': {
                    'session-id': session_id,
                    'upload-time': datetime.utcnow().isoformat(),
                    'expires': (datetime.utcnow() + timedelta(hours=24)).isoformat()
                }
            }
            
            # Upload to S3
            await asyncio.to_thread(
                self.s3_client.put_object,
                Bucket=self.bucket_name,
                Key=key,
                Body=image_data,
                **extra_args
            )
            
            # Return the S3 URL
            url = f"https://{self.bucket_name}.s3.{settings.s3_region}.amazonaws.com/{key}"
            return url
            
        except Exception as e:
            print(f"Failed to upload image to S3: {e}")
            return None
    
    async def upload_image_from_path(
        self, 
        file_path: str, 
        session_id: str, 
        frame_timestamp: datetime
    ) -> Optional[str]:
        """Upload image from file path to S3."""
        try:
            with open(file_path, 'rb') as f:
                image_data = f.read()
            
            return await self.upload_image(image_data, session_id, frame_timestamp)
        
        except Exception as e:
            print(f"Failed to read image file {file_path}: {e}")
            return None
    
    async def get_presigned_url(
        self, 
        s3_key: str, 
        expiration: int = 3600
    ) -> Optional[str]:
        """Generate a presigned URL for temporary access to an S3 object."""
        if not self.s3_client:
            return None
        
        try:
            url = await asyncio.to_thread(
                self.s3_client.generate_presigned_url,
                'get_object',
                Params={'Bucket': self.bucket_name, 'Key': s3_key},
                ExpiresIn=expiration
            )
            return url
        
        except Exception as e:
            print(f"Failed to generate presigned URL: {e}")
            return None
    
    async def delete_session_images(self, session_id: str) -> bool:
        """Delete all images for a specific session."""
        if not self.s3_client:
            return False
        
        try:
            # List all objects with the session prefix
            prefix = f"sessions/{session_id}/"
            
            response = await asyncio.to_thread(
                self.s3_client.list_objects_v2,
                Bucket=self.bucket_name,
                Prefix=prefix
            )
            
            if 'Contents' not in response:
                return True  # No objects to delete
            
            # Delete objects in batches
            objects_to_delete = [{'Key': obj['Key']} for obj in response['Contents']]
            
            if objects_to_delete:
                await asyncio.to_thread(
                    self.s3_client.delete_objects,
                    Bucket=self.bucket_name,
                    Delete={'Objects': objects_to_delete}
                )
            
            return True
        
        except Exception as e:
            print(f"Failed to delete session images: {e}")
            return False
    
    # ========================================================================
    # CRITICAL METHODS FOR AGENTCONTEXT INTEGRATION
    # ========================================================================
    
    async def upload_for_agent_context(
        self, 
        file_data: bytes, 
        session_id: str,
        content_type: Optional[str] = None,
        metadata: Optional[Dict[str, str]] = None
    ) -> str:
        """
        Upload file specifically for AgentContext usage.
        
        CRITICAL: This method creates file references for:
        - TextImageAgentContext.image_ref
        - VideoAgentContext file references
        
        Args:
            file_data: Binary file data
            session_id: Session ID for organizing files
            content_type: MIME type of file
            metadata: Additional metadata to store
            
        Returns:
            str: File reference for use in AgentContext
            
        Raises:
            S3ServiceError: If upload fails
        """
        if not self.s3_client:
            raise S3ServiceError("S3 client not available")
        
        try:
            # Generate unique file reference
            timestamp = datetime.utcnow().strftime("%Y%m%d_%H%M%S")
            file_hash = hashlib.md5(file_data).hexdigest()[:8]
            file_extension = self._detect_file_extension(content_type, file_data)
            
            # Determine file type for proper organization
            if content_type and content_type.startswith('image/'):
                file_ref = f"agent-context/images/{session_id}/{timestamp}_{file_hash}{file_extension}"
            elif content_type and content_type.startswith('video/'):
                file_ref = f"agent-context/videos/{session_id}/{timestamp}_{file_hash}{file_extension}"
            else:
                file_ref = f"agent-context/files/{session_id}/{timestamp}_{file_hash}{file_extension}"
            
            # Prepare upload parameters
            extra_args = {
                'ServerSideEncryption': 'AES256',
                'Metadata': {
                    'session-id': session_id,
                    'upload-timestamp': timestamp,
                    'content-hash': file_hash,
                    'agent-context-file': 'true'
                }
            }
            
            if content_type:
                extra_args['ContentType'] = content_type
            
            if metadata:
                extra_args['Metadata'].update(metadata)
            
            # Upload to S3
            await asyncio.to_thread(
                self.s3_client.put_object,
                Bucket=self.bucket_name,
                Key=file_ref,
                Body=file_data,
                **extra_args
            )
            
            logger.info(f"AgentContext file uploaded: {file_ref}")
            return file_ref
            
        except Exception as e:
            logger.error(f"AgentContext file upload failed: {e}")
            raise S3ServiceError(f"Failed to upload file for AgentContext: {e}")
    
    async def get_agent_context_url(self, file_ref: str, expires_in: int = 3600) -> str:
        """
        Get presigned URL for AgentContext file access.
        
        CRITICAL: This method is used by:
        - TextImageAgent to get actual image URLs from image_ref
        - VideoAgent to get video file URLs
        - Tools that need to access uploaded files
        
        Args:
            file_ref: File reference from AgentContext (image_ref, etc.)
            expires_in: URL expiration time in seconds
            
        Returns:
            str: Presigned URL for file access
            
        Raises:
            S3ServiceError: If URL generation fails
        """
        if not self.s3_client:
            raise S3ServiceError("S3 client not available")
        
        try:
            url = await asyncio.to_thread(
                self.s3_client.generate_presigned_url,
                'get_object',
                Params={'Bucket': self.bucket_name, 'Key': file_ref},
                ExpiresIn=expires_in
            )
            
            logger.debug(f"Generated presigned URL for AgentContext file: {file_ref}")
            return url
            
        except ClientError as e:
            logger.error(f"Failed to generate presigned URL for {file_ref}: {e}")
            raise S3ServiceError(f"URL generation failed: {e}")
        except Exception as e:
            logger.error(f"Unexpected error generating URL: {e}")
            raise S3ServiceError(f"URL generation error: {e}")
    
    def _detect_file_extension(self, content_type: Optional[str], data: bytes) -> str:
        """
        Detect file extension from content type or binary data.
        
        CRITICAL: Proper file extension detection ensures:
        - Correct MIME type handling by browsers
        - Proper tool processing of files
        - File type validation
        """
        if content_type:
            extension = mimetypes.guess_extension(content_type)
            if extension:
                return extension
        
        # Fallback: detect from data headers
        if data.startswith(b'\\xff\\xd8\\xff'):
            return '.jpg'
        elif data.startswith(b'\\x89PNG\\r\\n\\x1a\\n'):
            return '.png'
        elif data.startswith(b'GIF87a') or data.startswith(b'GIF89a'):
            return '.gif'
        elif data.startswith(b'\\x00\\x00\\x00 ftyp'):
            return '.mp4'
        elif data.startswith(b'RIFF') and b'WEBP' in data[:20]:
            return '.webp'
        elif data.startswith(b'RIFF') and b'AVI ' in data[:20]:
            return '.avi'
        else:
            return '.bin'  # Generic binary file
    
    async def validate_agent_context_file(self, file_ref: str) -> Dict[str, Any]:
        """
        Validate that an AgentContext file reference exists and is accessible.
        
        CRITICAL: This validation prevents:
        - Broken image_ref in TextImageAgentContext
        - Invalid file references in VideoAgentContext
        - Tool failures due to missing files
        
        Args:
            file_ref: File reference to validate
            
        Returns:
            Dict with validation results and metadata
        """
        validation = {
            "exists": False,
            "accessible": False,
            "content_type": None,
            "size_bytes": None,
            "metadata": {},
            "error": None
        }
        
        if not self.s3_client:
            validation["error"] = "S3 client not available"
            return validation
        
        try:
            # Check if file exists and get metadata
            response = await asyncio.to_thread(
                self.s3_client.head_object,
                Bucket=self.bucket_name,
                Key=file_ref
            )
            
            validation["exists"] = True
            validation["accessible"] = True
            validation["content_type"] = response.get('ContentType')
            validation["size_bytes"] = response.get('ContentLength')
            validation["metadata"] = response.get('Metadata', {})
            
        except ClientError as e:
            error_code = e.response['Error']['Code']
            if error_code == '404':
                validation["error"] = "File not found"
            elif error_code == '403':
                validation["error"] = "Access denied"
            else:
                validation["error"] = f"AWS error: {e}"
        except Exception as e:
            validation["error"] = f"Validation error: {e}"
        
        return validation
    
    # ========================================================================
    # END AGENTCONTEXT INTEGRATION METHODS
    # ========================================================================
    
    async def cleanup_expired_images(self) -> int:
        """Clean up images older than 24 hours. Returns count of deleted images."""
        if not self.s3_client:
            return 0
        
        try:
            # List all objects in the sessions prefix
            response = await asyncio.to_thread(
                self.s3_client.list_objects_v2,
                Bucket=self.bucket_name,
                Prefix="sessions/"
            )
            
            if 'Contents' not in response:
                return 0
            
            expired_objects = []
            cutoff_time = datetime.utcnow() - timedelta(hours=24)
            
            for obj in response['Contents']:
                # Check if object is older than 24 hours
                if obj['LastModified'].replace(tzinfo=None) < cutoff_time:
                    expired_objects.append({'Key': obj['Key']})
            
            if expired_objects:
                # Delete expired objects
                await asyncio.to_thread(
                    self.s3_client.delete_objects,
                    Bucket=self.bucket_name,
                    Delete={'Objects': expired_objects}
                )
                
                return len(expired_objects)
            
            return 0
        
        except Exception as e:
            print(f"Failed to cleanup expired images: {e}")
            return 0
    
    def is_available(self) -> bool:
        """Check if S3 service is available and configured."""
        return self.s3_client is not None


# Global S3 service instance
s3_service = S3Service()
