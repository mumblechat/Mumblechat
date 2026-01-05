package com.ramapay.app.chat.relay

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent storage for relay messages.
 * 
 * Implements the storage requirements from MumbleChat Protocol docs (04_RELAY_AND_REWARDS.md):
 * - Store encrypted message blobs with TTL
 * - Track storage usage for tier calculation
 * - Handle cleanup of expired messages
 * - Deliver messages when recipients come online
 * 
 * Storage Structure:
 * ```
 * files/relay_messages/
 *   ├── index.json (encrypted message index)
 *   └── messages/
 *       ├── {messageId}.mcm (encrypted message files)
 *       └── ...
 * ```
 */
@Singleton
class RelayStorage @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val RELAY_DIR = "relay_messages"
        private const val MESSAGES_DIR = "messages"
        private const val INDEX_FILE = "index.json"
        private const val MESSAGE_EXTENSION = ".mcm"
        
        // Max storage limits
        const val MAX_STORAGE_BYTES = 10L * 1024 * 1024 * 1024 // 10 GB absolute max
    }
    
    private val relayDir: File by lazy {
        File(context.filesDir, RELAY_DIR).also { it.mkdirs() }
    }
    
    private val messagesDir: File by lazy {
        File(relayDir, MESSAGES_DIR).also { it.mkdirs() }
    }
    
    private val indexFile: File by lazy {
        File(relayDir, INDEX_FILE)
    }
    
    private val mutex = Mutex()
    
    // In-memory index for fast lookups
    private val messageIndex = mutableMapOf<String, StoredMessage>()
    
    /**
     * Stored message metadata.
     */
    data class StoredMessage(
        val id: String,
        val recipientKeyHash: String,   // First 8 bytes of keccak256(address) in hex
        val senderKeyHash: String,      // First 8 bytes of keccak256(address) in hex
        val receivedAt: Long,
        val expiresAt: Long,
        val size: Long,
        val delivered: Boolean = false,
        val deliveredAt: Long? = null
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() > expiresAt
        
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("recipientKeyHash", recipientKeyHash)
            put("senderKeyHash", senderKeyHash)
            put("receivedAt", receivedAt)
            put("expiresAt", expiresAt)
            put("size", size)
            put("delivered", delivered)
            deliveredAt?.let { put("deliveredAt", it) }
        }
        
        companion object {
            fun fromJson(json: JSONObject): StoredMessage = StoredMessage(
                id = json.getString("id"),
                recipientKeyHash = json.getString("recipientKeyHash"),
                senderKeyHash = json.getString("senderKeyHash"),
                receivedAt = json.getLong("receivedAt"),
                expiresAt = json.getLong("expiresAt"),
                size = json.getLong("size"),
                delivered = json.optBoolean("delivered", false),
                deliveredAt = if (json.has("deliveredAt")) json.getLong("deliveredAt") else null
            )
        }
    }
    
    /**
     * Full message with encrypted blob.
     */
    data class FullMessage(
        val metadata: StoredMessage,
        val encryptedBlob: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FullMessage) return false
            return metadata.id == other.metadata.id
        }
        
        override fun hashCode(): Int = metadata.id.hashCode()
    }
    
    /**
     * Initialize storage and load index.
     */
    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    loadIndex()
                    Timber.d("RelayStorage initialized with ${messageIndex.size} messages")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to initialize RelayStorage")
                    // Start fresh if index is corrupted
                    messageIndex.clear()
                }
            }
        }
    }
    
    /**
     * Check if we can accept more messages based on storage limit.
     */
    suspend fun canAccept(messageSize: Long, storageLimitMB: Long): Boolean {
        val currentUsage = getCurrentStorageUsage()
        val limitBytes = storageLimitMB * 1024 * 1024
        return currentUsage + messageSize <= minOf(limitBytes, MAX_STORAGE_BYTES)
    }
    
    /**
     * Store a message for offline delivery.
     * 
     * @param messageId Unique message ID
     * @param recipientAddress Recipient wallet address
     * @param senderAddress Sender wallet address  
     * @param encryptedBlob Encrypted message content (opaque to relay)
     * @param ttlDays Time-to-live in days
     * @param storageLimitMB Current storage limit in MB
     * @return true if stored successfully
     */
    suspend fun store(
        messageId: String,
        recipientAddress: String,
        senderAddress: String,
        encryptedBlob: ByteArray,
        ttlDays: Int = RelayConfig.DEFAULT_MESSAGE_TTL_DAYS,
        storageLimitMB: Long = RelayConfig.StorageTiers.BRONZE_MB
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val messageSize = encryptedBlob.size.toLong()
                
                // Check storage capacity
                if (!canAccept(messageSize, storageLimitMB)) {
                    // Try cleanup first
                    cleanupExpiredInternal()
                    
                    if (!canAccept(messageSize, storageLimitMB)) {
                        Timber.w("Storage full, cannot accept message $messageId")
                        return@withContext false
                    }
                }
                
                val now = System.currentTimeMillis()
                val ttlMs = ttlDays.toLong() * 24 * 60 * 60 * 1000
                
                val storedMessage = StoredMessage(
                    id = messageId,
                    recipientKeyHash = hashAddress(recipientAddress),
                    senderKeyHash = hashAddress(senderAddress),
                    receivedAt = now,
                    expiresAt = now + ttlMs,
                    size = messageSize
                )
                
                // Write message file
                val messageFile = File(messagesDir, "$messageId$MESSAGE_EXTENSION")
                messageFile.writeBytes(encryptedBlob)
                
                // Update index
                messageIndex[messageId] = storedMessage
                saveIndex()
                
                Timber.d("Stored message $messageId for ${storedMessage.recipientKeyHash} (${messageSize} bytes, TTL: $ttlDays days)")
                true
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to store message $messageId")
                false
            }
        }
    }
    
    /**
     * Get all pending messages for a recipient.
     */
    suspend fun getMessagesFor(recipientAddress: String): List<FullMessage> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val recipientHash = hashAddress(recipientAddress)
            val now = System.currentTimeMillis()
            
            messageIndex.values
                .filter { it.recipientKeyHash == recipientHash && !it.isExpired() && !it.delivered }
                .mapNotNull { metadata ->
                    try {
                        val messageFile = File(messagesDir, "${metadata.id}$MESSAGE_EXTENSION")
                        if (messageFile.exists()) {
                            FullMessage(metadata, messageFile.readBytes())
                        } else {
                            // Clean up orphaned index entry
                            messageIndex.remove(metadata.id)
                            null
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to read message ${metadata.id}")
                        null
                    }
                }
        }
    }
    
    /**
     * Mark a message as delivered.
     */
    suspend fun markDelivered(messageId: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val message = messageIndex[messageId] ?: return@withContext false
            
            // Update metadata
            messageIndex[messageId] = message.copy(
                delivered = true,
                deliveredAt = System.currentTimeMillis()
            )
            
            // Delete the message file (no longer needed)
            val messageFile = File(messagesDir, "$messageId$MESSAGE_EXTENSION")
            if (messageFile.exists()) {
                messageFile.delete()
            }
            
            saveIndex()
            Timber.d("Marked message $messageId as delivered")
            true
        }
    }
    
    /**
     * Delete a specific message.
     */
    suspend fun delete(messageId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            messageIndex.remove(messageId)
            val messageFile = File(messagesDir, "$messageId$MESSAGE_EXTENSION")
            if (messageFile.exists()) {
                messageFile.delete()
            }
            saveIndex()
            Timber.d("Deleted message $messageId")
        }
    }
    
    /**
     * Clean up expired messages.
     * @return Number of messages cleaned up
     */
    suspend fun cleanupExpired(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            cleanupExpiredInternal()
        }
    }
    
    private fun cleanupExpiredInternal(): Int {
        val now = System.currentTimeMillis()
        val expiredIds = messageIndex.values
            .filter { it.expiresAt < now || it.delivered }
            .map { it.id }
        
        var cleanedCount = 0
        for (id in expiredIds) {
            messageIndex.remove(id)
            val messageFile = File(messagesDir, "$id$MESSAGE_EXTENSION")
            if (messageFile.exists() && messageFile.delete()) {
                cleanedCount++
            }
        }
        
        if (cleanedCount > 0) {
            saveIndex()
            Timber.d("Cleaned up $cleanedCount expired/delivered messages")
        }
        
        return cleanedCount
    }
    
    /**
     * Get current storage usage in bytes.
     */
    suspend fun getCurrentStorageUsage(): Long = withContext(Dispatchers.IO) {
        mutex.withLock {
            messageIndex.values.sumOf { it.size }
        }
    }
    
    /**
     * Get current storage usage in MB.
     */
    suspend fun getCurrentStorageUsageMB(): Long {
        return getCurrentStorageUsage() / (1024 * 1024)
    }
    
    /**
     * Get pending message count.
     */
    suspend fun getPendingMessageCount(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            messageIndex.values.count { !it.delivered && !it.isExpired() }
        }
    }
    
    /**
     * Get total messages relayed (delivered count).
     */
    suspend fun getDeliveredCount(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            messageIndex.values.count { it.delivered }
        }
    }
    
    /**
     * Get storage statistics.
     */
    suspend fun getStatistics(): StorageStatistics = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pending = messageIndex.values.filter { !it.delivered && !it.isExpired() }
            val delivered = messageIndex.values.filter { it.delivered }
            
            StorageStatistics(
                pendingMessages = pending.size,
                deliveredMessages = delivered.size,
                pendingStorageBytes = pending.sumOf { it.size },
                totalStorageBytes = getCurrentStorageUsage(),
                oldestMessageTimestamp = pending.minOfOrNull { it.receivedAt },
                newestMessageTimestamp = pending.maxOfOrNull { it.receivedAt }
            )
        }
    }
    
    data class StorageStatistics(
        val pendingMessages: Int,
        val deliveredMessages: Int,
        val pendingStorageBytes: Long,
        val totalStorageBytes: Long,
        val oldestMessageTimestamp: Long?,
        val newestMessageTimestamp: Long?
    )
    
    /**
     * Hash address to first 8 bytes for privacy.
     */
    private fun hashAddress(address: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(address.lowercase().toByteArray())
        return hash.take(8).joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Load index from disk.
     */
    private fun loadIndex() {
        messageIndex.clear()
        
        if (!indexFile.exists()) {
            return
        }
        
        try {
            val json = indexFile.readText()
            val array = JSONArray(json)
            
            for (i in 0 until array.length()) {
                val messageJson = array.getJSONObject(i)
                val message = StoredMessage.fromJson(messageJson)
                messageIndex[message.id] = message
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load index, starting fresh")
            messageIndex.clear()
        }
    }
    
    /**
     * Save index to disk.
     */
    private fun saveIndex() {
        try {
            val array = JSONArray()
            messageIndex.values.forEach { message ->
                array.put(message.toJson())
            }
            indexFile.writeText(array.toString())
        } catch (e: Exception) {
            Timber.e(e, "Failed to save index")
        }
    }
    
    /**
     * Clear all stored messages (for testing or reset).
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            messageIndex.clear()
            messagesDir.listFiles()?.forEach { it.delete() }
            if (indexFile.exists()) {
                indexFile.delete()
            }
            Timber.d("Cleared all relay storage")
        }
    }
}
