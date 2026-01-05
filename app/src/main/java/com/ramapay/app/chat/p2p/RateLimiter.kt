package com.ramapay.app.chat.p2p

import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rate Limiter for P2P Operations
 * 
 * Implements multiple rate limiting strategies to prevent:
 * - Sybil attacks (mass fake peer registrations)
 * - DoS attacks (message flooding)
 * - Resource exhaustion
 * 
 * Rate limits:
 * - Peer additions: 10 per minute
 * - Messages per peer: 100 per minute
 * - DHT operations: 50 per minute
 * - Relay requests: 20 per minute
 * 
 * Based on technical review recommendations for Sybil resistance.
 */
@Singleton
class RateLimiter @Inject constructor() {
    companion object {
        private const val TAG = "RateLimiter"
        
        // Rate limits per minute
        const val MAX_PEER_ADDITIONS_PER_MINUTE = 10
        const val MAX_MESSAGES_PER_PEER_PER_MINUTE = 100
        const val MAX_DHT_OPERATIONS_PER_MINUTE = 50
        const val MAX_RELAY_REQUESTS_PER_MINUTE = 20
        const val MAX_CONNECTION_ATTEMPTS_PER_MINUTE = 15
        
        // Global limits
        const val MAX_TOTAL_OPERATIONS_PER_MINUTE = 500
        
        // Window duration
        const val WINDOW_MS = 60_000L  // 1 minute
        
        // Cleanup interval
        const val CLEANUP_INTERVAL_MS = 5 * 60_000L  // 5 minutes
    }
    
    /**
     * Rate limit categories.
     */
    enum class Category {
        PEER_ADDITION,
        MESSAGE_SEND,
        MESSAGE_RECEIVE,
        DHT_OPERATION,
        RELAY_REQUEST,
        CONNECTION_ATTEMPT
    }
    
    /**
     * Sliding window counter for a specific key.
     */
    data class WindowCounter(
        val timestamps: MutableList<Long> = mutableListOf(),
        var lastCleanup: Long = System.currentTimeMillis()
    ) {
        @Synchronized
        fun increment(): Int {
            val now = System.currentTimeMillis()
            
            // Cleanup old entries
            if (now - lastCleanup > CLEANUP_INTERVAL_MS) {
                timestamps.removeAll { now - it > WINDOW_MS }
                lastCleanup = now
            }
            
            timestamps.add(now)
            return getCount()
        }
        
        @Synchronized
        fun getCount(): Int {
            val now = System.currentTimeMillis()
            return timestamps.count { now - it <= WINDOW_MS }
        }
    }
    
    // Counters per category + key
    private val counters = ConcurrentHashMap<String, WindowCounter>()
    
    // Global operation counter
    private val globalCounter = AtomicInteger(0)
    private var globalWindowStart = System.currentTimeMillis()
    
    // Blocked addresses (temporary blacklist)
    private val blockedAddresses = ConcurrentHashMap<String, Long>()
    private val BLOCK_DURATION_MS = 5 * 60_000L  // 5 minute block
    
    /**
     * Check if operation is allowed and increment counter.
     * 
     * @param category The rate limit category
     * @param key Unique identifier (wallet address, peer ID, etc.)
     * @return true if operation allowed, false if rate limited
     */
    fun checkAndIncrement(category: Category, key: String): Boolean {
        val compositeKey = "${category.name}:$key"
        
        // Check if address is blocked
        if (isBlocked(key)) {
            Timber.w("$TAG: Address $key is temporarily blocked")
            return false
        }
        
        // Check global limit
        if (!checkGlobalLimit()) {
            Timber.w("$TAG: Global rate limit exceeded")
            return false
        }
        
        // Get or create counter
        val counter = counters.getOrPut(compositeKey) { WindowCounter() }
        val count = counter.increment()
        
        // Check against category limit
        val limit = getLimitForCategory(category)
        val allowed = count <= limit
        
        if (!allowed) {
            Timber.w("$TAG: Rate limit exceeded for $category: $key ($count/$limit)")
            
            // Auto-block if severely over limit
            if (count > limit * 3) {
                blockAddress(key)
            }
        }
        
        return allowed
    }
    
    /**
     * Check rate limit without incrementing counter.
     */
    fun wouldExceedLimit(category: Category, key: String): Boolean {
        val compositeKey = "${category.name}:$key"
        val counter = counters[compositeKey] ?: return false
        return counter.getCount() >= getLimitForCategory(category)
    }
    
    /**
     * Get current count for a category/key.
     */
    fun getCurrentCount(category: Category, key: String): Int {
        val compositeKey = "${category.name}:$key"
        return counters[compositeKey]?.getCount() ?: 0
    }
    
    /**
     * Temporarily block an address.
     */
    fun blockAddress(address: String) {
        blockedAddresses[address.lowercase()] = System.currentTimeMillis() + BLOCK_DURATION_MS
        Timber.w("$TAG: Blocked address: $address for ${BLOCK_DURATION_MS / 1000}s")
    }
    
    /**
     * Check if an address is blocked.
     */
    fun isBlocked(address: String): Boolean {
        val blockExpiry = blockedAddresses[address.lowercase()] ?: return false
        if (System.currentTimeMillis() > blockExpiry) {
            blockedAddresses.remove(address.lowercase())
            return false
        }
        return true
    }
    
    /**
     * Get limit for a category.
     */
    private fun getLimitForCategory(category: Category): Int {
        return when (category) {
            Category.PEER_ADDITION -> MAX_PEER_ADDITIONS_PER_MINUTE
            Category.MESSAGE_SEND -> MAX_MESSAGES_PER_PEER_PER_MINUTE
            Category.MESSAGE_RECEIVE -> MAX_MESSAGES_PER_PEER_PER_MINUTE
            Category.DHT_OPERATION -> MAX_DHT_OPERATIONS_PER_MINUTE
            Category.RELAY_REQUEST -> MAX_RELAY_REQUESTS_PER_MINUTE
            Category.CONNECTION_ATTEMPT -> MAX_CONNECTION_ATTEMPTS_PER_MINUTE
        }
    }
    
    /**
     * Check global rate limit.
     */
    private fun checkGlobalLimit(): Boolean {
        val now = System.currentTimeMillis()
        
        // Reset window if expired
        if (now - globalWindowStart > WINDOW_MS) {
            globalCounter.set(0)
            globalWindowStart = now
        }
        
        return globalCounter.incrementAndGet() <= MAX_TOTAL_OPERATIONS_PER_MINUTE
    }
    
    /**
     * Cleanup old entries to prevent memory leaks.
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        
        // Remove old counters
        val keysToRemove = counters.entries
            .filter { it.value.getCount() == 0 }
            .map { it.key }
        
        keysToRemove.forEach { counters.remove(it) }
        
        // Remove expired blocks
        val expiredBlocks = blockedAddresses.entries
            .filter { now > it.value }
            .map { it.key }
        
        expiredBlocks.forEach { blockedAddresses.remove(it) }
        
        if (keysToRemove.isNotEmpty() || expiredBlocks.isNotEmpty()) {
            Timber.d("$TAG: Cleanup - removed ${keysToRemove.size} counters, ${expiredBlocks.size} blocks")
        }
    }
    
    /**
     * Get statistics for monitoring.
     */
    fun getStats(): RateLimiterStats {
        return RateLimiterStats(
            activeCounters = counters.size,
            blockedAddresses = blockedAddresses.size,
            globalOperationsInWindow = globalCounter.get()
        )
    }
}

data class RateLimiterStats(
    val activeCounters: Int,
    val blockedAddresses: Int,
    val globalOperationsInWindow: Int
)
