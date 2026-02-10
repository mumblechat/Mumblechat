package com.ramapay.app.chat.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.ramapay.app.R
import com.ramapay.app.chat.blockchain.MumbleChatBlockchainService
import com.ramapay.app.chat.core.WalletBridge
import com.ramapay.app.chat.crypto.ChatKeyManager
import com.ramapay.app.chat.crypto.MessageEncryption
import com.ramapay.app.chat.network.ConnectionState
import com.ramapay.app.chat.network.P2PManager
import com.ramapay.app.ui.HomeActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service for MumbleChat Relay Node operation.
 * 
 * Based on MumbleChat Protocol documentation (03_MESSAGING_PROTOCOL.md, 04_RELAY_AND_REWARDS.md):
 * 
 * This service:
 * - Runs as Android foreground service with persistent notification
 * - Maintains P2P connections even when app is in background
 * - Sends blockchain heartbeats every 5.5 hours (contract timeout is 6 hours)
 * - Accepts and stores messages for offline recipients
 * - Delivers messages when recipients come online
 * - Cleans up expired messages (TTL-based)
 * - Tracks storage usage for tier calculation
 * 
 * NO FIREBASE/APNs - Fully decentralized notifications via this foreground service.
 */
@AndroidEntryPoint
class RelayService : Service() {
    
    companion object {
        private const val TAG = "RelayService"
        
        // Actions
        const val ACTION_START = "com.ramapay.app.chat.relay.START"
        const val ACTION_STOP = "com.ramapay.app.chat.relay.STOP"
        const val ACTION_HEARTBEAT = "com.ramapay.app.chat.relay.HEARTBEAT"
        
        // Extras
        const val EXTRA_STORAGE_MB = "storage_mb"
        
        /**
         * Start the relay service.
         */
        fun start(context: Context, storageMB: Long = RelayConfig.StorageTiers.BRONZE_MB) {
            val intent = Intent(context, RelayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_STORAGE_MB, storageMB)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * Stop the relay service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, RelayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
        
        /**
         * Check if relay service is running.
         */
        @Volatile
        private var isRunning = false
        
        fun isRunning(): Boolean = isRunning
    }
    
    // Injected dependencies
    @Inject lateinit var p2pManager: P2PManager
    @Inject lateinit var relayStorage: RelayStorage
    @Inject lateinit var blockchainService: MumbleChatBlockchainService
    @Inject lateinit var walletBridge: WalletBridge
    @Inject lateinit var chatKeyManager: ChatKeyManager
    @Inject lateinit var messageEncryption: MessageEncryption
    
    // Service scope
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Jobs
    private var heartbeatJob: Job? = null
    private var cleanupJob: Job? = null
    private var messageListenerJob: Job? = null
    private var deliveryJob: Job? = null
    
    // State
    private val _relayState = MutableStateFlow(RelayState.STOPPED)
    val relayState: StateFlow<RelayState> = _relayState
    
    private var storageLimitMB: Long = RelayConfig.StorageTiers.BRONZE_MB
    private var sessionStartTime: Long = 0
    private var messagesRelayedThisSession: Long = 0
    
    // Wake lock for keeping CPU active during relay operations
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Binder for local binding
    private val binder = RelayBinder()
    
    inner class RelayBinder : Binder() {
        fun getService(): RelayService = this@RelayService
    }
    
    enum class RelayState {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        ERROR
    }
    
    // ============ Service Lifecycle ============
    
    override fun onCreate() {
        super.onCreate()
        Timber.d("$TAG: onCreate")
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("$TAG: onStartCommand action=${intent?.action}")
        
        when (intent?.action) {
            ACTION_START -> {
                storageLimitMB = intent.getLongExtra(EXTRA_STORAGE_MB, RelayConfig.StorageTiers.BRONZE_MB)
                startRelay()
            }
            ACTION_STOP -> {
                stopRelay()
            }
            ACTION_HEARTBEAT -> {
                sendHeartbeat()
            }
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        Timber.d("$TAG: onDestroy")
        stopRelay()
        serviceScope.cancel()
        super.onDestroy()
    }
    
    // ============ Relay Operations ============
    
    private fun startRelay() {
        if (_relayState.value == RelayState.RUNNING) {
            Timber.d("$TAG: Already running")
            return
        }
        
        _relayState.value = RelayState.STARTING
        isRunning = true
        sessionStartTime = System.currentTimeMillis()
        
        // Start foreground with notification
        startForeground(RelayConfig.FOREGROUND_NOTIFICATION_ID, buildNotification())
        
        // Acquire wake lock
        acquireWakeLock()
        
        serviceScope.launch {
            try {
                Timber.d("$TAG: Starting relay operations...")
                
                // Initialize storage
                relayStorage.initialize()
                
                // Get wallet and keys
                val walletAddress = walletBridge.getCurrentWalletAddress()
                if (walletAddress == null) {
                    Timber.e("$TAG: No wallet available")
                    _relayState.value = RelayState.ERROR
                    return@launch
                }
                
                val chatKeys = chatKeyManager.deriveChatKeys()
                if (chatKeys == null) {
                    Timber.e("$TAG: Failed to derive chat keys")
                    _relayState.value = RelayState.ERROR
                    return@launch
                }
                
                // Initialize P2P
                p2pManager.initialize(chatKeys, walletAddress)
                p2pManager.connect()
                
                // Start background jobs
                startHeartbeatJob()
                startCleanupJob()
                startMessageListener()
                startDeliveryJob()
                
                _relayState.value = RelayState.RUNNING
                Timber.d("$TAG: Relay service started successfully")
                
                // Update notification with running state
                updateNotification()
                
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to start relay")
                _relayState.value = RelayState.ERROR
            }
        }
    }
    
    private fun stopRelay() {
        if (_relayState.value == RelayState.STOPPED) {
            return
        }
        
        _relayState.value = RelayState.STOPPING
        Timber.d("$TAG: Stopping relay...")
        
        // Cancel all jobs
        heartbeatJob?.cancel()
        cleanupJob?.cancel()
        messageListenerJob?.cancel()
        deliveryJob?.cancel()
        
        // Disconnect P2P
        p2pManager.disconnect()
        
        // Release wake lock
        releaseWakeLock()
        
        // Stop foreground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        
        _relayState.value = RelayState.STOPPED
        isRunning = false
        
        // Log session stats
        val sessionDuration = (System.currentTimeMillis() - sessionStartTime) / 1000
        Timber.d("$TAG: Relay stopped. Session: ${sessionDuration}s, Messages relayed: $messagesRelayedThisSession")
        
        stopSelf()
    }
    
    // ============ Heartbeat ============
    
    private fun startHeartbeatJob() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                sendHeartbeat()
                delay(RelayConfig.HEARTBEAT_INTERVAL_MS)
            }
        }
    }
    
    private fun sendHeartbeat() {
        serviceScope.launch {
            try {
                val walletAddress = walletBridge.getCurrentWalletAddress() ?: return@launch
                
                // Get current storage usage
                val storageMB = relayStorage.getCurrentStorageUsageMB()
                
                // Send heartbeat to blockchain
                // Note: This will be called via the blockchain service
                Timber.d("$TAG: Sending heartbeat - storage: ${storageMB}MB")
                
                // The actual on-chain heartbeat transaction would be initiated here
                // For now, we just log it - the actual implementation would call:
                // blockchainService.sendHeartbeat(storageMB)
                
                // Update notification
                updateNotification()
                
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to send heartbeat")
            }
        }
    }
    
    // ============ Message Handling ============
    
    private fun startMessageListener() {
        messageListenerJob?.cancel()
        messageListenerJob = serviceScope.launch {
            p2pManager.incomingMessages.collect { message ->
                handleIncomingMessage(message)
            }
        }
    }
    
    private suspend fun handleIncomingMessage(message: P2PManager.IncomingMessage) {
        try {
            val myAddress = walletBridge.getCurrentWalletAddress() ?: return
            
            // Check if this message is for us or needs to be relayed
            if (message.senderAddress.equals(myAddress, ignoreCase = true)) {
                // We sent this message, ignore
                return
            }
            
            // Check if recipient is online
            val recipientOnline = p2pManager.isPeerOnline(message.senderAddress)
            
            if (!recipientOnline) {
                // Store for later delivery
                val stored = relayStorage.store(
                    messageId = message.messageId,
                    recipientAddress = message.senderAddress,
                    senderAddress = message.senderAddress,
                    encryptedBlob = message.encrypted.ciphertext + message.encrypted.nonce,
                    ttlDays = RelayConfig.DEFAULT_MESSAGE_TTL_DAYS,
                    storageLimitMB = storageLimitMB
                )
                
                if (stored) {
                    messagesRelayedThisSession++
                    Timber.d("$TAG: Stored message ${message.messageId} for offline recipient")
                    updateNotification()
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error handling incoming message")
        }
    }
    
    // ============ Message Delivery ============
    
    private fun startDeliveryJob() {
        deliveryJob?.cancel()
        deliveryJob = serviceScope.launch {
            // Monitor connection state changes
            p2pManager.connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    // Check for pending messages when peers connect
                    deliverPendingMessages()
                }
            }
        }
    }
    
    private suspend fun deliverPendingMessages() {
        try {
            val onlinePeers = p2pManager.getOnlinePeers()
            
            for (peer in onlinePeers) {
                val messages = relayStorage.getMessagesFor(peer)
                
                for (fullMessage in messages) {
                    try {
                        // Attempt delivery
                        val delivered = p2pManager.deliverStoredMessage(
                            recipientAddress = peer,
                            messageId = fullMessage.metadata.id,
                            encryptedBlob = fullMessage.encryptedBlob
                        )
                        
                        if (delivered) {
                            relayStorage.markDelivered(fullMessage.metadata.id)
                            messagesRelayedThisSession++
                            Timber.d("$TAG: Delivered stored message ${fullMessage.metadata.id} to $peer")
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "$TAG: Failed to deliver message ${fullMessage.metadata.id}")
                    }
                }
            }
            
            updateNotification()
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error delivering pending messages")
        }
    }
    
    // ============ Cleanup ============
    
    private fun startCleanupJob() {
        cleanupJob?.cancel()
        cleanupJob = serviceScope.launch {
            while (isActive) {
                delay(RelayConfig.CLEANUP_INTERVAL_MS)
                val cleaned = relayStorage.cleanupExpired()
                if (cleaned > 0) {
                    Timber.d("$TAG: Cleaned up $cleaned expired messages")
                    updateNotification()
                }
            }
        }
    }
    
    // ============ Notification ============
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                RelayConfig.NOTIFICATION_CHANNEL_ID,
                "MumbleChat Relay Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification for MumbleChat relay node service"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, HomeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val stats = runCatching { 
            kotlinx.coroutines.runBlocking { relayStorage.getStatistics() }
        }.getOrNull()
        
        val peerCount = p2pManager.peerCount.value
        val pendingMessages = stats?.pendingMessages ?: 0
        val sessionSeconds = (System.currentTimeMillis() - sessionStartTime) / 1000
        val sessionMinutes = sessionSeconds / 60
        
        val contentText = when (_relayState.value) {
            RelayState.RUNNING -> "🟢 Active | $peerCount peers | $pendingMessages pending | ${sessionMinutes}m uptime"
            RelayState.STARTING -> "🟡 Starting..."
            RelayState.STOPPING -> "🟠 Stopping..."
            RelayState.ERROR -> "🔴 Error - Tap to retry"
            RelayState.STOPPED -> "⚫ Stopped"
        }
        
        return NotificationCompat.Builder(this, RelayConfig.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("MumbleChat Relay Node")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    private fun updateNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(RelayConfig.FOREGROUND_NOTIFICATION_ID, buildNotification())
    }
    
    // ============ Wake Lock ============
    
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MumbleChat::RelayService"
            ).apply {
                acquire(10 * 60 * 60 * 1000L) // 10 hours max
            }
            Timber.d("$TAG: Wake lock acquired")
        }
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Timber.d("$TAG: Wake lock released")
            }
        }
        wakeLock = null
    }
    
    // ============ Public API ============
    
    /**
     * Get current relay statistics.
     */
    suspend fun getStatistics(): RelayStatistics {
        val storageStats = relayStorage.getStatistics()
        val tier = RelayConfig.RelayTier.calculateTier(
            dailyUptimeHours = getDailyUptimeHours(),
            storageMB = storageStats.totalStorageBytes / (1024 * 1024)
        )
        
        return RelayStatistics(
            isRunning = _relayState.value == RelayState.RUNNING,
            tier = tier,
            peerCount = p2pManager.peerCount.value,
            pendingMessages = storageStats.pendingMessages,
            deliveredMessages = storageStats.deliveredMessages,
            storageUsedMB = storageStats.totalStorageBytes / (1024 * 1024),
            storageLimitMB = storageLimitMB,
            sessionUptimeSeconds = (System.currentTimeMillis() - sessionStartTime) / 1000,
            messagesRelayedThisSession = messagesRelayedThisSession
        )
    }
    
    private fun getDailyUptimeHours(): Int {
        // This would be calculated from actual tracked uptime
        // For now, return session uptime converted to hours
        return ((System.currentTimeMillis() - sessionStartTime) / (1000 * 60 * 60)).toInt()
    }
    
    data class RelayStatistics(
        val isRunning: Boolean,
        val tier: RelayConfig.RelayTier,
        val peerCount: Int,
        val pendingMessages: Int,
        val deliveredMessages: Int,
        val storageUsedMB: Long,
        val storageLimitMB: Long,
        val sessionUptimeSeconds: Long,
        val messagesRelayedThisSession: Long
    )
}
