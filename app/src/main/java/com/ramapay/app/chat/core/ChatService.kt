package com.ramapay.app.chat.core

import android.content.Context
import com.ramapay.app.chat.blockchain.MumbleChatBlockchainService
import com.ramapay.app.chat.crypto.ChatKeyManager
import com.ramapay.app.chat.crypto.MessageEncryption
import com.ramapay.app.chat.data.dao.ContactDao
import com.ramapay.app.chat.data.entity.MessageEntity
import com.ramapay.app.chat.data.entity.MessageStatus
import com.ramapay.app.chat.data.entity.MessageType
import com.ramapay.app.chat.data.repository.ConversationRepository
import com.ramapay.app.chat.data.repository.GroupRepository
import com.ramapay.app.chat.data.repository.MessageRepository
import com.ramapay.app.chat.network.P2PManager
import com.ramapay.app.chat.p2p.P2PTransport
import com.ramapay.app.chat.p2p.QRCodePeerExchange
import com.ramapay.app.chat.protocol.MessageCodec
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
 * 
 * NEW: Now integrates with MumbleChat Protocol v1.0 (P2PTransport)
 */
@Singleton
class ChatService @Inject constructor(
    private val context: Context,
    private val p2pManager: P2PManager,
    private val p2pTransport: P2PTransport,  // NEW: MumbleChat Protocol transport
    private val qrCodePeerExchange: QRCodePeerExchange,  // NEW: QR code peer discovery
    private val messageCodec: MessageCodec,  // NEW: Binary protocol codec
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val groupRepository: GroupRepository,
    private val chatKeyManager: ChatKeyManager,
    private val messageEncryption: MessageEncryption,
    private val walletBridge: WalletBridge,
    private val registrationManager: RegistrationManager,
    private val blockchainService: MumbleChatBlockchainService,
    private val contactDao: ContactDao
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
            Timber.d("ChatService: Initializing for wallet $walletAddress")
            
            // 1. Derive chat keys from wallet
            chatKeys = chatKeyManager.deriveChatKeys()
            
            if (chatKeys == null) {
                Timber.e("ChatService: Failed to derive chat keys")
                return Result.failure(Exception("Failed to derive chat keys"))
            }
            Timber.d("ChatService: Chat keys derived successfully")

            // 2. Check registration status
            val isRegistered = registrationManager.isRegistered(walletAddress)
            Timber.d("ChatService: Registration status for $walletAddress: $isRegistered")

            if (!isRegistered) {
                _registrationRequired.value = true
                // Can continue but will need to register before chatting
                Timber.d("ChatService: User not registered, waiting for registration")
                return Result.success(Unit)
            }

            // User is registered - mark as initialized immediately
            // P2P connection happens in background
            _registrationRequired.value = false
            _isInitialized.value = true
            Timber.d("ChatService: User is registered, marked as initialized")

            // 3. Connect to P2P network (in background, non-blocking)
            try {
                p2pManager.initialize(chatKeys!!, walletAddress)
                p2pManager.connect()
                Timber.d("ChatService: P2P connection initiated")
            } catch (e: Exception) {
                // P2P failure shouldn't block chat functionality
                // Messages will be sent via relay
                Timber.w(e, "ChatService: P2P connection failed, will use relay")
            }

            // 4. Start listening for incoming messages
            try {
                startMessageListener()
            } catch (e: Exception) {
                Timber.w(e, "ChatService: Message listener failed to start")
            }

            // 5. Sync pending messages from relays
            try {
                syncPendingMessages()
            } catch (e: Exception) {
                Timber.w(e, "ChatService: Pending message sync failed")
            }

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
            // 0. Check if recipient has blocked sender
            val canSend = blockchainService.canSendMessage(senderAddress, recipientAddress)
            if (!canSend) {
                return Result.failure(Exception("Cannot send message - you may be blocked by this user"))
            }
            
            // Also check local block status - don't send to blocked users
            val contact = contactDao.getByAddress(senderAddress, recipientAddress)
            if (contact?.isBlocked == true) {
                return Result.failure(Exception("Cannot send message to blocked user"))
            }

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

            // 5. Encrypt message with AEAD binding (prevents replay attacks)
            // AAD = senderNodeId || recipientNodeId || SHA256(messageId)
            val senderNodeId = senderAddress.lowercase().removePrefix("0x").take(32).toByteArray(Charsets.UTF_8)
            val recipientNodeId = recipientAddress.lowercase().removePrefix("0x").take(32).toByteArray(Charsets.UTF_8)
            
            val encrypted = messageEncryption.encryptMessage(
                content,
                keys.sessionPrivate,
                recipientPubKey,
                senderNodeId,      // AEAD: prevents sender spoofing
                recipientNodeId,   // AEAD: prevents recipient confusion
                message.id         // AEAD: prevents replay attacks
            )

            // 6. Send via P2P (try new protocol first, fallback to legacy)
            val encryptedBytes = encrypted.toBytes()
            val sendResult = try {
                // Try MumbleChat Protocol v1.0 (P2PTransport)
                val protocolMessage = messageCodec.encodeMessage(
                    messageId = message.id,
                    payload = encryptedBytes,
                    flags = MessageCodec.FLAG_ENCRYPTED
                )
                val p2pResult = p2pTransport.sendMessage(recipientAddress, protocolMessage)
                if (p2pResult.isSuccess) {
                    val result = p2pResult.getOrNull()
                    P2PManager.SendResult(
                        direct = result?.direct ?: false,
                        relayed = result?.relayed ?: false
                    )
                } else {
                    // Fallback to legacy P2PManager
                    Timber.d("Falling back to legacy P2P for $recipientAddress")
                    p2pManager.sendMessage(recipientAddress, encrypted, message.id)
                }
            } catch (e: Exception) {
                Timber.w(e, "Protocol transport failed, using legacy")
                p2pManager.sendMessage(recipientAddress, encrypted, message.id)
            }

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
            // Listen to legacy P2PManager
            p2pManager.incomingMessages.collect { incoming ->
                handleIncomingMessage(incoming)
            }
        }
        
        // Also listen to new P2PTransport (MumbleChat Protocol v1.0)
        scope.launch {
            p2pTransport.incomingMessages.collect { protoMessage ->
                handleProtocolMessage(protoMessage)
            }
        }
    }

    /**
     * Handle messages from MumbleChat Protocol v1.0 (P2PTransport)
     */
    private suspend fun handleProtocolMessage(protoMessage: P2PTransport.IncomingMessage) {
        try {
            val keys = chatKeys ?: return
            val myAddress = walletBridge.getCurrentWalletAddress() ?: return

            // Only process chat messages
            if (protoMessage.type != MessageCodec.MessageType.CHAT_MESSAGE && 
                protoMessage.type != MessageCodec.MessageType.DATA) {
                return
            }

            // Decode protocol message
            val decoded = messageCodec.decodeMessage(protoMessage.data)
            
            // Get sender's public key
            val senderPubKey = registrationManager.getPublicKey(protoMessage.senderAddress) ?: return

            // Build AEAD AAD for decryption (must match sender's encryption)
            val senderNodeId = protoMessage.senderAddress.lowercase().removePrefix("0x").take(32).toByteArray(Charsets.UTF_8)
            val recipientNodeId = myAddress.lowercase().removePrefix("0x").take(32).toByteArray(Charsets.UTF_8)

            // Parse encrypted payload
            val encrypted = MessageEncryption.EncryptedMessage.fromBytes(decoded.payload)

            // Decrypt with AEAD verification (will fail if AAD doesn't match = replay attack)
            val decrypted = messageEncryption.decryptMessage(
                encrypted,
                keys.sessionPrivate,
                senderPubKey,
                senderNodeId,      // AEAD: verify sender
                recipientNodeId,   // AEAD: verify recipient
                decoded.messageId  // AEAD: verify message ID
            )

            // Get or create conversation
            val conversation = conversationRepository.getOrCreate(myAddress, protoMessage.senderAddress)

            // Create message entity
            val message = MessageEntity(
                id = decoded.messageId,
                conversationId = conversation.id,
                groupId = null,
                senderAddress = protoMessage.senderAddress,
                recipientAddress = myAddress,
                contentType = MessageType.TEXT.name,
                content = decrypted,
                encryptedContent = decoded.payload,
                timestamp = protoMessage.timestamp,
                status = MessageStatus.DELIVERED,
                replyToId = null,
                isDeleted = false,
                signature = null
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

            // Send delivery acknowledgment via protocol
            p2pTransport.sendDeliveryAck(protoMessage.senderAddress, decoded.messageId)

        } catch (e: javax.crypto.AEADBadTagException) {
            Timber.e(e, "AEAD verification failed - possible replay attack from ${protoMessage.senderAddress}")
            // Do not process - this message has been tampered with or replayed
        } catch (e: Exception) {
            Timber.e(e, "Failed to handle protocol message")
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
    
    /**
     * Wait for a transaction to be confirmed with the specified number of block confirmations.
     * Delegates to the blockchain service.
     */
    suspend fun waitForConfirmations(
        txHash: String,
        requiredConfirmations: Int = 2,
        maxWaitTimeMs: Long = 90000,
        pollIntervalMs: Long = 2000
    ) = blockchainService.waitForConfirmations(txHash, requiredConfirmations, maxWaitTimeMs, pollIntervalMs)
    
    // ========== QR Code Peer Exchange ==========
    
    /**
     * Generate a QR code for peer discovery.
     * This QR can be scanned by another user to establish a P2P connection.
     * 
     * The QR contains:
     * - Wallet address
     * - Current IP/port
     * - Timestamp (expires after 5 minutes)
     * - Signature (proves ownership)
     * 
     * @param size QR code size in pixels
     * @return Bitmap of the QR code, or null on failure
     */
    suspend fun generatePeerQRCode(size: Int = 400): android.graphics.Bitmap? {
        return try {
            val walletAddress = walletBridge.getCurrentWalletAddress() ?: return null
            val keys = chatKeys ?: return null
            
            val result = qrCodePeerExchange.generateQRCode(
                walletAddress = walletAddress,
                privateKeyBytes = keys.identityPrivate,
                publicEndpoint = null  // Will be discovered via STUN
            )
            
            when (result) {
                is QRCodePeerExchange.QRCodeResult.Success -> result.bitmap
                is QRCodePeerExchange.QRCodeResult.Error -> {
                    Timber.e("QR generation failed: ${result.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate peer QR code")
            null
        }
    }
    
    /**
     * Get the deep link URL for peer connection.
     * Can be shared via NFC, messaging, etc.
     * 
     * @return Deep link URL (mumblechat://connect?wallet=...&ip=...&port=...&ts=...&sig=...)
     */
    suspend fun getPeerDeepLink(): String? {
        return try {
            val walletAddress = walletBridge.getCurrentWalletAddress() ?: return null
            val keys = chatKeys ?: return null
            
            val result = qrCodePeerExchange.generateQRCode(
                walletAddress = walletAddress,
                privateKeyBytes = keys.identityPrivate,
                publicEndpoint = null
            )
            
            when (result) {
                is QRCodePeerExchange.QRCodeResult.Success -> result.deepLink
                is QRCodePeerExchange.QRCodeResult.Error -> null
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate peer deep link")
            null
        }
    }
    
    /**
     * Parse and process a scanned QR code or deep link.
     * If valid, will attempt to establish connection to the peer.
     * 
     * @param qrContent The scanned QR code content or deep link URL
     * @return Result with connected wallet address on success
     */
    suspend fun processPeerQRCode(qrContent: String): Result<String> {
        return try {
            when (val result = qrCodePeerExchange.parseQRCode(qrContent)) {
                is QRCodePeerExchange.ParseResult.Success -> {
                    val walletAddress = result.peer.walletAddress
                    Timber.i("Successfully added peer from QR: $walletAddress")
                    Result.success(walletAddress)
                }
                is QRCodePeerExchange.ParseResult.InvalidFormat -> {
                    Result.failure(Exception(result.message))
                }
                is QRCodePeerExchange.ParseResult.Expired -> {
                    Result.failure(Exception(result.message))
                }
                is QRCodePeerExchange.ParseResult.InvalidSignature -> {
                    Result.failure(Exception(result.message))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to process peer QR code")
            Result.failure(e)
        }
    }
    
    /**
     * Check if a deep link is a MumbleChat peer connection link.
     */
    fun isMumbleChatDeepLink(url: String): Boolean {
        return url.startsWith("mumblechat://connect")
    }
}
