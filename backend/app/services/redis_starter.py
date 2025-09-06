"""Redis auto-start utility for development environment."""

import subprocess
import time
import logging
import docker
from typing import Optional

logger = logging.getLogger(__name__)

class RedisAutoStarter:
    """Automatically starts Redis in Docker if not available."""
    
    def __init__(self, container_name: str = "checkmate-redis-auto"):
        self.container_name = container_name
        self.docker_client = None
        
    def _get_docker_client(self) -> Optional[docker.DockerClient]:
        """Get Docker client if available."""
        try:
            if self.docker_client is None:
                self.docker_client = docker.from_env()
                # Test connection
                self.docker_client.ping()
            return self.docker_client
        except Exception as e:
            logger.warning(f"Docker not available: {e}")
            return None
    
    def is_redis_running(self) -> bool:
        """Check if Redis is already running."""
        try:
            import redis
            r = redis.Redis(host='localhost', port=6379, socket_connect_timeout=1)
            r.ping()
            return True
        except Exception:
            return False
    
    def start_redis_container(self) -> bool:
        """Start Redis in Docker container."""
        docker_client = self._get_docker_client()
        if not docker_client:
            return False
            
        try:
            # Remove existing container if it exists
            try:
                existing = docker_client.containers.get(self.container_name)
                logger.info(f"Removing existing Redis container: {self.container_name}")
                existing.remove(force=True)
            except docker.errors.NotFound:
                pass
            
            # Start new Redis container
            logger.info("Starting Redis container...")
            container = docker_client.containers.run(
                "redis:7-alpine",
                name=self.container_name,
                ports={'6379/tcp': 6379},
                detach=True,
                remove=True,  # Auto-remove when stopped
                command="redis-server --appendonly yes"
            )
            
            # Wait for Redis to be ready
            max_attempts = 30
            for attempt in range(max_attempts):
                if self.is_redis_running():
                    logger.info("✅ Redis container started successfully!")
                    return True
                time.sleep(0.5)
            
            logger.error("Redis container started but not responding")
            return False
            
        except Exception as e:
            logger.error(f"Failed to start Redis container: {e}")
            return False
    
    def ensure_redis_available(self) -> bool:
        """Ensure Redis is available, start if needed."""
        if self.is_redis_running():
            logger.info("Redis is already running")
            return True
        
        logger.info("Redis not found, attempting to start Docker container...")
        return self.start_redis_container()
    
    def stop_redis_container(self):
        """Stop the Redis container."""
        docker_client = self._get_docker_client()
        if not docker_client:
            return
            
        try:
            container = docker_client.containers.get(self.container_name)
            logger.info("Stopping Redis container...")
            container.stop()
        except docker.errors.NotFound:
            logger.info("Redis container not found")
        except Exception as e:
            logger.error(f"Error stopping Redis container: {e}")


# Global instance
redis_starter = RedisAutoStarter()
