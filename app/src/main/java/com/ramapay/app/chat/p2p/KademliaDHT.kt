package com.ramapay.app.chat.p2p

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import timber.log.Timber
import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kademlia Distributed Hash Table
 * 
 * Enables O(log n) peer discovery in the network.
 * Each node maintains k-buckets organized by XOR distance.
 * 
 * Key Concepts:
 * - NodeID: SHA256(lowercase(walletAddress)) - 256 bits
 * - XOR Distance: bitwise XOR between two NodeIDs
 * - k-buckets: 256 buckets, each holding up to k=20 nodes
 * - Bucket i contains nodes at distance 2^i to 2^(i+1) from us
 * 
 * Sybil Resistance:
 * - Wallet signature verification on peer announcements
 * - Rate limiting on peer additions
 * - Optional on-chain registration requirement
 * 
 * Operations:
 * - PING: Check if node is alive
 * - FIND_NODE: Get k closest nodes to a target
 * - STORE: Store a key-value pair
 * - FIND_VALUE: Retrieve a stored value
 */
@Singleton
class KademliaDHT @Inject constructor(
    private val rateLimiter: RateLimiter
) {
    companion object {
        private const val TAG = "KademliaDHT"
        
        // Kademlia parameters
        private const val K = 20  // Max nodes per bucket
        private const val ALPHA = 3  // Parallel lookups
        private const val ID_BITS = 256  // SHA256 = 256 bits
        
        // Timeouts
        private const val NODE_TIMEOUT_MS = 15 * 60 * 1000L  // 15 minutes
        private const val REFRESH_INTERVAL_MS = 60 * 60 * 1000L  // 1 hour
        
        // Sybil resistance
        private const val SIGNATURE_MAX_AGE_MS = 5 * 60 * 1000L  // 5 minutes
    }
    
    /**
     * Our node ID (set on initialization).
     */
    private lateinit var myNodeId: ByteArray
    private lateinit var myWalletAddress: String
    
    /**
     * K-buckets: 256 buckets, each containing up to K nodes.
     * Bucket i contains nodes at XOR distance [2^i, 2^(i+1))
     */
    private val kBuckets = Array(ID_BITS) { KBucket(K) }
    
    /**
     * Value store for DHT storage.
     */
    private val valueStore = ConcurrentHashMap<String, StoredValue>()
    
    /**
     * Routing table statistics.
     */
    private val _stats = MutableStateFlow(DHTStats())
    val stats: StateFlow<DHTStats> = _stats.asStateFlow()
    
    /**
     * Initialize the DHT with our wallet address.
     */
    fun initialize(walletAddress: String) {
        myWalletAddress = walletAddress
        myNodeId = walletToNodeId(walletAddress)
        Timber.i("$TAG: Initialized with NodeID: ${myNodeId.toHexString().take(16)}...")
    }
    
    /**
     * Convert wallet address to NodeID.
     */
    fun walletToNodeId(walletAddress: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(walletAddress.lowercase().toByteArray(Charsets.UTF_8))
    }
    
    /**
     * Add a node to the routing table with Sybil resistance.
     * 
     * Verifies:
     * 1. Rate limit not exceeded
     * 2. Wallet signature is valid (if provided)
     * 3. Signature is recent (within 5 minutes)
     * 
     * @return true if node was added, false if rejected
     */
    fun addNode(node: DHTNode): Boolean {
        if (node.walletAddress.equals(myWalletAddress, ignoreCase = true)) {
            return false // Don't add ourselves
        }
        
        // 1. Check rate limit
        if (!rateLimiter.checkAndIncrement(RateLimiter.Category.PEER_ADDITION, node.walletAddress)) {
            Timber.w("$TAG: Rejected peer ${node.walletAddress.take(10)}... - rate limit exceeded")
            return false
        }
        
        // 2. Verify wallet signature if provided
        if (node.signature != null && node.signatureTimestamp != null) {
            if (!verifyPeerSignature(node)) {
                Timber.w("$TAG: Rejected peer ${node.walletAddress.take(10)}... - invalid signature")
                return false
            }
            
            // 3. Check signature freshness
            if (System.currentTimeMillis() - node.signatureTimestamp > SIGNATURE_MAX_AGE_MS) {
                Timber.w("$TAG: Rejected peer ${node.walletAddress.take(10)}... - stale signature")
                return false
            }
        }
        
        val nodeId = walletToNodeId(node.walletAddress)
        val bucketIndex = getBucketIndex(nodeId)
        
        if (bucketIndex < 0 || bucketIndex >= ID_BITS) {
            Timber.w("$TAG: Invalid bucket index for node: ${node.walletAddress}")
            return false
        }
        
        val bucket = kBuckets[bucketIndex]
        bucket.addNode(node.copy(nodeId = nodeId))
        
        updateStats()
        Timber.d("$TAG: Added node ${node.walletAddress.take(10)}... to bucket $bucketIndex")
        return true
    }
    
    /**
     * Add a node without signature verification (for trusted sources like blockchain).
     */
    fun addTrustedNode(node: DHTNode) {
        if (node.walletAddress.equals(myWalletAddress, ignoreCase = true)) {
            return // Don't add ourselves
        }
        
        val nodeId = walletToNodeId(node.walletAddress)
        val bucketIndex = getBucketIndex(nodeId)
        
        if (bucketIndex < 0 || bucketIndex >= ID_BITS) {
            Timber.w("$TAG: Invalid bucket index for node: ${node.walletAddress}")
            return
        }
        
        val bucket = kBuckets[bucketIndex]
        bucket.addNode(node.copy(nodeId = nodeId))
        
        updateStats()
        Timber.d("$TAG: Added trusted node ${node.walletAddress.take(10)}... to bucket $bucketIndex")
    }
    
    /**
     * Verify a peer's wallet signature.
     * 
     * The signature should be over: walletAddress + timestamp
     */
    private fun verifyPeerSignature(node: DHTNode): Boolean {
        val signature = node.signature ?: return false
        val timestamp = node.signatureTimestamp ?: return false
        
        return try {
            // Message format: "MUMBLECHAT_PEER:<wallet>:<timestamp>"
            val message = "MUMBLECHAT_PEER:${node.walletAddress.lowercase()}:$timestamp"
            val messageHash = Hash.sha3(message.toByteArray(Charsets.UTF_8))
            
            // Parse signature components (r, s, v)
            if (signature.size != 65) {
                Timber.w("$TAG: Invalid signature length: ${signature.size}")
                return false
            }
            
            val r = signature.copyOfRange(0, 32)
            val s = signature.copyOfRange(32, 64)
            val v = signature[64]
            
            val signatureData = Sign.SignatureData(v, r, s)
            
            // Recover public key from signature
            val recoveredKey = Sign.signedMessageToKey(messageHash, signatureData)
            val recoveredAddress = "0x" + Keys.getAddress(recoveredKey)
            
            // Verify recovered address matches claimed wallet
            val matches = recoveredAddress.equals(node.walletAddress, ignoreCase = true)
            
            if (!matches) {
                Timber.w("$TAG: Signature mismatch - expected ${node.walletAddress}, got $recoveredAddress")
            }
            
            matches
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to verify peer signature")
            false
        }
    }
    
    /**
     * Remove a node from the routing table.
     */
    fun removeNode(walletAddress: String) {
        val nodeId = walletToNodeId(walletAddress)
        val bucketIndex = getBucketIndex(nodeId)
        
        if (bucketIndex >= 0 && bucketIndex < ID_BITS) {
            kBuckets[bucketIndex].removeNode(walletAddress)
            updateStats()
        }
    }
    
    /**
     * Find the K closest nodes to a target wallet address.
     */
    fun findClosestNodes(targetWallet: String, count: Int = K): List<DHTNode> {
        val targetId = walletToNodeId(targetWallet)
        return findClosestNodesById(targetId, count)
    }
    
    /**
     * Find the K closest nodes to a target NodeID.
     */
    fun findClosestNodesById(targetId: ByteArray, count: Int = K): List<DHTNode> {
        val allNodes = mutableListOf<Pair<DHTNode, ByteArray>>()
        
        // Collect all nodes with their XOR distance
        for (bucket in kBuckets) {
            for (node in bucket.getNodes()) {
                val distance = xorDistance(node.nodeId, targetId)
                allNodes.add(Pair(node, distance))
            }
        }
        
        // Sort by XOR distance and return closest
        return allNodes
            .sortedWith { a, b -> compareByteArrays(a.second, b.second) }
            .take(count)
            .map { it.first }
    }
    
    /**
     * Get the bucket index for a node ID.
     * Returns the position of the highest differing bit (0-255).
     */
    private fun getBucketIndex(nodeId: ByteArray): Int {
        val distance = xorDistance(myNodeId, nodeId)
        
        // Find the highest set bit
        for (i in 0 until ID_BITS) {
            val byteIndex = i / 8
            val bitIndex = 7 - (i % 8)
            
            if (byteIndex >= distance.size) break
            
            if ((distance[byteIndex].toInt() shr bitIndex) and 1 == 1) {
                return ID_BITS - 1 - i
            }
        }
        
        return 0 // Same node ID (shouldn't happen)
    }
    
    /**
     * Calculate XOR distance between two node IDs.
     */
    private fun xorDistance(a: ByteArray, b: ByteArray): ByteArray {
        val result = ByteArray(a.size)
        for (i in a.indices) {
            result[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        }
        return result
    }
    
    /**
     * Compare two byte arrays as big-endian numbers.
     */
    private fun compareByteArrays(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return 0
    }
    
    /**
     * Store a value in the DHT.
     */
    fun storeValue(key: String, value: ByteArray, ttl: Long = 24 * 60 * 60 * 1000L) {
        valueStore[key] = StoredValue(
            value = value,
            storedAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + ttl
        )
        Timber.d("$TAG: Stored value for key: $key")
    }
    
    /**
     * Retrieve a value from the DHT.
     */
    fun getValue(key: String): ByteArray? {
        val stored = valueStore[key] ?: return null
        
        if (System.currentTimeMillis() > stored.expiresAt) {
            valueStore.remove(key)
            return null
        }
        
        return stored.value
    }
    
    /**
     * Get all known nodes.
     */
    fun getAllNodes(): List<DHTNode> {
        return kBuckets.flatMap { it.getNodes() }
    }
    
    /**
     * Get the number of known nodes.
     */
    fun getNodeCount(): Int {
        return kBuckets.sumOf { it.size() }
    }
    
    /**
     * Get nodes that need to be pinged (stale nodes).
     */
    fun getStaleNodes(): List<DHTNode> {
        val staleThreshold = System.currentTimeMillis() - NODE_TIMEOUT_MS
        return getAllNodes().filter { it.lastSeen < staleThreshold }
    }
    
    /**
     * Mark a node as seen (update last seen time).
     */
    fun markNodeSeen(walletAddress: String) {
        val nodeId = walletToNodeId(walletAddress)
        val bucketIndex = getBucketIndex(nodeId)
        
        if (bucketIndex >= 0 && bucketIndex < ID_BITS) {
            kBuckets[bucketIndex].touchNode(walletAddress)
        }
    }
    
    /**
     * Get bucket information for debugging.
     */
    fun getBucketInfo(): List<BucketInfo> {
        return kBuckets.mapIndexed { index, bucket ->
            BucketInfo(
                index = index,
                size = bucket.size(),
                nodes = bucket.getNodes().map { it.walletAddress.take(10) + "..." }
            )
        }.filter { it.size > 0 }
    }
    
    /**
     * Update routing table statistics.
     */
    private fun updateStats() {
        _stats.value = DHTStats(
            totalNodes = getNodeCount(),
            bucketsFilled = kBuckets.count { it.size() > 0 },
            storedValues = valueStore.size
        )
    }
    
    /**
     * Clean up expired values.
     */
    fun cleanupExpiredValues() {
        val now = System.currentTimeMillis()
        valueStore.entries.removeIf { it.value.expiresAt < now }
    }
    
    // Data classes
    
    data class DHTNode(
        val walletAddress: String,
        val nodeId: ByteArray = ByteArray(32),
        val publicIp: String,
        val publicPort: Int,
        val lastSeen: Long = System.currentTimeMillis(),
        val isRelay: Boolean = false,
        // Sybil resistance fields
        val signature: ByteArray? = null,           // Wallet signature for verification
        val signatureTimestamp: Long? = null        // When the signature was created
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DHTNode) return false
            return walletAddress.equals(other.walletAddress, ignoreCase = true)
        }
        
        override fun hashCode(): Int {
            return walletAddress.lowercase().hashCode()
        }
        
        /**
         * Check if this node has a valid signature.
         */
        fun hasValidSignature(): Boolean {
            return signature != null && signatureTimestamp != null && signature.size == 65
        }
    }
    
    data class StoredValue(
        val value: ByteArray,
        val storedAt: Long,
        val expiresAt: Long
    )
    
    data class DHTStats(
        val totalNodes: Int = 0,
        val bucketsFilled: Int = 0,
        val storedValues: Int = 0
    )
    
    data class BucketInfo(
        val index: Int,
        val size: Int,
        val nodes: List<String>
    )
    
    /**
     * K-Bucket implementation.
     * Stores up to K nodes, ordered by most recently seen.
     */
    private class KBucket(private val maxSize: Int) {
        private val nodes = mutableListOf<DHTNode>()
        
        @Synchronized
        fun addNode(node: DHTNode) {
            // Check if node already exists
            val existingIndex = nodes.indexOfFirst { 
                it.walletAddress.equals(node.walletAddress, ignoreCase = true) 
            }
            
            if (existingIndex >= 0) {
                // Move to end (most recently seen)
                nodes.removeAt(existingIndex)
                nodes.add(node)
            } else if (nodes.size < maxSize) {
                // Add to end
                nodes.add(node)
            } else {
                // Bucket full - check if oldest node is stale
                val oldest = nodes.firstOrNull()
                if (oldest != null && oldest.lastSeen < System.currentTimeMillis() - NODE_TIMEOUT_MS) {
                    nodes.removeAt(0)
                    nodes.add(node)
                }
                // Otherwise, don't add (Kademlia prefers older, stable nodes)
            }
        }
        
        @Synchronized
        fun removeNode(walletAddress: String) {
            nodes.removeIf { it.walletAddress.equals(walletAddress, ignoreCase = true) }
        }
        
        @Synchronized
        fun touchNode(walletAddress: String) {
            val index = nodes.indexOfFirst { it.walletAddress.equals(walletAddress, ignoreCase = true) }
            if (index >= 0) {
                val node = nodes.removeAt(index)
                nodes.add(node.copy(lastSeen = System.currentTimeMillis()))
            }
        }
        
        @Synchronized
        fun getNodes(): List<DHTNode> = nodes.toList()
        
        @Synchronized
        fun size(): Int = nodes.size
    }
    
    // Extension function
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
