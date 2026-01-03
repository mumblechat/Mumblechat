package com.ramapay.app.chat.core

import android.content.Context
import com.ramapay.app.chat.crypto.ChatKeyManager
import com.ramapay.app.chat.crypto.MessageEncryption
import com.ramapay.app.chat.data.entity.MessageEntity
import com.ramapay.app.chat.data.entity.MessageStatus
import com.ramapay.app.chat.data.entity.MessageType
import com.ramapay.app.chat.data.repository.ConversationRepository
import com.ramapay.app.chat.data.repository.GroupRepository
import com.ramapay.app.chat.data.repository.MessageRepository
import com.ramapay.app.chat.network.P2PManager
import com.ramapay.app.chat.registry.RegistrationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Main orchestrator for MumbleChat functionality.
 * 
 * This service handles:
 * - Chat initialization
 * - Message sending/receiving
 * - P2P network management
 * - Message encryption/decryption
 * 
 * IMPORTANT: This service does NOT modify any wallet data.
 * All wallet access is READ-ONLY through WalletBridge.
 */
@Singleton
class ChatService @Inject constructor(
    private val context: Context,
    private val p2pManager: P2PManager,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val groupRepository: GroupRepository,
    private val chatKeyManager: ChatKeyManager,
    private val messageEncryption: MessageEncryption,
    private val walletBridge: WalletBridge,
    private val registrationManager: RegistrationManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized
    
    private val _registrationRequired = MutableStateFlow(false)
    val registrationRequired: StateFlow<Boolean> = _registrationRequired
    
    private var chatKeys: ChatKeyManager.ChatKeyPair? = null

    /**
     * Initialize chat service for current wallet.
     * Called when app starts or wallet changes.
     */
    suspend fun initialize(): Result<Unit> {
        val walletAddress = walletBridge.getCurrentWalletAddress()
            ?: return Result.failure(Exception("No wallet available"))

        return try {
            // 1. Derive chat keys from wallet
            chatKeys = chatKeyManager.deriveChatKeys()
            
            if (chatKeys == null) {
                return Result.failure(Exception("Failed to derive chat keys"))
            }

            // 2. Check registration status
            val isRegistered = registrationManager.isRegistered(walletAddress)

            if (!isRegistered) {
                _registrationRequired.value = true
                // Can continue but will need to register before chatting
                return Result.success(Unit)
            }

            // 3. Connect to P2P network
            p2pManager.initialize(chatKeys!!, walletAddress)
            p2pManager.connect()

            // 4. Start listening for incoming messages
            startMessageListener()

            // 5. Sync pending messages from relays
            syncPendingMessages()

            _isInitialized.value = true
            Result.success(Unit)

        } catch (e: Exception) {
            Timber.e(e, "Chat initialization failed")
            Result.failure(e)
        }
    }

    /**
     * Check if an address is registered on the blockchain.
     */
    suspend fun isRegistered(address: String): Boolean {
        return registrationManager.isRegistered(address)
    }

    /**
     * Register current wallet on the MumbleChat registry.
     * Returns the transaction data to be signed and sent.
     */
    suspend fun register(): Result<String> {
        val keys = chatKeys ?: return Result.failure(Exception("Keys not derived"))
        
        return try {
            // Get transaction data for registration
            val txData = registrationManager.getRegistrationTxData(keys.identityPublic)
            
            Result.success(txData)
        } catch (e: Exception) {
            Timber.e(e, "Registration failed")
            Result.failure(e)
        }
    }
    
    /**
     * Complete registration after transaction is confirmed.
     */
    suspend fun completeRegistration(): Result<Unit> {
        val keys = chatKeys ?: return Result.failure(Exception("Keys not derived"))
        
        return try {
            val walletAddress = walletBridge.getCurrentWalletAddress()
                ?: return Result.failure(Exception("No wallet available"))
            
            // Mark as registered
            registrationManager.markRegistered(walletAddress)
            _registrationRequired.value = false
            
            // Now complete initialization
            p2pManager.initialize(keys, walletAddress)
            p2pManager.connect()
            startMessageListener()
            syncPendingMessages()
            
            _isInitialized.value = true
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Registration completion failed")
            Result.failure(e)
        }
    }

    /**
     * Send a direct message to another wallet.
     */
    suspend fun sendMessage(
        recipientAddress: String,
        content: String,
        contentType: MessageType = MessageType.TEXT
    ): Result<MessageEntity> {
        val keys = chatKeys ?: return Result.failure(Exception("Not initialized"))
        val senderAddress = walletBridge.getCurrentWalletAddress()
            ?: return Result.failure(Exception("No wallet"))

        return try {
            // 1. Get or create conversation
            val conversation = conversationRepository.getOrCreate(senderAddress, recipientAddress)

            // 2. Get recipient's public key
            val recipientPubKey = registrationManager.getPublicKey(recipientAddress)
                ?: return Result.failure(Exception("Recipient not registered"))

            // 3. Create message entity
            val message = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversation.id,
                groupId = null,
                senderAddress = senderAddress,
                recipientAddress = recipientAddress,
                contentType = contentType.name,
                content = content,
                encryptedContent = null,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.SENDING,
                replyToId = null,
                isDeleted = false,
                signature = null
            )

            // 4. Save locally first
            messageRepository.insert(message)

            // 5. Encrypt message
            val encrypted = messageEncryption.encryptMessage(
                content,
                keys.sessionPrivate,
                recipientPubKey
            )

            // 6. Send via P2P or relay
            val sendResult = p2pManager.sendMessage(
                recipientAddress,
                encrypted,
                message.id
            )

            // 7. Update status
            val newStatus = when {
                sendResult.direct -> MessageStatus.SENT_DIRECT
                sendResult.relayed -> MessageStatus.SENT_TO_RELAY
                else -> MessageStatus.FAILED
            }
            messageRepository.updateStatus(message.id, newStatus)

            // 8. Update conversation
            conversationRepository.updateLastMessage(
                conversation.id,
                message.id,
                content.take(100),
                message.timestamp
            )

            Result.success(message.copy(status = newStatus))

        } catch (e: Exception) {
            Timber.e(e, "Failed to send message")
            Result.failure(e)
        }
    }

    /**
     * Send a message to a group.
     */
    suspend fun sendGroupMessage(
        groupId: String,
        content: String,
        contentType: MessageType = MessageType.TEXT
    ): Result<MessageEntity> {
        val keys = chatKeys ?: return Result.failure(Exception("Not initialized"))
        val senderAddress = walletBridge.getCurrentWalletAddress()
            ?: return Result.failure(Exception("No wallet"))

        return try {
            // 1. Get group
            val group = groupRepository.getById(groupId)
                ?: return Result.failure(Exception("Group not found"))

            // 2. Get group key
            val groupKey = groupRepository.getGroupKey(groupId)
                ?: return Result.failure(Exception("Group key not found"))

            // 3. Create message
            val message = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = groupId,
                groupId = groupId,
                senderAddress = senderAddress,
                recipientAddress = null,
                contentType = contentType.name,
                content = content,
                encryptedContent = null,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.SENDING,
                replyToId = null,
                isDeleted = false,
                signature = null
            )

            // 4. Save locally
            messageRepository.insert(message)

            // 5. Encrypt with group key
            val encrypted = messageEncryption.encryptWithGroupKey(content, groupKey)

            // 6. Sign with identity key
            val signature = messageEncryption.sign(encrypted.ciphertext, keys.identityPrivate)

            // 7. Send to all members
            val members = groupRepository.getMembers(groupId)
            for (member in members) {
                if (member.memberAddress != senderAddress) {
                    p2pManager.sendGroupMessage(
                        member.memberAddress,
                        groupId,
                        encrypted,
                        signature
                    )
                }
            }

            // 8. Update status
            messageRepository.updateStatus(message.id, MessageStatus.SENT_DIRECT)

            // 9. Update group last message
            groupRepository.updateLastMessage(
                groupId,
                message.id,
                content.take(100),
                message.timestamp
            )

            Result.success(message)

        } catch (e: Exception) {
            Timber.e(e, "Failed to send group message")
            Result.failure(e)
        }
    }

    private fun startMessageListener() {
        scope.launch {
            p2pManager.incomingMessages.collect { incoming ->
                handleIncomingMessage(incoming)
            }
        }
    }

    private suspend fun handleIncomingMessage(incoming: P2PManager.IncomingMessage) {
        try {
            val keys = chatKeys ?: return
            val myAddress = walletBridge.getCurrentWalletAddress() ?: return

            // Get sender's public key
            val senderPubKey = registrationManager.getPublicKey(incoming.senderAddress) ?: return

            // Decrypt message
            val decrypted = messageEncryption.decryptMessage(
                incoming.encrypted,
                keys.sessionPrivate,
                senderPubKey
            )

            // Get or create conversation
            val conversation = conversationRepository.getOrCreate(myAddress, incoming.senderAddress)

            // Create message entity
            val message = MessageEntity(
                id = incoming.messageId,
                conversationId = conversation.id,
                groupId = null,
                senderAddress = incoming.senderAddress,
                recipientAddress = myAddress,
                contentType = MessageType.TEXT.name,
                content = decrypted,
                encryptedContent = incoming.encrypted.ciphertext,
                timestamp = incoming.timestamp,
                status = MessageStatus.DELIVERED,
                replyToId = null,
                isDeleted = false,
                signature = incoming.signature
            )

            // Save to database
            messageRepository.insertIfNotExists(message)

            // Update conversation
            conversationRepository.updateLastMessage(
                conversation.id,
                message.id,
                decrypted.take(100),
                message.timestamp
            )
            conversationRepository.incrementUnread(conversation.id)

            // Send delivery acknowledgment
            p2pManager.sendDeliveryAck(incoming.senderAddress, incoming.messageId)

        } catch (e: Exception) {
            Timber.e(e, "Failed to handle incoming message")
        }
    }

    private suspend fun syncPendingMessages() {
        try {
            val pendingMessages = p2pManager.fetchPendingMessages()
            for (incoming in pendingMessages) {
                handleIncomingMessage(incoming)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync pending messages")
        }
    }

    /**
     * Cleanup when wallet changes or app closes.
     */
    fun cleanup() {
        p2pManager.disconnect()
        chatKeys = null
        _isInitialized.value = false
        _registrationRequired.value = false
        // Clear caches to avoid stale data
        registrationManager.clearCache()
        chatKeyManager.clearCache()
    }
    
    /**
     * Force re-check registration status from blockchain (bypasses cache).
     */
    suspend fun forceCheckRegistration(address: String): Boolean {
        // Clear cache first to get fresh data
        registrationManager.clearCache()
        return registrationManager.isRegistered(address)
    }
}
