package com.ramapay.app.chat.p2p

import com.ramapay.app.chat.blockchain.MumbleChatBlockchainService
import com.ramapay.app.chat.blockchain.RelayNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves peers from the blockchain registry.
 * 
 * Queries the MumbleChatRegistry smart contract to find registered peers.
 * This provides a decentralized peer discovery mechanism without any servers.
 * 
 * Contract: 0x4f8D4955F370881B05b68D2344345E749d8632e3
 * Network: Ramestta (Chain ID 1370)
 */
@Singleton
class BlockchainPeerResolver @Inject constructor(
    private val blockchainService: MumbleChatBlockchainService
) {
    companion object {
        private const val TAG = "BlockchainPeerResolver"
        private const val MAX_PEERS_TO_FETCH = 50
    }
    
    data class BlockchainPeerRegistration(
        val walletAddress: String,
        val publicIp: String,
        val publicPort: Int,
        val registrationTime: Long,
        val isRelay: Boolean
    )
    
    /**
     * Resolve all registered peers from the blockchain.
     */
    suspend fun resolvePeers(): List<BlockchainPeerRegistration> = withContext(Dispatchers.IO) {
        try {
            Timber.d("$TAG: Fetching peers from blockchain registry...")
            
            val registrations = mutableListOf<BlockchainPeerRegistration>()
            
            // Get relay nodes (they're more reliable for discovery)
            val relayNodes = blockchainService.getActiveRelayNodes()
            Timber.d("$TAG: Found ${relayNodes.size} relay nodes")
            
            relayNodes.forEach { relay ->
                val parsed = parseEndpoint(relay.endpoint)
                if (parsed.first.isNotEmpty() && parsed.second > 0) {
                    registrations.add(
                        BlockchainPeerRegistration(
                            walletAddress = relay.walletAddress,
                            publicIp = parsed.first,
                            publicPort = parsed.second,
                            registrationTime = System.currentTimeMillis(),
                            isRelay = true
                        )
                    )
                }
            }
            
            Timber.i("$TAG: Resolved ${registrations.size} unique peers from blockchain")
            registrations.take(MAX_PEERS_TO_FETCH)
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to resolve peers from blockchain")
            emptyList()
        }
    }
    
    /**
     * Resolve a specific peer by wallet address.
     */
    suspend fun resolvePeer(walletAddress: String): BlockchainPeerRegistration? = withContext(Dispatchers.IO) {
        try {
            // Check if this address has a relay registration
            val relayInfo = blockchainService.getRelayNodeDetails(walletAddress)
            
            if (relayInfo != null && relayInfo.isActive) {
                val (ip, port) = parseEndpoint(relayInfo.endpoint)
                if (ip.isNotEmpty() && port > 0) {
                    return@withContext BlockchainPeerRegistration(
                        walletAddress = walletAddress,
                        publicIp = ip,
                        publicPort = port,
                        registrationTime = System.currentTimeMillis(),
                        isRelay = true
                    )
                }
            }
            
            // Check identity registration
            val identity = blockchainService.getIdentity(walletAddress)
            if (identity != null && identity.isActive) {
                // Identity doesn't have endpoint, return without IP
                // The peer will need to be found via DHT or hole punching
                return@withContext null
            }
            
            null
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to resolve peer: $walletAddress")
            null
        }
    }
    
    /**
     * Parse an endpoint string into IP and port.
     * Supports formats: "ip:port", "hostname:port", "ip"
     */
    private fun parseEndpoint(endpoint: String): Pair<String, Int> {
        return try {
            // Skip DNS names like "relay.mumblechat.io" - these are not real IPs
            if (endpoint.contains(".io") || endpoint.contains(".com") || endpoint.contains(".net")) {
                Timber.w("$TAG: Skipping DNS endpoint: $endpoint")
                return Pair("", 0)
            }
            
            if (endpoint.contains(":")) {
                val parts = endpoint.split(":")
                val ip = parts[0]
                val port = parts[1].toIntOrNull() ?: 19372
                Pair(ip, port)
            } else {
                Pair(endpoint, 19372) // Default port
            }
        } catch (e: Exception) {
            Pair("", 0)
        }
    }
    
    /**
     * Check if an address is a registered relay.
     */
    suspend fun isRelay(walletAddress: String): Boolean {
        return try {
            val relayInfo = blockchainService.getRelayNodeDetails(walletAddress)
            relayInfo?.isActive == true
        } catch (e: Exception) {
            false
        }
    }
}
