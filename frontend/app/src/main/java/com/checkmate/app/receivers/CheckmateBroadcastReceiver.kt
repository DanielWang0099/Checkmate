package com.checkmate.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.checkmate.app.services.CheckmateService
import com.checkmate.app.utils.NotificationHelper
import timber.log.Timber

/**
 * Broadcast receiver for handling notification actions and system events.
 */
class CheckmateBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DISMISS_NOTIFICATION -> {
                handleDismissNotification(context)
            }
            
            ACTION_VIEW_SOURCES -> {
                handleViewSources(context, intent)
            }
            
            ACTION_TOGGLE_MONITORING -> {
                handleToggleMonitoring(context)
            }
            
            ACTION_MANUAL_CAPTURE -> {
                handleManualCapture(context)
            }
            
            Intent.ACTION_BOOT_COMPLETED -> {
                handleBootCompleted(context)
            }
            
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REPLACED -> {
                if (intent.dataString?.contains(context.packageName) == true) {
                    handleAppUpdated(context)
                }
            }
            
            else -> {
                Timber.d("Unhandled broadcast action: ${intent.action}")
            }
        }
    }

    private fun handleDismissNotification(context: Context) {
        try {
            NotificationHelper.cancelAllNotifications(context)
            Timber.d("Notifications dismissed via broadcast")
        } catch (e: Exception) {
            Timber.e(e, "Error dismissing notifications")
        }
    }

    private fun handleViewSources(context: Context, intent: Intent) {
        try {
            val sources = intent.getStringArrayExtra("sources")
            if (sources?.isNotEmpty() == true) {
                // Open the first source in browser
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(sources[0])).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                
                if (browserIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(browserIntent)
                } else {
                    Timber.w("No browser app available to open source")
                }
                
                // Dismiss the notification
                NotificationHelper.cancelAllNotifications(context)
                
                Timber.d("Opened source URL: ${sources[0]}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error opening source URL")
        }
    }

    private fun handleToggleMonitoring(context: Context) {
        try {
            val serviceIntent = Intent(context, CheckmateService::class.java).apply {
                action = CheckmateService.ACTION_TOGGLE_MONITORING
            }
            
            ContextCompat.startForegroundService(context, serviceIntent)
            Timber.d("Toggle monitoring requested via broadcast")
            
        } catch (e: Exception) {
            Timber.e(e, "Error toggling monitoring")
        }
    }

    private fun handleManualCapture(context: Context) {
        try {
            val serviceIntent = Intent(context, CheckmateService::class.java).apply {
                action = CheckmateService.ACTION_MANUAL_CAPTURE
            }
            
            ContextCompat.startForegroundService(context, serviceIntent)
            Timber.d("Manual capture requested via broadcast")
            
        } catch (e: Exception) {
            Timber.e(e, "Error requesting manual capture")
        }
    }

    private fun handleBootCompleted(context: Context) {
        try {
            // Auto-start the service if the user has previously enabled it
            // This would typically check a preference setting
            
            Timber.d("Boot completed - checking auto-start preferences")
            
            // For now, we'll just log. In a full implementation, you'd check
            // SharedPreferences or DataStore to see if auto-start is enabled
            
        } catch (e: Exception) {
            Timber.e(e, "Error handling boot completed")
        }
    }

    private fun handleAppUpdated(context: Context) {
        try {
            Timber.d("App updated - checking service state")
            
            // If the service was running before the update, restart it
            // This would typically check the service state and restart if needed
            
        } catch (e: Exception) {
            Timber.e(e, "Error handling app update")
        }
    }

    companion object {
        const val ACTION_DISMISS_NOTIFICATION = "com.checkmate.app.ACTION_DISMISS_NOTIFICATION"
        const val ACTION_VIEW_SOURCES = "com.checkmate.app.ACTION_VIEW_SOURCES"
        const val ACTION_TOGGLE_MONITORING = "com.checkmate.app.ACTION_TOGGLE_MONITORING"
        const val ACTION_MANUAL_CAPTURE = "com.checkmate.app.ACTION_MANUAL_CAPTURE"
    }
}
