package com.ramapay.app.chat.network

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * MobileRelayServer - Turn your phone into a MumbleChat relay node!
 * ═══════════════════════════════════════════════════════════════════════════
 * 
 * This allows the Android app to act as a relay server that other users
 * (web or mobile) can connect to directly.
 * 
 * Features:
 * - Accept WebSocket connections from other clients
 * - Relay messages between connected users
 * - Store messages for offline users
 * - Connect to official hub for cross-node routing
 * - Earn MCT tokens for relaying (when staked)
 * 
 * Architecture:
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │                      Mobile Relay Node                              │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │                                                                     │
 * │   Web Client ──────►┐                                               │
 * │                     │                                               │
 * │   Web Client ──────►├────► MobileRelayServer ◄────► Hub Server     │
 * │                     │           │                                   │
 * │   Mobile App ──────►┘           │                                   │
 * │                                 ▼                                   │
 * │                         Local Message Store                         │
 * │                                                                     │
 * └─────────────────────────────────────────────────────────────────────┘
 */
@Singleton
class MobileRelayServer @Inject constructor(
    private val context: Context,
    private val hubConnection: HubConnection
) {
    companion object {
        private const val TAG = "MobileRelayServer"
        
        // Server configuration
        const val DEFAULT_PORT = 19370
        const val MAX_CONNECTIONS = 50
        const val MESSAGE_EXPIRY_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
        const val MAX_PENDING_MESSAGES = 1000
        
        // Hub node connection
        const val HUB_NODE_URL = "wss://hub.mumblechat.com/node/connect"
        const val NODE_HEARTBEAT_INTERVAL_MS = 30_000L  // 30 seconds  
        const val NODE_RECONNECT_DELAY_MS = 5_000L      // 5 seconds
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private var server: RelayWebSocketServer? = null
    private var isRunning = false
    private var serverPort = DEFAULT_PORT
    
    // Connected clients (session ID -> client info)
    private val connectedClients = ConcurrentHashMap<String, ConnectedClient>()
    
    // User address to session mapping
    private val userSessions = ConcurrentHashMap<String, String>()
    
    // Offline message store
    private val offlineMessages = ConcurrentHashMap<String, MutableList<StoredMessage>>()
    
    // Stats
    private var messagesRelayed = 0L
    private var startTime = 0L
    
    // Persistent stats via SharedPreferences
    private val prefs by lazy { 
        context.getSharedPreferences("mumblechat_relay_stats", Context.MODE_PRIVATE) 
    }
    private var totalMessagesRelayed: Long
        get() = prefs.getLong("total_messages_relayed", 0)
        set(value) = prefs.edit().putLong("total_messages_relayed", value).apply()
    private var lastHeartbeatMessages: Long
        get() = prefs.getLong("last_heartbeat_messages", 0)
        set(value) = prefs.edit().putLong("last_heartbeat_messages", value).apply()
    private var messagesSinceLastHeartbeat: Long
        get() = prefs.getLong("messages_since_heartbeat", 0)
        set(value) = prefs.edit().putLong("messages_since_heartbeat", value).apply()
    
    /** Track a relayed message - updates both session and persistent counters */
    private fun recordMessageRelayed() {
        messagesRelayed++
        totalMessagesRelayed++
        messagesSinceLastHeartbeat++
    }
    
    // State
    private val _serverState = MutableStateFlow(RelayServerState.STOPPED)
    val serverState: StateFlow<RelayServerState> = _serverState
    
    /**
     * Check if the mobile relay server is currently running (local server + hub connection).
     */
    fun isRunning(): Boolean = _serverState.value == RelayServerState.RUNNING
    
    private val _stats = MutableStateFlow(RelayStats())
    val stats: StateFlow<RelayStats> = _stats
    
    // Cross-node listener job
    private var crossNodeListenerJob: Job? = null
    private var nodeEventListenerJob: Job? = null
    
    // Node ID for registration
    private var nodeId: String? = null
    
    // *** DEDICATED NODE WebSocket to hub ***
    // The hub requires NODE_AUTH on /node/connect endpoint (separate from user chat)
    private var nodeWebSocket: WebSocketClient? = null
    private var nodeReconnectJob: Job? = null
    private var nodeHeartbeatJob: Job? = null
    private var isNodeConnected = false
    
    // Wallet address for node registration
    private var walletAddress: String? = null
    
    // ============ Data Classes ============
    
    enum class RelayServerState {
        STOPPED,
        STARTING,
        RUNNING,
        ERROR
    }
    
    data class ConnectedClient(
        val sessionId: String,
        val socket: WebSocket,
        var walletAddress: String? = null,
        var displayName: String? = null,
        var publicKey: String? = null,
        val connectedAt: Long = System.currentTimeMillis()
    )
    
    data class StoredMessage(
        val id: String,
        val from: String,
        val to: String,
        val payload: String,
        val encrypted: Boolean,
        val senderPublicKey: String?,
        val signature: String?,
        val timestamp: Long,
        val expiresAt: Long
    )
    
    data class RelayStats(
        val isRunning: Boolean = false,
        val port: Int = 0,
        val connectedClients: Int = 0,
        val messagesRelayed: Long = 0,
        val totalMessagesRelayed: Long = 0,  // Persistent count across sessions
        val offlineMessagesStored: Int = 0,
        val uptimeSeconds: Long = 0,
        val hubEndpoint: String? = null,  // Hub-provided tunnel endpoint (no IP exposed!)
        val hubFeePercent: Int = 10       // Hub takes 10% of rewards
    )
    
    // Hub-provided tunnel endpoint (secure - doesn't expose IP)
    private var hubTunnelEndpoint: String? = null
    private var hubFeePercent: Int = 10
    
    // ============ Public API ============
    
    /**
     * Start the mobile relay server
     * @param port Local WebSocket server port
     * @param wallet Wallet address for hub node registration (must be staked)
     */
    fun start(port: Int = DEFAULT_PORT, wallet: String? = null): Result<Unit> {
        if (isRunning) {
            Timber.d("$TAG: Already running")
            return Result.success(Unit)
        }
        
        return try {
            _serverState.value = RelayServerState.STARTING
            serverPort = port
            walletAddress = wallet
            
            // Generate unique node ID
            nodeId = "mobile-${System.currentTimeMillis()}-${(Math.random() * 10000).toInt()}"
            
            server = RelayWebSocketServer(InetSocketAddress(port))
            server?.start()
            
            isRunning = true
            startTime = System.currentTimeMillis()
            _serverState.value = RelayServerState.RUNNING
            
            // Start stats update loop
            startStatsLoop()
            
            // Start cleanup loop
            startCleanupLoop()
            
            // Cross-node messages are now handled by the dedicated node WebSocket
            // (no longer via the user's HubConnection)
            
            // *** Connect to hub as a RELAY NODE via dedicated /node/connect ***
            // This is SEPARATE from the user's chat connection!
            scope.launch {
                delay(2000) // Wait for local server to stabilize
                connectToHubAsNode()
            }
            
            Timber.d("$TAG: Server started on port $port")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start server")
            _serverState.value = RelayServerState.ERROR
            Result.failure(e)
        }
    }
    
    /**
     * Stop the relay server
     */
    fun stop() {
        if (!isRunning) return
        
        try {
            // Cancel all background jobs
            crossNodeListenerJob?.cancel()
            nodeEventListenerJob?.cancel()
            nodeReconnectJob?.cancel()
            nodeHeartbeatJob?.cancel()
            
            // Disconnect dedicated node WebSocket
            disconnectNodeWebSocket()
            
            server?.stop()
            server = null
            isRunning = false
            _serverState.value = RelayServerState.STOPPED
            
            connectedClients.clear()
            userSessions.clear()
            
            Timber.d("$TAG: Server stopped")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error stopping server")
        }
    }
    
    /**
     * Get server endpoint URL for sharing
     * Returns the HUB TUNNEL endpoint (secure - doesn't expose your IP!)
     * Hub takes 10% of rewards for providing this service.
     */
    fun getEndpointUrl(): String? {
        if (!isRunning) return null
        
        // Return hub-provided tunnel endpoint (SECURE - no IP exposed!)
        // This is provided by hub after NODE_AUTH
        return hubTunnelEndpoint
    }
    
    /**
     * Get local IP endpoint (for advanced users only - exposes IP)
     */
    fun getLocalEndpointUrl(): String? {
        if (!isRunning) return null
        val ip = getLocalIpAddress()
        return if (ip != null) "ws://$ip:$serverPort" else null
    }
    
    /**
     * Check if a user is connected to this relay
     */
    fun isUserConnected(address: String): Boolean {
        return userSessions.containsKey(address.lowercase())
    }
    
    // ============ WebSocket Server ============
    
    private inner class RelayWebSocketServer(address: InetSocketAddress) : 
        WebSocketServer(address) {
        
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val sessionId = generateSessionId()
            val client = ConnectedClient(sessionId, conn)
            connectedClients[sessionId] = client
            
            // Store session ID in connection attachment
            conn.setAttachment(sessionId)
            
            Timber.d("$TAG: Client connected: $sessionId")
            updateStats()
        }
        
        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            val sessionId = conn.getAttachment<String>() ?: return
            val client = connectedClients.remove(sessionId)
            
            client?.walletAddress?.let { address ->
                userSessions.remove(address)
            }
            
            Timber.d("$TAG: Client disconnected: $sessionId")
            updateStats()
        }
        
        override fun onMessage(conn: WebSocket, message: String) {
            val sessionId = conn.getAttachment<String>() ?: return
            handleClientMessage(sessionId, message)
        }
        
        override fun onError(conn: WebSocket?, ex: Exception) {
            Timber.e(ex, "$TAG: WebSocket error")
        }
        
        override fun onStart() {
            Timber.d("$TAG: WebSocket server started")
        }
    }
    
    // ============ Message Handling ============
    
    private fun handleClientMessage(sessionId: String, message: String) {
        try {
            val json = JSONObject(message)
            val type = json.optString("type", "")
            val client = connectedClients[sessionId] ?: return
            
            when (type) {
                "authenticate" -> handleAuthenticate(sessionId, json, client)
                "relay" -> handleRelay(sessionId, json, client)
                "sync", "fetch" -> handleSync(sessionId, client)
                "ping" -> handlePing(client)
                "read" -> handleReadReceipt(json, client)
                else -> Timber.d("$TAG: Unknown message type: $type")
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error handling message")
        }
    }
    
    private fun handleAuthenticate(sessionId: String, json: JSONObject, client: ConnectedClient) {
        val address = json.optString("address", json.optString("walletAddress", "")).lowercase()
        val displayName = json.optString("displayName")
        val publicKey = json.optString("publicKey")
        
        if (address.isBlank()) {
            sendError(client, "Invalid address")
            return
        }
        
        client.walletAddress = address
        client.displayName = displayName
        client.publicKey = publicKey
        
        userSessions[address] = sessionId
        
        // Send success response
        val response = JSONObject().apply {
            put("type", "authenticated")
            put("success", true)
            put("timestamp", System.currentTimeMillis())
        }
        client.socket.send(response.toString())
        
        Timber.d("$TAG: Client authenticated: ${address.take(8)}...")
        
        // Deliver any pending offline messages
        deliverOfflineMessages(address, client)
    }
    
    private fun handleRelay(sessionId: String, json: JSONObject, sender: ConnectedClient) {
        val to = json.optString("to", "").lowercase()
        val from = sender.walletAddress ?: json.optString("from", "").lowercase()
        val payload = json.optString("payload", json.optString("encryptedData", ""))
        val messageId = json.optString("messageId", "msg_${System.currentTimeMillis()}")
        val encrypted = json.optBoolean("encrypted", true)
        val senderPublicKey = json.optString("senderPublicKey", sender.publicKey)
        val signature = json.optString("signature")
        
        if (to.isBlank() || payload.isBlank()) {
            sendError(sender, "Invalid message format")
            return
        }
        
        Timber.d("$TAG: Relaying message: ${from.take(8)}... -> ${to.take(8)}...")
        
        // Check if recipient is connected locally
        val recipientSessionId = userSessions[to]
        val recipient = recipientSessionId?.let { connectedClients[it] }
        
        var delivered = false
        
        if (recipient != null && recipient.socket.isOpen) {
            // Deliver directly - include all field names for web/mobile compatibility
            val deliveryMsg = JSONObject().apply {
                put("type", "message")
                put("from", from)
                put("senderAddress", from)
                put("to", to)
                put("payload", payload)
                put("encryptedData", payload)
                put("encryptedBlob", payload)  // Web compatibility
                put("encrypted", encrypted)
                put("senderPublicKey", senderPublicKey)
                put("signature", signature)
                put("messageId", messageId)
                put("timestamp", System.currentTimeMillis())
            }
            recipient.socket.send(deliveryMsg.toString())
            delivered = true
            
            Timber.d("$TAG: Message delivered locally: $messageId")
        } else {
            // Store for offline delivery
            storeOfflineMessage(to, StoredMessage(
                id = messageId,
                from = from,
                to = to,
                payload = payload,
                encrypted = encrypted,
                senderPublicKey = senderPublicKey,
                signature = signature,
                timestamp = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + MESSAGE_EXPIRY_MS
            ))
            
            Timber.d("$TAG: Message stored for offline delivery: $messageId")
            
            // Try to relay through hub for cross-node delivery
            scope.launch {
                if (hubConnection.connectionState.value == HubConnection.HubConnectionState.AUTHENTICATED) {
                    hubConnection.sendMessage(
                        to = to,
                        encryptedPayload = payload,
                        messageId = messageId,
                        encrypted = encrypted,
                        signature = signature,
                        senderPublicKey = senderPublicKey
                    )
                }
            }
        }
        
        // Send acknowledgment to sender
        val ack = JSONObject().apply {
            put("type", "relay_ack")
            put("messageId", messageId)
            put("delivered", delivered)
            put("timestamp", System.currentTimeMillis())
            if (!delivered) {
                put("status", "queued_offline")
            }
        }
        sender.socket.send(ack.toString())
        
        recordMessageRelayed()
        updateStats()
    }
    
    private fun handleSync(sessionId: String, client: ConnectedClient) {
        val address = client.walletAddress ?: return
        deliverOfflineMessages(address, client)
    }
    
    private fun handlePing(client: ConnectedClient) {
        val pong = JSONObject().apply {
            put("type", "pong")
            put("timestamp", System.currentTimeMillis())
        }
        client.socket.send(pong.toString())
    }
    
    private fun handleReadReceipt(json: JSONObject, client: ConnectedClient) {
        val messageId = json.optString("messageId")
        val to = json.optString("to", "").lowercase()
        
        // Forward read receipt to original sender
        val recipientSessionId = userSessions[to]
        val recipient = recipientSessionId?.let { connectedClients[it] }
        
        if (recipient != null && recipient.socket.isOpen) {
            val receipt = JSONObject().apply {
                put("type", "read_receipt")
                put("messageId", messageId)
                put("from", client.walletAddress)
                put("timestamp", System.currentTimeMillis())
            }
            recipient.socket.send(receipt.toString())
        }
    }
    
    // ============ Offline Message Store ============
    
    private fun storeOfflineMessage(address: String, message: StoredMessage) {
        val messages = offlineMessages.getOrPut(address) { mutableListOf() }
        
        // Limit stored messages
        if (messages.size >= MAX_PENDING_MESSAGES) {
            messages.removeAt(0) // Remove oldest
        }
        
        messages.add(message)
        updateStats()
    }
    
    private fun deliverOfflineMessages(address: String, client: ConnectedClient) {
        val messages = offlineMessages.remove(address) ?: return
        
        if (messages.isEmpty()) return
        
        Timber.d("$TAG: Delivering ${messages.size} offline messages to ${address.take(8)}...")
        
        // Send as batch - include all field names for web/mobile compatibility
        val batch = JSONObject().apply {
            put("type", "offline_messages")
            put("messages", JSONArray().apply {
                messages.forEach { msg ->
                    put(JSONObject().apply {
                        put("type", "message")
                        put("messageId", msg.id)
                        put("from", msg.from)
                        put("senderAddress", msg.from)
                        put("to", msg.to)
                        put("payload", msg.payload)
                        put("encryptedData", msg.payload)
                        put("encryptedBlob", msg.payload)  // Web compatibility
                        put("encrypted", msg.encrypted)
                        put("senderPublicKey", msg.senderPublicKey)
                        put("signature", msg.signature)
                        put("timestamp", msg.timestamp)
                        put("isOfflineMessage", true)
                    })
                }
            })
        }
        
        client.socket.send(batch.toString())
        updateStats()
    }
    
    // ============ Hub Node Connection (Dedicated WebSocket) ============
    
    /**
     * Connect to hub as a RELAY NODE via dedicated /node/connect WebSocket.
     * 
     * This is CRITICAL - the hub has 3 endpoints:
     *   /node/connect  → For relay nodes (sends NODE_AUTH, gets tunnel)
     *   /node/{id}     → For users connecting through a specific relay
     *   /user/connect  → For regular chat users (auto-assigned to a node)
     * 
     * The mobile relay MUST connect to /node/connect to be recognized as a node.
     * The existing HubConnection connects as /user/connect (for chat).
     */
    private fun connectToHubAsNode() {
        if (!isRunning) return
        
        val wallet = walletAddress ?: hubConnection.getWalletAddress()
        if (wallet == null) {
            Timber.e("$TAG: No wallet address, can't register as node")
            return
        }
        
        Timber.d("$TAG: ═══════════════════════════════════════════════")
        Timber.d("$TAG: Connecting to hub as RELAY NODE")
        Timber.d("$TAG: Wallet: ${wallet.take(10)}...")
        Timber.d("$TAG: Endpoint: $HUB_NODE_URL")
        Timber.d("$TAG: ═══════════════════════════════════════════════")
        
        try {
            disconnectNodeWebSocket()
            
            nodeWebSocket = object : WebSocketClient(URI(HUB_NODE_URL)) {
                override fun onOpen(handshake: ServerHandshake?) {
                    Timber.d("$TAG: ✅ Node WebSocket CONNECTED to hub")
                    isNodeConnected = true
                    
                    // Send NODE_AUTH immediately
                    val auth = JSONObject().apply {
                        put("type", "NODE_AUTH")
                        put("walletAddress", wallet)
                        put("nodeId", nodeId ?: "mobile-node")
                        put("port", serverPort)
                        put("signature", "mobile-node-v4")
                        put("timestamp", System.currentTimeMillis())
                        put("platform", "android")
                        put("version", "4.4")
                    }
                    send(auth.toString())
                    Timber.d("$TAG: NODE_AUTH sent to hub")
                }
                
                override fun onMessage(message: String?) {
                    message ?: return
                    handleNodeHubMessage(message)
                }
                
                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Timber.d("$TAG: Node WebSocket closed: code=$code reason=$reason")
                    isNodeConnected = false
                    nodeHeartbeatJob?.cancel()
                    
                    // Auto-reconnect if still running
                    if (isRunning) {
                        scheduleNodeReconnect()
                    }
                }
                
                override fun onError(ex: Exception?) {
                    Timber.e(ex, "$TAG: Node WebSocket error")
                    isNodeConnected = false
                }
            }
            
            nodeWebSocket?.connect()
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to connect node WebSocket")
            if (isRunning) {
                scheduleNodeReconnect()
            }
        }
    }
    
    /**
     * Handle messages from the hub on the dedicated node connection
     */
    private fun handleNodeHubMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")
            
            when (type) {
                "TUNNEL_ESTABLISHED", "NODE_AUTHENTICATED" -> {
                    val tunnelId = json.optString("tunnelId", "")
                    val endpoint = json.optString("endpoint", "")
                    val fee = json.optInt("hubFeePercent", 10)
                    
                    hubTunnelEndpoint = endpoint
                    hubFeePercent = fee
                    
                    Timber.d("$TAG: ✅ ═══════════════════════════════════════")
                    Timber.d("$TAG: ✅ NODE REGISTERED ON HUB!")
                    Timber.d("$TAG: ✅ Tunnel ID: $tunnelId")
                    Timber.d("$TAG: ✅ Endpoint: $endpoint")
                    Timber.d("$TAG: ✅ Hub Fee: $fee%")
                    Timber.d("$TAG: ✅ ═══════════════════════════════════════")
                    
                    // Start node heartbeat loop (30s keepalive)
                    startNodeHeartbeatLoop()
                    updateStats()
                }
                
                "NODE_AUTH_FAILED" -> {
                    val error = json.optString("error", "Unknown")
                    val message = json.optString("message", "")
                    Timber.e("$TAG: ❌ Node auth FAILED: $error - $message")
                    
                    if (error == "INSUFFICIENT_STAKE") {
                        Timber.e("$TAG: Need to stake MCT! Current: ${json.optString("currentStake")}, Required: ${json.optString("requiredStake")}")
                    }
                }
                
                "HEARTBEAT_ACK" -> {
                    // Hub acknowledged our heartbeat
                    Timber.d("$TAG: Hub heartbeat ACK")
                }
                
                "message", "relay" -> {
                    // Cross-node message routed through hub
                    handleCrossNodeHubMessage(json)
                }
                
                "user_connected" -> {
                    val sessionId = json.optString("sessionId", "")
                    val userAddress = json.optString("walletAddress", "")
                    Timber.d("$TAG: User connected via hub tunnel: $sessionId ($userAddress)")
                }
                
                "user_disconnected" -> {
                    val sessionId = json.optString("sessionId", "")
                    Timber.d("$TAG: User disconnected from hub tunnel: $sessionId")
                }
                
                "pong" -> { /* keepalive response */ }
                
                else -> {
                    Timber.d("$TAG: Node hub msg: $type")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error handling node hub message")
        }
    }
    
    /**
     * Handle cross-node message received on the dedicated node connection
     */
    private fun handleCrossNodeHubMessage(json: JSONObject) {
        val from = json.optString("from", json.optString("senderAddress", ""))
        val to = json.optString("to", json.optString("recipientAddress", ""))
        val payload = json.optString("payload", json.optString("encryptedData", ""))
        val toAddress = to.lowercase()
        
        Timber.d("$TAG: Cross-node msg: ${from.take(8)} -> ${toAddress.take(8)}")
        
        // Check if recipient is connected to THIS relay
        val recipientSessionId = userSessions[toAddress]
        val recipient = recipientSessionId?.let { connectedClients[it] }
        
        if (recipient != null && recipient.socket.isOpen) {
            // Deliver to local client
            val deliveryMsg = JSONObject().apply {
                put("type", "message")
                put("from", from)
                put("senderAddress", from)
                put("to", to)
                put("payload", payload)
                put("encryptedData", payload)
                put("encryptedBlob", payload)
                put("encrypted", json.optBoolean("encrypted", true))
                put("senderPublicKey", json.optString("senderPublicKey", ""))
                put("signature", json.optString("signature", ""))
                put("messageId", json.optString("messageId", ""))
                put("timestamp", json.optLong("timestamp", System.currentTimeMillis()))
                put("crossNode", true)
            }
            recipient.socket.send(deliveryMsg.toString())
            recordMessageRelayed()
            Timber.d("$TAG: Cross-node message delivered locally")
        } else {
            // Store for offline
            storeOfflineMessage(toAddress, StoredMessage(
                id = json.optString("messageId", "cross-${System.currentTimeMillis()}"),
                from = from,
                to = toAddress,
                payload = payload,
                encrypted = json.optBoolean("encrypted", true),
                senderPublicKey = json.optString("senderPublicKey"),
                signature = json.optString("signature"),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                expiresAt = System.currentTimeMillis() + MESSAGE_EXPIRY_MS
            ))
            Timber.d("$TAG: Cross-node message stored offline for $toAddress")
        }
        
        updateStats()
    }
    
    /**
     * Send periodic heartbeat to hub on node connection (30s keepalive)
     */
    private fun startNodeHeartbeatLoop() {
        nodeHeartbeatJob?.cancel()
        nodeHeartbeatJob = scope.launch {
            while (isActive && isNodeConnected) {
                delay(NODE_HEARTBEAT_INTERVAL_MS)
                try {
                    nodeWebSocket?.let { ws ->
                        if (ws.isOpen) {
                            val sinceLast = messagesSinceLastHeartbeat
                            val heartbeat = JSONObject().apply {
                                put("type", "HEARTBEAT")
                                put("connectedUsers", connectedClients.size)
                                put("messagesRelayed", messagesRelayed)
                                put("totalMessagesRelayed", totalMessagesRelayed)
                                put("messagesSinceLastHeartbeat", sinceLast)
                                put("uptimeSeconds", (System.currentTimeMillis() - startTime) / 1000)
                                put("offlineMessages", offlineMessages.values.sumOf { it.size })
                                put("timestamp", System.currentTimeMillis())
                            }
                            ws.send(heartbeat.toString())
                            
                            // Reset per-heartbeat counter after sending
                            lastHeartbeatMessages = totalMessagesRelayed
                            messagesSinceLastHeartbeat = 0
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Node heartbeat failed")
                }
            }
        }
    }
    
    /**
     * Schedule reconnect to hub node endpoint
     */
    private fun scheduleNodeReconnect() {
        nodeReconnectJob?.cancel()
        nodeReconnectJob = scope.launch {
            delay(NODE_RECONNECT_DELAY_MS)
            if (isRunning && !isNodeConnected) {
                Timber.d("$TAG: Reconnecting node WebSocket to hub...")
                connectToHubAsNode()
            }
        }
    }
    
    /**
     * Disconnect the dedicated node WebSocket
     */
    private fun disconnectNodeWebSocket() {
        try {
            nodeWebSocket?.close()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error closing node WebSocket")
        }
        nodeWebSocket = null
        isNodeConnected = false
    }
    
    // ============ Utilities ============
    
    private fun sendError(client: ConnectedClient, message: String) {
        val error = JSONObject().apply {
            put("type", "error")
            put("message", message)
        }
        client.socket.send(error.toString())
    }
    
    private fun generateSessionId(): String {
        return "session_${System.currentTimeMillis()}_${(Math.random() * 100000).toInt()}"
    }
    
    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to get local IP")
        }
        return null
    }
    
    private fun updateStats() {
        val totalOffline = offlineMessages.values.sumOf { it.size }
        val uptimeSeconds = if (startTime > 0) {
            (System.currentTimeMillis() - startTime) / 1000
        } else 0
        
        _stats.value = RelayStats(
            isRunning = isRunning,
            port = serverPort,
            connectedClients = connectedClients.size,
            messagesRelayed = messagesRelayed,
            totalMessagesRelayed = totalMessagesRelayed,
            offlineMessagesStored = totalOffline,
            uptimeSeconds = uptimeSeconds,
            hubEndpoint = hubTunnelEndpoint,
            hubFeePercent = hubFeePercent
        )
    }
    
    private fun startStatsLoop() {
        scope.launch {
            while (isActive && isRunning) {
                updateStats()
                delay(5000) // Update every 5 seconds
            }
        }
    }
    
    private fun startCleanupLoop() {
        scope.launch {
            while (isActive && isRunning) {
                delay(60 * 60 * 1000) // Every hour
                cleanupExpiredMessages()
            }
        }
    }
    
    private fun cleanupExpiredMessages() {
        val now = System.currentTimeMillis()
        var cleaned = 0
        
        offlineMessages.forEach { (address, messages) ->
            val before = messages.size
            messages.removeAll { it.expiresAt < now }
            cleaned += (before - messages.size)
            
            if (messages.isEmpty()) {
                offlineMessages.remove(address)
            }
        }
        
        if (cleaned > 0) {
            Timber.d("$TAG: Cleaned up $cleaned expired messages")
            updateStats()
        }
    }
    
    // ============ Advanced Features ============
    
    /**
     * Get the current storage usage in MB
     */
    fun getCurrentStorageMB(): Long {
        var totalBytes = 0L
        
        offlineMessages.forEach { (_, messages) ->
            messages.forEach { msg ->
                totalBytes += msg.payload.length
            }
        }
        
        return totalBytes / (1024 * 1024)
    }
}
