package com.checkmate.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import timber.log.Timber

/**
 * Main Application class for Checkmate.
 * Handles global initialization and configuration.
 */
class CheckmateApplication : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "checkmate_service"
        const val NOTIFICATION_CHANNEL_ALERTS = "checkmate_alerts"
        const val NOTIFICATION_CHANNEL_ERRORS = "checkmate_errors"
        
        private lateinit var instance: CheckmateApplication
        
        fun getInstance(): CheckmateApplication = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // Initialize logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        
        // Create notification channels
        createNotificationChannels()
        
        Timber.d("Checkmate Application initialized")
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Service notification channel
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Checkmate Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background fact-checking service notifications"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            
            // Alert notification channel
            val alertChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ALERTS,
                "Fact Check Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Fact-checking alerts and warnings"
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
            }
            
            // Error notification channel
            val errorChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ERRORS,
                "Checkmate Errors",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical errors and service issues"
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
            }
            
            notificationManager.createNotificationChannels(
                listOf(serviceChannel, alertChannel, errorChannel)
            )
        }
    }
}
