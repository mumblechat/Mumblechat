package com.ramapay.app.chat.network

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * HubConnection - WebSocket connection to MumbleChat Hub
 * ═══════════════════════════════════════════════════════════════════════════
 * 
 * Connects to hub.mumblechat.com for message relay, compatible with web clients.
 * 
 * Supports:
 * - Official hub (hub.mumblechat.com)
 * - Mobile relay nodes (other phones running RelayService)
 * - Custom endpoints (user-provided relay servers)
 * 
 * Message Flow:
 * ┌────────────┐     ┌─────────────────┐     ┌────────────┐
 * │ Android App│────►│ Hub/Relay Node  │────►│ Web Client │
 * │            │◄────│                 │◄────│            │
 * └────────────┘     └─────────────────┘     └────────────┘
 */
@Singleton
class HubConnection @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "HubConnection"
        
        // Official Hub
        const val HUB_API_URL = "https://hub.mumblechat.com"
        const val HUB_WS_URL = "wss://hub.mumblechat.com"
        
        // Connection settings
        const val PING_INTERVAL_MS = 30_000L
        const val RECONNECT_DELAY_MS = 5_000L
        const val MAX_RECONNECT_ATTEMPTS = 10
        const val CONNECTION_TIMEOUT_MS = 10_000L
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .connectTimeout(CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for WebSocket
        .build()
    
    // State
    private var webSocket: WebSocket? = null
    private var currentEndpoint: String? = null
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    
    private val _connectionState = MutableStateFlow(HubConnectionState.DISCONNECTED)
    val connectionState: StateFlow<HubConnectionState> = _connectionState
    
    private val _incomingMessages = MutableSharedFlow<HubMessage>(extraBufferCapacity = 100)
    val incomingMessages: SharedFlow<HubMessage> = _incomingMessages
    
    private val _deliveryStatus = MutableSharedFlow<DeliveryStatus>(extraBufferCapacity = 50)
    val deliveryStatus: SharedFlow<DeliveryStatus> = _deliveryStatus
    
    // Cross-node events for mobile relay
    private val _nodeEvent = MutableSharedFlow<NodeEvent>(extraBufferCapacity = 20)
    val nodeEvent: SharedFlow<NodeEvent> = _nodeEvent
    
    // Cross-node message delivery (for when acting as relay node)
    private val _crossNodeMessages = MutableSharedFlow<CrossNodeMessage>(extraBufferCapacity = 50)
    val crossNodeMessages: SharedFlow<CrossNodeMessage> = _crossNodeMessages
    
    // User info
    private var walletAddress: String? = null
    private var displayName: String? = null
    private var publicKey: String? = null
    
    // Custom endpoint support
    private var customEndpoint: String? = null
    
    // ============ Data Classes ============
    
    enum class HubConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        AUTHENTICATED,
        RECONNECTING,
        ERROR
    }
    
    data class HubMessage(
        val type: String,
        val messageId: String,
        val from: String,
        val to: String,
        val payload: String?,           // Encrypted content
        val encryptedData: String?,     // Alternative field name
        val encrypted: Boolean,
        val senderPublicKey: String?,
        val signature: String?,
        val timestamp: Long,
        val isOfflineMessage: Boolean = false
    )
    
    data class DeliveryStatus(
        val messageId: String,
        val status: MessageDeliveryStatus,
        val recipient: String?,
        val timestamp: Long
    )
    
    enum class MessageDeliveryStatus {
        SENDING,
        SENT,
        PENDING,      // Recipient offline, queued
        DELIVERED,
        READ,
        FAILED
    }
    
    data class RelayEndpoint(
        val endpoint: String,
        val tunnelId: String,
        val users: Int,
        val load: Float
    )
    
    /**
     * Cross-node message from another mobile relay
     */
    data class CrossNodeMessage(
        val messageId: String,
        val from: String,
        val to: String,
        val payload: String,
        val encrypted: Boolean,
        val senderPublicKey: String?,
        val signature: String?,
        val sourceNode: String,
        val timestamp: Long
    )
    
    /**
     * Events for mobile relay node operation
     */
    sealed class NodeEvent {
        data class UserConnected(val sessionId: String) : NodeEvent()
        data class UserDisconnected(val sessionId: String) : NodeEvent()
        data class TunnelEstablished(val tunnelId: String, val endpoint: String) : NodeEvent()
        data class CrossNodeDelivery(val messageId: String, val delivered: Boolean) : NodeEvent()
    }
    
    // ============ Public API ============
    
    /**
     * Set custom relay endpoint (user's own server or mobile relay)
     */
    fun setCustomEndpoint(endpoint: String?) {
        customEndpoint = endpoint
        Timber.d("$TAG: Custom endpoint set to: $endpoint")
    }
    
    /**
     * Connect to hub with user credentials
     */
    suspend fun connect(
        address: String,
        name: String?,
        pubKey: String?
    ): Result<Unit> {
        walletAddress = address.lowercase()
        displayName = name ?: address.take(8)
        publicKey = pubKey
        
        return try {
            // Get best endpoint
            val endpoint = customEndpoint ?: getBestEndpoint()
            
            if (endpoint == null) {
                Timber.e("$TAG: No endpoints available")
                _connectionState.value = HubConnectionState.ERROR
                return Result.failure(Exception("No relay endpoints available"))
            }
            
            connectWebSocket(endpoint)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Connection failed")
            _connectionState.value = HubConnectionState.ERROR
            Result.failure(e)
        }
    }
    
    /**
     * Disconnect from hub
     */
    fun disconnect() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _connectionState.value = HubConnectionState.DISCONNECTED
        Timber.d("$TAG: Disconnected")
    }
    
    /**
     * Send a message through hub
     */
    fun sendMessage(
        to: String,
        encryptedPayload: String,
        messageId: String,
        encrypted: Boolean = true,
        algorithm: String = "ECDH-AES-256-GCM",
        signature: String? = null,
        senderPublicKey: String? = null
    ): Boolean {
        val ws = webSocket ?: return false
        
        val message = JSONObject().apply {
            put("type", "relay")
            put("from", walletAddress)
            put("to", to.lowercase())
            put("payload", encryptedPayload)
            put("encryptedData", encryptedPayload)
            put("encrypted", encrypted)
            put("algorithm", algorithm)
            put("messageId", messageId)
            put("signature", signature)
            put("senderPublicKey", senderPublicKey ?: publicKey)
            put("timestamp", System.currentTimeMillis())
        }
        
        val sent = ws.send(message.toString())
        if (sent) {
            Timber.d("$TAG: Message sent: $messageId to ${to.take(8)}...")
        } else {
            Timber.e("$TAG: Failed to send message: $messageId")
        }
        return sent
    }
    
    /**
     * Send read receipt
     */
    fun sendReadReceipt(messageId: String, to: String) {
        val ws = webSocket ?: return
        
        val receipt = JSONObject().apply {
            put("type", "read")
            put("messageId", messageId)
            put("from", walletAddress)
            put("to", to.lowercase())
            put("timestamp", System.currentTimeMillis())
        }
        
        ws.send(receipt.toString())
        Timber.d("$TAG: Read receipt sent for: $messageId")
    }
    
    /**
     * Request sync of pending messages
     */
    fun requestSync() {
        val ws = webSocket ?: return
        
        val sync = JSONObject().apply {
            put("type", "sync")
            put("address", walletAddress)
        }
        
        ws.send(sync.toString())
        Timber.d("$TAG: Sync requested")
    }
    
    /**
     * Get available relay endpoints from hub API
     */
    suspend fun getAvailableEndpoints(): List<RelayEndpoint> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$HUB_API_URL/api/endpoints")
                .get()
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                
                val json = JSONObject(response.body?.string() ?: "{}")
                val endpoints = json.optJSONArray("endpoints") ?: return@withContext emptyList()
                
                (0 until endpoints.length()).map { i ->
                    val ep = endpoints.getJSONObject(i)
                    RelayEndpoint(
                        endpoint = ep.getString("endpoint"),
                        tunnelId = ep.getString("tunnelId"),
                        users = ep.optInt("users", 0),
                        load = ep.optDouble("load", 0.0).toFloat()
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to get endpoints")
            emptyList()
        }
    }
    
    /**
     * Check if a user is online via hub API
     */
    suspend fun isUserOnline(address: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$HUB_API_URL/api/user/${address.lowercase()}")
                .get()
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                
                val json = JSONObject(response.body?.string() ?: "{}")
                json.optBoolean("online", false)
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to check user status")
            false
        }
    }
    
    // ============ Private Methods ============
    
    private suspend fun getBestEndpoint(): String? = withContext(Dispatchers.IO) {
        val endpoints = getAvailableEndpoints()
        
        if (endpoints.isEmpty()) {
            // Fallback to auto-connect endpoint
            return@withContext "$HUB_WS_URL/user/connect"
        }
        
        // Sort by load and return best
        val best = endpoints.minByOrNull { it.load }
        best?.endpoint
    }
    
    private fun connectWebSocket(endpoint: String) {
        _connectionState.value = HubConnectionState.CONNECTING
        currentEndpoint = endpoint
        
        Timber.d("$TAG: Connecting to $endpoint")
        
        val request = Request.Builder()
            .url(endpoint)
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Timber.d("$TAG: WebSocket opened")
                _connectionState.value = HubConnectionState.CONNECTED
                reconnectAttempts = 0
                
                // Authenticate
                authenticate()
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Timber.d("$TAG: WebSocket closing: $code $reason")
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Timber.d("$TAG: WebSocket closed: $code $reason")
                _connectionState.value = HubConnectionState.DISCONNECTED
                
                if (code != 1000) { // Not a normal close
                    scheduleReconnect()
                }
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.e(t, "$TAG: WebSocket failure")
                _connectionState.value = HubConnectionState.ERROR
                scheduleReconnect()
            }
        })
    }
    
    private fun authenticate() {
        val ws = webSocket ?: return
        
        val auth = JSONObject().apply {
            put("type", "authenticate")
            put("walletAddress", walletAddress)
            put("address", walletAddress)
            put("displayName", displayName)
            put("publicKey", publicKey)
            put("timestamp", System.currentTimeMillis())
        }
        
        ws.send(auth.toString())
        Timber.d("$TAG: Authentication sent")
    }
    
    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")
            
            when (type) {
                "authenticated", "auth_success", "CONNECTED" -> {
                    _connectionState.value = HubConnectionState.AUTHENTICATED
                    Timber.d("$TAG: Authenticated successfully")
                    
                    // Request any pending messages
                    requestSync()
                }
                
                "message" -> {
                    val msg = parseIncomingMessage(json)
                    scope.launch {
                        _incomingMessages.emit(msg)
                    }
                    Timber.d("$TAG: Message received from ${msg.from.take(8)}...")
                }
                
                "relay_ack", "delivery_receipt" -> {
                    val status = DeliveryStatus(
                        messageId = json.optString("messageId"),
                        status = if (json.optBoolean("delivered", false)) 
                            MessageDeliveryStatus.DELIVERED else MessageDeliveryStatus.SENT,
                        recipient = json.optString("to"),
                        timestamp = json.optLong("timestamp", System.currentTimeMillis())
                    )
                    scope.launch { _deliveryStatus.emit(status) }
                    Timber.d("$TAG: Delivery receipt: ${status.messageId} -> ${status.status}")
                }
                
                "message_queued" -> {
                    val status = DeliveryStatus(
                        messageId = json.optString("messageId"),
                        status = MessageDeliveryStatus.PENDING,
                        recipient = json.optString("recipient"),
                        timestamp = System.currentTimeMillis()
                    )
                    scope.launch { _deliveryStatus.emit(status) }
                    Timber.d("$TAG: Message queued for offline user: ${json.optString("recipient")?.take(8)}...")
                }
                
                "read_receipt" -> {
                    val status = DeliveryStatus(
                        messageId = json.optString("messageId"),
                        status = MessageDeliveryStatus.READ,
                        recipient = json.optString("from"),
                        timestamp = json.optLong("timestamp", System.currentTimeMillis())
                    )
                    scope.launch { _deliveryStatus.emit(status) }
                    Timber.d("$TAG: Read receipt: ${status.messageId}")
                }
                
                "stored_messages", "offline_messages" -> {
                    val messages = json.optJSONArray("messages") ?: JSONArray()
                    Timber.d("$TAG: Received ${messages.length()} offline messages")
                    
                    for (i in 0 until messages.length()) {
                        val msgJson = messages.getJSONObject(i)
                        val msg = parseIncomingMessage(msgJson, isOffline = true)
                        scope.launch { _incomingMessages.emit(msg) }
                    }
                }
                
                "presence", "status" -> {
                    val address = json.optString("address")
                    val online = json.optBoolean("online", false) || 
                                 json.optString("status") == "online"
                    Timber.d("$TAG: Presence update: $address -> $online")
                    // TODO: Emit presence event
                }
                
                "pong" -> {
                    // Heartbeat response
                }
                
                // *** CROSS-NODE MESSAGE FROM HUB ***
                // This allows messages to be routed through multiple mobile relay nodes
                "cross_node_message", "CROSS_NODE_MESSAGE" -> {
                    handleCrossNodeMessage(json)
                }
                
                // *** NODE AUTH SUCCESS - Mobile relay registered with hub ***
                "NODE_AUTHENTICATED", "TUNNEL_ESTABLISHED" -> {
                    val tunnelId = json.optString("tunnelId")
                    val endpoint = json.optString("endpoint")
                    Timber.d("$TAG: Mobile relay tunnel established: $tunnelId at $endpoint")
                    _connectionState.value = HubConnectionState.AUTHENTICATED
                }
                
                // *** MESSAGE FROM HUB USER (when acting as relay node) ***
                "MESSAGE_FROM_USER" -> {
                    val sessionId = json.optString("sessionId")
                    val payload = json.optJSONObject("payload")
                    if (payload != null) {
                        handleMessageFromHubUser(sessionId, payload)
                    }
                }
                
                // *** USER CONNECTED VIA OUR TUNNEL ***
                "USER_CONNECTED" -> {
                    val sessionId = json.optString("sessionId")
                    Timber.d("$TAG: User connected via our tunnel: $sessionId")
                    scope.launch { _nodeEvent.emit(NodeEvent.UserConnected(sessionId)) }
                }
                
                // *** USER DISCONNECTED FROM OUR TUNNEL ***
                "USER_DISCONNECTED" -> {
                    val sessionId = json.optString("sessionId")
                    Timber.d("$TAG: User disconnected from our tunnel: $sessionId")
                    scope.launch { _nodeEvent.emit(NodeEvent.UserDisconnected(sessionId)) }
                }
                
                "error" -> {
                    val errorMsg = json.optString("message", "Unknown error")
                    Timber.e("$TAG: Hub error: $errorMsg")
                }
                
                else -> {
                    Timber.d("$TAG: Unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to parse message: $text")
        }
    }
    
    private fun parseIncomingMessage(json: JSONObject, isOffline: Boolean = false): HubMessage {
        return HubMessage(
            type = "message",
            messageId = json.optString("messageId", "msg_${System.currentTimeMillis()}"),
            from = json.optString("from", json.optString("senderAddress", "")),
            to = json.optString("to", walletAddress ?: ""),
            payload = json.optString("payload", json.optString("text", "")),
            encryptedData = json.optString("encryptedData", json.optString("encryptedBlob", "")),
            encrypted = json.optBoolean("encrypted", false),
            senderPublicKey = if (json.has("senderPublicKey")) json.optString("senderPublicKey") else null,
            signature = if (json.has("signature")) json.optString("signature") else null,
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            isOfflineMessage = isOffline || json.optBoolean("isOfflineMessage", false)
        )
    }
    
    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Timber.e("$TAG: Max reconnect attempts reached")
            _connectionState.value = HubConnectionState.ERROR
            return
        }
        
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            reconnectAttempts++
            val delay = RECONNECT_DELAY_MS * minOf(reconnectAttempts, 3)
            
            Timber.d("$TAG: Reconnecting in ${delay}ms (attempt $reconnectAttempts)")
            _connectionState.value = HubConnectionState.RECONNECTING
            
            delay(delay)
            
            currentEndpoint?.let { endpoint ->
                connectWebSocket(endpoint)
            }
        }
    }
    
    // ============ Cross-Node Handlers ============
    
    /**
     * Handle cross-node message from hub (message routed through multiple nodes)
     * Compatible with both web desktop nodes and mobile nodes
     */
    private fun handleCrossNodeMessage(json: JSONObject) {
        try {
            // Web format uses targetSessionId + payload
            // Mobile format uses direct fields (from, to, payload)
            val targetSessionId = json.optString("targetSessionId", "")
            val payloadObj = json.optJSONObject("payload")
            
            val msg: CrossNodeMessage
            
            if (payloadObj != null && targetSessionId.isNotEmpty()) {
                // Web node format: { targetSessionId, payload: {...} }
                msg = CrossNodeMessage(
                    messageId = payloadObj.optString("messageId", "msg_${System.currentTimeMillis()}"),
                    from = payloadObj.optString("from", payloadObj.optString("senderAddress", "")),
                    to = payloadObj.optString("to", ""),
                    payload = payloadObj.optString("payload", payloadObj.optString("encryptedBlob", "")),
                    encrypted = payloadObj.optBoolean("encrypted", true),
                    senderPublicKey = if (payloadObj.has("senderPublicKey")) payloadObj.optString("senderPublicKey") else null,
                    signature = if (payloadObj.has("signature")) payloadObj.optString("signature") else null,
                    sourceNode = json.optString("sourceNode", json.optString("fromNode", "web-node")),
                    timestamp = payloadObj.optLong("timestamp", System.currentTimeMillis())
                )
            } else {
                // Mobile node format: direct fields
                msg = CrossNodeMessage(
                    messageId = json.optString("messageId", "msg_${System.currentTimeMillis()}"),
                    from = json.optString("from", ""),
                    to = json.optString("to", ""),
                    payload = json.optString("payload", json.optString("encryptedData", "")),
                    encrypted = json.optBoolean("encrypted", true),
                    senderPublicKey = if (json.has("senderPublicKey")) json.optString("senderPublicKey") else null,
                    signature = if (json.has("signature")) json.optString("signature") else null,
                    sourceNode = json.optString("sourceNode", json.optString("fromNode", "")),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis())
                )
            }
            
            Timber.d("$TAG: Cross-node message received: ${msg.from.take(8)} -> ${msg.to.take(8)} via ${msg.sourceNode}")
            
            scope.launch {
                _crossNodeMessages.emit(msg)
            }
            
            // Send delivery receipt back to source node
            sendCrossNodeDeliveryReceipt(msg.messageId, msg.from, msg.sourceNode)
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to handle cross-node message")
        }
    }
    
    /**
     * Handle message from hub user when acting as a relay node
     */
    private fun handleMessageFromHubUser(sessionId: String, payload: JSONObject) {
        try {
            val type = payload.optString("type", "")
            
            when (type) {
                "relay" -> {
                    // User is sending a message through our relay
                    val msg = HubMessage(
                        type = "message",
                        messageId = payload.optString("messageId", "msg_${System.currentTimeMillis()}"),
                        from = payload.optString("from", ""),
                        to = payload.optString("to", ""),
                        payload = payload.optString("payload"),
                        encryptedData = payload.optString("encryptedData"),
                        encrypted = payload.optBoolean("encrypted", true),
                        senderPublicKey = payload.optString("senderPublicKey"),
                        signature = payload.optString("signature"),
                        timestamp = payload.optLong("timestamp", System.currentTimeMillis()),
                        isOfflineMessage = false
                    )
                    
                    scope.launch {
                        _incomingMessages.emit(msg)
                    }
                    
                    Timber.d("$TAG: Hub user message: ${msg.from.take(8)} -> ${msg.to.take(8)}")
                }
                
                "authenticate" -> {
                    val address = payload.optString("address")
                    Timber.d("$TAG: Hub user authenticated: ${address.take(8)}")
                    
                    // Send success back
                    sendToHubUser(sessionId, JSONObject().apply {
                        put("type", "authenticated")
                        put("success", true)
                        put("timestamp", System.currentTimeMillis())
                    })
                }
                
                "fetch", "sync" -> {
                    // User requesting pending messages - would be handled by MobileRelayServer
                    Timber.d("$TAG: Hub user requesting sync: $sessionId")
                }
                
                else -> {
                    Timber.d("$TAG: Unknown hub user message type: $type")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to handle hub user message")
        }
    }
    
    /**
     * Send message to a user connected via hub tunnel
     */
    fun sendToHubUser(sessionId: String, payload: JSONObject) {
        val ws = webSocket ?: return
        
        val msg = JSONObject().apply {
            put("type", "MESSAGE_TO_USER")
            put("userId", sessionId)
            put("payload", payload)
        }
        
        ws.send(msg.toString())
    }
    
    /**
     * Send cross-node delivery receipt
     */
    private fun sendCrossNodeDeliveryReceipt(messageId: String, from: String, sourceNode: String) {
        val ws = webSocket ?: return
        
        val receipt = JSONObject().apply {
            put("type", "CROSS_NODE_DELIVERY")
            put("messageId", messageId)
            put("delivered", true)
            put("from", from)
            put("targetNode", sourceNode)
            put("timestamp", System.currentTimeMillis())
        }
        
        ws.send(receipt.toString())
        Timber.d("$TAG: Cross-node delivery receipt sent: $messageId")
    }
    
    /**
     * Register this device as a mobile relay node with the hub
     */
    fun registerAsRelayNode(nodeId: String, port: Int) {
        val ws = webSocket ?: return
        
        val auth = JSONObject().apply {
            put("type", "NODE_AUTH")
            put("walletAddress", walletAddress)
            put("nodeId", nodeId)
            put("port", port)
            put("signature", "auto-node") // Would be real signature in production
            put("timestamp", System.currentTimeMillis())
        }
        
        ws.send(auth.toString())
        Timber.d("$TAG: Registered as relay node: $nodeId")
    }
    
    // ============ Ping/Keepalive ============
    
    private fun startPingLoop() {
        scope.launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                
                webSocket?.let { ws ->
                    val ping = JSONObject().apply {
                        put("type", "ping")
                    }
                    ws.send(ping.toString())
                }
            }
        }
    }
}
