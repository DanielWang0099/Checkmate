"""Enhanced error recovery service with automatic recovery strategies."""

import asyncio
import logging
import time
from typing import Dict, Optional, List, Any, Callable
from datetime import datetime, timedelta
from dataclasses import dataclass, field
from enum import Enum

from ..models.schemas import (
    ErrorType, ErrorSeverity, EnhancedErrorResponse, 
    RecoveryAction, ErrorContext, ServiceDegradationMode, SystemHealth
)


logger = logging.getLogger(__name__)


class RecoveryStrategy(Enum):
    """Available recovery strategies."""
    EXPONENTIAL_BACKOFF = "exponential_backoff"
    CIRCUIT_BREAKER = "circuit_breaker"
    RETRY_WITH_JITTER = "retry_with_jitter"
    GRACEFUL_DEGRADATION = "graceful_degradation"
    FAILOVER = "failover"
    HEALTH_CHECK_RECOVERY = "health_check_recovery"


@dataclass
class RetryConfig:
    """Configuration for retry mechanisms."""
    max_attempts: int = 3
    base_delay: float = 1.0
    max_delay: float = 60.0
    exponential_base: float = 2.0
    jitter: bool = True
    backoff_strategy: RecoveryStrategy = RecoveryStrategy.EXPONENTIAL_BACKOFF


@dataclass
class CircuitBreakerState:
    """State tracking for circuit breaker pattern."""
    failure_count: int = 0
    last_failure_time: Optional[datetime] = None
    state: str = "closed"  # closed, open, half_open
    next_attempt_time: Optional[datetime] = None
    failure_threshold: int = 5
    recovery_timeout: int = 60  # seconds


@dataclass
class OperationContext:
    """Context for tracking operation attempts and recovery."""
    operation_id: str
    session_id: Optional[str] = None
    attempt_count: int = 0
    first_attempt_time: datetime = field(default_factory=datetime.utcnow)
    last_attempt_time: datetime = field(default_factory=datetime.utcnow)
    errors: List[EnhancedErrorResponse] = field(default_factory=list)
    recovery_actions_taken: List[str] = field(default_factory=list)
    user_notified: bool = False


class ErrorRecoveryService:
    """Service for handling error recovery and automatic retry mechanisms."""
    
    def __init__(self):
        self.circuit_breakers: Dict[str, CircuitBreakerState] = {}
        self.operation_contexts: Dict[str, OperationContext] = {}
        self.system_health = SystemHealth()
        self.recovery_callbacks: Dict[str, Callable] = {}
        self.health_check_interval = 30  # seconds
        self.last_health_check = datetime.utcnow()
        
        # Start background health monitoring
        asyncio.create_task(self._health_monitor_loop())
    
    async def handle_error_with_recovery(
        self,
        error: Exception,
        operation: str,
        session_id: Optional[str] = None,
        retry_config: Optional[RetryConfig] = None,
        auto_recover: bool = True
    ) -> EnhancedErrorResponse:
        """Handle error with automatic recovery attempts."""
        
        # Create operation context if not exists
        operation_id = f"{operation}_{session_id or 'global'}"
        if operation_id not in self.operation_contexts:
            self.operation_contexts[operation_id] = OperationContext(
                operation_id=operation_id,
                session_id=session_id
            )
        
        context = self.operation_contexts[operation_id]
        context.attempt_count += 1
        context.last_attempt_time = datetime.utcnow()
        
        # Determine error type and severity
        error_type = self._classify_error(error)
        severity = self._determine_severity(error_type, context.attempt_count)
        
        # Create enhanced error response
        enhanced_error = EnhancedErrorResponse.create_with_recovery(
            error_type=error_type,
            severity=severity,
            message=str(error),
            operation=operation,
            session_id=session_id,
            retry_count=context.attempt_count
        )
        
        context.errors.append(enhanced_error)
        
        # Update circuit breaker state
        self._update_circuit_breaker(operation, error_type)
        
        # Attempt automatic recovery if enabled
        if auto_recover and self._should_attempt_recovery(context, retry_config):
            recovery_result = await self._attempt_automatic_recovery(
                enhanced_error, context, retry_config
            )
            if recovery_result:
                return recovery_result
        
        # Update system health based on error patterns
        self._update_system_health(error_type, operation)
        
        logger.error(
            f"Error in {operation} (attempt {context.attempt_count}): {error}",
            extra={
                "operation": operation,
                "session_id": session_id,
                "error_type": error_type.value,
                "severity": severity.value
            }
        )
        
        return enhanced_error
    
    async def retry_operation(
        self,
        operation_callable: Callable,
        operation_name: str,
        session_id: Optional[str] = None,
        retry_config: Optional[RetryConfig] = None,
        *args,
        **kwargs
    ) -> Any:
        """Retry an operation with recovery strategies."""
        
        config = retry_config or RetryConfig()
        operation_id = f"{operation_name}_{session_id or 'global'}"
        
        for attempt in range(config.max_attempts):
            try:
                # Check circuit breaker
                if not self._circuit_breaker_allows_request(operation_name):
                    raise Exception(f"Circuit breaker open for {operation_name}")
                
                result = await operation_callable(*args, **kwargs)
                
                # Success - reset circuit breaker and clean up context
                self._reset_circuit_breaker(operation_name)
                if operation_id in self.operation_contexts:
                    del self.operation_contexts[operation_id]
                
                return result
                
            except Exception as e:
                if attempt == config.max_attempts - 1:
                    # Final attempt failed
                    return await self.handle_error_with_recovery(
                        e, operation_name, session_id, config, auto_recover=False
                    )
                
                # Calculate delay for next attempt
                delay = self._calculate_retry_delay(attempt, config)
                
                logger.warning(
                    f"Attempt {attempt + 1} failed for {operation_name}, "
                    f"retrying in {delay:.2f}s: {e}"
                )
                
                await asyncio.sleep(delay)
    
    def register_recovery_callback(self, error_type: ErrorType, callback: Callable):
        """Register a callback for specific error type recovery."""
        self.recovery_callbacks[error_type.value] = callback
    
    async def execute_recovery_action(
        self,
        action: RecoveryAction,
        context: OperationContext
    ) -> bool:
        """Execute a specific recovery action."""
        
        try:
            if action.action_type == "retry_with_backoff":
                return await self._retry_with_backoff(context)
            elif action.action_type == "check_connectivity":
                return await self._check_connectivity()
            elif action.action_type == "switch_to_offline_mode":
                return await self._switch_to_offline_mode()
            elif action.action_type == "refresh_credentials":
                return await self._refresh_credentials(context.session_id)
            elif action.action_type == "health_check":
                return await self._perform_health_check()
            elif action.action_type == "fallback_service":
                return await self._activate_fallback_service()
            elif action.action_type == "exponential_backoff":
                delay = min(60, 2 ** (context.attempt_count - 1))
                await asyncio.sleep(delay)
                return True
            else:
                # Try registered callback
                if action.action_type in self.recovery_callbacks:
                    return await self.recovery_callbacks[action.action_type](context)
                
                logger.warning(f"Unknown recovery action: {action.action_type}")
                return False
                
        except Exception as e:
            logger.error(f"Recovery action {action.action_type} failed: {e}")
            return False
    
    async def get_system_health(self) -> SystemHealth:
        """Get current system health status."""
        await self._update_health_status()
        return self.system_health
    
    def _classify_error(self, error: Exception) -> ErrorType:
        """Classify error type based on exception."""
        error_str = str(error).lower()
        error_type = type(error).__name__.lower()
        
        if "network" in error_str or "connection" in error_str:
            return ErrorType.NETWORK_ERROR
        elif "auth" in error_str or "permission" in error_str:
            return ErrorType.AUTHENTICATION_ERROR
        elif "validation" in error_str or "invalid" in error_str:
            return ErrorType.VALIDATION_ERROR
        elif "not found" in error_str or "404" in error_str:
            return ErrorType.RESOURCE_NOT_FOUND
        elif "rate limit" in error_str or "429" in error_str:
            return ErrorType.RATE_LIMIT_ERROR
        elif "service unavailable" in error_str or "503" in error_str:
            return ErrorType.SERVICE_UNAVAILABLE
        else:
            return ErrorType.INTERNAL_ERROR
    
    def _determine_severity(self, error_type: ErrorType, attempt_count: int) -> ErrorSeverity:
        """Determine error severity based on type and attempt count."""
        if attempt_count >= 3:
            return ErrorSeverity.CRITICAL
        elif error_type in [ErrorType.AUTHENTICATION_ERROR, ErrorType.PERMISSION_ERROR]:
            return ErrorSeverity.HIGH
        elif error_type in [ErrorType.NETWORK_ERROR, ErrorType.SERVICE_UNAVAILABLE]:
            return ErrorSeverity.MEDIUM if attempt_count == 1 else ErrorSeverity.HIGH
        else:
            return ErrorSeverity.LOW
    
    def _should_attempt_recovery(
        self,
        context: OperationContext,
        retry_config: Optional[RetryConfig]
    ) -> bool:
        """Determine if automatic recovery should be attempted."""
        config = retry_config or RetryConfig()
        
        # Check max attempts
        if context.attempt_count >= config.max_attempts:
            return False
        
        # Check if too much time has passed
        time_elapsed = datetime.utcnow() - context.first_attempt_time
        if time_elapsed.total_seconds() > 300:  # 5 minutes
            return False
        
        # Check latest error type
        if context.errors:
            latest_error = context.errors[-1]
            # Don't auto-retry certain error types
            if latest_error.error_type in [
                ErrorType.AUTHENTICATION_ERROR,
                ErrorType.PERMISSION_ERROR,
                ErrorType.VALIDATION_ERROR
            ]:
                return False
        
        return True
    
    async def _attempt_automatic_recovery(
        self,
        error: EnhancedErrorResponse,
        context: OperationContext,
        retry_config: Optional[RetryConfig]
    ) -> Optional[EnhancedErrorResponse]:
        """Attempt automatic recovery strategies."""
        
        # Try each auto-executable recovery action
        for action in error.recovery_actions:
            if action.auto_executable and action.action_type not in context.recovery_actions_taken:
                success = await self.execute_recovery_action(action, context)
                context.recovery_actions_taken.append(action.action_type)
                
                if success:
                    logger.info(f"Recovery action {action.action_type} succeeded for {context.operation_id}")
                    # Create success response indicating recovery
                    recovery_response = EnhancedErrorResponse.create_with_recovery(
                        error_type=ErrorType.INTERNAL_ERROR,  # Will be overridden
                        severity=ErrorSeverity.INFO,
                        message=f"Recovered from {error.error_type.value} using {action.action_type}",
                        operation=error.context.operation if error.context else "unknown",
                        session_id=context.session_id,
                        user_message="Issue resolved automatically",
                        retry_count=context.attempt_count
                    )
                    return recovery_response
        
        return None
    
    def _calculate_retry_delay(self, attempt: int, config: RetryConfig) -> float:
        """Calculate delay for retry attempt."""
        if config.backoff_strategy == RecoveryStrategy.EXPONENTIAL_BACKOFF:
            delay = min(config.base_delay * (config.exponential_base ** attempt), config.max_delay)
        else:
            delay = config.base_delay
        
        # Add jitter to prevent thundering herd
        if config.jitter:
            import random
            delay *= (0.5 + random.random() * 0.5)
        
        return delay
    
    def _update_circuit_breaker(self, operation: str, error_type: ErrorType):
        """Update circuit breaker state for operation."""
        if operation not in self.circuit_breakers:
            self.circuit_breakers[operation] = CircuitBreakerState()
        
        breaker = self.circuit_breakers[operation]
        breaker.failure_count += 1
        breaker.last_failure_time = datetime.utcnow()
        
        # Open circuit breaker if threshold reached
        if breaker.failure_count >= breaker.failure_threshold:
            breaker.state = "open"
            breaker.next_attempt_time = datetime.utcnow() + timedelta(seconds=breaker.recovery_timeout)
            logger.warning(f"Circuit breaker opened for {operation}")
    
    def _circuit_breaker_allows_request(self, operation: str) -> bool:
        """Check if circuit breaker allows request."""
        if operation not in self.circuit_breakers:
            return True
        
        breaker = self.circuit_breakers[operation]
        now = datetime.utcnow()
        
        if breaker.state == "closed":
            return True
        elif breaker.state == "open":
            if breaker.next_attempt_time and now >= breaker.next_attempt_time:
                breaker.state = "half_open"
                return True
            return False
        elif breaker.state == "half_open":
            return True
        
        return False
    
    def _reset_circuit_breaker(self, operation: str):
        """Reset circuit breaker after successful operation."""
        if operation in self.circuit_breakers:
            breaker = self.circuit_breakers[operation]
            breaker.failure_count = 0
            breaker.state = "closed"
            breaker.next_attempt_time = None
    
    def _update_system_health(self, error_type: ErrorType, operation: str):
        """Update system health based on error patterns."""
        # Simplified health update - can be enhanced based on specific requirements
        if error_type == ErrorType.SERVICE_UNAVAILABLE:
            if "bedrock" in operation.lower():
                self.system_health.bedrock_available = False
            elif "search" in operation.lower():
                self.system_health.search_available = False
        
        # Update service mode based on available services
        if not self.system_health.bedrock_available or not self.system_health.search_available:
            self.system_health.service_mode = ServiceDegradationMode.REDUCED_FEATURES
        elif not self.system_health.redis_available:
            self.system_health.service_mode = ServiceDegradationMode.REDUCED_FEATURES
    
    async def _health_monitor_loop(self):
        """Background health monitoring loop."""
        while True:
            try:
                await asyncio.sleep(self.health_check_interval)
                await self._update_health_status()
            except Exception as e:
                logger.error(f"Health monitor error: {e}")
    
    async def _update_health_status(self):
        """Update system health status."""
        try:
            # Check Redis
            self.system_health.redis_available = await self._check_redis_health()
            
            # Check Bedrock
            self.system_health.bedrock_available = await self._check_bedrock_health()
            
            # Check Search
            self.system_health.search_available = await self._check_search_health()
            
            # Update service mode
            if all([
                self.system_health.redis_available,
                self.system_health.bedrock_available,
                self.system_health.search_available
            ]):
                self.system_health.service_mode = ServiceDegradationMode.FULL_SERVICE
            elif self.system_health.bedrock_available:
                self.system_health.service_mode = ServiceDegradationMode.REDUCED_FEATURES
            else:
                self.system_health.service_mode = ServiceDegradationMode.OFFLINE_MODE
            
            self.system_health.last_health_check = datetime.utcnow()
            
        except Exception as e:
            logger.error(f"Health status update failed: {e}")
    
    # Recovery action implementations
    async def _retry_with_backoff(self, context: OperationContext) -> bool:
        """Implement retry with backoff logic."""
        delay = min(60, 2 ** (context.attempt_count - 1))
        await asyncio.sleep(delay)
        return True
    
    async def _check_connectivity(self) -> bool:
        """Check network connectivity."""
        try:
            import aiohttp
            async with aiohttp.ClientSession() as session:
                async with session.get("https://httpbin.org/status/200", timeout=5) as response:
                    return response.status == 200
        except:
            return False
    
    async def _switch_to_offline_mode(self) -> bool:
        """Switch system to offline mode."""
        self.system_health.service_mode = ServiceDegradationMode.OFFLINE_MODE
        return True
    
    async def _refresh_credentials(self, session_id: Optional[str]) -> bool:
        """Refresh authentication credentials."""
        # Placeholder - implement based on auth system
        return True
    
    async def _perform_health_check(self) -> bool:
        """Perform comprehensive health check."""
        await self._update_health_status()
        return True
    
    async def _activate_fallback_service(self) -> bool:
        """Activate fallback service."""
        # Placeholder - implement based on fallback strategy
        return True
    
    async def _check_redis_health(self) -> bool:
        """Check Redis connectivity."""
        # Placeholder - implement Redis health check
        return True
    
    async def _check_bedrock_health(self) -> bool:
        """Check Bedrock service health."""
        # Placeholder - implement Bedrock health check
        return True
    
    async def _check_search_health(self) -> bool:
        """Check search service health."""
        # Placeholder - implement search health check
        return True


# Global instance
error_recovery_service = ErrorRecoveryService()
