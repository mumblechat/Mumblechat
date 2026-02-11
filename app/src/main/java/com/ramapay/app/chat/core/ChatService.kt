package com.ramapay.app.chat.core

import android.content.Context
import android.content.SharedPreferences
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
import com.ramapay.app.chat.network.HubConnection
import com.ramapay.app.chat.network.HybridNetworkManager
import com.ramapay.app.chat.network.MobileRelayServer
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
 * - Hub relay connections (for web app compatibility)
 * - Mobile relay server (phone as relay node)
 * - Message encryption/decryption
 * 
 * IMPORTANT: This service does NOT modify any wallet data.
 * All wallet access is READ-ONLY through WalletBridge.
 * 
 * NEW: Integrates with HubConnection for web app compatibility
 * NEW: Integrates with MobileRelayServer for phone-as-relay
 */
@Singleton
class ChatService @Inject constructor(
    private val context: Context,
    private val p2pManager: P2PManager,
    private val p2pTransport: P2PTransport,  // MumbleChat Protocol transport
    private val qrCodePeerExchange: QRCodePeerExchange,  // QR code peer discovery
    private val messageCodec: MessageCodec,  // Binary protocol codec
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val groupRepository: GroupRepository,
    private val chatKeyManager: ChatKeyManager,
    private val messageEncryption: MessageEncryption,
    private val walletBridge: WalletBridge,
    private val registrationManager: RegistrationManager,
    private val blockchainService: MumbleChatBlockchainService,
    private val contactDao: ContactDao,
    // NEW: Hub connection for web app compatibility
    private val hubConnection: HubConnection,
    private val mobileRelayServer: MobileRelayServer,
    private val hybridNetworkManager: HybridNetworkManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Shared preferences for relay settings
    private val preferences: SharedPreferences by lazy {
        context.getSharedPreferences("mumblechat_relay_prefs", Context.MODE_PRIVATE)
    }
    
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized
    
    private val _registrationRequired = MutableStateFlow(false)
    val registrationRequired: StateFlow<Boolean> = _registrationRequired
    
    // Hub connection state exposed for UI
    val hubConnectionState: StateFlow<HubConnection.HubConnectionState> = hubConnection.connectionState
    
    // Mobile relay server state exposed for UI
    val mobileRelayState: StateFlow<MobileRelayServer.RelayServerState> = mobileRelayServer.serverState
    
    // Hybrid network mode
    val networkConnectionMode: StateFlow<HybridNetworkManager.ConnectionMode> = hybridNetworkManager.connectionMode
    
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

            // 4. Connect to Hub relay (for web app compatibility)
            try {
                val publicKeyBase64 = android.util.Base64.encodeToString(
                    chatKeys!!.sessionPublic, android.util.Base64.NO_WRAP
                )
                
                // Get display name from profile if available
                val displayName = walletBridge.getDisplayName() ?: walletAddress.take(8)
                
                hubConnection.connect(walletAddress, displayName, publicKeyBase64)
                Timber.d("ChatService: Hub connection initiated")
            } catch (e: Exception) {
                // Hub failure shouldn't block chat
                Timber.w(e, "ChatService: Hub connection failed, will use P2P only")
            }

            // 5. Start listening for incoming messages
            try {
                startMessageListener()
            } catch (e: Exception) {
                Timber.w(e, "ChatService: Message listener failed to start")
            }

            // 6. Sync pending messages from relays
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

            // 6. Send via Hub relay FIRST for cross-platform compatibility
            // NOTE: Mobile and web use incompatible E2EE schemes, so we send plaintext via hub
            // Hub connection is TLS encrypted, providing transport security
            val publicKeyBase64 = android.util.Base64.encodeToString(
                keys.sessionPublic,
                android.util.Base64.NO_WRAP
            )
            
            val hubSent = try {
                hubConnection.sendMessage(
                    to = recipientAddress,
                    encryptedPayload = content,  // Send plaintext for cross-platform
                    messageId = message.id,
                    encrypted = false,  // Mark as unencrypted for web compatibility
                    senderPublicKey = publicKeyBase64
                )
            } catch (e: Exception) {
                Timber.w(e, "Hub relay failed")
                false
            }
            
            // 6b. If Hub relay succeeded, we're done. Otherwise try P2P (encrypted)
            val finalSendResult = if (hubSent) {
                Timber.d("Message sent via Hub relay: ${message.id}")
                P2PManager.SendResult(direct = false, relayed = true)
            } else {
                // Hub unavailable, fall back to P2P encrypted (mobile-to-mobile)
                Timber.d("Hub relay failed, trying P2P for $recipientAddress")
                val encryptedBytes = encrypted.toBytes()
                try {
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
                        Timber.d("Falling back to legacy P2P for $recipientAddress")
                        p2pManager.sendMessage(recipientAddress, encrypted, message.id)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "P2P transport failed, using legacy")
                    p2pManager.sendMessage(recipientAddress, encrypted, message.id)
                }
            }

            // 7. Update status
            val newStatus = when {
                finalSendResult.direct -> MessageStatus.SENT_DIRECT
                finalSendResult.relayed -> MessageStatus.SENT_TO_RELAY
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
        
        // Listen to Hub messages (for web app compatibility)
        scope.launch {
            hubConnection.incomingMessages.collect { hubMessage ->
                handleHubMessage(hubMessage)
            }
        }
        
        // Listen to Hub delivery status updates
        scope.launch {
            hubConnection.deliveryStatus.collect { status ->
                handleHubDeliveryStatus(status)
            }
        }
        
        // Listen to Hybrid Network messages (unified layer)
        scope.launch {
            hybridNetworkManager.incomingMessages.collect { msg ->
                handleHybridMessage(msg)
            }
        }
        
        // Listen to Hybrid Network delivery updates
        scope.launch {
            hybridNetworkManager.deliveryStatus.collect { update ->
                handleHybridDeliveryUpdate(update)
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
        
        // Also sync from hub
        try {
            hubConnection.requestSync()
            Timber.d("ChatService: Hub sync requested")
        } catch (e: Exception) {
            Timber.w(e, "Failed to sync from hub")
        }
    }
    
    /**
     * Handle incoming messages from Hub relay
     */
    private suspend fun handleHubMessage(hubMessage: HubConnection.HubMessage) {
        try {
            val keys = chatKeys ?: return
            val myAddress = walletBridge.getCurrentWalletAddress() ?: return
            
            val encryptedPayload = hubMessage.encryptedData ?: hubMessage.payload ?: return
            
            // Decrypt message if we have sender's public key
            var decryptedContent: String? = null
            if (hubMessage.encrypted && hubMessage.senderPublicKey != null) {
                try {
                    val payloadBytes = android.util.Base64.decode(encryptedPayload, android.util.Base64.NO_WRAP)
                    val senderPubKeyBytes = android.util.Base64.decode(hubMessage.senderPublicKey, android.util.Base64.NO_WRAP)
                    
                    // Split payload into ciphertext and nonce (last 12 bytes)
                    val nonceSize = 12
                    val ciphertext = payloadBytes.copyOfRange(0, payloadBytes.size - nonceSize)
                    val nonce = payloadBytes.copyOfRange(payloadBytes.size - nonceSize, payloadBytes.size)
                    
                    val decrypted = messageEncryption.decrypt(
                        MessageEncryption.SimpleEncryptedMessage(ciphertext, nonce),
                        senderPubKeyBytes
                    )
                    decryptedContent = String(decrypted)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to decrypt hub message")
                    // Store encrypted for later decryption
                    decryptedContent = null
                }
            } else if (!hubMessage.encrypted) {
                // Plaintext message
                decryptedContent = encryptedPayload
            }
            
            // Get or create conversation
            val conversation = conversationRepository.getOrCreate(myAddress, hubMessage.from)
            
            // Determine message status:
            // - If offline message with status='delivered', mark as DELIVERED
            // - Otherwise, default to DELIVERED
            val messageStatus = if (hubMessage.isOfflineMessage && hubMessage.status == "delivered") {
                MessageStatus.DELIVERED
            } else {
                MessageStatus.DELIVERED  // Default for received messages
            }
            
            // Create message entity
            val message = MessageEntity(
                id = hubMessage.messageId,
                conversationId = conversation.id,
                groupId = null,
                senderAddress = hubMessage.from,
                recipientAddress = hubMessage.to,
                contentType = MessageType.TEXT.name,
                content = decryptedContent ?: "[Encrypted]",
                encryptedContent = if (hubMessage.encrypted) encryptedPayload.toByteArray(Charsets.UTF_8) else null,
                timestamp = hubMessage.timestamp,
                status = messageStatus,
                replyToId = null,
                isDeleted = false,
                signature = hubMessage.signature?.let { 
                    try { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) } 
                    catch (e: Exception) { null } 
                }
            )
            
            // Save to database (ignores if already exists)
            messageRepository.insertIfNotExists(message)
            
            // If this is an offline message that was just delivered, update the message status
            if (hubMessage.isOfflineMessage && hubMessage.status == "delivered") {
                messageRepository.updateStatus(hubMessage.messageId, MessageStatus.DELIVERED)
                Timber.d("ChatService: Offline message marked as DELIVERED: ${hubMessage.messageId}")
            }
            
            // Update conversation
            conversationRepository.updateLastMessage(
                conversation.id,
                message.id,
                (decryptedContent ?: "[Encrypted]").take(100),
                message.timestamp
            )
            conversationRepository.incrementUnread(conversation.id)
            
            // Send read receipt back via hub
            hubConnection.sendReadReceipt(hubMessage.messageId, hubMessage.from)
            
            Timber.d("ChatService: Received hub message from ${hubMessage.from}")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to handle hub message")
        }
    }
    
    /**
     * Handle delivery status updates from Hub
     */
    private suspend fun handleHubDeliveryStatus(status: HubConnection.DeliveryStatus) {
        try {
            val newStatus = when (status.status) {
                HubConnection.MessageDeliveryStatus.SENDING -> MessageStatus.SENDING
                HubConnection.MessageDeliveryStatus.SENT -> MessageStatus.SENT_DIRECT
                HubConnection.MessageDeliveryStatus.PENDING -> MessageStatus.SENT_TO_RELAY
                HubConnection.MessageDeliveryStatus.DELIVERED -> MessageStatus.DELIVERED
                HubConnection.MessageDeliveryStatus.READ -> MessageStatus.READ
                HubConnection.MessageDeliveryStatus.FAILED -> MessageStatus.FAILED
            }
            
            messageRepository.updateStatus(status.messageId, newStatus)
            Timber.d("ChatService: Message ${status.messageId} status updated to $newStatus")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to handle hub delivery status")
        }
    }
    
    /**
     * Handle incoming messages from HybridNetworkManager
     */
    private suspend fun handleHybridMessage(msg: HybridNetworkManager.IncomingChatMessage) {
        try {
            val myAddress = walletBridge.getCurrentWalletAddress() ?: return
            
            // Get or create conversation
            val conversation = conversationRepository.getOrCreate(myAddress, msg.from)
            
            // Create message entity
            val message = MessageEntity(
                id = msg.messageId,
                conversationId = conversation.id,
                groupId = null,
                senderAddress = msg.from,
                recipientAddress = msg.to,
                contentType = MessageType.TEXT.name,
                content = msg.decryptedText ?: "[Encrypted]",
                encryptedContent = if (msg.encrypted) msg.encryptedPayload.toByteArray(Charsets.UTF_8) else null,
                timestamp = msg.timestamp,
                status = if (msg.isOffline) MessageStatus.DELIVERED else MessageStatus.DELIVERED,
                replyToId = null,
                isDeleted = false,
                signature = msg.signature?.let { 
                    try { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) } 
                    catch (e: Exception) { null } 
                }
            )
            
            // Save to database (ignores if already exists)
            messageRepository.insertIfNotExists(message)
            
            // Update conversation
            conversationRepository.updateLastMessage(
                conversation.id,
                message.id,
                (msg.decryptedText ?: "[Encrypted]").take(100),
                message.timestamp
            )
            conversationRepository.incrementUnread(conversation.id)
            
            // Send read receipt
            hybridNetworkManager.sendReadReceipt(msg.messageId, msg.from)
            
            Timber.d("ChatService: Received hybrid message from ${msg.from} via ${msg.source}")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to handle hybrid message")
        }
    }
    
    /**
     * Handle delivery status updates from HybridNetworkManager
     */
    private suspend fun handleHybridDeliveryUpdate(update: HybridNetworkManager.MessageDeliveryUpdate) {
        try {
            val newStatus = when (update.status) {
                HybridNetworkManager.DeliveryStatus.SENDING -> MessageStatus.SENDING
                HybridNetworkManager.DeliveryStatus.SENT -> MessageStatus.SENT_DIRECT
                HybridNetworkManager.DeliveryStatus.PENDING -> MessageStatus.SENT_TO_RELAY
                HybridNetworkManager.DeliveryStatus.DELIVERED -> MessageStatus.DELIVERED
                HybridNetworkManager.DeliveryStatus.READ -> MessageStatus.READ
                HybridNetworkManager.DeliveryStatus.FAILED -> MessageStatus.FAILED
            }
            
            messageRepository.updateStatus(update.messageId, newStatus)
            Timber.d("ChatService: Message ${update.messageId} status updated via ${update.deliveryMethod}")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to handle hybrid delivery update")
        }
    }

    /**
     * Cleanup when wallet changes or app closes.
     */
    fun cleanup() {
        p2pManager.disconnect()
        hubConnection.disconnect()
        mobileRelayServer.stop()
        hybridNetworkManager.disconnect()
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
    
    // ═══════════════════════════════════════════════════════════════════════
    // HUB & MOBILE RELAY METHODS
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Send a message via Hub relay (for web app compatibility)
     * This is the preferred method when communicating with web clients
     */
    suspend fun sendMessageViaHub(
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
            
            // 1. Get or create conversation
            val conversation = conversationRepository.getOrCreate(senderAddress, recipientAddress)
            
            // 2. Generate message ID
            val messageId = "msg_${System.currentTimeMillis()}_${(Math.random() * 100000).toInt()}"
            
            // 3. Create message entity
            val message = MessageEntity(
                id = messageId,
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
            
            // 5. Get recipient's public key for encryption
            val recipientPubKey = try {
                registrationManager.getPublicKey(recipientAddress)
            } catch (e: Exception) {
                null // Will send with our key
            }
            
            // 6. Encrypt message
            val encryptedPayload: String
            val isEncrypted: Boolean
            
            if (recipientPubKey != null) {
                val encrypted = messageEncryption.encrypt(content.toByteArray(), recipientPubKey)
                encryptedPayload = android.util.Base64.encodeToString(
                    encrypted.ciphertext + encrypted.nonce,
                    android.util.Base64.NO_WRAP
                )
                isEncrypted = true
            } else {
                // No public key - send plaintext (recipient not registered)
                encryptedPayload = content
                isEncrypted = false
            }
            
            // 7. Get our public key for sender identification
            val publicKeyBase64 = android.util.Base64.encodeToString(
                keys.sessionPublic,
                android.util.Base64.NO_WRAP
            )
            
            // 8. Send via Hub
            val sent = hubConnection.sendMessage(
                to = recipientAddress,
                encryptedPayload = encryptedPayload,
                messageId = messageId,
                encrypted = isEncrypted,
                senderPublicKey = publicKeyBase64
            )
            
            // 9. Update status
            val newStatus = if (sent) MessageStatus.SENT_DIRECT else MessageStatus.FAILED
            messageRepository.updateStatus(messageId, newStatus)
            
            // 10. Update conversation
            conversationRepository.updateLastMessage(
                conversation.id,
                messageId,
                content.take(100),
                message.timestamp
            )
            
            if (sent) {
                Result.success(message.copy(status = newStatus))
            } else {
                Result.failure(Exception("Failed to send via Hub"))
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to send message via Hub")
            Result.failure(e)
        }
    }
    
    /**
     * Set custom relay endpoint (user-provided relay server)
     */
    fun setCustomRelayEndpoint(endpoint: String?) {
        hubConnection.setCustomEndpoint(endpoint)
        
        // Reconnect if we have keys
        if (endpoint != null && chatKeys != null) {
            scope.launch {
                try {
                    val walletAddress = walletBridge.getCurrentWalletAddress() ?: return@launch
                    val publicKeyBase64 = android.util.Base64.encodeToString(
                        chatKeys!!.sessionPublic, android.util.Base64.NO_WRAP
                    )
                    val displayName = walletBridge.getDisplayName() ?: walletAddress.take(8)
                    
                    hubConnection.connect(walletAddress, displayName, publicKeyBase64)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to connect to custom endpoint")
                }
            }
        }
    }
    
    /**
     * Start mobile relay server (turn this phone into a relay node)
     * Other users can connect to this phone to relay their messages
     * 
     * @param port Optional specific port (default: 8765)
     * @return Endpoint URL that can be shared with others
     */
    fun startMobileRelayServer(port: Int = 8765): String? {
        return try {
            val wallet = walletBridge.getCurrentWalletAddress()
            mobileRelayServer.start(port, wallet)
            
            // NOTE: Mobile relay server runs LOCALLY on the phone
            // It also opens a DEDICATED WebSocket to hub at /node/connect
            // This is SEPARATE from the user's chat connection!
            //
            // Architecture:
            // - User chat: hubConnection (as USER at /user/connect) - always active
            // - Mobile relay: mobileRelayServer → hub at /node/connect (as NODE)
            // - Local server: WebSocket server on phone for direct P2P
            
            Timber.d("ChatService: Mobile relay server started on port $port")
            Timber.d("ChatService: Node connecting to hub at /node/connect")
            
            mobileRelayServer.getEndpointUrl()
        } catch (e: Exception) {
            Timber.e(e, "Failed to start mobile relay server")
            null
        }
    }
    
    /**
     * Stop mobile relay server
     */
    fun stopMobileRelayServer() {
        mobileRelayServer.stop()
    }
    
    /**
     * Get the mobile relay endpoint URL for sharing
     */
    fun getMobileRelayEndpoint(): String? {
        return mobileRelayServer.getEndpointUrl()
    }
    
    /**
     * Get mobile relay server statistics
     */
    fun getMobileRelayStats(): MobileRelayServer.RelayStats {
        return mobileRelayServer.stats.value
    }
    
    /**
     * Check if hub is connected
     */
    fun isHubConnected(): Boolean {
        return hubConnection.connectionState.value == HubConnection.HubConnectionState.AUTHENTICATED
    }
    
    /**
     * Check if mobile relay is running
     */
    fun isMobileRelayRunning(): Boolean {
        return mobileRelayServer.serverState.value == MobileRelayServer.RelayServerState.RUNNING
    }
    
    /**
     * Get available relay endpoints from hub
     */
    suspend fun getAvailableRelayEndpoints(): List<HubConnection.RelayEndpoint> {
        return hubConnection.getAvailableEndpoints()
    }
    
    /**
     * Check if a user is online (via hub)
     */
    suspend fun isUserOnlineViaHub(address: String): Boolean {
        return hubConnection.isUserOnline(address)
    }
    
    /**
     * Request sync of offline messages from hub
     */
    fun requestHubSync() {
        hubConnection.requestSync()
    }
    
    // ═══════════ ADVANCED RELAY FEATURES ═══════════
    
    /**
     * Result of a heartbeat operation
     */
    data class HeartbeatResult(
        val success: Boolean,
        val txHash: String? = null,
        val error: String? = null
    )
    
    /**
     * Send a manual heartbeat to the blockchain.
     * This is useful when the user wants to ensure their node is active
     * without waiting for the automatic 5.5 hour interval.
     */
    suspend fun sendManualHeartbeat(): HeartbeatResult {
        return try {
            val walletAddress = walletBridge.getCurrentWalletAddress()
                ?: return HeartbeatResult(false, error = "No wallet connected")
            
            // Get current storage usage
            val storageMB = mobileRelayServer.getCurrentStorageMB()
            
            // Send heartbeat via blockchain service
            val txHash = blockchainService.sendHeartbeat(storageMB)
            
            if (txHash != null) {
                // Store last heartbeat time
                preferences.edit().putLong(KEY_LAST_HEARTBEAT, System.currentTimeMillis()).apply()
                HeartbeatResult(true, txHash = txHash)
            } else {
                HeartbeatResult(false, error = "Transaction failed")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to send manual heartbeat")
            HeartbeatResult(false, error = e.message)
        }
    }
    
    /**
     * Get the last heartbeat timestamp
     */
    fun getLastHeartbeatTime(): Long {
        return preferences.getLong(KEY_LAST_HEARTBEAT, 0L)
    }
    
    /**
     * Get the number of connected P2P peers
     */
    fun getConnectedP2PPeers(): Int {
        return p2pManager.getConnectedPeerCount()
    }
    
    /**
     * Set the connection mode for relay operations
     */
    fun setConnectionMode(mode: com.ramapay.app.chat.ui.ConnectionMode) {
        when (mode) {
            com.ramapay.app.chat.ui.ConnectionMode.HUB_BASED -> {
                // Use only hub connections
                p2pManager.setP2PEnabled(false)
                hubConnection.setEnabled(true)
            }
            com.ramapay.app.chat.ui.ConnectionMode.DIRECT_P2P -> {
                // Use only P2P connections
                p2pManager.setP2PEnabled(true)
                hubConnection.setEnabled(false)
            }
            com.ramapay.app.chat.ui.ConnectionMode.HYBRID -> {
                // Use both
                p2pManager.setP2PEnabled(true)
                hubConnection.setEnabled(true)
            }
        }
        preferences.edit().putString(KEY_CONNECTION_MODE, mode.name).apply()
    }
    
    companion object {
        private const val KEY_LAST_HEARTBEAT = "last_heartbeat_time"
        private const val KEY_CONNECTION_MODE = "connection_mode"
    }
}
