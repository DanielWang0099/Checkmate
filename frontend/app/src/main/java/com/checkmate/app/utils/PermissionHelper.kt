package com.checkmate.app.utils

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.checkmate.app.services.CheckmateAccessibilityService
import timber.log.Timber

/**
 * Utility class for checking and managing app permissions.
 */
object PermissionHelper {

    /**
     * Check if all required permissions are granted.
     */
    fun hasAllRequiredPermissions(context: Context): Boolean {
        return hasNotificationPermission(context) && 
               isAccessibilityServiceEnabled(context) &&
               hasRecordAudioPermission(context)
    }

    /**
     * Check if notification permission is granted.
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Notifications don't require explicit permission on older versions
        }
    }

    /**
     * Check if record audio permission is granted.
     */
    fun hasRecordAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if the accessibility service is enabled.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        return try {
            val serviceName = "${context.packageName}/${CheckmateAccessibilityService::class.java.name}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            
            enabledServices?.contains(serviceName) == true
            
        } catch (e: Exception) {
            Timber.e(e, "Error checking accessibility service status")
            false
        }
    }

    /**
     * Check if a specific service is currently running.
     */
    fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
            
            runningServices.any { serviceInfo ->
                serviceInfo.service.className == serviceClass.name
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error checking service status")
            false
        }
    }

    /**
     * Get a list of missing permissions.
     */
    fun getMissingPermissions(context: Context): List<String> {
        val missingPermissions = mutableListOf<String>()
        
        if (!hasNotificationPermission(context)) {
            missingPermissions.add("Notification Permission")
        }
        
        if (!hasRecordAudioPermission(context)) {
            missingPermissions.add("Microphone Permission")
        }
        
        if (!isAccessibilityServiceEnabled(context)) {
            missingPermissions.add("Accessibility Service")
        }
        
        return missingPermissions
    }

    /**
     * Get user-friendly permission descriptions.
     */
    fun getPermissionDescription(permission: String): String {
        return when (permission) {
            "Notification Permission" -> "Required to show fact-check alerts and notifications"
            "Microphone Permission" -> "Required for audio capture and speech recognition"
            "Accessibility Service" -> "Required to monitor screen content for fact-checking"
            else -> "Required for app functionality"
        }
    }

    /**
     * Check if we have system alert window permission (for overlays).
     */
    fun hasSystemAlertWindowPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * Check various optional permissions that enhance functionality.
     */
    fun hasOptionalPermissions(context: Context): Map<String, Boolean> {
        return mapOf(
            "SYSTEM_ALERT_WINDOW" to hasSystemAlertWindowPermission(context),
            "CAMERA" to (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED),
            "RECORD_AUDIO" to (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        )
    }
}
