package com.checkmate.app.test

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.checkmate.app.data.*
import com.checkmate.app.managers.WebSocketManager
import com.checkmate.app.utils.NetworkManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*
import timber.log.Timber
import kotlin.test.*

/**
 * Comprehensive integration tests for Phase 1 Step 3 WebSocket implementation.
 * Tests the complete WebSocket coordination layer including:
 * - WebSocketManager centralized coordination
 * - NetworkManager delegation
 * - Connection lifecycle management
 * - Error recovery and reconnection
 * - Message queuing and delivery
 * - UI integration and status updates
 */
@RunWith(AndroidJUnit4::class)
class WebSocketIntegrationTest {

    private lateinit var context: Context
    private lateinit var webSocketManager: WebSocketManager
    private lateinit var networkManager: NetworkManager
    
    @Mock
    private lateinit var mockSessionManager: SessionManager
    
    private val testScope = TestScope()
    
    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        context = ApplicationProvider.getApplicationContext()
        
        // Initialize test environment
        webSocketManager = WebSocketManager.getInstance(context)
        networkManager = NetworkManager(context)
        
        // Setup Timber for testing
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                println("TEST LOG [$tag]: $message")
            }
        })
    }
    
    @After
    fun cleanup() {
        webSocketManager.disconnect()
        testScope.cancel()
        Timber.uprootAll()
    }

    @Test
    fun testWebSocketManagerInitialization() = testScope.runTest {
        // Test: WebSocketManager proper initialization
        val sessionToken = "test_session_token_123"
        
        // Mock session state
        whenever(mockSessionManager.getCurrentSessionState()).thenReturn(
            SessionState(
                sessionId = sessionToken,
                isActive = true,
                timestamp = System.currentTimeMillis()
            )
        )
        
        // Test connection initialization
        val connectionResult = webSocketManager.initializeConnection(sessionToken)
        
        // Verify: Connection attempt was made
        assertTrue(connectionResult, "WebSocket connection should be initiated successfully")
        
        // Verify: Connection state is updating
        val initialState = webSocketManager.getConnectionState()
        assertTrue(
            initialState == ConnectionState.CONNECTING || initialState == ConnectionState.CONNECTED,
            "Initial connection state should be CONNECTING or CONNECTED, got: $initialState"
        )
    }

    @Test
    fun testNetworkManagerDelegation() = testScope.runTest {
        // Test: NetworkManager properly delegates to WebSocketManager
        val testSessionToken = "delegation_test_token"
        
        // Setup session state
        whenever(mockSessionManager.getCurrentSessionState()).thenReturn(
            SessionState(sessionId = testSessionToken, isActive = true, timestamp = System.currentTimeMillis())
        )
        
        // Test connection through NetworkManager
        val networkConnectionResult = networkManager.connectWebSocket()
        
        // Verify: Connection was delegated to WebSocketManager
        assertTrue(networkConnectionResult, "NetworkManager should successfully delegate connection")
        
        // Test status query delegation
        val isConnected = networkManager.isConnected()
        val connectionState = networkManager.getConnectionState()
        
        // Verify: Status queries are properly delegated
        assertEquals(webSocketManager.isConnected(), isConnected, "Connection status should match between managers")
        assertEquals(webSocketManager.getConnectionState(), connectionState, "Connection state should match between managers")
    }

    @Test
    fun testMessageSendingFlow() = testScope.runTest {
        // Test: Complete message sending flow through coordination layer
        val sessionToken = "message_test_token"
        val testFrameBundle = createTestFrameBundle(sessionToken)
        
        // Initialize connection
        webSocketManager.initializeConnection(sessionToken)
        
        // Wait for connection to establish
        delay(1000)
        
        // Test frame bundle sending through NetworkManager
        val sendResult = networkManager.sendFrameBundle(testFrameBundle)
        
        // Verify: Message was queued/sent successfully
        assertTrue(sendResult, "Frame bundle should be sent successfully")
        
        // Test session control messages
        val sessionSettings = SessionSettings(
            sessionToken = sessionToken,
            detectionMode = "advanced",
            confidenceThreshold = 0.85f,
            frameRate = 30
        )
        
        val sessionStartResult = networkManager.sendSessionStart(sessionSettings)
        assertTrue(sessionStartResult, "Session start should be sent successfully")
        
        val sessionStopResult = networkManager.sendSessionStop()
        assertTrue(sessionStopResult, "Session stop should be sent successfully")
    }

    @Test
    fun testConnectionStateManagement() = testScope.runTest {
        // Test: Connection state transitions and management
        val sessionToken = "state_test_token"
        
        // Initial state should be disconnected
        assertEquals(ConnectionState.DISCONNECTED, webSocketManager.getConnectionState())
        
        // Start connection
        webSocketManager.initializeConnection(sessionToken)
        
        // State should transition to connecting
        val connectingState = webSocketManager.getConnectionState()
        assertTrue(
            connectingState == ConnectionState.CONNECTING || connectingState == ConnectionState.CONNECTED,
            "State should be CONNECTING or CONNECTED after initialization"
        )
        
        // Test disconnection
        webSocketManager.disconnect()
        
        // Allow time for disconnection
        delay(500)
        
        // State should return to disconnected
        assertEquals(ConnectionState.DISCONNECTED, webSocketManager.getConnectionState())
    }

    @Test
    fun testErrorRecoveryMechanism() = testScope.runTest {
        // Test: Error recovery and reconnection logic
        val sessionToken = "error_recovery_test"
        
        // Initialize connection
        webSocketManager.initializeConnection(sessionToken)
        
        // Simulate connection error by forcing disconnect
        webSocketManager.disconnect()
        
        // Wait for error state
        delay(1000)
        
        // Test reconnection mechanism
        val reconnectResult = webSocketManager.reconnect()
        assertTrue(reconnectResult, "Reconnection should be initiated successfully")
        
        // Verify recovery attempt
        delay(2000)
        val postRecoveryState = webSocketManager.getConnectionState()
        assertNotEquals(ConnectionState.ERROR, postRecoveryState, "Connection should recover from error state")
    }

    @Test
    fun testMessageQueuingOffline() = testScope.runTest {
        // Test: Message queuing when offline and delivery when online
        val sessionToken = "queue_test_token"
        
        // Ensure WebSocket is disconnected
        webSocketManager.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, webSocketManager.getConnectionState())
        
        // Send messages while offline
        val testMessage1 = mapOf("type" to "test_message_1", "data" to "offline_test")
        val testMessage2 = mapOf("type" to "test_message_2", "data" to "queue_test")
        
        webSocketManager.sendMessage(testMessage1)
        webSocketManager.sendMessage(testMessage2)
        
        // Verify messages are queued
        val queuedCount = webSocketManager.getQueuedMessageCount()
        assertEquals(2, queuedCount, "Two messages should be queued while offline")
        
        // Connect and verify queue processing
        webSocketManager.initializeConnection(sessionToken)
        
        // Allow time for queue processing
        delay(3000)
        
        // Verify queue is processed
        val postConnectQueueCount = webSocketManager.getQueuedMessageCount()
        assertEquals(0, postConnectQueueCount, "Queued messages should be processed after connection")
    }

    @Test
    fun testMetricsTracking() = testScope.runTest {
        // Test: Connection metrics tracking and reporting
        val sessionToken = "metrics_test_token"
        
        // Initialize connection
        webSocketManager.initializeConnection(sessionToken)
        delay(1000)
        
        // Send test messages to generate metrics
        repeat(5) { index ->
            val testMessage = mapOf("type" to "metrics_test", "index" to index)
            webSocketManager.sendMessage(testMessage)
            delay(100)
        }
        
        // Get metrics
        val metrics = webSocketManager.getCurrentMetrics()
        
        // Verify metrics tracking
        assertTrue(metrics.containsKey("messages_sent"), "Metrics should track messages sent")
        assertTrue(metrics.containsKey("connection_time"), "Metrics should track connection time")
        assertTrue(metrics.containsKey("reconnect_count"), "Metrics should track reconnection count")
        
        val messagesSent = metrics["messages_sent"] as? Int ?: 0
        assertTrue(messagesSent >= 5, "Should track at least 5 sent messages, got: $messagesSent")
    }

    @Test
    fun testConcurrentOperations() = testScope.runTest {
        // Test: Concurrent WebSocket operations and thread safety
        val sessionToken = "concurrent_test_token"
        
        // Initialize connection
        webSocketManager.initializeConnection(sessionToken)
        delay(1000)
        
        // Launch concurrent message sending
        val jobs = List(10) { index ->
            async {
                val message = mapOf("type" to "concurrent_test", "index" to index)
                webSocketManager.sendMessage(message)
            }
        }
        
        // Wait for all operations to complete
        jobs.awaitAll()
        
        // Verify system stability
        assertTrue(webSocketManager.isConnected(), "WebSocket should remain connected after concurrent operations")
        
        val metrics = webSocketManager.getCurrentMetrics()
        val messagesSent = metrics["messages_sent"] as? Int ?: 0
        assertTrue(messagesSent >= 10, "Should handle concurrent message sending, sent: $messagesSent")
    }

    /**
     * Helper method to create test frame bundle
     */
    private fun createTestFrameBundle(sessionToken: String): FrameBundle {
        val testFrame = FrameData(
            frameId = "test_frame_001",
            timestamp = System.currentTimeMillis(),
            imageData = "base64_test_image_data",
            location = LocationData(
                latitude = 37.7749,
                longitude = -122.4194,
                accuracy = 10.0f
            )
        )
        
        return FrameBundle(
            bundleId = "test_bundle_001",
            sessionToken = sessionToken,
            frames = listOf(testFrame),
            timestamp = System.currentTimeMillis()
        )
    }
}