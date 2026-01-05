package com.ramapay.app.chat.p2p

import com.ramapay.app.chat.nat.HolePuncher
import com.ramapay.app.chat.nat.StunClient
import com.ramapay.app.chat.protocol.MessageCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * P2P Transport Layer for MumbleChat Protocol
 * 
 * Handles all network communication including:
 * - STUN for public IP discovery
 * - UDP hole punching for NAT traversal
 * - Direct P2P connections
 * - Message encoding/decoding
 * - Reliable delivery with retries
 * - Peer connection management
 * 
 * ZERO-COST: Uses only free STUN servers (Google, Cloudflare)
 */
@Singleton
class P2PTransport @Inject constructor(
    private val stunClient: StunClient,
    private val holePuncher: HolePuncher,
    private val bootstrapManager: BootstrapManager,
    private val dht: KademliaDHT,
    private val messageCodec: MessageCodec
) {
    companion object {
        private const val TAG = "P2PTransport"
        
        // Ports
        private const val P2P_PORT = 19372
        private const val RECEIVE_BUFFER_SIZE = 65536
        
        // Timeouts and retries
        private const val ACK_TIMEOUT_MS = 5000L
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
        
        // Connection limits
        private const val MAX_ACTIVE_CONNECTIONS = 20
    }
    
    // State
    private var myNodeId: ByteArray = ByteArray(32)
    private var myWalletAddress: String = ""
    private var socket: DatagramSocket? = null
    private var isRunning = false
    
    // Connections
    private val activeConnections = ConcurrentHashMap<String, PeerConnection>()
    
    // Pending acknowledgments
    private val pendingAcks = ConcurrentHashMap<Int, PendingMessage>()
    
    // Message flow
    private val _incomingMessages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 100)
    val incomingMessages: SharedFlow<IncomingMessage> = _incomingMessages.asSharedFlow()
    
    // Connection state flow
    private val _connectionState = MutableStateFlow(TransportState.STOPPED)
    val connectionState: StateFlow<TransportState> = _connectionState.asStateFlow()
    
    // Public endpoint
    private var myPublicEndpoint: StunClient.StunResult? = null
    
    enum class TransportState {
        STOPPED,
        STARTING,
        DISCOVERING,  // STUN discovery
        BOOTSTRAPPING, // Finding peers
        RUNNING,
        ERROR
    }
    
    data class PeerConnection(
        val walletAddress: String,
        val nodeId: ByteArray,
        val address: InetSocketAddress,
        val state: ConnectionState,
        val connectedAt: Long = System.currentTimeMillis(),
        val lastActivity: Long = System.currentTimeMillis(),
        val messagesSent: Int = 0,
        val messagesReceived: Int = 0
    ) {
        enum class ConnectionState {
            CONNECTING,
            CONNECTED,
            DISCONNECTING,
            DISCONNECTED
        }
    }
    
    /**
     * Incoming message from the protocol.
     * Used for compatibility with ChatService.
     */
    data class IncomingMessage(
        val from: String,  // Wallet address (legacy)
        val senderAddress: String,  // Wallet address (ChatService compatible)
        val type: MessageCodec.MessageType,
        val payload: ByteArray,
        val data: ByteArray,  // Raw data (same as payload)
        val timestamp: Long = System.currentTimeMillis()
    ) {
        constructor(from: String, type: MessageCodec.MessageType, payload: ByteArray, timestamp: Long = System.currentTimeMillis()) 
            : this(from, from, type, payload, payload, timestamp)
    }
    
    /**
     * Protocol message for ChatService integration.
     */
    data class ProtocolMessage(
        val senderAddress: String,
        val data: ByteArray,
        val timestamp: Long
    )
    
    /**
     * Send result for ChatService compatibility.
     */
    data class SendResult(
        val direct: Boolean,
        val relayed: Boolean
    )
    
    data class PendingMessage(
        val sequenceNumber: Int,
        val encoded: ByteArray,
        val destination: InetSocketAddress,
        val sentAt: Long,
        val retries: Int = 0
    )
    
    /**
     * Start the transport layer.
     */
    suspend fun start(walletAddress: String) = withContext(Dispatchers.IO) {
        if (isRunning) {
            Timber.w("$TAG: Already running")
            return@withContext
        }
        
        Timber.i("$TAG: Starting P2P transport for $walletAddress")
        _connectionState.value = TransportState.STARTING
        
        myWalletAddress = walletAddress
        myNodeId = dht.walletToNodeId(walletAddress)
        dht.initialize(walletAddress)
        
        try {
            // Create UDP socket
            socket = DatagramSocket(P2P_PORT)
            socket?.soTimeout = 1000
            
            isRunning = true
            
            // Discover public endpoint via STUN
            _connectionState.value = TransportState.DISCOVERING
            myPublicEndpoint = stunClient.discoverPublicAddress(socket!!)
            
            if (myPublicEndpoint != null) {
                Timber.i("$TAG: Public endpoint: ${myPublicEndpoint?.publicIp}:${myPublicEndpoint?.publicPort}")
            } else {
                Timber.w("$TAG: Could not discover public endpoint (may be behind strict NAT)")
            }
            
            // Bootstrap - find peers
            _connectionState.value = TransportState.BOOTSTRAPPING
            val peers = bootstrapManager.bootstrap(walletAddress)
            Timber.i("$TAG: Bootstrap found ${peers.size} peers")
            
            // Add peers to DHT
            peers.forEach { peer ->
                dht.addNode(KademliaDHT.DHTNode(
                    walletAddress = peer.walletAddress,
                    publicIp = peer.publicIp,
                    publicPort = peer.publicPort,
                    isRelay = peer.source == BootstrapManager.PeerSource.BLOCKCHAIN
                ))
            }
            
            // Start receive loop
            _connectionState.value = TransportState.RUNNING
            launch { receiveLoop() }
            
            // Start maintenance loop
            launch { maintenanceLoop() }
            
            // Start retry loop for pending messages
            launch { retryLoop() }
            
            Timber.i("$TAG: Transport started successfully")
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start transport")
            _connectionState.value = TransportState.ERROR
            stop()
        }
    }
    
    /**
     * Stop the transport layer.
     */
    fun stop() {
        Timber.i("$TAG: Stopping P2P transport")
        isRunning = false
        
        // Disconnect all peers
        activeConnections.values.forEach { conn ->
            disconnectPeer(conn.walletAddress)
        }
        activeConnections.clear()
        
        socket?.close()
        socket = null
        
        _connectionState.value = TransportState.STOPPED
    }
    
    /**
     * Connect to a peer.
     */
    suspend fun connectToPeer(peerWallet: String): Boolean = withContext(Dispatchers.IO) {
        if (activeConnections.containsKey(peerWallet.lowercase())) {
            Timber.d("$TAG: Already connected to $peerWallet")
            return@withContext true
        }
        
        if (activeConnections.size >= MAX_ACTIVE_CONNECTIONS) {
            Timber.w("$TAG: Max connections reached")
            return@withContext false
        }
        
        Timber.d("$TAG: Connecting to peer: $peerWallet")
        
        // Find peer info
        val peerInfo = bootstrapManager.activePeers.value[peerWallet.lowercase()]
        if (peerInfo == null) {
            Timber.w("$TAG: Peer not found in bootstrap: $peerWallet")
            return@withContext false
        }
        
        try {
            // Try hole punching
            val result = holePuncher.punchHole(
                HolePuncher.PeerEndpoint(
                    publicIp = peerInfo.publicIp,
                    publicPort = peerInfo.publicPort,
                    walletAddress = peerWallet
                ),
                myWalletAddress
            )
            
            if (result.success && result.peerAddress != null) {
                val connection = PeerConnection(
                    walletAddress = peerWallet,
                    nodeId = dht.walletToNodeId(peerWallet),
                    address = result.peerAddress,
                    state = PeerConnection.ConnectionState.CONNECTED
                )
                
                activeConnections[peerWallet.lowercase()] = connection
                bootstrapManager.markPeerConnected(peerWallet)
                
                // Send handshake
                sendHandshake(connection)
                
                Timber.i("$TAG: Connected to peer: $peerWallet")
                return@withContext true
            }
            
            Timber.w("$TAG: Hole punch failed for $peerWallet: ${result.method}")
            return@withContext false
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to connect to $peerWallet")
            return@withContext false
        }
    }
    
    /**
     * Disconnect from a peer.
     */
    fun disconnectPeer(peerWallet: String) {
        val key = peerWallet.lowercase()
        val connection = activeConnections.remove(key) ?: return
        
        // Send disconnect message
        runCatching {
            val encoded = messageCodec.encode(
                type = MessageCodec.MessageType.DISCONNECT,
                payload = ByteArray(0),
                sourceNodeId = myNodeId,
                destNodeId = connection.nodeId
            )
            sendRaw(encoded.bytes, connection.address)
        }
        
        bootstrapManager.markPeerDisconnected(peerWallet)
        Timber.d("$TAG: Disconnected from $peerWallet")
    }
    
    /**
     * Send a message to a peer.
     */
    suspend fun sendMessage(
        peerWallet: String,
        type: MessageCodec.MessageType,
        payload: ByteArray,
        requireAck: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val key = peerWallet.lowercase()
        val connection = activeConnections[key]
        
        if (connection == null) {
            Timber.w("$TAG: Not connected to $peerWallet")
            return@withContext false
        }
        
        try {
            var flags: Short = 0
            if (requireAck) {
                flags = (flags.toInt() or MessageCodec.Flags.REQUIRE_ACK.toInt()).toShort()
            }
            
            val encoded = messageCodec.encode(
                type = type,
                payload = payload,
                sourceNodeId = myNodeId,
                destNodeId = connection.nodeId,
                flags = flags
            )
            
            sendRaw(encoded.bytes, connection.address)
            
            if (requireAck) {
                pendingAcks[encoded.sequenceNumber] = PendingMessage(
                    sequenceNumber = encoded.sequenceNumber,
                    encoded = encoded.bytes,
                    destination = connection.address,
                    sentAt = System.currentTimeMillis()
                )
            }
            
            // Update stats
            activeConnections[key] = connection.copy(
                lastActivity = System.currentTimeMillis(),
                messagesSent = connection.messagesSent + 1
            )
            
            true
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to send message to $peerWallet")
            false
        }
    }
    
    /**
     * Send a message to a peer (ChatService compatible).
     * This is the high-level interface used by ChatService.
     * 
     * @param recipientAddress Wallet address of recipient
     * @param encodedMessage Pre-encoded message bytes (from MessageCodec)
     * @return Result containing SendResult with direct/relayed status
     */
    suspend fun sendMessage(
        recipientAddress: String,
        encodedMessage: ByteArray
    ): Result<SendResult> = withContext(Dispatchers.IO) {
        try {
            val key = recipientAddress.lowercase()
            val connection = activeConnections[key]
            
            if (connection != null && connection.state == PeerConnection.ConnectionState.CONNECTED) {
                // Direct P2P connection available
                sendRaw(encodedMessage, connection.address)
                
                // Update stats
                activeConnections[key] = connection.copy(
                    lastActivity = System.currentTimeMillis(),
                    messagesSent = connection.messagesSent + 1
                )
                
                Timber.d("$TAG: Sent message directly to $recipientAddress")
                Result.success(SendResult(direct = true, relayed = false))
            } else {
                // Try to connect first
                val connected = connectToPeer(recipientAddress)
                if (connected) {
                    val newConnection = activeConnections[key]
                    if (newConnection != null) {
                        sendRaw(encodedMessage, newConnection.address)
                        Timber.d("$TAG: Sent message directly to $recipientAddress after connect")
                        Result.success(SendResult(direct = true, relayed = false))
                    } else {
                        // Fallback to relay (would need relay implementation)
                        Timber.w("$TAG: Connection lost, would relay to $recipientAddress")
                        Result.success(SendResult(direct = false, relayed = false))
                    }
                } else {
                    // No direct connection, message cannot be delivered
                    // In future: try relay nodes
                    Timber.w("$TAG: Cannot connect to $recipientAddress")
                    Result.success(SendResult(direct = false, relayed = false))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to send message to $recipientAddress")
            Result.failure(e)
        }
    }
    
    /**
     * Send a delivery acknowledgment to a peer.
     * 
     * @param recipientAddress Wallet address of the message sender
     * @param messageId ID of the message being acknowledged
     */
    suspend fun sendDeliveryAck(recipientAddress: String, messageId: String) {
        try {
            val ackPayload = messageId.toByteArray(Charsets.UTF_8)
            sendMessage(
                peerWallet = recipientAddress,
                type = MessageCodec.MessageType.ACK,
                payload = ackPayload,
                requireAck = false  // ACKs don't need ACKs
            )
            Timber.d("$TAG: Sent delivery ACK for $messageId to $recipientAddress")
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed to send delivery ACK")
        }
    }
    
    /**
     * Send raw data.
     */
    private fun sendRaw(data: ByteArray, address: InetSocketAddress) {
        val packet = DatagramPacket(data, data.size, address.address, address.port)
        socket?.send(packet)
    }
    
    /**
     * Send handshake to peer.
     */
    private suspend fun sendHandshake(connection: PeerConnection) {
        val payload = myPublicEndpoint?.let {
            "${it.publicIp}:${it.publicPort}".toByteArray(Charsets.UTF_8)
        } ?: ByteArray(0)
        
        val encoded = messageCodec.encode(
            type = MessageCodec.MessageType.HANDSHAKE,
            payload = payload,
            sourceNodeId = myNodeId,
            destNodeId = connection.nodeId
        )
        
        sendRaw(encoded.bytes, connection.address)
    }
    
    /**
     * Receive loop - processes incoming messages.
     */
    private suspend fun receiveLoop() = withContext(Dispatchers.IO) {
        val buffer = ByteArray(RECEIVE_BUFFER_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)
        
        while (isRunning) {
            try {
                socket?.receive(packet)
                
                val data = buffer.copyOf(packet.length)
                val senderAddress = InetSocketAddress(packet.address, packet.port)
                
                processIncomingPacket(data, senderAddress)
                
            } catch (e: java.net.SocketTimeoutException) {
                // Normal timeout, continue
            } catch (e: Exception) {
                if (isRunning) {
                    Timber.w(e, "$TAG: Receive error")
                }
            }
        }
    }
    
    /**
     * Process an incoming packet.
     */
    private suspend fun processIncomingPacket(data: ByteArray, sender: InetSocketAddress) {
        val message = messageCodec.decode(data)
        if (message == null) {
            Timber.w("$TAG: Failed to decode message from $sender")
            return
        }
        
        // Find sender wallet
        val senderWallet = activeConnections.entries
            .find { it.value.nodeId.contentEquals(message.sourceNodeId) }
            ?.key
        
        // Handle ACKs
        if (message.isAck) {
            pendingAcks.remove(message.sequenceNumber)
            return
        }
        
        // Send ACK if required
        if (message.requiresAck) {
            sendAck(message.sequenceNumber, message.sourceNodeId, sender)
        }
        
        // Process by message type
        when (message.type) {
            MessageCodec.MessageType.PING -> handlePing(message, sender)
            MessageCodec.MessageType.PONG -> handlePong(message)
            MessageCodec.MessageType.HANDSHAKE -> handleHandshake(message, sender)
            MessageCodec.MessageType.HANDSHAKE_ACK -> handleHandshakeAck(message)
            MessageCodec.MessageType.DISCONNECT -> handleDisconnect(message)
            MessageCodec.MessageType.FIND_NODE -> handleFindNode(message, sender)
            MessageCodec.MessageType.FIND_NODE_RESPONSE -> handleFindNodeResponse(message)
            
            // Chat messages - emit to flow
            MessageCodec.MessageType.CHAT_MESSAGE,
            MessageCodec.MessageType.CHAT_ACK,
            MessageCodec.MessageType.CHAT_READ,
            MessageCodec.MessageType.TYPING_INDICATOR -> {
                if (senderWallet != null) {
                    _incomingMessages.emit(IncomingMessage(
                        from = senderWallet,
                        type = message.type,
                        payload = message.payload
                    ))
                }
            }
            
            else -> {
                Timber.d("$TAG: Unhandled message type: ${message.type}")
            }
        }
    }
    
    /**
     * Send an ACK for a message.
     */
    private fun sendAck(sequenceNumber: Int, destNodeId: ByteArray, address: InetSocketAddress) {
        val encoded = messageCodec.encode(
            type = MessageCodec.MessageType.PONG, // Using PONG as ACK
            payload = ByteArray(0),
            sourceNodeId = myNodeId,
            destNodeId = destNodeId,
            flags = MessageCodec.Flags.IS_ACK
        )
        sendRaw(encoded.bytes, address)
    }
    
    // Message handlers
    
    private fun handlePing(message: MessageCodec.DecodedMessage, sender: InetSocketAddress) {
        val pongPayload = messageCodec.createPongPayload(
            pingTimestamp = if (message.payload.size >= 8) {
                java.nio.ByteBuffer.wrap(message.payload).long
            } else {
                0L
            }
        )
        
        val encoded = messageCodec.encode(
            type = MessageCodec.MessageType.PONG,
            payload = pongPayload,
            sourceNodeId = myNodeId,
            destNodeId = message.sourceNodeId
        )
        sendRaw(encoded.bytes, sender)
    }
    
    private fun handlePong(message: MessageCodec.DecodedMessage) {
        // Calculate RTT if needed
        if (message.payload.size >= 16) {
            val buffer = java.nio.ByteBuffer.wrap(message.payload)
            val pingTime = buffer.long
            val rtt = System.currentTimeMillis() - pingTime
            Timber.d("$TAG: Pong received, RTT: ${rtt}ms")
        }
    }
    
    private suspend fun handleHandshake(message: MessageCodec.DecodedMessage, sender: InetSocketAddress) {
        Timber.d("$TAG: Received handshake from $sender")
        
        // Send handshake ACK
        val payload = myPublicEndpoint?.let {
            "${it.publicIp}:${it.publicPort}".toByteArray(Charsets.UTF_8)
        } ?: ByteArray(0)
        
        val encoded = messageCodec.encode(
            type = MessageCodec.MessageType.HANDSHAKE_ACK,
            payload = payload,
            sourceNodeId = myNodeId,
            destNodeId = message.sourceNodeId
        )
        sendRaw(encoded.bytes, sender)
    }
    
    private fun handleHandshakeAck(message: MessageCodec.DecodedMessage) {
        Timber.d("$TAG: Handshake ACK received")
    }
    
    private fun handleDisconnect(message: MessageCodec.DecodedMessage) {
        val wallet = activeConnections.entries
            .find { it.value.nodeId.contentEquals(message.sourceNodeId) }
            ?.key
        
        if (wallet != null) {
            activeConnections.remove(wallet)
            bootstrapManager.markPeerDisconnected(wallet)
            Timber.d("$TAG: Peer disconnected: $wallet")
        }
    }
    
    private fun handleFindNode(message: MessageCodec.DecodedMessage, sender: InetSocketAddress) {
        val targetNodeId = message.payload
        val closestNodes = dht.findClosestNodesById(targetNodeId)
        
        val nodeInfoList = closestNodes.map { node ->
            val ipParts = node.publicIp.split(".")
            val ipBytes = ByteArray(4) { i ->
                ipParts.getOrElse(i) { "0" }.toIntOrNull()?.toByte() ?: 0
            }
            MessageCodec.NodeInfo(node.nodeId, ipBytes, node.publicPort)
        }
        
        val responsePayload = messageCodec.createFindNodeResponsePayload(nodeInfoList)
        
        val encoded = messageCodec.encode(
            type = MessageCodec.MessageType.FIND_NODE_RESPONSE,
            payload = responsePayload,
            sourceNodeId = myNodeId,
            destNodeId = message.sourceNodeId
        )
        sendRaw(encoded.bytes, sender)
    }
    
    private fun handleFindNodeResponse(message: MessageCodec.DecodedMessage) {
        val nodes = messageCodec.parseFindNodeResponsePayload(message.payload)
        Timber.d("$TAG: Received ${nodes.size} nodes in FIND_NODE response")
        
        nodes.forEach { nodeInfo ->
            // Add to DHT (would need wallet address mapping)
            // For now, log it
            Timber.d("$TAG: Node: ${nodeInfo.ipString}:${nodeInfo.port}")
        }
    }
    
    /**
     * Maintenance loop - sends keepalives, cleans up stale connections.
     */
    private suspend fun maintenanceLoop() = withContext(Dispatchers.IO) {
        while (isRunning) {
            delay(30000) // Every 30 seconds
            
            // Ping all active connections
            activeConnections.values.forEach { connection ->
                val pingPayload = messageCodec.createPingPayload()
                val encoded = messageCodec.encode(
                    type = MessageCodec.MessageType.PING,
                    payload = pingPayload,
                    sourceNodeId = myNodeId,
                    destNodeId = connection.nodeId
                )
                runCatching { sendRaw(encoded.bytes, connection.address) }
            }
            
            // Clean up stale connections
            val staleThreshold = System.currentTimeMillis() - 5 * 60 * 1000 // 5 minutes
            val staleConnections = activeConnections.filter { it.value.lastActivity < staleThreshold }
            staleConnections.keys.forEach { wallet ->
                Timber.d("$TAG: Removing stale connection: $wallet")
                activeConnections.remove(wallet)
                bootstrapManager.markPeerDisconnected(wallet)
            }
            
            // Clean up expired DHT values
            dht.cleanupExpiredValues()
            
            Timber.d("$TAG: Maintenance complete. Active connections: ${activeConnections.size}")
        }
    }
    
    /**
     * Retry loop - resends unacknowledged messages.
     */
    private suspend fun retryLoop() = withContext(Dispatchers.IO) {
        while (isRunning) {
            delay(1000) // Check every second
            
            val now = System.currentTimeMillis()
            val toRetry = pendingAcks.values.filter { 
                now - it.sentAt > ACK_TIMEOUT_MS && it.retries < MAX_RETRIES 
            }
            
            toRetry.forEach { pending ->
                Timber.d("$TAG: Retrying message ${pending.sequenceNumber} (attempt ${pending.retries + 1})")
                
                runCatching { sendRaw(pending.encoded, pending.destination) }
                
                pendingAcks[pending.sequenceNumber] = pending.copy(
                    sentAt = now,
                    retries = pending.retries + 1
                )
            }
            
            // Remove messages that exceeded max retries
            pendingAcks.entries.removeIf { 
                it.value.retries >= MAX_RETRIES 
            }
        }
    }
    
    // Public accessors
    
    fun getActiveConnections(): List<PeerConnection> = activeConnections.values.toList()
    fun getPublicEndpoint(): StunClient.StunResult? = myPublicEndpoint
    fun isConnectedTo(walletAddress: String): Boolean = 
        activeConnections.containsKey(walletAddress.lowercase())
}
