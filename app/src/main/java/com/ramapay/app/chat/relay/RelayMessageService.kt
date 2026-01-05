package com.ramapay.app.chat.relay

import android.content.Context
import com.ramapay.app.chat.blockchain.MumbleChatBlockchainService
import com.ramapay.app.chat.crypto.ChatKeyStore
import com.ramapay.app.chat.crypto.MessageEncryption
import com.ramapay.app.chat.data.entity.MessageEntity
import com.ramapay.app.chat.data.entity.MessageStatus
import com.ramapay.app.chat.data.repository.MessageRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * RELAY-BASED MESSAGE DELIVERY SERVICE
 * ═══════════════════════════════════════════════════════════════════════════
 * 
 * Works over the internet - not limited to same WiFi!
 * 
 * Architecture:
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │                                                                          │
 * │   Device A                Relay Node                  Device B          │
 * │   (Sender)                (Always On)                (Receiver)         │
 * │                                                                          │
 * │      │                        │                          │               │
 * │      │───── Send Message ────►│                          │               │
 * │      │                        │──── Store Message ────►  │               │
 * │      │                        │                          │               │
 * │      │                        │◄─── Poll for Messages ───│               │
 * │      │                        │                          │               │
 * │      │                        │──── Deliver Message ───► │               │
 * │      │                        │                          │               │
 * └──────────────────────────────────────────────────────────────────────────┘
 * 
 * Protocol:
 * - All messages are E2E encrypted
 * - Relay nodes only see encrypted blobs
 * - Messages stored with TTL (24 hours default)
 * - Relay nodes earn MCT for service
 */
@Singleton
class RelayMessageService @Inject constructor(
    private val context: Context,
    private val chatKeyStore: ChatKeyStore,
    private val blockchainService: MumbleChatBlockchainService
) {
    companion object {
        private const val TAG = "RelayMessageService"
        const val RELAY_PORT = 19372  // Relay service port
        const val MESSAGE_TTL_MS = 24 * 60 * 60 * 1000L  // 24 hours
        const val POLL_INTERVAL_MS = 5_000L  // Poll every 5 seconds
        const val CONNECTION_TIMEOUT_MS = 10_000  // 10 second timeout
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Stored messages for offline recipients (keyed by recipient address)
    private val pendingMessages = ConcurrentHashMap<String, MutableList<RelayMessage>>()
    
    // Connected clients (for relay node mode)
    private val connectedClients = ConcurrentHashMap<String, ClientConnection>()
    
    // Known relay nodes with their endpoints
    private val relayNodes = ConcurrentHashMap<String, RelayNodeInfo>()
    
    // Flow for incoming messages
    private val _incomingMessages = MutableSharedFlow<RelayMessage>()
    val incomingMessages: SharedFlow<RelayMessage> = _incomingMessages
    
    // Service state
    private val _isRelayMode = MutableStateFlow(false)
    val isRelayMode: StateFlow<Boolean> = _isRelayMode
    
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var myWalletAddress: String? = null
    private var pollJob: Job? = null

    /**
     * Initialize service with wallet address.
     */
    fun initialize(walletAddress: String) {
        myWalletAddress = walletAddress
        Timber.d("$TAG: Initialized for wallet $walletAddress")
    }

    /**
     * Start as relay node - accept and forward messages.
     */
    suspend fun startRelayMode(publicEndpoint: String? = null) = withContext(Dispatchers.IO) {
        if (isRunning) {
            Timber.w("$TAG: Already running")
            return@withContext
        }
        
        try {
            serverSocket = ServerSocket(RELAY_PORT)
            serverSocket?.reuseAddress = true
            isRunning = true
            _isRelayMode.value = true
            
            Timber.i("$TAG: Started relay server on port $RELAY_PORT")
            
            // Accept connections
            scope.launch {
                while (isActive && isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        handleClientConnection(clientSocket)
                    } catch (e: Exception) {
                        if (isRunning) {
                            Timber.e(e, "$TAG: Error accepting connection")
                        }
                    }
                }
            }
            
            // Start cleanup job
            scope.launch {
                while (isActive && isRunning) {
                    delay(60_000) // Every minute
                    cleanupExpiredMessages()
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start relay server")
            isRunning = false
            _isRelayMode.value = false
        }
    }

    /**
     * Stop relay mode.
     */
    fun stopRelayMode() {
        isRunning = false
        _isRelayMode.value = false
        serverSocket?.close()
        serverSocket = null
        connectedClients.values.forEach { it.close() }
        connectedClients.clear()
        Timber.i("$TAG: Stopped relay server")
    }

    /**
     * Send message through relay network.
     */
    suspend fun sendMessage(
        recipientAddress: String,
        encryptedContent: ByteArray,
        messageId: String,
        contentType: String = "TEXT"
    ): Boolean = withContext(Dispatchers.IO) {
        val senderAddress = myWalletAddress ?: return@withContext false
        
        val message = RelayMessage(
            id = messageId,
            senderAddress = senderAddress,
            recipientAddress = recipientAddress,
            encryptedContent = encryptedContent,
            contentType = contentType,
            timestamp = System.currentTimeMillis()
        )
        
        // Try to send to recipient's relay node or any available relay
        val sent = sendToRelayNetwork(message)
        
        if (!sent) {
            Timber.w("$TAG: Failed to send message $messageId through relay network")
        }
        
        sent
    }

    /**
     * Start polling for incoming messages.
     */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        
        pollJob = scope.launch {
            while (isActive) {
                try {
                    pollForMessages()
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Error polling for messages")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        Timber.d("$TAG: Started polling for messages")
    }

    /**
     * Stop polling.
     */
    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        Timber.d("$TAG: Stopped polling")
    }

    /**
     * Register a relay node endpoint.
     */
    suspend fun registerRelayNode(address: String, host: String, port: Int) {
        relayNodes[address] = RelayNodeInfo(address, host, port, System.currentTimeMillis())
        Timber.d("$TAG: Registered relay node $address at $host:$port")
    }

    /**
     * Discover relay nodes from blockchain.
     */
    suspend fun discoverRelayNodes() = withContext(Dispatchers.IO) {
        try {
            val nodes = blockchainService.getActiveRelayNodes()
            Timber.d("$TAG: Found ${nodes.size} relay nodes from blockchain")
            
            for (node in nodes) {
                val endpoint = node.endpoint
                if (endpoint.isNotEmpty()) {
                    val parts = endpoint.split(":")
                    if (parts.size == 2) {
                        val host = parts[0]
                        val port = parts[1].toIntOrNull() ?: RELAY_PORT
                        relayNodes[node.walletAddress] = RelayNodeInfo(node.walletAddress, host, port, System.currentTimeMillis())
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to discover relay nodes")
        }
    }

    /**
     * Get stored messages for an address (relay node function).
     */
    fun getMessagesForRecipient(recipientAddress: String): List<RelayMessage> {
        return pendingMessages[recipientAddress.lowercase()]?.toList() ?: emptyList()
    }

    /**
     * Mark messages as delivered (relay node function).
     */
    fun markDelivered(messageIds: List<String>) {
        for ((_, messages) in pendingMessages) {
            messages.removeAll { it.id in messageIds }
        }
        Timber.d("$TAG: Marked ${messageIds.size} messages as delivered")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PRIVATE IMPLEMENTATION
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun handleClientConnection(socket: Socket) {
        scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)
                
                // Read request
                val requestLine = reader.readLine() ?: return@launch
                val request = JSONObject(requestLine)
                
                when (request.optString("type")) {
                    "SEND" -> handleSendRequest(request, writer)
                    "POLL" -> handlePollRequest(request, writer)
                    "ACK" -> handleAckRequest(request, writer)
                    "PING" -> handlePingRequest(writer)
                    else -> {
                        writer.println(JSONObject().put("status", "error").put("message", "Unknown request type"))
                    }
                }
                
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Error handling client")
            } finally {
                socket.close()
            }
        }
    }

    private fun handleSendRequest(request: JSONObject, writer: PrintWriter) {
        try {
            val recipientAddress = request.getString("to").lowercase()
            val messageJson = request.getJSONObject("message")
            
            val message = RelayMessage(
                id = messageJson.getString("id"),
                senderAddress = messageJson.getString("from"),
                recipientAddress = recipientAddress,
                encryptedContent = android.util.Base64.decode(
                    messageJson.getString("content"),
                    android.util.Base64.NO_WRAP
                ),
                contentType = messageJson.optString("contentType", "TEXT"),
                timestamp = messageJson.optLong("timestamp", System.currentTimeMillis())
            )
            
            // Store for recipient
            pendingMessages.getOrPut(recipientAddress) { mutableListOf() }.add(message)
            
            Timber.d("$TAG: Stored message ${message.id} for $recipientAddress")
            
            writer.println(JSONObject()
                .put("status", "ok")
                .put("messageId", message.id))
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error handling send request")
            writer.println(JSONObject().put("status", "error").put("message", e.message))
        }
    }

    private suspend fun handlePollRequest(request: JSONObject, writer: PrintWriter) {
        try {
            val address = request.getString("address").lowercase()
            val signature = request.optString("signature", "")
            val timestamp = request.optLong("timestamp", 0L)
            
            // Verify signature to prove ownership of address
            if (signature.isNotEmpty() && timestamp > 0) {
                val message = "mumblechat:poll:$address:$timestamp"
                val now = System.currentTimeMillis()
                
                // Signature must be within 5 minutes
                if (kotlin.math.abs(now - timestamp) > 5 * 60 * 1000) {
                    writer.println(JSONObject()
                        .put("status", "error")
                        .put("error", "Signature expired"))
                    return
                }
                
                // Verify ECDSA signature
                val isValid = try {
                    verifyAddressSignature(address, message, signature)
                } catch (e: Exception) {
                    Timber.w(e, "$TAG: Signature verification failed")
                    false
                }
                
                if (!isValid) {
                    writer.println(JSONObject()
                        .put("status", "error")
                        .put("error", "Invalid signature"))
                    return
                }
            }
            
            val messages = getMessagesForRecipient(address)
            
            val messagesJson = JSONArray()
            for (msg in messages) {
                messagesJson.put(JSONObject()
                    .put("id", msg.id)
                    .put("from", msg.senderAddress)
                    .put("content", android.util.Base64.encodeToString(msg.encryptedContent, android.util.Base64.NO_WRAP))
                    .put("contentType", msg.contentType)
                    .put("timestamp", msg.timestamp))
            }
            
            writer.println(JSONObject()
                .put("status", "ok")
                .put("messages", messagesJson)
                .put("count", messages.size))
            
            Timber.d("$TAG: Returned ${messages.size} messages for $address")
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error handling poll request")
            writer.println(JSONObject().put("status", "error").put("message", e.message))
        }
    }

    private fun handleAckRequest(request: JSONObject, writer: PrintWriter) {
        try {
            val messageIds = request.getJSONArray("messageIds")
            val ids = mutableListOf<String>()
            for (i in 0 until messageIds.length()) {
                ids.add(messageIds.getString(i))
            }
            
            markDelivered(ids)
            
            writer.println(JSONObject().put("status", "ok"))
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error handling ack request")
            writer.println(JSONObject().put("status", "error").put("message", e.message))
        }
    }

    private fun handlePingRequest(writer: PrintWriter) {
        writer.println(JSONObject()
            .put("status", "ok")
            .put("type", "PONG")
            .put("timestamp", System.currentTimeMillis()))
    }

    private suspend fun sendToRelayNetwork(message: RelayMessage): Boolean {
        // Try each relay node
        val nodes = relayNodes.values.toList()
        
        if (nodes.isEmpty()) {
            // Try to discover nodes
            discoverRelayNodes()
        }
        
        for (node in relayNodes.values) {
            try {
                val success = sendToRelayNode(node, message)
                if (success) {
                    Timber.d("$TAG: Message sent via relay ${node.address}")
                    return true
                }
            } catch (e: Exception) {
                Timber.w(e, "$TAG: Failed to send via relay ${node.address}")
            }
        }
        
        // If no relay nodes available, try direct local storage (for when we ARE the relay)
        if (_isRelayMode.value) {
            val recipient = message.recipientAddress.lowercase()
            pendingMessages.getOrPut(recipient) { mutableListOf() }.add(message)
            Timber.d("$TAG: Stored message locally (we are relay)")
            return true
        }
        
        return false
    }

    private suspend fun sendToRelayNode(node: RelayNodeInfo, message: RelayMessage): Boolean {
        return withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(node.host, node.port), CONNECTION_TIMEOUT_MS)
                socket.soTimeout = CONNECTION_TIMEOUT_MS
                
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                
                // Send message
                val request = JSONObject()
                    .put("type", "SEND")
                    .put("to", message.recipientAddress)
                    .put("message", JSONObject()
                        .put("id", message.id)
                        .put("from", message.senderAddress)
                        .put("content", android.util.Base64.encodeToString(message.encryptedContent, android.util.Base64.NO_WRAP))
                        .put("contentType", message.contentType)
                        .put("timestamp", message.timestamp))
                
                writer.println(request.toString())
                
                // Read response
                val responseLine = reader.readLine()
                val response = JSONObject(responseLine)
                
                response.optString("status") == "ok"
                
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Error sending to relay ${node.host}:${node.port}")
                false
            } finally {
                socket?.close()
            }
        }
    }

    private suspend fun pollForMessages() = withContext(Dispatchers.IO) {
        val address = myWalletAddress ?: return@withContext
        
        for (node in relayNodes.values) {
            try {
                val messages = pollRelayNode(node, address)
                
                if (messages.isNotEmpty()) {
                    Timber.d("$TAG: Received ${messages.size} messages from relay ${node.address}")
                    
                    val messageIds = mutableListOf<String>()
                    for (msg in messages) {
                        _incomingMessages.emit(msg)
                        messageIds.add(msg.id)
                    }
                    
                    // Acknowledge receipt
                    ackMessages(node, messageIds)
                }
                
            } catch (e: Exception) {
                Timber.w(e, "$TAG: Failed to poll relay ${node.address}")
            }
        }
    }

    private suspend fun pollRelayNode(node: RelayNodeInfo, address: String): List<RelayMessage> {
        return withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(node.host, node.port), CONNECTION_TIMEOUT_MS)
                socket.soTimeout = CONNECTION_TIMEOUT_MS
                
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                
                // Poll request
                val request = JSONObject()
                    .put("type", "POLL")
                    .put("address", address)
                
                writer.println(request.toString())
                
                // Read response
                val responseLine = reader.readLine()
                val response = JSONObject(responseLine)
                
                if (response.optString("status") != "ok") {
                    return@withContext emptyList()
                }
                
                val messagesJson = response.getJSONArray("messages")
                val messages = mutableListOf<RelayMessage>()
                
                for (i in 0 until messagesJson.length()) {
                    val msgJson = messagesJson.getJSONObject(i)
                    messages.add(RelayMessage(
                        id = msgJson.getString("id"),
                        senderAddress = msgJson.getString("from"),
                        recipientAddress = address,
                        encryptedContent = android.util.Base64.decode(
                            msgJson.getString("content"),
                            android.util.Base64.NO_WRAP
                        ),
                        contentType = msgJson.optString("contentType", "TEXT"),
                        timestamp = msgJson.optLong("timestamp")
                    ))
                }
                
                messages
                
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Error polling relay ${node.host}:${node.port}")
                emptyList()
            } finally {
                socket?.close()
            }
        }
    }

    private suspend fun ackMessages(node: RelayNodeInfo, messageIds: List<String>) {
        withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(node.host, node.port), CONNECTION_TIMEOUT_MS)
                
                val writer = PrintWriter(socket.getOutputStream(), true)
                
                val request = JSONObject()
                    .put("type", "ACK")
                    .put("messageIds", JSONArray(messageIds))
                
                writer.println(request.toString())
                
            } catch (e: Exception) {
                Timber.w(e, "$TAG: Error acking messages")
            } finally {
                socket?.close()
            }
        }
    }

    private fun cleanupExpiredMessages() {
        val now = System.currentTimeMillis()
        var cleaned = 0
        
        for ((_, messages) in pendingMessages) {
            val before = messages.size
            messages.removeAll { now - it.timestamp > MESSAGE_TTL_MS }
            cleaned += before - messages.size
        }
        
        if (cleaned > 0) {
            Timber.d("$TAG: Cleaned up $cleaned expired messages")
        }
    }
    
    /**
     * Verify that a signature was created by the owner of the given address.
     * Uses ECDSA signature recovery to verify the signer matches the claimed address.
     */
    private fun verifyAddressSignature(address: String, message: String, signature: String): Boolean {
        return try {
            val signatureBytes = android.util.Base64.decode(signature, android.util.Base64.NO_WRAP)
            if (signatureBytes.size != 65) {
                Timber.w("$TAG: Invalid signature length: ${signatureBytes.size}")
                return false
            }
            
            // Compute message hash (Ethereum personal sign format)
            val prefix = "\u0019Ethereum Signed Message:\n${message.length}"
            val prefixedMessage = prefix + message
            val messageHash = org.web3j.crypto.Hash.sha3(prefixedMessage.toByteArray())
            
            // Extract r, s, v from signature
            val r = signatureBytes.copyOfRange(0, 32)
            val s = signatureBytes.copyOfRange(32, 64)
            val v = signatureBytes[64].toInt() and 0xFF
            
            // Normalize v value
            val normalizedV = when {
                v >= 27 -> v - 27
                else -> v
            }
            
            // Create signature data
            val sigData = org.web3j.crypto.Sign.SignatureData(
                (normalizedV + 27).toByte(),
                r,
                s
            )
            
            // Recover public key from signature
            val recoveredKey = org.web3j.crypto.Sign.signedMessageHashToKey(messageHash, sigData)
            val recoveredAddress = org.web3j.crypto.Keys.getAddress(recoveredKey)
            
            // Compare addresses (case-insensitive)
            val matches = recoveredAddress.equals(address.removePrefix("0x"), ignoreCase = true)
            
            if (!matches) {
                Timber.w("$TAG: Signature address mismatch: expected=$address, recovered=0x$recoveredAddress")
            }
            
            matches
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error verifying signature")
            false
        }
    }
}

/**
 * Message to be relayed.
 */
data class RelayMessage(
    val id: String,
    val senderAddress: String,
    val recipientAddress: String,
    val encryptedContent: ByteArray,
    val contentType: String,
    val timestamp: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RelayMessage
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Relay node connection info.
 */
data class RelayNodeInfo(
    val address: String,
    val host: String,
    val port: Int,
    val lastSeen: Long
)

/**
 * Client connection wrapper.
 */
class ClientConnection(
    val socket: Socket,
    val address: String
) {
    fun close() {
        try {
            socket.close()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
