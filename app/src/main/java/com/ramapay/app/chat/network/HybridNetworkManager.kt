package com.ramapay.app.chat.network

import android.content.Context
import com.ramapay.app.chat.crypto.ChatKeyManager
import com.ramapay.app.chat.crypto.MessageEncryption
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * HybridNetworkManager - Unified network layer for MumbleChat
 * ═══════════════════════════════════════════════════════════════════════════
 * 
 * Manages all network connectivity options:
 * 1. Hub Connection (official hub.mumblechat.com)
 * 2. P2P Direct (phone-to-phone when possible)
 * 3. Mobile Relay (when phone acts as relay node)
 * 4. Custom Endpoint (user-provided relay servers)
 * 
 * Priority Order for Message Delivery:
 * 1. Direct P2P (if peer is reachable)
 * 2. Hub relay (if connected to hub)
 * 3. Mobile relay peers (if any available)
 * 4. Store locally for later delivery
 * 
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │                    HybridNetworkManager                                 │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │                                                                         │
 * │   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐   │
 * │   │  Hub Conn   │  │   P2P Mgr   │  │Mobile Relay │  │   Custom    │   │
 * │   │ (WebSocket) │  │ (TCP/UDP)   │  │  (Server)   │  │  Endpoint   │   │
 * │   └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘   │
 * │          │                │                │                │          │
 * │          └────────────────┼────────────────┼────────────────┘          │
 * │                           │                                            │
 * │                    ┌──────▼──────┐                                     │
 * │                    │  Unified    │                                     │
 * │                    │  Message    │                                     │
 * │                    │  Delivery   │                                     │
 * │                    └─────────────┘                                     │
 * │                                                                         │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
@Singleton
class HybridNetworkManager @Inject constructor(
    private val context: Context,
    private val hubConnection: HubConnection,
    private val p2pManager: P2PManager,
    private val mobileRelayServer: MobileRelayServer,
    private val messageEncryption: MessageEncryption
) {
    companion object {
        private const val TAG = "HybridNetworkManager"
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Current connection mode
    private val _connectionMode = MutableStateFlow(ConnectionMode.DISCONNECTED)
    val connectionMode: StateFlow<ConnectionMode> = _connectionMode
    
    // Overall connection state
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected
    
    // Incoming messages from all sources
    private val _incomingMessages = MutableSharedFlow<IncomingChatMessage>(extraBufferCapacity = 100)
    val incomingMessages: SharedFlow<IncomingChatMessage> = _incomingMessages
    
    // Delivery status updates
    private val _deliveryStatus = MutableSharedFlow<MessageDeliveryUpdate>(extraBufferCapacity = 50)
    val deliveryStatus: SharedFlow<MessageDeliveryUpdate> = _deliveryStatus
    
    // User info
    private var walletAddress: String? = null
    private var displayName: String? = null
    private var chatKeys: ChatKeyManager.ChatKeyPair? = null
    
    // Settings
    private var preferP2P = true
    private var enableMobileRelay = false
    private var customEndpoint: String? = null
    
    // ============ Data Classes ============
    
    enum class ConnectionMode {
        DISCONNECTED,
        HUB_ONLY,           // Connected to official hub
        P2P_ONLY,           // P2P mesh only
        HYBRID,             // Both hub and P2P
        MOBILE_RELAY,       // Acting as relay node
        CUSTOM_ENDPOINT     // Connected to custom relay
    }
    
    data class IncomingChatMessage(
        val messageId: String,
        val from: String,
        val to: String,
        val encryptedPayload: String,
        val decryptedText: String?,   // Decrypted if possible
        val encrypted: Boolean,
        val senderPublicKey: String?,
        val signature: String?,
        val timestamp: Long,
        val source: MessageSource,
        val isOffline: Boolean = false
    )
    
    enum class MessageSource {
        HUB,
        P2P,
        MOBILE_RELAY,
        CUSTOM_ENDPOINT
    }
    
    data class MessageDeliveryUpdate(
        val messageId: String,
        val status: DeliveryStatus,
        val recipient: String,
        val deliveryMethod: MessageSource?,
        val timestamp: Long
    )
    
    enum class DeliveryStatus {
        SENDING,
        SENT,
        PENDING,    // Queued for offline user
        DELIVERED,
        READ,
        FAILED
    }
    
    data class NetworkSettings(
        val preferP2P: Boolean = true,
        val enableMobileRelay: Boolean = false,
        val customEndpoint: String? = null,
        val useOfficialHub: Boolean = true
    )
    
    // ============ Initialization ============
    
    /**
     * Initialize the network manager with user credentials
     */
    suspend fun initialize(
        address: String,
        name: String?,
        keys: ChatKeyManager.ChatKeyPair,
        settings: NetworkSettings = NetworkSettings()
    ): Result<Unit> {
        walletAddress = address.lowercase()
        displayName = name
        chatKeys = keys
        
        preferP2P = settings.preferP2P
        enableMobileRelay = settings.enableMobileRelay
        customEndpoint = settings.customEndpoint
        
        return try {
            Timber.d("$TAG: Initializing with settings: $settings")
            
            // Connect to hub (primary connection method)
            if (settings.useOfficialHub || settings.customEndpoint != null) {
                hubConnection.setCustomEndpoint(settings.customEndpoint)
                
                val publicKeyBase64 = android.util.Base64.encodeToString(
                    keys.sessionPublic, android.util.Base64.NO_WRAP
                )
                
                hubConnection.connect(address, name, publicKeyBase64)
            }
            
            // Initialize P2P if preferred
            if (settings.preferP2P) {
                p2pManager.initialize(keys, address)
                p2pManager.connect()
            }
            
            // Start mobile relay if enabled
            if (settings.enableMobileRelay) {
                mobileRelayServer.start()
            }
            
            // Setup message listeners
            setupMessageListeners()
            
            // Update connection mode
            updateConnectionMode()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Initialization failed")
            Result.failure(e)
        }
    }
    
    /**
     * Disconnect from all networks
     */
    fun disconnect() {
        hubConnection.disconnect()
        p2pManager.disconnect()
        mobileRelayServer.stop()
        
        _connectionMode.value = ConnectionMode.DISCONNECTED
        _isConnected.value = false
        
        Timber.d("$TAG: Disconnected from all networks")
    }
    
    // ============ Message Sending ============
    
    /**
     * Send a message to a recipient using the best available method
     */
    suspend fun sendMessage(
        to: String,
        plainText: String,
        contactPublicKey: ByteArray?
    ): Result<String> {
        val keys = chatKeys ?: return Result.failure(Exception("Not initialized"))
        val from = walletAddress ?: return Result.failure(Exception("No wallet address"))
        
        val messageId = generateMessageId()
        val toAddress = to.lowercase()
        
        return try {
            // Encrypt message
            val encryptedPayload = if (contactPublicKey != null) {
                messageEncryption.encrypt(plainText.toByteArray(), contactPublicKey)
            } else {
                // No public key, send plaintext (recipient needs to request key exchange)
                MessageEncryption.SimpleEncryptedMessage(
                    ciphertext = plainText.toByteArray(),
                    nonce = ByteArray(12),
                    isEncrypted = false
                )
            }
            
            val payloadBase64 = android.util.Base64.encodeToString(
                encryptedPayload.toBytes(),
                android.util.Base64.NO_WRAP
            )
            
            val publicKeyBase64 = android.util.Base64.encodeToString(
                keys.sessionPublic,
                android.util.Base64.NO_WRAP
            )
            
            // Emit sending status
            scope.launch {
                _deliveryStatus.emit(MessageDeliveryUpdate(
                    messageId = messageId,
                    status = DeliveryStatus.SENDING,
                    recipient = toAddress,
                    deliveryMethod = null,
                    timestamp = System.currentTimeMillis()
                ))
            }
            
            var sent = false
            var deliveryMethod: MessageSource? = null
            
            // Try P2P first if preferred and available
            if (preferP2P && p2pManager.connectionState.value == ConnectionState.CONNECTED) {
                // Convert SimpleEncryptedMessage to EncryptedMessage for P2P
                val p2pEncrypted = MessageEncryption.EncryptedMessage(
                    nonce = encryptedPayload.nonce,
                    ciphertext = encryptedPayload.ciphertext.copyOfRange(0, encryptedPayload.ciphertext.size - 16.coerceAtMost(encryptedPayload.ciphertext.size)),
                    authTag = if (encryptedPayload.ciphertext.size > 16) 
                        encryptedPayload.ciphertext.copyOfRange(encryptedPayload.ciphertext.size - 16, encryptedPayload.ciphertext.size)
                    else ByteArray(16)
                )
                val p2pResult = p2pManager.sendMessage(toAddress, p2pEncrypted, messageId)
                if (p2pResult.direct || p2pResult.relayed) {
                    sent = true
                    deliveryMethod = MessageSource.P2P
                    Timber.d("$TAG: Message sent via P2P")
                }
            }
            
            // Try hub connection
            if (!sent && hubConnection.connectionState.value == HubConnection.HubConnectionState.AUTHENTICATED) {
                sent = hubConnection.sendMessage(
                    to = toAddress,
                    encryptedPayload = payloadBase64,
                    messageId = messageId,
                    encrypted = encryptedPayload.isEncrypted,
                    senderPublicKey = publicKeyBase64
                )
                if (sent) {
                    deliveryMethod = if (customEndpoint != null) MessageSource.CUSTOM_ENDPOINT else MessageSource.HUB
                    Timber.d("$TAG: Message sent via Hub")
                }
            }
            
            // Try mobile relay connections
            if (!sent && mobileRelayServer.serverState.value == MobileRelayServer.RelayServerState.RUNNING) {
                if (mobileRelayServer.isUserConnected(toAddress)) {
                    // User is connected to our relay - would be delivered locally
                    // This case is handled by the relay server itself
                    sent = true
                    deliveryMethod = MessageSource.MOBILE_RELAY
                }
            }
            
            if (sent) {
                scope.launch {
                    _deliveryStatus.emit(MessageDeliveryUpdate(
                        messageId = messageId,
                        status = DeliveryStatus.SENT,
                        recipient = toAddress,
                        deliveryMethod = deliveryMethod,
                        timestamp = System.currentTimeMillis()
                    ))
                }
                Result.success(messageId)
            } else {
                scope.launch {
                    _deliveryStatus.emit(MessageDeliveryUpdate(
                        messageId = messageId,
                        status = DeliveryStatus.FAILED,
                        recipient = toAddress,
                        deliveryMethod = null,
                        timestamp = System.currentTimeMillis()
                    ))
                }
                Result.failure(Exception("No delivery method available"))
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to send message")
            Result.failure(e)
        }
    }
    
    /**
     * Send read receipt
     */
    fun sendReadReceipt(messageId: String, to: String) {
        hubConnection.sendReadReceipt(messageId, to)
    }
    
    // ============ Connection Management ============
    
    /**
     * Set custom relay endpoint
     */
    fun setCustomEndpoint(endpoint: String?) {
        customEndpoint = endpoint
        hubConnection.setCustomEndpoint(endpoint)
        
        if (endpoint != null) {
            scope.launch {
                walletAddress?.let { address ->
                    val publicKeyBase64 = chatKeys?.sessionPublic?.let {
                        android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)
                    }
                    hubConnection.connect(address, displayName, publicKeyBase64)
                }
            }
        }
    }
    
    /**
     * Enable/disable mobile relay server
     */
    fun setMobileRelayEnabled(enabled: Boolean) {
        enableMobileRelay = enabled
        
        if (enabled) {
            mobileRelayServer.start()
        } else {
            mobileRelayServer.stop()
        }
        
        updateConnectionMode()
    }
    
    /**
     * Get mobile relay endpoint URL for sharing
     */
    fun getMobileRelayEndpoint(): String? {
        return mobileRelayServer.getEndpointUrl()
    }
    
    /**
     * Check if a user is online
     */
    suspend fun isUserOnline(address: String): Boolean {
        // Check P2P first
        if (p2pManager.connectionState.value == ConnectionState.CONNECTED) {
            // TODO: Check P2P peer status
        }
        
        // Check hub
        if (hubConnection.connectionState.value == HubConnection.HubConnectionState.AUTHENTICATED) {
            return hubConnection.isUserOnline(address)
        }
        
        // Check mobile relay
        if (mobileRelayServer.serverState.value == MobileRelayServer.RelayServerState.RUNNING) {
            return mobileRelayServer.isUserConnected(address)
        }
        
        return false
    }
    
    /**
     * Get available relay endpoints
     */
    suspend fun getAvailableEndpoints(): List<HubConnection.RelayEndpoint> {
        return hubConnection.getAvailableEndpoints()
    }
    
    // ============ Private Methods ============
    
    private fun setupMessageListeners() {
        // Hub messages
        scope.launch {
            hubConnection.incomingMessages.collect { msg ->
                handleIncomingMessage(msg, MessageSource.HUB)
            }
        }
        
        // Hub delivery status
        scope.launch {
            hubConnection.deliveryStatus.collect { status ->
                val deliveryStatus = when (status.status) {
                    HubConnection.MessageDeliveryStatus.SENDING -> DeliveryStatus.SENDING
                    HubConnection.MessageDeliveryStatus.SENT -> DeliveryStatus.SENT
                    HubConnection.MessageDeliveryStatus.PENDING -> DeliveryStatus.PENDING
                    HubConnection.MessageDeliveryStatus.DELIVERED -> DeliveryStatus.DELIVERED
                    HubConnection.MessageDeliveryStatus.READ -> DeliveryStatus.READ
                    HubConnection.MessageDeliveryStatus.FAILED -> DeliveryStatus.FAILED
                }
                
                _deliveryStatus.emit(MessageDeliveryUpdate(
                    messageId = status.messageId,
                    status = deliveryStatus,
                    recipient = status.recipient ?: "",
                    deliveryMethod = MessageSource.HUB,
                    timestamp = status.timestamp
                ))
            }
        }
        
        // P2P messages
        scope.launch {
            p2pManager.incomingMessages.collect { msg ->
                handleIncomingP2PMessage(msg)
            }
        }
        
        // Connection state monitoring
        scope.launch {
            combine(
                hubConnection.connectionState,
                p2pManager.connectionState,
                mobileRelayServer.serverState
            ) { hub, p2p, relay ->
                Triple(hub, p2p, relay)
            }.collect {
                updateConnectionMode()
            }
        }
    }
    
    private suspend fun handleIncomingMessage(msg: HubConnection.HubMessage, source: MessageSource) {
        val encryptedPayload = msg.encryptedData ?: msg.payload ?: return
        
        // Try to decrypt
        var decryptedText: String? = null
        if (msg.encrypted && msg.senderPublicKey != null) {
            try {
                val payloadBytes = android.util.Base64.decode(encryptedPayload, android.util.Base64.NO_WRAP)
                val senderPubKeyBytes = android.util.Base64.decode(msg.senderPublicKey, android.util.Base64.NO_WRAP)
                
                // Split payload into ciphertext and nonce
                val nonceSize = 12
                val ciphertext = payloadBytes.copyOfRange(0, payloadBytes.size - nonceSize)
                val nonce = payloadBytes.copyOfRange(payloadBytes.size - nonceSize, payloadBytes.size)
                
                val decrypted = messageEncryption.decrypt(
                    MessageEncryption.SimpleEncryptedMessage(ciphertext, nonce),
                    senderPubKeyBytes
                )
                decryptedText = String(decrypted)
            } catch (e: Exception) {
                Timber.w(e, "$TAG: Failed to decrypt message")
            }
        } else if (!msg.encrypted) {
            decryptedText = encryptedPayload
        }
        
        _incomingMessages.emit(IncomingChatMessage(
            messageId = msg.messageId,
            from = msg.from,
            to = msg.to,
            encryptedPayload = encryptedPayload,
            decryptedText = decryptedText,
            encrypted = msg.encrypted,
            senderPublicKey = msg.senderPublicKey,
            signature = msg.signature,
            timestamp = msg.timestamp,
            source = source,
            isOffline = msg.isOfflineMessage
        ))
    }
    
    private suspend fun handleIncomingP2PMessage(msg: P2PManager.IncomingMessage) {
        val encryptedPayload = android.util.Base64.encodeToString(
            msg.encrypted.toBytes(),
            android.util.Base64.NO_WRAP
        )
        
        // P2P messages are always encrypted - try to decrypt
        var decryptedText: String? = null
        try {
            // Need sender's public key from contacts
            // TODO: Get from contact store
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed to decrypt P2P message")
        }
        
        _incomingMessages.emit(IncomingChatMessage(
            messageId = msg.messageId,
            from = msg.senderAddress,
            to = walletAddress ?: "",
            encryptedPayload = encryptedPayload,
            decryptedText = decryptedText,
            encrypted = true, // P2P messages are always encrypted
            senderPublicKey = null, // TODO: Include in P2P message
            signature = msg.signature?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) },
            timestamp = msg.timestamp,
            source = MessageSource.P2P,
            isOffline = false
        ))
    }
    
    private fun updateConnectionMode() {
        val hubConnected = hubConnection.connectionState.value == HubConnection.HubConnectionState.AUTHENTICATED
        val p2pConnected = p2pManager.connectionState.value == ConnectionState.CONNECTED
        val relayRunning = mobileRelayServer.serverState.value == MobileRelayServer.RelayServerState.RUNNING
        
        _connectionMode.value = when {
            customEndpoint != null && hubConnected -> ConnectionMode.CUSTOM_ENDPOINT
            relayRunning && hubConnected -> ConnectionMode.MOBILE_RELAY
            hubConnected && p2pConnected -> ConnectionMode.HYBRID
            hubConnected -> ConnectionMode.HUB_ONLY
            p2pConnected -> ConnectionMode.P2P_ONLY
            else -> ConnectionMode.DISCONNECTED
        }
        
        _isConnected.value = hubConnected || p2pConnected || relayRunning
        
        Timber.d("$TAG: Connection mode: ${_connectionMode.value}")
    }
    
    private fun generateMessageId(): String {
        return "msg_${System.currentTimeMillis()}_${(Math.random() * 100000).toInt()}"
    }
}
