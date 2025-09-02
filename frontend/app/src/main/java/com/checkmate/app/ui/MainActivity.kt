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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import com.checkmate.app.managers.SessionManager
import com.checkmate.app.services.CheckmateService
import com.checkmate.app.services.MediaProjectionService
import com.checkmate.app.ui.theme.CheckmateTheme
import com.checkmate.app.utils.PermissionHelper
import kotlinx.coroutines.launch
import timber.log.Timber

class MainActivity : ComponentActivity() {
    
    private lateinit var sessionManager: SessionManager
    
    private var isServiceRunning by mutableStateOf(false)
    private var hasRequiredPermissions by mutableStateOf(false)
    
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        sessionManager = SessionManager(this)
        
        setContent {
            CheckmateTheme {
                MainScreen(
                    isServiceRunning = isServiceRunning,
                    hasRequiredPermissions = hasRequiredPermissions,
                    onStartService = ::startFactCheckingService,
                    onStopService = ::stopFactCheckingService,
                    onRequestPermissions = ::requestAllPermissions,
                    onOpenSettings = ::openAppSettings
                )
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
    
    private fun startFactCheckingService() {
        if (!hasRequiredPermissions) {
            requestAllPermissions()
            return
        }
        
        try {
            val intent = Intent(this, CheckmateService::class.java).apply {
                action = CheckmateService.ACTION_START_SERVICE
            }
            ContextCompat.startForegroundService(this, intent)
            
            lifecycleScope.launch {
                // Give the service time to start
                kotlinx.coroutines.delay(1000)
                checkServiceStatus()
            }
            
        } catch (e: Exception) {
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
            
        } catch (e: Exception) {
            Timber.e(e, "Error starting media projection service")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isServiceRunning: Boolean,
    hasRequiredPermissions: Boolean,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit
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
            // Status Card
            StatusCard(
                isServiceRunning = isServiceRunning,
                hasRequiredPermissions = hasRequiredPermissions
            )
            
            // Control Buttons
            ControlButtons(
                isServiceRunning = isServiceRunning,
                hasRequiredPermissions = hasRequiredPermissions,
                onStartService = onStartService,
                onStopService = onStopService,
                onRequestPermissions = onRequestPermissions
            )
            
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
