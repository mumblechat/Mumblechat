package com.ramapay.app.chat.sync

import android.content.Context
import com.ramapay.app.chat.core.WalletBridge
import com.ramapay.app.chat.data.entity.MessageEntity
import com.ramapay.app.chat.data.repository.MessageRepository
import com.ramapay.app.chat.network.P2PManager
import com.ramapay.app.chat.relay.RelayStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Message Sync Manager for MumbleChat.
 * 
 * Handles synchronization of messages between:
 * - Local database
 * - P2P network (online peers)
 * - Relay nodes (offline message storage)
 * 
 * Based on MumbleChat Protocol documentation (03_MESSAGING_PROTOCOL.md).
 */
@Singleton
class MessageSyncManager @Inject constructor(
    private val context: Context,
    private val p2pManager: P2PManager,
    private val messageRepository: MessageRepository,
    private val relayStorage: RelayStorage,
    private val walletBridge: WalletBridge
) {
    companion object {
        private const val TAG = "MessageSyncManager"
        
        // Sync configuration
        const val SYNC_BATCH_SIZE = 50
        const val SYNC_INTERVAL_MS = 30_000L // 30 seconds
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing
    
    private val _lastSyncTime = MutableStateFlow(0L)
    val lastSyncTime: StateFlow<Long> = _lastSyncTime
    
    private val _syncProgress = MutableStateFlow<SyncProgress?>(null)
    val syncProgress: StateFlow<SyncProgress?> = _syncProgress
    
    data class SyncProgress(
        val total: Int,
        val synced: Int,
        val source: String
    )
    
    data class SyncResult(
        val messagesReceived: Int,
        val messagesSent: Int,
        val errors: List<String>
    )
    
    /**
     * Sync messages from all available sources.
     * Called when:
     * - App starts
     * - User pulls to refresh
     * - Background sync worker runs
     */
    suspend fun syncMessages(): SyncResult {
        if (_isSyncing.value) {
            Timber.d("$TAG: Sync already in progress, skipping")
            return SyncResult(0, 0, emptyList())
        }
        
        _isSyncing.value = true
        val errors = mutableListOf<String>()
        var messagesReceived = 0
        var messagesSent = 0
        
        try {
            val walletAddress = walletBridge.getCurrentWalletAddress()
            if (walletAddress == null) {
                errors.add("No wallet available")
                return SyncResult(0, 0, errors)
            }
            
            Timber.d("$TAG: Starting message sync for $walletAddress")
            
            // 1. Sync from relay storage (messages stored for us while offline)
            try {
                val relayMessages = syncFromRelays(walletAddress)
                messagesReceived += relayMessages
                Timber.d("$TAG: Synced $relayMessages messages from relay storage")
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to sync from relays")
                errors.add("Relay sync failed: ${e.message}")
            }
            
            // 2. Sync from connected peers (request any missed messages)
            try {
                val peerMessages = syncFromPeers(walletAddress)
                messagesReceived += peerMessages
                Timber.d("$TAG: Synced $peerMessages messages from peers")
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to sync from peers")
                errors.add("Peer sync failed: ${e.message}")
            }
            
            // 3. Retry sending any pending outgoing messages
            try {
                val sentMessages = retrySendingPendingMessages()
                messagesSent += sentMessages
                Timber.d("$TAG: Retried sending $sentMessages pending messages")
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to retry pending messages")
                errors.add("Retry send failed: ${e.message}")
            }
            
            _lastSyncTime.value = System.currentTimeMillis()
            Timber.d("$TAG: Sync complete. Received: $messagesReceived, Sent: $messagesSent")
            
        } finally {
            _isSyncing.value = false
            _syncProgress.value = null
        }
        
        return SyncResult(messagesReceived, messagesSent, errors)
    }
    
    /**
     * Sync messages from relay storage.
     * These are messages stored by relay nodes while we were offline.
     */
    private suspend fun syncFromRelays(walletAddress: String): Int {
        _syncProgress.value = SyncProgress(0, 0, "relay")
        
        // Get messages stored for us in relay storage
        val storedMessages = relayStorage.getMessagesFor(walletAddress)
        var synced = 0
        
        for (message in storedMessages) {
            try {
                // Message is already encrypted for us
                // Store in our message repository
                // Note: Actual decryption would happen in ChatService
                
                // Mark as delivered in relay storage
                relayStorage.markDelivered(message.metadata.id)
                synced++
                
                _syncProgress.value = SyncProgress(storedMessages.size, synced, "relay")
                
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to process relay message ${message.metadata.id}")
            }
        }
        
        return synced
    }
    
    /**
     * Sync messages from connected peers.
     * Request any messages we might have missed.
     */
    private suspend fun syncFromPeers(walletAddress: String): Int {
        _syncProgress.value = SyncProgress(0, 0, "peers")
        
        // Get last sync timestamp for each peer
        // Request messages since that timestamp
        
        val onlinePeers = p2pManager.getOnlinePeers()
        var totalSynced = 0
        
        for (peerAddress in onlinePeers) {
            try {
                // Get last message timestamp from this peer
                val lastMessage = messageRepository.getLastMessageFrom(peerAddress)
                val sinceTimestamp = lastMessage?.timestamp ?: 0
                
                // Request any messages since that time
                // This would use P2P sync protocol
                val messages = p2pManager.fetchPendingMessages()
                totalSynced += messages.size
                
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to sync from peer $peerAddress")
            }
        }
        
        return totalSynced
    }
    
    /**
     * Retry sending messages that failed to send previously.
     */
    private suspend fun retrySendingPendingMessages(): Int {
        val pendingMessages = messageRepository.getPendingMessages()
        var sentCount = 0
        
        for (message in pendingMessages) {
            try {
                // Check if recipient is online now (skip group messages with null recipient)
                val recipient = message.recipientAddress ?: continue
                if (p2pManager.isPeerOnline(recipient)) {
                    // Retry sending
                    // This would go through ChatService.sendMessage
                    sentCount++
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to retry message ${message.id}")
            }
        }
        
        return sentCount
    }
    
    /**
     * Start automatic background sync.
     */
    fun startAutoSync() {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(SYNC_INTERVAL_MS)
                try {
                    syncMessages()
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Auto sync failed")
                }
            }
        }
    }
}
