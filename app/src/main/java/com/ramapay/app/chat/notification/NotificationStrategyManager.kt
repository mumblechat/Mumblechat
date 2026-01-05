package com.ramapay.app.chat.notification

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hybrid Notification Strategy Manager
 * 
 * Dynamically selects the best notification strategy based on:
 * - Battery state (charging vs battery)
 * - Network type (WiFi vs mobile)
 * - App state (foreground vs background)
 * - Power save mode
 * 
 * Strategies:
 * 1. PERSISTENT - Constant connection (when charging + WiFi)
 * 2. ACTIVE - Aggressive polling every 30s (when app active)
 * 3. LAZY - Poll every 15 min via WorkManager (background + battery)
 * 4. STORE_FORWARD - Rely on relays, sync on app open (app killed)
 * 
 * Based on technical review recommendations for 75% mobile feasibility → 90%+
 */
@Singleton
class NotificationStrategyManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "NotificationStrategy"
        
        // Strategy thresholds
        const val ACTIVE_POLLING_INTERVAL_MS = 30_000L      // 30 seconds
        const val LAZY_POLLING_INTERVAL_MS = 15 * 60_000L   // 15 minutes (WorkManager min)
        const val KEEPALIVE_INTERVAL_MS = 30_000L           // 30 second heartbeat
        const val KEEPALIVE_PAYLOAD_BYTES = 100             // Minimal keepalive size
        
        // Recent activity threshold
        const val RECENT_ACTIVITY_THRESHOLD_MS = 5 * 60_000L  // 5 minutes
    }
    
    /**
     * Notification strategies ordered by power consumption (high → low)
     */
    enum class Strategy {
        /**
         * Persistent connection to best peer.
         * Use when: WiFi + Charging
         * Power: High, Latency: Instant
         */
        PERSISTENT,
        
        /**
         * Aggressive polling every 30 seconds.
         * Use when: App recently active
         * Power: Medium, Latency: 0-30s
         */
        ACTIVE,
        
        /**
         * Lazy polling via WorkManager.
         * Use when: Idle, on battery
         * Power: Low, Latency: 0-15min
         */
        LAZY,
        
        /**
         * Store-forward, sync on app open.
         * Use when: App killed by system
         * Power: Minimal, Latency: Variable
         */
        STORE_FORWARD
    }
    
    // Current strategy
    private val _currentStrategy = MutableStateFlow(Strategy.LAZY)
    val currentStrategy: StateFlow<Strategy> = _currentStrategy.asStateFlow()
    
    // Last app activity timestamp
    private var lastActivityTimestamp = System.currentTimeMillis()
    
    // Power state
    private val batteryManager by lazy { 
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager 
    }
    private val powerManager by lazy { 
        context.getSystemService(Context.POWER_SERVICE) as PowerManager 
    }
    private val connectivityManager by lazy { 
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager 
    }
    
    /**
     * Update app activity timestamp (call on user interaction)
     */
    fun markAppActive() {
        lastActivityTimestamp = System.currentTimeMillis()
        updateStrategy()
    }
    
    /**
     * Re-evaluate and select the best strategy based on current conditions.
     */
    fun updateStrategy(): Strategy {
        val isCharging = isDeviceCharging()
        val isWifi = isOnWifi()
        val isAppActive = isAppRecentlyActive()
        val isPowerSaveMode = powerManager.isPowerSaveMode
        
        val newStrategy = when {
            // Power save mode overrides everything
            isPowerSaveMode -> Strategy.LAZY
            
            // Ideal conditions: persistent connection
            isCharging && isWifi -> Strategy.PERSISTENT
            
            // App recently used: active polling
            isAppActive -> Strategy.ACTIVE
            
            // Background but connected: lazy polling
            else -> Strategy.LAZY
        }
        
        if (newStrategy != _currentStrategy.value) {
            Timber.i("$TAG: Strategy changed: ${_currentStrategy.value} → $newStrategy")
            Timber.d("$TAG: Conditions - charging=$isCharging, wifi=$isWifi, active=$isAppActive, powerSave=$isPowerSaveMode")
            _currentStrategy.value = newStrategy
        }
        
        return newStrategy
    }
    
    /**
     * Get polling interval for current strategy.
     */
    fun getPollingInterval(): Long {
        return when (_currentStrategy.value) {
            Strategy.PERSISTENT -> Long.MAX_VALUE  // No polling needed
            Strategy.ACTIVE -> ACTIVE_POLLING_INTERVAL_MS
            Strategy.LAZY -> LAZY_POLLING_INTERVAL_MS
            Strategy.STORE_FORWARD -> Long.MAX_VALUE  // No polling, sync on demand
        }
    }
    
    /**
     * Should maintain persistent connection?
     */
    fun shouldUsePersistentConnection(): Boolean {
        return _currentStrategy.value == Strategy.PERSISTENT
    }
    
    /**
     * Should use WorkManager for background sync?
     */
    fun shouldUseWorkManager(): Boolean {
        return _currentStrategy.value == Strategy.LAZY || 
               _currentStrategy.value == Strategy.STORE_FORWARD
    }
    
    /**
     * Get strategy description for UI/logging.
     */
    fun getStrategyDescription(): String {
        return when (_currentStrategy.value) {
            Strategy.PERSISTENT -> "Real-time (WiFi + Charging)"
            Strategy.ACTIVE -> "Active polling (30s)"
            Strategy.LAZY -> "Background sync (15min)"
            Strategy.STORE_FORWARD -> "Sync on app open"
        }
    }
    
    /**
     * Get expected latency description for UI.
     */
    fun getExpectedLatency(): String {
        return when (_currentStrategy.value) {
            Strategy.PERSISTENT -> "Instant"
            Strategy.ACTIVE -> "0-30 seconds"
            Strategy.LAZY -> "0-15 minutes"
            Strategy.STORE_FORWARD -> "When app opens"
        }
    }
    
    // ============ Private Helpers ============
    
    private fun isDeviceCharging(): Boolean {
        return try {
            batteryManager.isCharging
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed to check charging state")
            false
        }
    }
    
    private fun isOnWifi(): Boolean {
        return try {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed to check WiFi state")
            false
        }
    }
    
    private fun isAppRecentlyActive(): Boolean {
        return System.currentTimeMillis() - lastActivityTimestamp < RECENT_ACTIVITY_THRESHOLD_MS
    }
    
    /**
     * Battery usage estimates for logging/debugging.
     */
    fun getBatteryUsageEstimate(): String {
        return when (_currentStrategy.value) {
            Strategy.PERSISTENT -> "10-15% per hour (connection maintained)"
            Strategy.ACTIVE -> "5-8% per hour (active polling)"
            Strategy.LAZY -> "0.5-1% per hour (background sync)"
            Strategy.STORE_FORWARD -> "0.1% per hour (on-demand only)"
        }
    }
}
