package com.checkmate.app.utils

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.checkmate.app.CheckmateApplication
import com.checkmate.app.R
import com.checkmate.app.data.AppConfig
import com.checkmate.app.data.NotificationColor
import com.checkmate.app.data.NotificationPayload
import timber.log.Timber

/**
 * Helper class for displaying fact-check notifications.
 */
object NotificationHelper {

    fun showFactCheckNotification(context: Context, notification: NotificationPayload) {
        try {
            val notificationManager = context.getSystemService<NotificationManager>() ?: return
            
            // Create expandable notification
            val builder = NotificationCompat.Builder(context, CheckmateApplication.NOTIFICATION_CHANNEL_ALERTS)
                .setSmallIcon(getNotificationIcon(notification.color))
                .setContentTitle(getNotificationTitle(notification.color))
                .setContentText(notification.shortText)
                .setPriority(getNotificationPriority(notification.color))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            
            // Set color indicator
            builder.setColor(getNotificationColor(context, notification.color))
            
            // Add expanded content if details are available
            if (!notification.details.isNullOrBlank()) {
                val expandedText = buildExpandedText(notification)
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            }
            
            // Add action buttons
            addNotificationActions(context, builder, notification)
            
            // Show notification
            notificationManager.notify(
                AppConfig.NOTIFICATION_ID_ALERT + notification.hashCode(),
                builder.build()
            )
            
            Timber.d("Fact-check notification shown: ${notification.color}")
            
        } catch (e: Exception) {
            Timber.e(e, "Error showing fact-check notification")
        }
    }

    private fun getNotificationIcon(color: NotificationColor): Int {
        return when (color) {
            NotificationColor.GREEN -> R.drawable.ic_check_circle
            NotificationColor.YELLOW -> R.drawable.ic_warning
            NotificationColor.RED -> R.drawable.ic_error
        }
    }

    private fun getNotificationTitle(color: NotificationColor): String {
        return when (color) {
            NotificationColor.GREEN -> "✓ Verified Information"
            NotificationColor.YELLOW -> "⚠ Check This Claim"
            NotificationColor.RED -> "❌ Potentially False"
        }
    }

    private fun getNotificationPriority(color: NotificationColor): Int {
        return when (color) {
            NotificationColor.GREEN -> NotificationCompat.PRIORITY_LOW
            NotificationColor.YELLOW -> NotificationCompat.PRIORITY_DEFAULT
            NotificationColor.RED -> NotificationCompat.PRIORITY_HIGH
        }
    }

    private fun getNotificationColor(context: Context, color: NotificationColor): Int {
        return when (color) {
            NotificationColor.GREEN -> androidx.core.content.ContextCompat.getColor(context, R.color.notification_green)
            NotificationColor.YELLOW -> androidx.core.content.ContextCompat.getColor(context, R.color.notification_yellow)
            NotificationColor.RED -> androidx.core.content.ContextCompat.getColor(context, R.color.notification_red)
        }
    }

    private fun buildExpandedText(notification: NotificationPayload): String {
        val text = StringBuilder()
        
        text.append(notification.shortText)
        
        if (!notification.details.isNullOrBlank()) {
            text.append("\n\n")
            text.append(notification.details)
        }
        
        if (notification.sources.isNotEmpty()) {
            text.append("\n\nSources:")
            notification.sources.forEach { source ->
                text.append("\n• ${source.title ?: source.url}")
            }
        }
        
        text.append("\n\nConfidence: ${(notification.confidence * 100).toInt()}%")
        
        return text.toString()
    }

    private fun addNotificationActions(
        context: Context,
        builder: NotificationCompat.Builder,
        notification: NotificationPayload
    ) {
        // Dismiss action
        val dismissIntent = Intent("com.checkmate.app.ACTION_DISMISS_NOTIFICATION")
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context, 0, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        builder.addAction(
            R.drawable.ic_close,
            "Dismiss",
            dismissPendingIntent
        )
        
        // If there are sources, add "View Sources" action
        if (notification.sources.isNotEmpty()) {
            val sourcesIntent = Intent("com.checkmate.app.ACTION_VIEW_SOURCES").apply {
                putExtra("sources", notification.sources.map { it.url }.toTypedArray())
            }
            val sourcesPendingIntent = PendingIntent.getBroadcast(
                context, 1, sourcesIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            builder.addAction(
                R.drawable.ic_link,
                "Sources",
                sourcesPendingIntent
            )
        }
    }

    fun showErrorNotification(context: Context, title: String, message: String) {
        try {
            val notificationManager = context.getSystemService<NotificationManager>() ?: return
            
            val notification = NotificationCompat.Builder(context, CheckmateApplication.NOTIFICATION_CHANNEL_ERRORS)
                .setSmallIcon(R.drawable.ic_error)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .build()
            
            notificationManager.notify(AppConfig.NOTIFICATION_ID_ERROR, notification)
            
        } catch (e: Exception) {
            Timber.e(e, "Error showing error notification")
        }
    }

    fun cancelAllNotifications(context: Context) {
        try {
            val notificationManager = context.getSystemService<NotificationManager>()
            notificationManager?.cancelAll()
        } catch (e: Exception) {
            Timber.e(e, "Error canceling notifications")
        }
    }
}
