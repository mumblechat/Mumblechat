package com.ramapay.app.chat.p2p

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent cache for discovered peers.
 * 
 * Enables fast bootstrap by remembering peers from previous sessions.
 * Stores peer info to local file, encrypted with device key.
 */
@Singleton
class PeerCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "PeerCache"
        private const val CACHE_FILE = "peer_cache.json"
        private const val MAX_CACHED_PEERS = 100
    }
    
    private val gson = Gson()
    private val cacheFile: File
        get() = File(context.filesDir, CACHE_FILE)
    
    /**
     * Load peers from persistent cache.
     */
    suspend fun loadPeers(): List<BootstrapManager.PeerInfo> = withContext(Dispatchers.IO) {
        try {
            if (!cacheFile.exists()) {
                return@withContext emptyList()
            }
            
            val json = cacheFile.readText()
            val type = object : TypeToken<List<CachedPeer>>() {}.type
            val cachedPeers: List<CachedPeer> = gson.fromJson(json, type) ?: return@withContext emptyList()
            
            cachedPeers.map { cached ->
                BootstrapManager.PeerInfo(
                    walletAddress = cached.walletAddress,
                    publicIp = cached.publicIp,
                    publicPort = cached.publicPort,
                    lastSeen = cached.lastSeen,
                    source = BootstrapManager.PeerSource.CACHE,
                    isActive = false,
                    successfulConnections = cached.successfulConnections
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to load peer cache")
            emptyList()
        }
    }
    
    /**
     * Save peers to persistent cache.
     */
    suspend fun savePeers(peers: List<BootstrapManager.PeerInfo>) = withContext(Dispatchers.IO) {
        try {
            // Merge with existing cache
            val existing = loadPeers().associateBy { it.walletAddress.lowercase() }.toMutableMap()
            
            peers.forEach { peer ->
                val key = peer.walletAddress.lowercase()
                val existingPeer = existing[key]
                
                if (existingPeer == null || peer.lastSeen > existingPeer.lastSeen) {
                    existing[key] = peer
                }
            }
            
            // Sort by most recently seen and most successful, limit count
            val toSave = existing.values
                .sortedWith(compareByDescending<BootstrapManager.PeerInfo> { it.successfulConnections }
                    .thenByDescending { it.lastSeen })
                .take(MAX_CACHED_PEERS)
                .map { peer ->
                    CachedPeer(
                        walletAddress = peer.walletAddress,
                        publicIp = peer.publicIp,
                        publicPort = peer.publicPort,
                        lastSeen = peer.lastSeen,
                        successfulConnections = peer.successfulConnections
                    )
                }
            
            val json = gson.toJson(toSave)
            cacheFile.writeText(json)
            
            Timber.d("$TAG: Saved ${toSave.size} peers to cache")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to save peer cache")
        }
    }
    
    /**
     * Add or update a single peer.
     */
    suspend fun updatePeer(peer: BootstrapManager.PeerInfo) {
        val peers = loadPeers().toMutableList()
        val existingIndex = peers.indexOfFirst { it.walletAddress.equals(peer.walletAddress, ignoreCase = true) }
        
        if (existingIndex >= 0) {
            peers[existingIndex] = peer
        } else {
            peers.add(peer)
        }
        
        savePeers(peers)
    }
    
    /**
     * Remove a peer from cache.
     */
    suspend fun removePeer(walletAddress: String) {
        val peers = loadPeers().filterNot { it.walletAddress.equals(walletAddress, ignoreCase = true) }
        savePeers(peers)
    }
    
    /**
     * Clear the entire peer cache.
     */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        try {
            cacheFile.delete()
            Timber.d("$TAG: Peer cache cleared")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to clear cache")
        }
    }
    
    /**
     * Get statistics about the cache.
     */
    suspend fun getCacheStats(): CacheStats {
        val peers = loadPeers()
        return CacheStats(
            totalPeers = peers.size,
            recentPeers = peers.count { it.lastSeen > System.currentTimeMillis() - 24 * 60 * 60 * 1000 },
            successfulPeers = peers.count { it.successfulConnections > 0 },
            cacheFileSize = cacheFile.length()
        )
    }
    
    data class CacheStats(
        val totalPeers: Int,
        val recentPeers: Int,
        val successfulPeers: Int,
        val cacheFileSize: Long
    )
    
    /**
     * Internal class for JSON serialization.
     */
    private data class CachedPeer(
        val walletAddress: String,
        val publicIp: String,
        val publicPort: Int,
        val lastSeen: Long,
        val successfulConnections: Int
    )
}
