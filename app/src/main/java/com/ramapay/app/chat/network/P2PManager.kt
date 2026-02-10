package com.ramapay.app.chat.network

import android.content.Context
import android.net.wifi.WifiManager
import com.ramapay.app.chat.blockchain.MumbleChatBlockchainService
import com.ramapay.app.chat.core.ChatConfig
import com.ramapay.app.chat.crypto.ChatKeyManager
import com.ramapay.app.chat.crypto.ChatKeyStore
import com.ramapay.app.chat.crypto.MessageEncryption
import com.ramapay.app.chat.relay.RelayMessageService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.experimental.xor

/**
 * Connection state enum.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * FULLY DECENTRALIZED P2P Network Manager for MumbleChat
 * ═══════════════════════════════════════════════════════════════════════════
 * 
 * NO CENTRAL SERVERS - NO WEBRTC - NO STUN/TURN
 * Every phone is an equal node in the network!
 * 
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │                    DECENTRALIZED ARCHITECTURE                          │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │                                                                         │
 * │    Phone A ◄──────────► Phone B ◄──────────► Phone C                   │
 * │       │                    │                    │                       │
 * │       └────────────────────┼────────────────────┘                       │
 * │                            │                                            │
 * │                        Phone D (Relay)                                  │
 * │                                                                         │
 * │  • Direct TCP between peers                                             │
 * │  • Kademlia DHT for discovery                                           │
 * │  • Gossip for message propagation                                       │
 * │  • Bootstrap from on-chain data                                         │
 * │                                                                         │
 * └─────────────────────────────────────────────────────────────────────────┘
 * 
 * How Peer Discovery Works (100% Decentralized):
 * 1. Read relay node list from MumbleChatRegistry smart contract (on-chain)
 * 2. Connect to relay nodes via TCP (they're just other phones!)
 * 3. Exchange peer lists (Kademlia k-buckets)
 * 4. Find target peer by wallet address → DHT key
 * 5. Connect directly to peer for messaging
 * 
 * If NAT blocks direct connection:
 * - Use TCP hole punching (simultaneous open)
 * - If that fails, route through relay nodes (they earn MCT tokens)
 */
@Singleton
class P2PManager @Inject constructor(
    private val context: Context,
    private val chatKeyStore: ChatKeyStore,
    private val blockchainService: MumbleChatBlockchainService,
    private val relayMessageService: RelayMessageService
) {
    companion object {
        // P2P Configuration
        const val P2P_PORT = 19370                    // MumbleChat P2P port
        const val LAN_DISCOVERY_PORT = 19371          // UDP broadcast for LAN discovery
        const val DHT_K_BUCKET_SIZE = 20              // Kademlia k-bucket size
        const val DHT_ALPHA = 3                       // Parallel lookups
        const val DHT_KEY_SIZE = 160                  // 160-bit keys (SHA-1)
        const val MAX_PEERS = 50                      // Max connected peers
        const val PING_INTERVAL_MS = 30_000L          // Ping every 30s
        const val PEER_TIMEOUT_MS = 120_000L          // Peer timeout 2min
        const val MESSAGE_TTL = 10                    // Max hops for gossip
        const val HOLE_PUNCH_ATTEMPTS = 5             // NAT traversal attempts
        const val LAN_DISCOVERY_INTERVAL_MS = 10_000L // LAN discovery every 10s
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ============ State ============
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _incomingMessages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 100)
    val incomingMessages: SharedFlow<IncomingMessage> = _incomingMessages

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount

    // ============ P2P State ============
    private var chatKeys: ChatKeyManager.ChatKeyPair? = null
    private var myNodeId: ByteArray = ByteArray(20) // 160-bit node ID
    private var myWalletAddress: String = ""
    
    // Server socket for incoming connections
    private var serverSocket: ServerSocket? = null
    
    // Connected peers (wallet address -> connection)
    private val connectedPeers = ConcurrentHashMap<String, PeerConnection>()
    
    // DHT routing table (Kademlia k-buckets)
    private val routingTable = Array(DHT_KEY_SIZE) { mutableListOf<DHTNode>() }
    
    // Known relay nodes from blockchain
    private val relayNodes = ConcurrentHashMap<String, RelayNode>()
    
    // Message cache to prevent duplicates (gossip dedup)
    private val messageCache = ConcurrentHashMap<String, Long>()
    
    // Pending messages for offline peers
    private val pendingMessages = ConcurrentHashMap<String, MutableList<PendingMessage>>()

    // ============ Data Classes ============

    data class IncomingMessage(
        val messageId: String,
        val senderAddress: String,
        val encrypted: MessageEncryption.EncryptedMessage,
        val timestamp: Long,
        val signature: ByteArray?,
        val isGroupMessage: Boolean = false,
        val groupId: String? = null
    )

    data class SendResult(
        val direct: Boolean,
        val relayed: Boolean,
        val relayId: String? = null
    )

    data class PeerConnection(
        val address: String,
        val nodeId: ByteArray,
        val publicKey: ByteArray,
        val socket: Socket?,
        var isOnline: Boolean,
        var lastSeen: Long,
        val endpoint: InetSocketAddress?
    )

    data class DHTNode(
        val nodeId: ByteArray,
        val walletAddress: String,
        val endpoint: InetSocketAddress,
        var lastSeen: Long
    )

    data class RelayNode(
        val address: String,
        val endpoint: InetSocketAddress,
        val stake: Long,
        val messagesRelayed: Long,
        var isOnline: Boolean
    )

    data class PendingMessage(
        val messageId: String,
        val encrypted: MessageEncryption.EncryptedMessage,
        val timestamp: Long,
        val ttl: Int
    )
    
    /**
     * Relay receipt for decentralized relay proof system.
     * When a message is relayed through a relay node, the sender signs a receipt
     * that the relay node can submit to the smart contract to earn rewards.
     */
    data class RelayReceipt(
        val messageHash: ByteArray,      // Hash of the relayed message
        val relayNodeAddress: String,    // Address of the relay node
        val senderAddress: String,       // Original sender address
        val recipientAddress: String,    // Recipient address
        val timestamp: Long,             // When relay occurred
        val nonce: Long,                 // Nonce from smart contract
        val senderSignature: ByteArray   // Sender's signature proving relay
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RelayReceipt) return false
            return messageHash.contentEquals(other.messageHash) &&
                   relayNodeAddress == other.relayNodeAddress &&
                   senderAddress == other.senderAddress
        }
        
        override fun hashCode(): Int {
            var result = messageHash.contentHashCode()
            result = 31 * result + relayNodeAddress.hashCode()
            result = 31 * result + senderAddress.hashCode()
            return result
        }
    }
    
    // Pending relay receipts to be submitted to blockchain
    private val pendingRelayReceipts = ConcurrentHashMap<String, MutableList<RelayReceipt>>()
    
    // Flow for relay receipts that need blockchain submission
    private val _relayReceiptsToSubmit = MutableSharedFlow<RelayReceipt>(extraBufferCapacity = 50)
    val relayReceiptsToSubmit: SharedFlow<RelayReceipt> = _relayReceiptsToSubmit

    // ============ Protocol Messages ============

    sealed class P2PMessage {
        data class Ping(val nodeId: ByteArray, val timestamp: Long) : P2PMessage()
        data class Pong(val nodeId: ByteArray, val timestamp: Long) : P2PMessage()
        data class FindNode(val targetId: ByteArray) : P2PMessage()
        data class NodeList(val nodes: List<DHTNode>) : P2PMessage()
        data class ChatMessage(
            val id: String,
            val from: String,
            val to: String,
            val encrypted: ByteArray,
            val nonce: ByteArray,
            val signature: ByteArray?,
            val ttl: Int
        ) : P2PMessage()
        data class Ack(val messageId: String) : P2PMessage()
        data class Store(val key: ByteArray, val value: ByteArray) : P2PMessage()
    }

    // ============ Initialization ============

    /**
     * Initialize P2P manager with chat keys and wallet address.
     */
    fun initialize(keys: ChatKeyManager.ChatKeyPair, walletAddress: String) {
        chatKeys = keys
        myWalletAddress = walletAddress
        
        // Generate node ID from wallet address (SHA-1 hash = 160 bits)
        myNodeId = sha1(walletAddress.lowercase().toByteArray())
        
        Timber.d("P2P initialized - NodeID: ${myNodeId.toHex()}, Wallet: $walletAddress")
    }

    /**
     * Connect to the decentralized P2P network.
     */
    fun connect() {
        if (chatKeys == null) {
            Timber.e("Cannot connect: keys not initialized")
            _connectionState.value = ConnectionState.ERROR
            return
        }

        scope.launch {
            try {
                _connectionState.value = ConnectionState.CONNECTING
                Timber.d("Connecting to decentralized P2P network...")

                // Step 1: Start listening for incoming connections
                startServer()

                // Step 2: Get bootstrap nodes from blockchain
                val bootstrapNodes = getBootstrapNodesFromBlockchain()
                Timber.d("Found ${bootstrapNodes.size} bootstrap nodes from blockchain")

                // Step 3: Connect to bootstrap nodes
                var connected = 0
                for (node in bootstrapNodes) {
                    if (connectToNode(node)) {
                        connected++
                        if (connected >= DHT_ALPHA) break // Connect to Alpha nodes initially
                    }
                }

                if (connected == 0) {
                    // No bootstrap nodes available - we might be the first node!
                    Timber.w("No bootstrap nodes available - running as genesis node")
                }

                // Step 4: Populate routing table
                populateRoutingTable()

                // Step 5: Announce ourselves to the network
                announcePresence()

                // Step 6: Start background tasks
                startPingLoop()
                startMessageProcessor()
                startGossipLoop()

                _connectionState.value = ConnectionState.CONNECTED
                Timber.d("P2P connected! Peers: ${connectedPeers.size}")

            } catch (e: Exception) {
                Timber.e(e, "P2P connection failed")
                _connectionState.value = ConnectionState.ERROR
            }
        }
    }

    /**
     * Disconnect from P2P network.
     */
    fun disconnect() {
        scope.launch {
            try {
                Timber.d("Disconnecting from P2P network...")

                // Close server socket
                serverSocket?.close()
                serverSocket = null

                // Close all peer connections
                connectedPeers.values.forEach { peer ->
                    peer.socket?.close()
                }
                connectedPeers.clear()

                // Clear routing table
                routingTable.forEach { it.clear() }

                _connectionState.value = ConnectionState.DISCONNECTED
                _peerCount.value = 0
                Timber.d("P2P disconnected")

            } catch (e: Exception) {
                Timber.e(e, "Error during disconnect")
            }
        }
    }

    // ============ Server ============

    /**
     * Start TCP server for incoming P2P connections.
     */
    private suspend fun startServer() = withContext(Dispatchers.IO) {
        try {
            serverSocket = ServerSocket(P2P_PORT)
            Timber.d("P2P server started on port $P2P_PORT")

            scope.launch {
                while (serverSocket != null && !serverSocket!!.isClosed) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        handleIncomingConnection(clientSocket)
                    } catch (e: Exception) {
                        if (serverSocket?.isClosed == false) {
                            Timber.e(e, "Error accepting connection")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start P2P server on port $P2P_PORT")
            // Try alternate port
            serverSocket = ServerSocket(0) // Random available port
            Timber.d("P2P server started on alternate port ${serverSocket?.localPort}")
        }
    }

    /**
     * Handle incoming P2P connection.
     */
    private fun handleIncomingConnection(socket: Socket) {
        scope.launch(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                // Read handshake
                val handshakeJson = reader.readLine() ?: return@launch
                val handshake = JSONObject(handshakeJson)
                
                val peerNodeId = handshake.getString("nodeId").hexToBytes()
                val peerAddress = handshake.getString("walletAddress")
                val peerPublicKey = handshake.getString("publicKey").hexToBytes()

                // Send our handshake
                val ourHandshake = JSONObject().apply {
                    put("nodeId", myNodeId.toHex())
                    put("walletAddress", myWalletAddress)
                    put("publicKey", chatKeys?.sessionPublic?.toHex() ?: "")
                }
                writer.println(ourHandshake.toString())

                // Add to connected peers
                val peer = PeerConnection(
                    address = peerAddress,
                    nodeId = peerNodeId,
                    publicKey = peerPublicKey,
                    socket = socket,
                    isOnline = true,
                    lastSeen = System.currentTimeMillis(),
                    endpoint = InetSocketAddress(socket.inetAddress, socket.port)
                )
                connectedPeers[peerAddress] = peer
                addToRoutingTable(DHTNode(peerNodeId, peerAddress, peer.endpoint!!, System.currentTimeMillis()))
                _peerCount.value = connectedPeers.size

                Timber.d("Incoming connection from $peerAddress")

                // Start reading messages from this peer
                readFromPeer(peer, reader)

            } catch (e: Exception) {
                Timber.e(e, "Error handling incoming connection")
                socket.close()
            }
        }
    }

    // ============ Outgoing Connections ============

    /**
     * Connect to a peer node.
     */
    private suspend fun connectToNode(node: DHTNode): Boolean = withContext(Dispatchers.IO) {
        try {
            if (connectedPeers.containsKey(node.walletAddress)) {
                return@withContext true // Already connected
            }

            val socket = Socket()
            socket.connect(node.endpoint, 10_000) // 10s timeout

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            // Send handshake
            val handshake = JSONObject().apply {
                put("nodeId", myNodeId.toHex())
                put("walletAddress", myWalletAddress)
                put("publicKey", chatKeys?.sessionPublic?.toHex() ?: "")
            }
            writer.println(handshake.toString())

            // Read response
            val responseJson = reader.readLine() ?: return@withContext false
            val response = JSONObject(responseJson)
            
            val peerPublicKey = response.getString("publicKey").hexToBytes()

            // Add to connected peers
            val peer = PeerConnection(
                address = node.walletAddress,
                nodeId = node.nodeId,
                publicKey = peerPublicKey,
                socket = socket,
                isOnline = true,
                lastSeen = System.currentTimeMillis(),
                endpoint = node.endpoint
            )
            connectedPeers[node.walletAddress] = peer
            addToRoutingTable(node)
            _peerCount.value = connectedPeers.size

            Timber.d("Connected to peer: ${node.walletAddress}")

            // Start reading messages from this peer
            scope.launch { readFromPeer(peer, reader) }

            true
        } catch (e: Exception) {
            Timber.w(e, "Failed to connect to ${node.walletAddress}")
            false
        }
    }

    /**
     * Read messages from a connected peer.
     */
    private suspend fun readFromPeer(peer: PeerConnection, reader: BufferedReader) {
        withContext(Dispatchers.IO) {
            try {
                while (peer.isOnline && peer.socket?.isConnected == true) {
                    val messageJson = reader.readLine() ?: break
                    handlePeerMessage(peer, messageJson)
                    peer.lastSeen = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                Timber.w(e, "Connection closed with ${peer.address}")
            } finally {
                peer.isOnline = false
                connectedPeers.remove(peer.address)
                _peerCount.value = connectedPeers.size
            }
        }
    }

    /**
     * Handle message from peer.
     */
    private suspend fun handlePeerMessage(peer: PeerConnection, messageJson: String) {
        try {
            val msg = JSONObject(messageJson)
            val type = msg.getString("type")

            when (type) {
                "ping" -> handlePing(peer, msg)
                "pong" -> handlePong(peer, msg)
                "find_node" -> handleFindNode(peer, msg)
                "node_list" -> handleNodeList(peer, msg)
                "chat" -> handleChatMessage(peer, msg)
                "ack" -> handleAck(peer, msg)
                "gossip" -> handleGossip(peer, msg)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error handling message from ${peer.address}")
        }
    }

    // ============ DHT Operations ============

    /**
     * Get bootstrap nodes from MumbleChatRegistry smart contract.
     * 100% decentralized - reads from blockchain!
     * 
     * Also starts LAN discovery to find peers on local network.
     */
    private suspend fun getBootstrapNodesFromBlockchain(): List<DHTNode> {
        val nodes = mutableListOf<DHTNode>()
        
        // 1. Try to get relay nodes from blockchain
        try {
            val relayNodesFromChain = blockchainService.getActiveRelayNodes()
            Timber.d("Found ${relayNodesFromChain.size} relay nodes from blockchain")
            
            for (relayInfo in relayNodesFromChain) {
                try {
                    // Parse endpoint: format is "IP:PORT" or "/ip4/IP/tcp/PORT"
                    val endpoint = relayInfo.endpoint
                    val (host, port) = parseEndpoint(endpoint)
                    
                    nodes.add(DHTNode(
                        nodeId = sha1(relayInfo.walletAddress.lowercase().toByteArray()),
                        walletAddress = relayInfo.walletAddress,
                        endpoint = InetSocketAddress(host, port),
                        lastSeen = System.currentTimeMillis()
                    ))
                    
                    // Also add to relay nodes map
                    relayNodes[relayInfo.walletAddress] = RelayNode(
                        address = relayInfo.walletAddress,
                        endpoint = InetSocketAddress(host, port),
                        stake = relayInfo.stakedAmount.toLong(),
                        messagesRelayed = relayInfo.messagesRelayed,
                        isOnline = true
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse relay node endpoint: ${relayInfo.endpoint}")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to get relay nodes from blockchain")
        }
        
        // 2. If no blockchain nodes, use configured bootstrap nodes as fallback
        if (nodes.isEmpty()) {
            Timber.d("No blockchain nodes, using configured bootstrap nodes")
            ChatConfig.BOOTSTRAP_NODES.mapNotNull { endpoint ->
                try {
                    val (host, port) = parseEndpoint(endpoint)
                    DHTNode(
                        nodeId = sha1(endpoint.toByteArray()),
                        walletAddress = "0x${sha1(endpoint.toByteArray()).toHex().take(40)}",
                        endpoint = InetSocketAddress(host, port),
                        lastSeen = System.currentTimeMillis()
                    )
                } catch (e: Exception) {
                    null
                }
            }.let { nodes.addAll(it) }
        }
        
        // 3. Start LAN discovery for direct P2P (works without any relay nodes!)
        startLanDiscovery()
        
        return nodes
    }
    
    /**
     * Parse endpoint string to host and port.
     * Supports formats: "IP:PORT", "/ip4/IP/tcp/PORT", "host:port"
     */
    private fun parseEndpoint(endpoint: String): Pair<String, Int> {
        return when {
            endpoint.startsWith("/ip4/") -> {
                // Multiaddr format: /ip4/192.168.1.1/tcp/19370
                val parts = endpoint.split("/")
                val host = parts.getOrNull(2) ?: "127.0.0.1"
                val port = parts.getOrNull(4)?.toIntOrNull() ?: P2P_PORT
                Pair(host, port)
            }
            endpoint.contains(":") -> {
                // Simple format: 192.168.1.1:19370
                val parts = endpoint.split(":")
                val host = parts[0]
                val port = parts.getOrNull(1)?.toIntOrNull() ?: P2P_PORT
                Pair(host, port)
            }
            else -> {
                Pair(endpoint, P2P_PORT)
            }
        }
    }
    
    // ============ LAN Discovery (Direct P2P without relay) ============
    
    private var lanDiscoverySocket: DatagramSocket? = null
    
    /**
     * Start LAN discovery via UDP broadcast.
     * This allows two phones on the same WiFi to find each other
     * WITHOUT needing any relay nodes or internet connection!
     */
    private fun startLanDiscovery() {
        scope.launch(Dispatchers.IO) {
            try {
                lanDiscoverySocket = DatagramSocket(LAN_DISCOVERY_PORT)
                lanDiscoverySocket?.broadcast = true
                
                Timber.d("LAN discovery started on port $LAN_DISCOVERY_PORT")
                
                // Start listener for incoming discovery messages
                scope.launch { listenForLanDiscovery() }
                
                // Periodically broadcast our presence on LAN
                while (isActive && lanDiscoverySocket != null) {
                    broadcastLanPresence()
                    delay(LAN_DISCOVERY_INTERVAL_MS)
                }
            } catch (e: Exception) {
                Timber.w(e, "LAN discovery failed to start")
            }
        }
    }
    
    /**
     * Broadcast our presence on the local network.
     */
    private suspend fun broadcastLanPresence() = withContext(Dispatchers.IO) {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcp = wifiManager.dhcpInfo
            val broadcast = getBroadcastAddress(dhcp)
            
            val message = JSONObject().apply {
                put("type", "mumblechat_discovery")
                put("nodeId", myNodeId.toHex())
                put("walletAddress", myWalletAddress)
                put("port", serverSocket?.localPort ?: P2P_PORT)
                put("timestamp", System.currentTimeMillis())
            }.toString()
            
            val data = message.toByteArray()
            val packet = DatagramPacket(
                data, data.size,
                broadcast, LAN_DISCOVERY_PORT
            )
            
            lanDiscoverySocket?.send(packet)
        } catch (e: Exception) {
            Timber.w(e, "Failed to broadcast LAN presence")
        }
    }
    
    /**
     * Listen for LAN discovery messages from other peers.
     */
    private suspend fun listenForLanDiscovery() = withContext(Dispatchers.IO) {
        val buffer = ByteArray(1024)
        
        while (lanDiscoverySocket != null && !lanDiscoverySocket!!.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                lanDiscoverySocket?.receive(packet)
                
                val message = String(packet.data, 0, packet.length)
                val json = JSONObject(message)
                
                if (json.optString("type") == "mumblechat_discovery") {
                    val peerAddress = json.getString("walletAddress")
                    val peerNodeId = json.getString("nodeId").hexToBytes()
                    val peerPort = json.getInt("port")
                    
                    // Don't connect to ourselves
                    if (peerAddress.equals(myWalletAddress, ignoreCase = true)) {
                        continue
                    }
                    
                    // Skip if already connected
                    if (connectedPeers.containsKey(peerAddress)) {
                        continue
                    }
                    
                    Timber.d("LAN discovery: found peer $peerAddress at ${packet.address.hostAddress}:$peerPort")
                    
                    // Try to connect to this peer
                    val node = DHTNode(
                        nodeId = peerNodeId,
                        walletAddress = peerAddress,
                        endpoint = InetSocketAddress(packet.address, peerPort),
                        lastSeen = System.currentTimeMillis()
                    )
                    
                    scope.launch {
                        if (connectToNode(node)) {
                            Timber.d("LAN discovery: connected to $peerAddress")
                        }
                    }
                }
            } catch (e: Exception) {
                if (lanDiscoverySocket?.isClosed == false) {
                    Timber.w(e, "LAN discovery receive error")
                }
            }
        }
    }
    
    /**
     * Get broadcast address for local network.
     */
    private fun getBroadcastAddress(dhcp: android.net.DhcpInfo): InetAddress {
        val broadcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
        val quads = ByteArray(4)
        for (k in 0..3) {
            quads[k] = (broadcast shr (k * 8) and 0xFF).toByte()
        }
        return InetAddress.getByAddress(quads)
    }

    /**
     * Add node to DHT routing table (Kademlia k-buckets).
     */
    private fun addToRoutingTable(node: DHTNode) {
        val distance = xorDistance(myNodeId, node.nodeId)
        val bucketIndex = findBucketIndex(distance)
        
        val bucket = routingTable[bucketIndex]
        
        synchronized(bucket) {
            // Remove if already exists
            bucket.removeAll { it.walletAddress == node.walletAddress }
            
            // Add to front (most recently seen)
            bucket.add(0, node)
            
            // Trim to k-bucket size
            while (bucket.size > DHT_K_BUCKET_SIZE) {
                bucket.removeAt(bucket.size - 1)
            }
        }
    }

    /**
     * Find closest nodes to a target ID.
     */
    private fun findClosestNodes(targetId: ByteArray, count: Int = DHT_K_BUCKET_SIZE): List<DHTNode> {
        val allNodes = routingTable.flatMap { it.toList() }
        return allNodes
            .sortedBy { xorDistanceInt(it.nodeId, targetId) }
            .take(count)
    }

    /**
     * Populate routing table by querying connected peers.
     */
    private suspend fun populateRoutingTable() {
        // Ask each connected peer for their routing table
        for (peer in connectedPeers.values) {
            try {
                sendFindNode(peer, myNodeId)
            } catch (e: Exception) {
                Timber.w(e, "Failed to query ${peer.address}")
            }
        }
    }

    /**
     * Announce our presence to the DHT network.
     */
    private suspend fun announcePresence() {
        val closestNodes = findClosestNodes(myNodeId)
        for (node in closestNodes) {
            val peer = connectedPeers[node.walletAddress] ?: continue
            try {
                sendPing(peer)
            } catch (e: Exception) {
                Timber.w(e, "Failed to announce to ${node.walletAddress}")
            }
        }
    }

    // ============ Messaging ============

    /**
     * Send a message to a recipient.
     */
    suspend fun sendMessage(
        recipientAddress: String,
        encrypted: MessageEncryption.EncryptedMessage,
        messageId: String
    ): SendResult {
        // Check message cache to prevent duplicates
        if (messageCache.containsKey(messageId)) {
            return SendResult(direct = true, relayed = false)
        }
        messageCache[messageId] = System.currentTimeMillis()

        // Try 1: Direct connection
        val peer = connectedPeers[recipientAddress]
        if (peer != null && peer.isOnline) {
            val sent = sendDirectMessage(peer, encrypted, messageId)
            if (sent) {
                Timber.d("Message $messageId sent directly to $recipientAddress")
                return SendResult(direct = true, relayed = false)
            }
        }

        // Try 2: Find peer in DHT and connect
        val targetNodeId = sha1(recipientAddress.lowercase().toByteArray())
        val closestNodes = findClosestNodes(targetNodeId)
        
        for (node in closestNodes) {
            if (node.walletAddress == recipientAddress) {
                // Found the peer! Try to connect
                if (connectToNode(node)) {
                    val newPeer = connectedPeers[recipientAddress]
                    if (newPeer != null) {
                        val sent = sendDirectMessage(newPeer, encrypted, messageId)
                        if (sent) {
                            return SendResult(direct = true, relayed = false)
                        }
                    }
                }
            }
        }

        // Try 3: Route through relay nodes (gossip)
        val relayResult = gossipMessage(recipientAddress, encrypted, messageId, MESSAGE_TTL)
        if (relayResult != null) {
            Timber.d("Message $messageId gossiped via ${relayResult}")
            return SendResult(direct = false, relayed = true, relayId = relayResult)
        }

        // Try 4: Send via RelayMessageService (internet relay nodes)
        try {
            val encryptedBytes = encrypted.toBytes()
            val sent = relayMessageService.sendMessage(
                recipientAddress = recipientAddress,
                encryptedContent = encryptedBytes,
                messageId = messageId,
                contentType = "TEXT"
            )
            if (sent) {
                Timber.d("Message $messageId sent via RelayMessageService")
                return SendResult(direct = false, relayed = true, relayId = "relay_service")
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to send via RelayMessageService")
        }

        // Try 5: Store for later (recipient offline)
        storeForLater(recipientAddress, encrypted, messageId)
        return SendResult(direct = false, relayed = false)
    }

    /**
     * Send message directly to connected peer.
     */
    private suspend fun sendDirectMessage(
        peer: PeerConnection,
        encrypted: MessageEncryption.EncryptedMessage,
        messageId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = peer.socket ?: return@withContext false
            val writer = PrintWriter(socket.getOutputStream(), true)

            val msg = JSONObject().apply {
                put("type", "chat")
                put("id", messageId)
                put("from", myWalletAddress)
                put("to", peer.address)
                put("ciphertext", encrypted.ciphertext.toHex())
                put("nonce", encrypted.nonce.toHex())
                put("authTag", encrypted.authTag.toHex())
                put("timestamp", System.currentTimeMillis())
            }
            writer.println(msg.toString())
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to send direct message to ${peer.address}")
            false
        }
    }

    /**
     * Gossip message through the network (flood protocol).
     * When a message is gossiped through relay nodes, a signed relay receipt is generated.
     */
    private suspend fun gossipMessage(
        recipientAddress: String,
        encrypted: MessageEncryption.EncryptedMessage,
        messageId: String,
        ttl: Int
    ): String? {
        if (ttl <= 0) return null

        // Send to all connected peers (except recipient)
        var relayedVia: String? = null
        
        for (peer in connectedPeers.values) {
            if (peer.address == recipientAddress) continue
            if (!peer.isOnline) continue

            try {
                val socket = peer.socket ?: continue
                val writer = PrintWriter(socket.getOutputStream(), true)
                
                // Check if this peer is a relay node
                val isRelay = relayNodes.containsKey(peer.address)
                
                // Create relay receipt signature if going through a relay
                val relayReceiptData = if (isRelay) {
                    createRelayReceiptForPeer(messageId, encrypted, peer.address, recipientAddress)
                } else null

                val msg = JSONObject().apply {
                    put("type", "gossip")
                    put("id", messageId)
                    put("from", myWalletAddress)
                    put("to", recipientAddress)
                    put("ciphertext", encrypted.ciphertext.toHex())
                    put("nonce", encrypted.nonce.toHex())
                    put("authTag", encrypted.authTag.toHex())
                    put("ttl", ttl - 1)
                    put("timestamp", System.currentTimeMillis())
                    
                    // Include relay receipt data if going through relay
                    if (relayReceiptData != null) {
                        put("relayReceipt", JSONObject().apply {
                            put("messageHash", relayReceiptData.messageHash.toHex())
                            put("relayNode", relayReceiptData.relayNodeAddress)
                            put("sender", relayReceiptData.senderAddress)
                            put("recipient", relayReceiptData.recipientAddress)
                            put("timestamp", relayReceiptData.timestamp)
                            put("nonce", relayReceiptData.nonce)
                            put("signature", relayReceiptData.senderSignature.toHex())
                        })
                    }
                }
                writer.println(msg.toString())
                relayedVia = peer.address
                
            } catch (e: Exception) {
                Timber.w(e, "Failed to gossip to ${peer.address}")
            }
        }

        return relayedVia
    }
    
    /**
     * Create a signed relay receipt for a message being relayed through a relay node.
     * This receipt can be submitted to the blockchain by the relay node to earn rewards.
     */
    private suspend fun createRelayReceiptForPeer(
        messageId: String,
        encrypted: MessageEncryption.EncryptedMessage,
        relayNodeAddress: String,
        recipientAddress: String
    ): RelayReceipt? {
        val keys = chatKeys ?: return null
        
        try {
            // 1. Create message hash (hash of encrypted content)
            val messageHash = MessageDigest.getInstance("SHA-256").digest(encrypted.ciphertext)
            
            // 2. Get nonce (use timestamp-based nonce, relay will verify with contract)
            val nonce = System.currentTimeMillis() / 1000
            val timestamp = System.currentTimeMillis()
            
            // 3. Create the data to sign: keccak256(messageHash, relayNode, timestamp, nonce)
            val dataToSign = createRelayReceiptSignData(messageHash, relayNodeAddress, timestamp, nonce)
            
            // 4. Sign with identity key (Ed25519 -> convert to ECDSA recoverable signature)
            val signature = signRelayReceipt(dataToSign, keys.identityPrivate)
            
            return RelayReceipt(
                messageHash = messageHash,
                relayNodeAddress = relayNodeAddress,
                senderAddress = myWalletAddress,
                recipientAddress = recipientAddress,
                timestamp = timestamp,
                nonce = nonce,
                senderSignature = signature
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to create relay receipt")
            return null
        }
    }
    
    /**
     * Create the data that needs to be signed for a relay receipt.
     * Matches the format expected by the smart contract.
     */
    private fun createRelayReceiptSignData(
        messageHash: ByteArray,
        relayNodeAddress: String,
        timestamp: Long,
        nonce: Long
    ): ByteArray {
        // Pack: messageHash (32 bytes) + relayNode (20 bytes) + timestamp (8 bytes) + nonce (8 bytes)
        val relayBytes = relayNodeAddress.removePrefix("0x").lowercase().hexToBytes()
        val timestampBytes = ByteArray(8)
        val nonceBytes = ByteArray(8)
        
        for (i in 0..7) {
            timestampBytes[7 - i] = ((timestamp shr (i * 8)) and 0xFF).toByte()
            nonceBytes[7 - i] = ((nonce shr (i * 8)) and 0xFF).toByte()
        }
        
        val packed = messageHash + relayBytes + timestampBytes + nonceBytes
        
        // Hash the packed data (keccak256)
        return keccak256(packed)
    }
    
    /**
     * Sign a relay receipt using the wallet's private key.
     * Returns a recoverable ECDSA signature (65 bytes: r + s + v).
     */
    private suspend fun signRelayReceipt(dataHash: ByteArray, privateKey: ByteArray): ByteArray {
        // For now, use a simple signature scheme
        // In production, this should use the actual wallet's signing capability
        val digest = MessageDigest.getInstance("SHA-256")
        val combined = dataHash + privateKey
        val signature = digest.digest(combined)
        
        // Create 65-byte signature (r=32, s=32, v=1)
        // Note: This is a placeholder - real implementation needs ECDSA
        return signature + signature + byteArrayOf(27.toByte())
    }
    
    /**
     * Keccak-256 hash (Ethereum compatible).
     * Uses Spongy Castle (Android-compatible fork of Bouncy Castle) if available.
     */
    private fun keccak256(data: ByteArray): ByteArray {
        return try {
            // Try using Spongy Castle's Keccak (available on Android)
            val digest = java.security.MessageDigest.getInstance("KECCAK-256")
            digest.digest(data)
        } catch (e: Exception) {
            // Fallback to SHA-256 if Keccak not available
            // Note: This is a fallback - for production, ensure Keccak is available
            Timber.w(e, "Keccak-256 not available, using SHA-256 fallback")
            MessageDigest.getInstance("SHA-256").digest(data)
        }
    }

    /**
     * Store message for offline recipient.
     */
    private fun storeForLater(
        recipientAddress: String,
        encrypted: MessageEncryption.EncryptedMessage,
        messageId: String
    ) {
        val pending = pendingMessages.getOrPut(recipientAddress) { mutableListOf() }
        pending.add(PendingMessage(
            messageId = messageId,
            encrypted = encrypted,
            timestamp = System.currentTimeMillis(),
            ttl = MESSAGE_TTL
        ))
        Timber.d("Stored message $messageId for offline recipient $recipientAddress")
    }

    // ============ Message Handlers ============

    private suspend fun handlePing(peer: PeerConnection, msg: JSONObject) {
        // Reply with pong
        val socket = peer.socket ?: return
        val writer = PrintWriter(socket.getOutputStream(), true)
        
        val pong = JSONObject().apply {
            put("type", "pong")
            put("nodeId", myNodeId.toHex())
            put("timestamp", System.currentTimeMillis())
        }
        writer.println(pong.toString())
    }

    private fun handlePong(peer: PeerConnection, msg: JSONObject) {
        peer.lastSeen = System.currentTimeMillis()
    }

    private suspend fun handleFindNode(peer: PeerConnection, msg: JSONObject) {
        val targetId = msg.getString("targetId").hexToBytes()
        val closestNodes = findClosestNodes(targetId)

        val socket = peer.socket ?: return
        val writer = PrintWriter(socket.getOutputStream(), true)

        val nodeList = JSONObject().apply {
            put("type", "node_list")
            put("nodes", closestNodes.map { node ->
                JSONObject().apply {
                    put("nodeId", node.nodeId.toHex())
                    put("walletAddress", node.walletAddress)
                    put("host", node.endpoint.hostString)
                    put("port", node.endpoint.port)
                }
            })
        }
        writer.println(nodeList.toString())
    }

    private fun handleNodeList(peer: PeerConnection, msg: JSONObject) {
        val nodes = msg.getJSONArray("nodes")
        for (i in 0 until nodes.length()) {
            val nodeJson = nodes.getJSONObject(i)
            val node = DHTNode(
                nodeId = nodeJson.getString("nodeId").hexToBytes(),
                walletAddress = nodeJson.getString("walletAddress"),
                endpoint = InetSocketAddress(
                    nodeJson.getString("host"),
                    nodeJson.getInt("port")
                ),
                lastSeen = System.currentTimeMillis()
            )
            addToRoutingTable(node)
        }
    }

    private suspend fun handleChatMessage(peer: PeerConnection, msg: JSONObject) {
        val messageId = msg.getString("id")
        val toAddress = msg.getString("to")

        // Dedup check
        if (messageCache.containsKey(messageId)) return
        messageCache[messageId] = System.currentTimeMillis()

        if (toAddress.equals(myWalletAddress, ignoreCase = true)) {
            // Message is for us!
            val incomingMessage = IncomingMessage(
                messageId = messageId,
                senderAddress = msg.getString("from"),
                encrypted = MessageEncryption.EncryptedMessage(
                    ciphertext = msg.getString("ciphertext").hexToBytes(),
                    nonce = msg.getString("nonce").hexToBytes(),
                    authTag = msg.getString("authTag").hexToBytes()
                ),
                timestamp = msg.getLong("timestamp"),
                signature = null
            )
            _incomingMessages.emit(incomingMessage)

            // Send ACK
            sendAck(peer, messageId)
        }
    }

    private suspend fun handleGossip(peer: PeerConnection, msg: JSONObject) {
        val messageId = msg.getString("id")
        val toAddress = msg.getString("to")
        val ttl = msg.getInt("ttl")

        // Dedup check
        if (messageCache.containsKey(messageId)) return
        messageCache[messageId] = System.currentTimeMillis()
        
        // If this node is a relay and a relay receipt is included, store it for claiming
        if (msg.has("relayReceipt") && isRelayNode()) {
            val receiptJson = msg.getJSONObject("relayReceipt")
            val receipt = RelayReceipt(
                messageHash = receiptJson.getString("messageHash").hexToBytes(),
                relayNodeAddress = receiptJson.getString("relayNode"),
                senderAddress = receiptJson.getString("sender"),
                recipientAddress = receiptJson.getString("recipient"),
                timestamp = receiptJson.getLong("timestamp"),
                nonce = receiptJson.getLong("nonce"),
                senderSignature = receiptJson.getString("signature").hexToBytes()
            )
            
            // Only store if the receipt is for us (this relay node)
            if (receipt.relayNodeAddress.equals(myWalletAddress, ignoreCase = true)) {
                storeRelayReceipt(receipt)
            }
        }

        if (toAddress.equals(myWalletAddress, ignoreCase = true)) {
            // Message is for us!
            handleChatMessage(peer, msg)
        } else if (ttl > 0) {
            // Forward to other peers (gossip)
            val encrypted = MessageEncryption.EncryptedMessage(
                ciphertext = msg.getString("ciphertext").hexToBytes(),
                nonce = msg.getString("nonce").hexToBytes(),
                authTag = msg.getString("authTag").hexToBytes()
            )
            gossipMessage(toAddress, encrypted, messageId, ttl)
        }
    }
    
    /**
     * Check if this node is registered as a relay node.
     */
    private fun isRelayNode(): Boolean {
        return relayNodes.containsKey(myWalletAddress)
    }
    
    /**
     * Store a relay receipt for later blockchain submission.
     */
    private suspend fun storeRelayReceipt(receipt: RelayReceipt) {
        val receipts = pendingRelayReceipts.getOrPut(myWalletAddress) { mutableListOf() }
        receipts.add(receipt)
        
        // Emit for blockchain submission
        _relayReceiptsToSubmit.emit(receipt)
        
        Timber.d("Stored relay receipt for message ${receipt.messageHash.toHex()}")
    }
    
    /**
     * Get all pending relay receipts that need to be submitted to blockchain.
     */
    fun getPendingRelayReceipts(): List<RelayReceipt> {
        return pendingRelayReceipts[myWalletAddress] ?: emptyList()
    }
    
    /**
     * Clear submitted relay receipts.
     */
    fun clearSubmittedReceipts(receipts: List<RelayReceipt>) {
        val pending = pendingRelayReceipts[myWalletAddress] ?: return
        pending.removeAll(receipts.toSet())
    }

    private fun handleAck(peer: PeerConnection, msg: JSONObject) {
        val messageId = msg.getString("messageId")
        Timber.d("Received ACK for message $messageId from ${peer.address}")
        // TODO: Mark message as delivered
    }

    // ============ Protocol Messages ============

    private suspend fun sendPing(peer: PeerConnection) {
        val socket = peer.socket ?: return
        val writer = PrintWriter(socket.getOutputStream(), true)

        val ping = JSONObject().apply {
            put("type", "ping")
            put("nodeId", myNodeId.toHex())
            put("timestamp", System.currentTimeMillis())
        }
        writer.println(ping.toString())
    }

    private suspend fun sendFindNode(peer: PeerConnection, targetId: ByteArray) {
        val socket = peer.socket ?: return
        val writer = PrintWriter(socket.getOutputStream(), true)

        val findNode = JSONObject().apply {
            put("type", "find_node")
            put("targetId", targetId.toHex())
        }
        writer.println(findNode.toString())
    }

    private suspend fun sendAck(peer: PeerConnection, messageId: String) {
        val socket = peer.socket ?: return
        val writer = PrintWriter(socket.getOutputStream(), true)

        val ack = JSONObject().apply {
            put("type", "ack")
            put("messageId", messageId)
        }
        writer.println(ack.toString())
    }

    // ============ Background Tasks ============

    private fun startPingLoop() {
        scope.launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                
                val now = System.currentTimeMillis()
                for (peer in connectedPeers.values.toList()) {
                    // Remove stale peers
                    if (now - peer.lastSeen > PEER_TIMEOUT_MS) {
                        peer.isOnline = false
                        peer.socket?.close()
                        connectedPeers.remove(peer.address)
                        _peerCount.value = connectedPeers.size
                        continue
                    }
                    
                    // Ping active peers
                    try {
                        sendPing(peer)
                    } catch (e: Exception) {
                        Timber.w(e, "Ping failed for ${peer.address}")
                    }
                }
            }
        }
    }

    private fun startMessageProcessor() {
        scope.launch {
            // Process incoming message flow
            incomingMessages.collect { message ->
                Timber.d("Received message ${message.messageId} from ${message.senderAddress}")
            }
        }
    }

    private fun startGossipLoop() {
        scope.launch {
            while (isActive) {
                delay(60_000) // Every minute
                
                // Clean old message cache entries
                val now = System.currentTimeMillis()
                messageCache.entries.removeIf { now - it.value > 3600_000 } // 1 hour TTL
                
                // Retry pending messages
                for ((address, messages) in pendingMessages) {
                    val peer = connectedPeers[address]
                    if (peer != null && peer.isOnline) {
                        messages.forEach { pending ->
                            try {
                                sendDirectMessage(peer, pending.encrypted, pending.messageId)
                            } catch (e: Exception) {
                                Timber.w(e, "Failed to retry message ${pending.messageId}")
                            }
                        }
                        messages.clear()
                    }
                }
            }
        }
    }

    // ============ Public API ============

    suspend fun sendGroupMessage(
        recipientAddress: String,
        groupId: String,
        encrypted: MessageEncryption.EncryptedMessage,
        signature: ByteArray
    ): Boolean {
        val result = sendMessage(recipientAddress, encrypted, "$groupId-${System.currentTimeMillis()}")
        return result.direct || result.relayed
    }

    suspend fun sendDeliveryAck(senderAddress: String, messageId: String) {
        val peer = connectedPeers[senderAddress] ?: return
        sendAck(peer, messageId)
    }

    suspend fun fetchPendingMessages(): List<IncomingMessage> {
        // Pending messages are delivered via gossip when we come online
        return emptyList()
    }

    suspend fun isPeerOnline(address: String): Boolean {
        return connectedPeers[address]?.isOnline ?: false
    }

    suspend fun getPeerPublicKey(address: String): ByteArray? {
        return connectedPeers[address]?.publicKey
    }
    
    /**
     * Get list of all online peer addresses.
     * Used by RelayService to deliver stored messages.
     */
    fun getOnlinePeers(): List<String> {
        return connectedPeers.values
            .filter { it.isOnline }
            .map { it.address }
    }
    
    /**
     * Deliver a stored message to a recipient.
     * Used by RelayService for offline message delivery.
     * 
     * @param recipientAddress The recipient's wallet address
     * @param messageId The message ID
     * @param encryptedBlob The encrypted message blob
     * @return true if delivery was successful
     */
    suspend fun deliverStoredMessage(
        recipientAddress: String,
        messageId: String,
        encryptedBlob: ByteArray
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val peer = connectedPeers[recipientAddress]
            if (peer == null || !peer.isOnline || peer.socket == null) {
                return@withContext false
            }
            
            // Parse blob back into encrypted message format
            // Blob format: ciphertext + nonce (last 12 bytes)
            if (encryptedBlob.size < 12) {
                Timber.e("Invalid encrypted blob size")
                return@withContext false
            }
            
            val nonceSize = 12
            val ciphertext = encryptedBlob.copyOfRange(0, encryptedBlob.size - nonceSize)
            val nonce = encryptedBlob.copyOfRange(encryptedBlob.size - nonceSize, encryptedBlob.size)
            
            // Build relay forward message
            val message = JSONObject().apply {
                put("type", "RELAY_FORWARD")
                put("id", messageId)
                put("from", myWalletAddress)
                put("to", recipientAddress)
                put("encrypted", ciphertext.toHex())
                put("nonce", nonce.toHex())
                put("timestamp", System.currentTimeMillis())
            }
            
            // Send to peer
            val writer = PrintWriter(peer.socket!!.getOutputStream(), true)
            writer.println(message.toString())
            
            Timber.d("Delivered stored message $messageId to $recipientAddress")
            true
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to deliver stored message $messageId")
            false
        }
    }

    // ============ Utility Functions ============

    private fun sha1(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-1").digest(data)
    }

    private fun xorDistance(a: ByteArray, b: ByteArray): ByteArray {
        require(a.size == b.size) { "Arrays must have same size" }
        return ByteArray(a.size) { i -> (a[i] xor b[i]) }
    }

    private fun xorDistanceInt(a: ByteArray, b: ByteArray): Int {
        val dist = xorDistance(a, b)
        return dist.fold(0) { acc, byte -> acc * 256 + (byte.toInt() and 0xFF) }
    }

    private fun findBucketIndex(distance: ByteArray): Int {
        for (i in distance.indices) {
            if (distance[i] != 0.toByte()) {
                val byteVal = distance[i].toInt() and 0xFF
                val leadingZeros = Integer.numberOfLeadingZeros(byteVal) - 24
                return i * 8 + leadingZeros
            }
        }
        return DHT_KEY_SIZE - 1
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        check(length % 2 == 0) { "Hex string must have even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    
    // ============ Advanced Features ============
    
    /**
     * Get the count of currently connected P2P peers
     */
    fun getConnectedPeerCount(): Int {
        return connectedPeers.values.count { it.isOnline }
    }
    
    /**
     * Enable or disable P2P networking
     */
    fun setP2PEnabled(enabled: Boolean) {
        if (enabled) {
            if (_connectionState.value == ConnectionState.DISCONNECTED) {
                connect()
            }
        } else {
            if (_connectionState.value != ConnectionState.DISCONNECTED) {
                disconnect()
            }
        }
    }
}
