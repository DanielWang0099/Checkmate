package com.checkmate.app.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.getSystemService
import com.checkmate.app.data.BatteryStatus
import com.checkmate.app.data.DeviceHints
import com.checkmate.app.data.PerformanceHints
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Tracks real-time device status including battery, power saving mode, and performance hints.
 */
class DeviceStatusTracker(private val context: Context) {
    
    private val _deviceHints = MutableStateFlow(DeviceHints(battery = 0.5f, powerSaver = false))
    val deviceHints: StateFlow<DeviceHints> = _deviceHints.asStateFlow()
    
    private val batteryManager = context.getSystemService<BatteryManager>()
    private val powerManager = context.getSystemService<PowerManager>()
    
    fun getCurrentDeviceHints(): DeviceHints {
        return try {
            val batteryLevel = getBatteryLevel()
            val isPowerSaveMode = powerManager?.isPowerSaveMode ?: false
            
            val hints = DeviceHints(
                battery = batteryLevel,
                powerSaver = isPowerSaveMode
            )
            
            _deviceHints.value = hints
            hints
            
        } catch (e: Exception) {
            Timber.e(e, "Error getting device hints")
            DeviceHints(battery = 0.5f, powerSaver = false)
        }
    }
    
    private fun getBatteryLevel(): Float {
        return try {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.div(100f) ?: 0.5f
        } catch (e: Exception) {
            Timber.e(e, "Error getting battery level")
            0.5f
        }
    }
    
    fun getBatteryStatus(): BatteryStatus {
        return try {
            val batteryLevel = getBatteryLevel()
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val temperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL
            
            val chargingType = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                else -> "Not charging"
            }
            
            BatteryStatus(
                level = (batteryLevel * 100).toInt(), // Convert to percentage
                isCharging = isCharging,
                temperature = (temperature / 10).toInt(), // Convert from tenths of degrees Celsius to int
                isPowerSaving = powerManager?.isPowerSaveMode ?: false
            )
            
        } catch (e: Exception) {
            Timber.e(e, "Error getting battery status")
            BatteryStatus(
                level = 50,
                isCharging = false,
                temperature = 25,
                isPowerSaving = false
            )
        }
    }
    
    fun getPerformanceHints(): PerformanceHints {
        return try {
            val batteryLevel = getBatteryLevel()
            val isPowerSaveMode = powerManager?.isPowerSaveMode ?: false
            
            // Estimate performance impact based on battery and power save mode
            val captureInterval = when {
                isPowerSaveMode || batteryLevel < 0.2f -> 60L // 60 seconds
                batteryLevel < 0.5f -> 45L // 45 seconds
                else -> 30L // 30 seconds (normal)
            }
            
            PerformanceHints(
                shouldReduceCapture = isPowerSaveMode || batteryLevel < 0.3f,
                shouldReduceProcessing = isPowerSaveMode || batteryLevel < 0.2f,
                suggestedCaptureInterval = captureInterval
            )
            
        } catch (e: Exception) {
            Timber.e(e, "Error getting performance hints")
            PerformanceHints(
                shouldReduceCapture = false,
                shouldReduceProcessing = false,
                suggestedCaptureInterval = 30L
            )
        }
    }
}
