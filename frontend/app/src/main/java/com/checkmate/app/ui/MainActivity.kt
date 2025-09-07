package com.checkmate.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import com.checkmate.app.data.*
import com.checkmate.app.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.*
import com.checkmate.app.data.*
import com.checkmate.app.services.CheckmateService
import com.checkmate.app.services.MediaProjectionService
import com.checkmate.app.ui.theme.CheckmateTheme
import com.checkmate.app.utils.PermissionHelper
import kotlinx.coroutines.launch
import timber.log.Timber
import com.checkmate.app.debug.PipelineDebugger

// Navigation screens
enum class Screen {
    MAIN,
    SETTINGS
}

class MainActivity : ComponentActivity() {
    
    private lateinit var sessionManager: SessionManager
    private val pipelineDebugger = PipelineDebugger.getInstance()
    
    private var isServiceRunning by mutableStateOf(false)
    private var hasRequiredPermissions by mutableStateOf(false)
    
    // Real-time state from SessionManager
    private var sessionState by mutableStateOf(SessionState())
    private var connectionState by mutableStateOf(ConnectionState.DISCONNECTED)
    private var lastNotification by mutableStateOf<NotificationPayload?>(null)
    private var batteryStatus by mutableStateOf<BatteryStatus?>(null)
    private var performanceHints by mutableStateOf<PerformanceHints?>(null)
    
    // Navigation state
    private var currentScreen by mutableStateOf(Screen.MAIN)
    private var preferencesState by mutableStateOf(PreferencesState())
    
    // Permission launchers
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { data ->
                startMediaProjectionService(data)
            }
        } else {
            Timber.w("Media projection permission denied")
        }
    }
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            checkAllPermissions()
        } else {
            Timber.w("Notification permission denied")
        }
    }
    
    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            checkAllPermissions()
        } else {
            Timber.w("Record audio permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        pipelineDebugger.logStageStart(PipelineDebugger.STAGE_INIT, mapOf(
            "activity" to "MainActivity",
            "sdk_version" to android.os.Build.VERSION.SDK_INT,
            "app_version" to (packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown")
        ))
        
        sessionManager = SessionManager.getInstance(this)
        
        // Observe real-time session state
        lifecycleScope.launch {
            sessionManager.sessionState.collect { state ->
                sessionState = state
                lastNotification = state.lastNotification
                connectionState = state.connectionState
            }
        }
        
        // Observe preferences state
        lifecycleScope.launch {
            sessionManager.preferences.collect { prefs ->
                preferencesState = prefs
            }
        }
        
        // Observe device status updates
        lifecycleScope.launch {
            while (true) {
                if (sessionState.isActive) {
                    batteryStatus = sessionManager.getBatteryStatus()
                    performanceHints = sessionManager.getPerformanceHints()
                    sessionManager.updateDeviceHints()
                }
                kotlinx.coroutines.delay(5000) // Update every 5 seconds
            }
        }
        
        setContent {
            CheckmateTheme {
                when (currentScreen) {
                    Screen.MAIN -> MainScreen(
                        isServiceRunning = isServiceRunning,
                        hasRequiredPermissions = hasRequiredPermissions,
                        sessionState = sessionState,
                        connectionState = connectionState,
                        lastNotification = lastNotification,
                        batteryStatus = batteryStatus,
                        performanceHints = performanceHints,
                        onStartService = ::startFactCheckingService,
                        onStopService = ::stopFactCheckingService,
                        onRequestPermissions = ::requestAllPermissions,
                        onOpenSettings = ::openAppSettings,
                        onClearNotification = ::clearLastNotification,
                        onNavigateToSettings = { currentScreen = Screen.SETTINGS }
                    )
                    
                    Screen.SETTINGS -> SettingsScreen(
                        preferencesState = preferencesState,
                        onNavigateBack = { currentScreen = Screen.MAIN },
                        onUpdatePreferences = { sessionType, durationMinutes, strictness, showDetails, showLinks ->
                            lifecycleScope.launch {
                                sessionManager.updatePreferences(
                                    sessionType = sessionType,
                                    sessionDurationMinutes = durationMinutes,
                                    strictness = strictness,
                                    showDetails = showDetails,
                                    showLinks = showLinks
                                )
                            }
                        }
                    )
                }
            }
        }
        
        checkAllPermissions()
        checkServiceStatus()
    }
    
    override fun onResume() {
        super.onResume()
        checkAllPermissions()
        checkServiceStatus()
    }
    
    private fun checkAllPermissions() {
        hasRequiredPermissions = PermissionHelper.hasAllRequiredPermissions(this)
    }
    
    private fun checkServiceStatus() {
        isServiceRunning = PermissionHelper.isServiceRunning(this, CheckmateService::class.java)
    }
    
    private fun requestAllPermissions() {
        when {
            !PermissionHelper.hasNotificationPermission(this) -> {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            
            !PermissionHelper.hasRecordAudioPermission(this) -> {
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            
            !PermissionHelper.isAccessibilityServiceEnabled(this) -> {
                openAccessibilitySettings()
            }
            
            else -> {
                requestMediaProjectionPermission()
            }
        }
    }
    
    private fun requestMediaProjectionPermission() {
        val mediaProjectionManager = getSystemService<MediaProjectionManager>()
        if (mediaProjectionManager != null) {
            val intent = mediaProjectionManager.createScreenCaptureIntent()
            mediaProjectionLauncher.launch(intent)
        }
    }
    
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Error opening accessibility settings")
        }
    }
    
    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Error opening app settings")
        }
    }
    
    private fun startFactCheckingService(bypassPermissionCheck: Boolean = false) {
        pipelineDebugger.logStageStart(PipelineDebugger.STAGE_SESSION_START, mapOf(
            "bypass_permission_check" to bypassPermissionCheck,
            "has_permissions" to hasRequiredPermissions
        ))
        
        if (!bypassPermissionCheck && !hasRequiredPermissions) {
            pipelineDebugger.logWarning(PipelineDebugger.STAGE_SESSION_START, "Missing permissions, requesting permissions first")
            requestAllPermissions()
            pipelineDebugger.logStageEnd(PipelineDebugger.STAGE_SESSION_START, false, mapOf("reason" to "missing_permissions"))
            return
        }
        
        try {
            pipelineDebugger.logInfo(PipelineDebugger.STAGE_SESSION_START, "Starting CheckmateService")
            
            // Start the service
            val serviceIntent = Intent(this, CheckmateService::class.java).apply {
                action = CheckmateService.ACTION_START_SERVICE
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            
            lifecycleScope.launch {
                try {
                    pipelineDebugger.logInfo(PipelineDebugger.STAGE_SESSION_START, "Waiting for service to initialize")
                    
                    // Give the service time to start
                    kotlinx.coroutines.delay(1000)
                    checkServiceStatus()
                    
                    pipelineDebugger.logInfo(PipelineDebugger.STAGE_SESSION_START, "Service started, creating session")
                    
                    // Now start a fact-checking session
                    val sessionIntent = Intent(this@MainActivity, CheckmateService::class.java).apply {
                        action = CheckmateService.ACTION_START_SESSION
                    }
                    startService(sessionIntent)
                    
                    pipelineDebugger.logStageEnd(PipelineDebugger.STAGE_SESSION_START, true, mapOf(
                        "service_started" to true,
                        "session_requested" to true
                    ))
                    
                    Timber.d("Started service and session")
                    
                } catch (e: Exception) {
                    pipelineDebugger.logError(PipelineDebugger.STAGE_SESSION_START, e, "Error during session launch sequence")
                    pipelineDebugger.logStageEnd(PipelineDebugger.STAGE_SESSION_START, false, mapOf("error" to (e.message ?: "Unknown error") as Any))
                    throw e
                }
            }
            
        } catch (e: Exception) {
            pipelineDebugger.logError(PipelineDebugger.STAGE_SESSION_START, e, "Error starting fact-checking service")
            pipelineDebugger.logStageEnd(PipelineDebugger.STAGE_SESSION_START, false, mapOf("error" to (e.message ?: "Unknown error") as Any))
            Timber.e(e, "Error starting fact-checking service")
        }
    }
    
    private fun stopFactCheckingService() {
        try {
            val intent = Intent(this, CheckmateService::class.java).apply {
                action = CheckmateService.ACTION_STOP_SERVICE
            }
            startService(intent)
            
            lifecycleScope.launch {
                // Give the service time to stop
                kotlinx.coroutines.delay(1000)
                checkServiceStatus()
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error stopping fact-checking service")
        }
    }
    
    private fun startMediaProjectionService(data: Intent) {
        try {
            val intent = Intent(this, MediaProjectionService::class.java).apply {
                action = MediaProjectionService.ACTION_START_PROJECTION
                putExtra(MediaProjectionService.EXTRA_RESULT_CODE, Activity.RESULT_OK)
                putExtra(MediaProjectionService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(this, intent)
            
            // After starting media projection, now start the main checkmate service
            lifecycleScope.launch {
                // Give media projection service time to initialize
                kotlinx.coroutines.delay(1500)
                startFactCheckingService(bypassPermissionCheck = true)
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error starting media projection service")
        }
    }
    
    private fun clearLastNotification() {
        lifecycleScope.launch {
            sessionManager.clearLastNotification()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isServiceRunning: Boolean,
    hasRequiredPermissions: Boolean,
    sessionState: SessionState,
    connectionState: ConnectionState,
    lastNotification: NotificationPayload?,
    batteryStatus: BatteryStatus?,
    performanceHints: PerformanceHints?,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onClearNotification: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Checkmate",
                        fontWeight = FontWeight.Bold
                    ) 
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Enhanced Status Card with real-time connection info
            EnhancedStatusCard(
                isServiceRunning = isServiceRunning,
                hasRequiredPermissions = hasRequiredPermissions,
                sessionState = sessionState,
                connectionState = connectionState
            )
            
            // Real-time Notification Display
            if (lastNotification != null) {
                RealTimeNotificationCard(
                    notification = lastNotification,
                    onDismiss = onClearNotification
                )
            }
            
            // Device & Battery Status (when session active)
            if (sessionState.isActive && batteryStatus != null) {
                DeviceBatteryStatusCard(
                    batteryStatus = batteryStatus,
                    performanceHints = performanceHints
                )
            }
            
            // Session Timeline & Memory (when session active)
            if (sessionState.isActive) {
                SessionTimelineCard(sessionState = sessionState)
                
                // Session Memory Details
                sessionState.sessionMemory?.let { memory ->
                    SessionMemoryCard(sessionMemory = memory)
                }
            }
            
            // Control Buttons
            ControlButtons(
                isServiceRunning = isServiceRunning,
                hasRequiredPermissions = hasRequiredPermissions,
                onStartService = onStartService,
                onStopService = onStopService,
                onRequestPermissions = onRequestPermissions
            )
            
            // Session Timeline (if session is active)
            if (sessionState.isActive) {
                SessionTimelineCard(sessionState = sessionState)
            }
            
            // Feature Information
            FeatureInfoCard()
            
            // Settings Button
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("App Settings")
            }
        }
    }
}

@Composable
fun StatusCard(
    isServiceRunning: Boolean,
    hasRequiredPermissions: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isServiceRunning && hasRequiredPermissions -> MaterialTheme.colorScheme.primaryContainer
                hasRequiredPermissions -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = when {
                        isServiceRunning && hasRequiredPermissions -> Icons.Default.CheckCircle
                        hasRequiredPermissions -> Icons.Default.PlayArrow
                        else -> Icons.Default.Warning
                    },
                    contentDescription = null,
                    tint = when {
                        isServiceRunning && hasRequiredPermissions -> MaterialTheme.colorScheme.primary
                        hasRequiredPermissions -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.error
                    }
                )
                
                Text(
                    text = when {
                        isServiceRunning && hasRequiredPermissions -> "Active"
                        hasRequiredPermissions -> "Ready"
                        else -> "Setup Required"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Text(
                text = when {
                    isServiceRunning && hasRequiredPermissions -> "Fact-checking is active and monitoring your screen"
                    hasRequiredPermissions -> "All permissions granted. Ready to start fact-checking"
                    else -> "Please grant all required permissions to continue"
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun EnhancedStatusCard(
    isServiceRunning: Boolean,
    hasRequiredPermissions: Boolean,
    sessionState: SessionState,
    connectionState: ConnectionState
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isServiceRunning && hasRequiredPermissions && connectionState == ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
                isServiceRunning && hasRequiredPermissions -> MaterialTheme.colorScheme.secondaryContainer
                hasRequiredPermissions -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Main Status Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = when {
                        isServiceRunning && connectionState == ConnectionState.CONNECTED -> Icons.Default.CheckCircle
                        isServiceRunning -> Icons.Default.Sync
                        hasRequiredPermissions -> Icons.Default.PlayArrow
                        else -> Icons.Default.Warning
                    },
                    contentDescription = null,
                    tint = when {
                        isServiceRunning && connectionState == ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                        isServiceRunning -> MaterialTheme.colorScheme.secondary
                        hasRequiredPermissions -> MaterialTheme.colorScheme.outline
                        else -> MaterialTheme.colorScheme.error
                    }
                )
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            isServiceRunning && connectionState == ConnectionState.CONNECTED -> "Active & Connected"
                            isServiceRunning && connectionState == ConnectionState.CONNECTING -> "Starting..."
                            isServiceRunning && connectionState == ConnectionState.RECONNECTING -> "Reconnecting..."
                            isServiceRunning && connectionState == ConnectionState.ERROR -> "Connection Error"
                            isServiceRunning -> "Service Running"
                            hasRequiredPermissions -> "Ready"
                            else -> "Setup Required"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Text(
                        text = when {
                            isServiceRunning && connectionState == ConnectionState.CONNECTED -> "Monitoring and fact-checking active"
                            isServiceRunning && connectionState == ConnectionState.CONNECTING -> "Establishing connection to server..."
                            isServiceRunning && connectionState == ConnectionState.RECONNECTING -> "Attempting to reconnect..."
                            isServiceRunning && connectionState == ConnectionState.ERROR -> "Unable to connect to fact-checking server"
                            isServiceRunning -> "Service started, waiting for connection..."
                            hasRequiredPermissions -> "All permissions granted. Ready to start"
                            else -> "Please grant required permissions"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Connection Status Details
            if (isServiceRunning) {
                Divider()
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ConnectionStatusChip(
                        label = "Connection",
                        status = connectionState.name,
                        isHealthy = connectionState == ConnectionState.CONNECTED
                    )
                    
                    if (sessionState.isActive && sessionState.sessionId != null) {
                        ConnectionStatusChip(
                            label = "Session",
                            status = "Active",
                            isHealthy = true
                        )
                    }
                }
                
                // Error Display
                if (sessionState.error != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = sessionState.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionStatusChip(
    label: String,
    status: String,
    isHealthy: Boolean
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (isHealthy) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.padding(2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (isHealthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
            )
            Text(
                text = "$label: $status",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
fun RealTimeNotificationCard(
    notification: NotificationPayload,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (notification.color) {
                NotificationColor.GREEN -> MaterialTheme.colorScheme.primaryContainer
                NotificationColor.YELLOW -> MaterialTheme.colorScheme.secondaryContainer  
                NotificationColor.RED -> MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = when (notification.color) {
                            NotificationColor.GREEN -> Icons.Default.CheckCircle
                            NotificationColor.YELLOW -> Icons.Default.Warning
                            NotificationColor.RED -> Icons.Default.Error
                        },
                        contentDescription = null,
                        tint = when (notification.color) {
                            NotificationColor.GREEN -> MaterialTheme.colorScheme.primary
                            NotificationColor.YELLOW -> MaterialTheme.colorScheme.secondary
                            NotificationColor.RED -> MaterialTheme.colorScheme.error
                        }
                    )
                    
                    Text(
                        text = when (notification.color) {
                            NotificationColor.GREEN -> "✓ Verified"
                            NotificationColor.YELLOW -> "⚠ Check This"
                            NotificationColor.RED -> "❌ Potential Issue"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Dismiss",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            
            Text(
                text = notification.shortText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            
            if (!notification.details.isNullOrBlank()) {
                Text(
                    text = notification.details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Sources
            if (notification.sources.isNotEmpty()) {
                Divider()
                Text(
                    text = "Sources:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                notification.sources.take(3).forEach { source ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Link,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = source.title ?: source.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            
            // Confidence meter
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Confidence: ${(notification.confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium
                )
                
                LinearProgressIndicator(
                    progress = notification.confidence,
                    modifier = Modifier.width(100.dp)
                )
            }
        }
    }
}

@Composable
fun ControlButtons(
    isServiceRunning: Boolean,
    hasRequiredPermissions: Boolean,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!hasRequiredPermissions) {
            Button(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Security, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Grant Permissions")
            }
        } else {
            if (isServiceRunning) {
                Button(
                    onClick = onStopService,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stop Fact-Checking")
                }
            } else {
                Button(
                    onClick = onStartService,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Fact-Checking")
                }
            }
        }
    }
}

@Composable
fun FeatureInfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "How Checkmate Works",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            
            FeatureItem(
                icon = Icons.Default.RemoveRedEye,
                title = "Screen Monitoring",
                description = "Automatically monitors content on your screen for fact-checking"
            )
            
            FeatureItem(
                icon = Icons.Default.Psychology,
                title = "AI Analysis",
                description = "Uses advanced AI to verify claims and detect misinformation"
            )
            
            FeatureItem(
                icon = Icons.Default.Notifications,
                title = "Real-time Alerts",
                description = "Get instant notifications about potentially false information"
            )
            
            FeatureItem(
                icon = Icons.Default.Security,
                title = "Privacy First",
                description = "All processing happens securely with your privacy in mind"
            )
        }
    }
}

@Composable
fun FeatureItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun DeviceBatteryStatusCard(
    batteryStatus: BatteryStatus,
    performanceHints: PerformanceHints?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                batteryStatus.level <= 15 -> Color.Red.copy(alpha = 0.1f)
                                                            batteryStatus.level <= 30 -> Color(0xFFFF9800)
                else -> Color.Green.copy(alpha = 0.1f)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Device Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            batteryStatus.isCharging -> Icons.Default.BatteryChargingFull
                            batteryStatus.level > 50 -> Icons.Default.BatteryFull
                            batteryStatus.level > 20 -> Icons.Default.Battery6Bar
                            else -> Icons.Default.BatteryAlert
                        },
                        contentDescription = "Battery",
                        tint = when {
                            batteryStatus.level <= 15 -> Color.Red
                            batteryStatus.level <= 30 -> Color(0xFFFF9800)
                            else -> Color.Green
                        },
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${batteryStatus.level}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Battery Details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Status: ${if (batteryStatus.isCharging) "Charging" else "Discharging"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (batteryStatus.temperature > 0) {
                        Text(
                            text = "Temp: ${batteryStatus.temperature}°C",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Power Saving: ${if (batteryStatus.isPowerSaving) "ON" else "OFF"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (batteryStatus.isPowerSaving) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Performance Hints
            performanceHints?.let { hints ->
                Spacer(modifier = Modifier.height(12.dp))
                
                if (hints.shouldReduceCapture || hints.shouldReduceProcessing || hints.suggestedCaptureInterval > 30) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Color(0xFFFF9800).copy(alpha = 0.1f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Performance Warning",
                            tint = Color(0xFFFF9800),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        Column {
                            Text(
                                text = "Performance Optimization",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFFF9800)
                            )
                            if (hints.shouldReduceCapture) {
                                Text(
                                    text = "• Reducing capture frequency",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (hints.shouldReduceProcessing) {
                                Text(
                                    text = "• Reducing processing intensity",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "• Capture interval: ${hints.suggestedCaptureInterval}s",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SessionTimelineCard(sessionState: SessionState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Session Timeline",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Timeline,
                        contentDescription = "Timeline",
                        tint = if (sessionState.isActive) Color.Green else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (sessionState.isActive) "Active" else "Inactive",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (sessionState.isActive) Color.Green else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Session Duration
            val sessionDuration = if (sessionState.sessionStartTime != null) {
                val startTime = sessionState.sessionStartTime.time
                val currentTime = System.currentTimeMillis()
                val durationMs = currentTime - startTime
                val minutes = (durationMs / 60000).toInt()
                val hours = minutes / 60
                val remainingMinutes = minutes % 60
                
                if (hours > 0) {
                    "${hours}h ${remainingMinutes}m"
                } else {
                    "${remainingMinutes}m"
                }
            } else {
                "Not started"
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Duration: $sessionDuration",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    sessionState.lastActivityTime?.let { lastActivity ->
                        val activityTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(lastActivity)
                        Text(
                            text = "Last Activity: $activityTime",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Claims: ${sessionState.sessionMemory?.timeline?.size ?: 0}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (sessionState.isActive) {
                        Text(
                            text = "Connection: ${if (sessionState.connectionState == ConnectionState.CONNECTED) "Connected" else "Disconnected"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (sessionState.connectionState == ConnectionState.CONNECTED) Color.Green else Color.Red
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SessionMemoryCard(sessionMemory: SessionMemory) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Session Memory",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = "${sessionMemory.timeline.size} events",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Current Activity
            sessionMemory.currentActivity?.let { activity ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Current Activity",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Column {
                        Text(
                            text = "Current: ${activity.app}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = activity.desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            // Recent Timeline Events (last 3)
            if (sessionMemory.timeline.isNotEmpty()) {
                Text(
                    text = "Recent Events",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                sessionMemory.timeline.takeLast(3).forEach { event ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Circle,
                            contentDescription = "Timeline Event",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = event.event,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        
                        Text(
                            text = event.t,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Last Claim Checked
            sessionMemory.lastClaimsChecked.lastOrNull()?.let { lastClaim ->
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            when (lastClaim.status) {
                                ClaimLabel.SUPPORTED -> Color.Green.copy(alpha = 0.1f)
                                ClaimLabel.FALSE -> Color.Red.copy(alpha = 0.1f)
                                ClaimLabel.CONTESTED, ClaimLabel.MISLEADING -> Color(0xFFFF9800).copy(alpha = 0.1f)
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            },
                            RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when (lastClaim.status) {
                            ClaimLabel.SUPPORTED -> Icons.Default.CheckCircle
                            ClaimLabel.FALSE -> Icons.Default.Cancel
                            ClaimLabel.CONTESTED, ClaimLabel.MISLEADING -> Icons.Default.Warning
                            else -> Icons.Default.HelpOutline
                        },
                        contentDescription = "Claim Result",
                        tint = when (lastClaim.status) {
                            ClaimLabel.SUPPORTED -> Color.Green
                            ClaimLabel.FALSE -> Color.Red
                            ClaimLabel.CONTESTED, ClaimLabel.MISLEADING -> Color(0xFFFF9800)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Column {
                        Text(
                            text = "Last Claim: ${lastClaim.status}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = when (lastClaim.status) {
                                ClaimLabel.SUPPORTED -> Color.Green
                                ClaimLabel.FALSE -> Color.Red
                                ClaimLabel.CONTESTED, ClaimLabel.MISLEADING -> Color(0xFFFF9800)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Text(
                            text = if (lastClaim.claim.length > 50) "${lastClaim.claim.take(50)}..." else lastClaim.claim,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Comprehensive Settings Screen for Checkmate
 * Matches the product requirements with all session types, strictness levels, and notification settings
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferencesState: PreferencesState,
    onNavigateBack: () -> Unit,
    onUpdatePreferences: (
        sessionType: SessionType?,
        durationMinutes: Int?,
        strictness: Float?,
        showDetails: Boolean?,
        showLinks: Boolean?
    ) -> Unit
) {
    var sessionType by remember { mutableStateOf(preferencesState.sessionType) }
    var sessionDurationMinutes by remember { mutableStateOf(preferencesState.sessionDurationMinutes) }
    var strictness by remember { mutableStateOf(preferencesState.strictness) }
    var showDetails by remember { mutableStateOf(preferencesState.showDetails) }
    var showLinks by remember { mutableStateOf(preferencesState.showLinks) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Settings",
                        fontWeight = FontWeight.Bold
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            
            // Session Type Section
            item {
                SettingsSection(
                    title = "Session Type",
                    description = "Determines when your fact-checking session will end"
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SessionTypeOption(
                            type = SessionType.MANUAL,
                            title = "Manual",
                            description = "Continue until you manually stop",
                            isSelected = sessionType == SessionType.MANUAL,
                            onSelect = { 
                                sessionType = SessionType.MANUAL
                                onUpdatePreferences(SessionType.MANUAL, null, null, null, null)
                            }
                        )
                        
                        SessionTypeOption(
                            type = SessionType.TIME,
                            title = "Time-boxed",
                            description = "Automatically stop after a set duration",
                            isSelected = sessionType == SessionType.TIME,
                            onSelect = { 
                                sessionType = SessionType.TIME
                                onUpdatePreferences(SessionType.TIME, sessionDurationMinutes, null, null, null)
                            }
                        )
                        
                        SessionTypeOption(
                            type = SessionType.ACTIVITY,
                            title = "Activity-based",
                            description = "Automatically detect when you change activities",
                            isSelected = sessionType == SessionType.ACTIVITY,
                            onSelect = { 
                                sessionType = SessionType.ACTIVITY
                                onUpdatePreferences(SessionType.ACTIVITY, null, null, null, null)
                            }
                        )
                    }
                }
            }

            // Time Duration Slider (only shown when TIME is selected)
            if (sessionType == SessionType.TIME) {
                item {
                    SettingsSection(
                        title = "Session Duration",
                        description = "How long should the session run (5 minutes to 3 hours)"
                    ) {
                        Column {
                            Text(
                                text = "${sessionDurationMinutes} minutes",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            
                            Slider(
                                value = sessionDurationMinutes.toFloat(),
                                onValueChange = { value ->
                                    sessionDurationMinutes = value.toInt()
                                },
                                onValueChangeFinished = {
                                    onUpdatePreferences(null, sessionDurationMinutes, null, null, null)
                                },
                                valueRange = 5f..180f, // 5 minutes to 3 hours
                                steps = 34, // Creates stops at 5, 10, 15, 20, 30, 45, 60, 90, 120, 180 minutes
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "5 min",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "3 hrs",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Strictness Level Section
            item {
                SettingsSection(
                    title = "Strictness Level",
                    description = "How sensitive should fact-checking be to potential misinformation"
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text = getStrictnessLabel(strictness),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        
                        // Discrete strictness levels as per backend config
                        val strictnessLevels = listOf(0.0f, 0.2f, 0.4f, 0.5f, 0.6f, 0.8f, 1.0f)
                        val currentIndex = strictnessLevels.indexOfFirst { 
                            kotlin.math.abs(it - strictness) < 0.01f 
                        }.takeIf { it >= 0 } ?: 3 // Default to 0.5 if not found
                        
                        Slider(
                            value = currentIndex.toFloat(),
                            onValueChange = { value ->
                                val newIndex = value.toInt().coerceIn(0, strictnessLevels.size - 1)
                                strictness = strictnessLevels[newIndex]
                            },
                            onValueChangeFinished = {
                                onUpdatePreferences(null, null, strictness, null, null)
                            },
                            valueRange = 0f..(strictnessLevels.size - 1).toFloat(),
                            steps = strictnessLevels.size - 2,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Relaxed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Very Strict",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        // Strictness explanation
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Text(
                                    text = getStrictnessDescription(strictness),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Notification Settings Section
            item {
                SettingsSection(
                    title = "Notification Contents",
                    description = "Choose what information to include in fact-check notifications"
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Show Details",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Include expandable explanation of fact-check results",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = showDetails,
                                onCheckedChange = { 
                                    showDetails = it
                                    onUpdatePreferences(null, null, null, showDetails, null)
                                }
                            )
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Show Source Links",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Include links to trusted sources used for verification",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = showLinks,
                                onCheckedChange = { 
                                    showLinks = it
                                    onUpdatePreferences(null, null, null, null, showLinks)
                                }
                            )
                        }
                    }
                }
            }

            // Information Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                text = "How to Use",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = "• Use the Quick Settings tile to start/stop sessions\n" +
                                   "• Checkmate monitors your screen and audio in real-time\n" +
                                   "• Notifications appear when potential misinformation is detected\n" +
                                   "• Tap notifications to see details (does not open the app)\n" +
                                   "• Configure settings here to customize your experience",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.4
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )
            
            content()
        }
    }
}

@Composable
fun SessionTypeOption(
    type: SessionType,
    title: String,
    description: String,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 4.dp else 1.dp
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onSelect
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

// Helper functions for strictness levels
fun getStrictnessLabel(strictness: Float): String {
    return when {
        strictness <= 0.05f -> "Very Relaxed (0.0)"
        strictness <= 0.25f -> "Relaxed (0.2)"
        strictness <= 0.45f -> "Balanced (0.4)"
        strictness <= 0.55f -> "Standard (0.5)"
        strictness <= 0.65f -> "Sensitive (0.6)"
        strictness <= 0.85f -> "Strict (0.8)"
        else -> "Very Strict (1.0)"
    }
}

fun getStrictnessDescription(strictness: Float): String {
    return when {
        strictness <= 0.05f -> "Only flags egregious falsehoods with high confidence. Minimal disruption."
        strictness <= 0.25f -> "Flags clear misinformation with strong evidence. Conservative approach."
        strictness <= 0.45f -> "Balanced fact-checking with moderate sensitivity to questionable claims."
        strictness <= 0.55f -> "Standard level - flags most potentially misleading content with reasonable confidence."
        strictness <= 0.65f -> "More sensitive - flags weaker claims and uses more sources for verification."
        strictness <= 0.85f -> "Strict fact-checking - proactive flagging of potential misinformation."
        else -> "Very strict - flags subtle risks and questionable claims with high sensitivity."
    }
}
