package com.ramapay.app.chat.p2p

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bootstrap Manager for MumbleChat Protocol
 * 
 * ZERO-COST DECENTRALIZED BOOTSTRAP
 * ================================
 * No servers required. Uses priority-based peer discovery:
 * 
 * Priority 1: Cached Peers (instant, already verified)
 * Priority 2: LAN Discovery (same WiFi, mDNS/broadcast)
 * Priority 3: Blockchain Registry (query smart contract)
 * Priority 4: QR Code Exchange (social proof, always works)
 * Priority 5: Peer Gossip (existing peers share their known peers)
 * 
 * The "cold start problem" is solved by:
 * - Same WiFi detection (apps find each other)
 * - QR code exchange when meeting in person
 * - Blockchain registry lookup
 * - Peer sharing (once you have 1 peer, you can find more)
 */
@Singleton
class BootstrapManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val peerCache: PeerCache,
    private val blockchainPeerResolver: BlockchainPeerResolver
) {
    companion object {
        private const val TAG = "BootstrapManager"
        
        // LAN Discovery
        private const val BROADCAST_PORT = 19375
        private val DISCOVERY_MAGIC = byteArrayOf(0x4D, 0x43, 0x44, 0x49) // "MCDI" = MumbleChat DIscovery
        
        // Limits
        private const val MIN_PEERS = 3
        private const val TARGET_PEERS = 8
        private const val MAX_PEERS = 20
        private const val MAX_CACHED_PEERS = 100
    }
    
    data class PeerInfo(
        val walletAddress: String,
        val publicIp: String,
        val publicPort: Int,
        val lastSeen: Long = System.currentTimeMillis(),
        val source: PeerSource,
        val isActive: Boolean = true,
        val successfulConnections: Int = 0
    )
    
    enum class PeerSource {
        CACHE,          // Previously connected peer
        LAN,            // Same WiFi network
        BLOCKCHAIN,     // From smart contract registry
        QR_CODE,        // Scanned QR code
        PEER_GOSSIP,    // Shared by another peer
        MANUAL          // Manually added
    }
    
    private val _activePeers = MutableStateFlow<Map<String, PeerInfo>>(emptyMap())
    val activePeers: StateFlow<Map<String, PeerInfo>> = _activePeers.asStateFlow()
    
    private val _bootstrapStatus = MutableStateFlow(BootstrapStatus.NOT_STARTED)
    val bootstrapStatus: StateFlow<BootstrapStatus> = _bootstrapStatus.asStateFlow()
    
    private var lanDiscoverySocket: DatagramSocket? = null
    
    enum class BootstrapStatus {
        NOT_STARTED,
        IN_PROGRESS,
        CONNECTED,
        NO_PEERS_FOUND
    }
    
    /**
     * Start the bootstrap process.
     * Uses priority-based discovery with no server dependencies.
     */
    suspend fun bootstrap(myWalletAddress: String): List<PeerInfo> = withContext(Dispatchers.IO) {
        Timber.i("$TAG: Starting bootstrap for $myWalletAddress")
        _bootstrapStatus.value = BootstrapStatus.IN_PROGRESS
        
        val discoveredPeers = mutableMapOf<String, PeerInfo>()
        
        // Priority 1: Cached Peers (fastest)
        Timber.d("$TAG: [Priority 1] Loading cached peers...")
        val cachedPeers = loadCachedPeers()
        cachedPeers.forEach { peer ->
            if (!peer.walletAddress.equals(myWalletAddress, ignoreCase = true)) {
                discoveredPeers[peer.walletAddress.lowercase()] = peer
            }
        }
        Timber.d("$TAG: Found ${cachedPeers.size} cached peers")
        
        if (discoveredPeers.size >= TARGET_PEERS) {
            Timber.i("$TAG: Have enough cached peers, bootstrap complete")
            _activePeers.value = discoveredPeers
            _bootstrapStatus.value = BootstrapStatus.CONNECTED
            return@withContext discoveredPeers.values.toList()
        }
        
        // Priority 2: LAN Discovery (same WiFi)
        Timber.d("$TAG: [Priority 2] Starting LAN discovery...")
        val lanDeferred = async { discoverLanPeers(myWalletAddress) }
        
        // Priority 3: Blockchain Registry (parallel with LAN)
        Timber.d("$TAG: [Priority 3] Querying blockchain registry...")
        val blockchainDeferred = async { discoverBlockchainPeers(myWalletAddress) }
        
        // Wait for both with timeout
        try {
            val lanPeers = withTimeoutOrNull(5000) { lanDeferred.await() } ?: emptyList()
            Timber.d("$TAG: LAN discovery found ${lanPeers.size} peers")
            lanPeers.forEach { peer ->
                discoveredPeers[peer.walletAddress.lowercase()] = peer
            }
        } catch (e: Exception) {
            Timber.w(e, "$TAG: LAN discovery failed")
        }
        
        try {
            val blockchainPeers = withTimeoutOrNull(10000) { blockchainDeferred.await() } ?: emptyList()
            Timber.d("$TAG: Blockchain registry found ${blockchainPeers.size} peers")
            blockchainPeers.forEach { peer ->
                if (!discoveredPeers.containsKey(peer.walletAddress.lowercase())) {
                    discoveredPeers[peer.walletAddress.lowercase()] = peer
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Blockchain discovery failed")
        }
        
        // Priority 5: Peer Gossip (ask existing peers for their known peers)
        if (discoveredPeers.isNotEmpty() && discoveredPeers.size < TARGET_PEERS) {
            Timber.d("$TAG: [Priority 5] Requesting peer gossip...")
            // This will be handled by the DHT/P2P manager once we have connections
        }
        
        // Update state
        _activePeers.value = discoveredPeers
        _bootstrapStatus.value = if (discoveredPeers.isNotEmpty()) {
            BootstrapStatus.CONNECTED
        } else {
            BootstrapStatus.NO_PEERS_FOUND
        }
        
        // Save to cache for next time
        savePeersToCache(discoveredPeers.values.toList())
        
        Timber.i("$TAG: Bootstrap complete. Found ${discoveredPeers.size} peers")
        discoveredPeers.values.toList()
    }
    
    /**
     * Load cached peers from persistent storage.
     */
    private suspend fun loadCachedPeers(): List<PeerInfo> {
        return peerCache.loadPeers()
            .filter { it.lastSeen > System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000 } // Last week
            .sortedByDescending { it.successfulConnections }
            .take(MAX_CACHED_PEERS)
    }
    
    /**
     * Save peers to cache for next bootstrap.
     */
    private suspend fun savePeersToCache(peers: List<PeerInfo>) {
        peerCache.savePeers(peers.take(MAX_CACHED_PEERS))
    }
    
    /**
     * Discover peers on the same WiFi network using broadcast.
     */
    private suspend fun discoverLanPeers(myWalletAddress: String): List<PeerInfo> = withContext(Dispatchers.IO) {
        val discoveredPeers = mutableListOf<PeerInfo>()
        
        try {
            lanDiscoverySocket?.close()
            lanDiscoverySocket = DatagramSocket(BROADCAST_PORT)
            lanDiscoverySocket?.broadcast = true
            lanDiscoverySocket?.soTimeout = 3000
            
            // Get broadcast address
            val broadcastAddress = getBroadcastAddress()
            if (broadcastAddress == null) {
                Timber.w("$TAG: No broadcast address available (not on WiFi?)")
                return@withContext emptyList()
            }
            
            // Send discovery broadcast
            val discoveryPacket = createDiscoveryPacket(myWalletAddress)
            val sendPacket = java.net.DatagramPacket(
                discoveryPacket,
                discoveryPacket.size,
                broadcastAddress,
                BROADCAST_PORT
            )
            
            lanDiscoverySocket?.send(sendPacket)
            Timber.d("$TAG: Sent LAN discovery broadcast")
            
            // Listen for responses
            val receiveBuffer = ByteArray(256)
            val receivePacket = java.net.DatagramPacket(receiveBuffer, receiveBuffer.size)
            
            val endTime = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < endTime) {
                try {
                    lanDiscoverySocket?.receive(receivePacket)
                    
                    val peerInfo = parseDiscoveryPacket(
                        receiveBuffer,
                        receivePacket.length,
                        receivePacket.address.hostAddress ?: ""
                    )
                    
                    if (peerInfo != null && !peerInfo.walletAddress.equals(myWalletAddress, ignoreCase = true)) {
                        Timber.d("$TAG: Discovered LAN peer: ${peerInfo.walletAddress}")
                        discoveredPeers.add(peerInfo)
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // Normal timeout
                } catch (e: Exception) {
                    Timber.w(e, "$TAG: LAN discovery receive error")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: LAN discovery failed")
        }
        
        discoveredPeers
    }
    
    /**
     * Discover peers from blockchain registry.
     */
    private suspend fun discoverBlockchainPeers(myWalletAddress: String): List<PeerInfo> {
        return blockchainPeerResolver.resolvePeers()
            .filter { !it.walletAddress.equals(myWalletAddress, ignoreCase = true) }
            .map { registration ->
                PeerInfo(
                    walletAddress = registration.walletAddress,
                    publicIp = registration.publicIp,
                    publicPort = registration.publicPort,
                    lastSeen = System.currentTimeMillis(),
                    source = PeerSource.BLOCKCHAIN
                )
            }
    }
    
    /**
     * Add a peer discovered via QR code.
     * This always works - no network required for initial exchange.
     */
    fun addPeerFromQrCode(
        walletAddress: String,
        publicIp: String,
        publicPort: Int
    ): PeerInfo {
        val peer = PeerInfo(
            walletAddress = walletAddress,
            publicIp = publicIp,
            publicPort = publicPort,
            lastSeen = System.currentTimeMillis(),
            source = PeerSource.QR_CODE
        )
        
        _activePeers.update { current ->
            current + (walletAddress.lowercase() to peer)
        }
        
        Timber.i("$TAG: Added peer from QR code: $walletAddress")
        return peer
    }
    
    /**
     * Add peers discovered via gossip from existing peers.
     */
    fun addPeersFromGossip(peers: List<PeerInfo>) {
        _activePeers.update { current ->
            val mutable = current.toMutableMap()
            peers.forEach { peer ->
                val key = peer.walletAddress.lowercase()
                if (!mutable.containsKey(key)) {
                    mutable[key] = peer.copy(source = PeerSource.PEER_GOSSIP)
                }
            }
            mutable
        }
        Timber.d("$TAG: Added ${peers.size} peers from gossip")
    }
    
    /**
     * Mark a peer as successfully connected.
     */
    fun markPeerConnected(walletAddress: String) {
        _activePeers.update { current ->
            val key = walletAddress.lowercase()
            val peer = current[key] ?: return@update current
            current + (key to peer.copy(
                lastSeen = System.currentTimeMillis(),
                isActive = true,
                successfulConnections = peer.successfulConnections + 1
            ))
        }
    }
    
    /**
     * Mark a peer as disconnected.
     */
    fun markPeerDisconnected(walletAddress: String) {
        _activePeers.update { current ->
            val key = walletAddress.lowercase()
            val peer = current[key] ?: return@update current
            current + (key to peer.copy(isActive = false))
        }
    }
    
    /**
     * Get the broadcast address for LAN discovery.
     */
    private fun getBroadcastAddress(): InetAddress? {
        try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val ipAddress = wifiInfo?.ipAddress ?: return null
            
            if (ipAddress == 0) return null
            
            // Convert to bytes and create broadcast address
            val ipBytes = byteArrayOf(
                (ipAddress and 0xFF).toByte(),
                (ipAddress shr 8 and 0xFF).toByte(),
                (ipAddress shr 16 and 0xFF).toByte(),
                255.toByte() // Broadcast
            )
            
            return InetAddress.getByAddress(ipBytes)
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed to get broadcast address")
            return null
        }
    }
    
    /**
     * Create LAN discovery packet.
     * Format: MAGIC (4) + VERSION (1) + WALLET_LEN (1) + WALLET + PORT (2)
     */
    private fun createDiscoveryPacket(walletAddress: String): ByteArray {
        val walletBytes = walletAddress.toByteArray(Charsets.UTF_8)
        val buffer = java.nio.ByteBuffer.allocate(8 + walletBytes.size)
        
        buffer.put(DISCOVERY_MAGIC)
        buffer.put(0x01) // Version
        buffer.put(walletBytes.size.toByte())
        buffer.put(walletBytes)
        buffer.putShort(19372.toShort()) // Our P2P port
        
        return buffer.array()
    }
    
    /**
     * Parse LAN discovery packet.
     */
    private fun parseDiscoveryPacket(data: ByteArray, length: Int, senderIp: String): PeerInfo? {
        if (length < 8) return null
        
        val buffer = java.nio.ByteBuffer.wrap(data, 0, length)
        
        // Check magic
        val magic = ByteArray(4)
        buffer.get(magic)
        if (!magic.contentEquals(DISCOVERY_MAGIC)) return null
        
        val version = buffer.get()
        if (version != 0x01.toByte()) return null
        
        val walletLen = buffer.get().toInt() and 0xFF
        if (walletLen > 50 || buffer.remaining() < walletLen + 2) return null
        
        val walletBytes = ByteArray(walletLen)
        buffer.get(walletBytes)
        val wallet = String(walletBytes, Charsets.UTF_8)
        
        val port = buffer.short.toInt() and 0xFFFF
        
        return PeerInfo(
            walletAddress = wallet,
            publicIp = senderIp,
            publicPort = port,
            source = PeerSource.LAN
        )
    }
    
    /**
     * Start listening for LAN discovery requests from other devices.
     */
    suspend fun startLanDiscoveryListener(myWalletAddress: String) = withContext(Dispatchers.IO) {
        Timber.d("$TAG: Starting LAN discovery listener")
        
        try {
            val socket = DatagramSocket(BROADCAST_PORT)
            socket.broadcast = true
            
            val receiveBuffer = ByteArray(256)
            val receivePacket = java.net.DatagramPacket(receiveBuffer, receiveBuffer.size)
            
            while (true) {
                try {
                    socket.receive(receivePacket)
                    
                    val peerInfo = parseDiscoveryPacket(
                        receiveBuffer,
                        receivePacket.length,
                        receivePacket.address.hostAddress ?: ""
                    )
                    
                    if (peerInfo != null && !peerInfo.walletAddress.equals(myWalletAddress, ignoreCase = true)) {
                        Timber.d("$TAG: Received LAN discovery from ${peerInfo.walletAddress}")
                        
                        // Add to our peers
                        _activePeers.update { current ->
                            current + (peerInfo.walletAddress.lowercase() to peerInfo)
                        }
                        
                        // Send response
                        val responsePacket = createDiscoveryPacket(myWalletAddress)
                        val sendPacket = java.net.DatagramPacket(
                            responsePacket,
                            responsePacket.size,
                            receivePacket.address,
                            receivePacket.port
                        )
                        socket.send(sendPacket)
                    }
                } catch (e: Exception) {
                    if (e.message?.contains("closed") != true) {
                        Timber.w(e, "$TAG: LAN listener error")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start LAN listener")
        }
    }
    
    /**
     * Cleanup resources.
     */
    fun shutdown() {
        lanDiscoverySocket?.close()
        lanDiscoverySocket = null
    }
}
