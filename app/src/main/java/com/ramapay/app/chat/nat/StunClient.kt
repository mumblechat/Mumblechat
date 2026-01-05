package com.ramapay.app.chat.nat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * STUN Client for NAT Discovery
 * 
 * Uses FREE public STUN servers (Google, Cloudflare) to discover
 * the phone's public IP address and port mapping.
 * 
 * STUN (Session Traversal Utilities for NAT) tells us:
 * - Our public IP address
 * - Our mapped port
 * - NAT type (for hole punching strategy)
 * 
 * No cost - uses free public infrastructure!
 */
@Singleton
class StunClient @Inject constructor() {
    
    companion object {
        private const val TAG = "StunClient"
        
        // Free public STUN servers
        val STUN_SERVERS = listOf(
            StunServer("stun.l.google.com", 19302),
            StunServer("stun1.l.google.com", 19302),
            StunServer("stun2.l.google.com", 19302),
            StunServer("stun.cloudflare.com", 3478),
            StunServer("stun.stunprotocol.org", 3478)
        )
        
        // STUN message types
        private const val BINDING_REQUEST: Short = 0x0001
        private const val BINDING_RESPONSE: Short = 0x0101
        private const val BINDING_ERROR: Short = 0x0111
        
        // STUN attributes
        private const val ATTR_MAPPED_ADDRESS: Short = 0x0001
        private const val ATTR_XOR_MAPPED_ADDRESS: Short = 0x0020
        
        // Magic cookie (RFC 5389)
        private const val MAGIC_COOKIE: Int = 0x2112A442
        
        private const val STUN_TIMEOUT_MS = 3000L
        private const val MAX_RETRIES = 2
    }
    
    data class StunServer(val host: String, val port: Int)
    
    data class StunResult(
        val publicIp: String,
        val publicPort: Int,
        val localPort: Int,
        val serverUsed: String
    )
    
    /**
     * Discover public IP and port using STUN.
     * Tries multiple servers for reliability.
     * 
     * @param localSocket Optional existing socket to use
     * @return StunResult with public address, or null if all servers fail
     */
    suspend fun discoverPublicAddress(localSocket: DatagramSocket? = null): StunResult? = withContext(Dispatchers.IO) {
        Timber.d("$TAG: Starting STUN discovery")
        
        val socket = localSocket ?: DatagramSocket()
        val shouldCloseSocket = localSocket == null
        
        try {
            for (server in STUN_SERVERS) {
                for (retry in 0 until MAX_RETRIES) {
                    try {
                        val result = queryStunServer(socket, server)
                        if (result != null) {
                            Timber.i("$TAG: STUN discovery success - Public: ${result.publicIp}:${result.publicPort}")
                            return@withContext result
                        }
                    } catch (e: Exception) {
                        Timber.w("$TAG: STUN query to ${server.host} failed (attempt ${retry + 1}): ${e.message}")
                    }
                }
            }
            
            Timber.e("$TAG: All STUN servers failed")
            null
        } finally {
            if (shouldCloseSocket) {
                socket.close()
            }
        }
    }
    
    /**
     * Query a single STUN server.
     */
    private suspend fun queryStunServer(socket: DatagramSocket, server: StunServer): StunResult? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(STUN_TIMEOUT_MS) {
            try {
                val serverAddress = InetAddress.getByName(server.host)
                
                // Build STUN Binding Request
                val transactionId = ByteArray(12)
                SecureRandom().nextBytes(transactionId)
                val request = buildBindingRequest(transactionId)
                
                // Send request
                val sendPacket = DatagramPacket(request, request.size, serverAddress, server.port)
                socket.send(sendPacket)
                
                // Receive response
                val responseBuffer = ByteArray(512)
                val receivePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.soTimeout = STUN_TIMEOUT_MS.toInt()
                socket.receive(receivePacket)
                
                // Parse response
                parseBindingResponse(responseBuffer, receivePacket.length, transactionId)?.let { (ip, port) ->
                    StunResult(
                        publicIp = ip,
                        publicPort = port,
                        localPort = socket.localPort,
                        serverUsed = server.host
                    )
                }
            } catch (e: Exception) {
                Timber.w("$TAG: STUN query error: ${e.message}")
                null
            }
        }
    }
    
    /**
     * Build a STUN Binding Request message.
     */
    private fun buildBindingRequest(transactionId: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(20) // Header only, no attributes
        
        // Message Type: Binding Request (0x0001)
        buffer.putShort(BINDING_REQUEST)
        
        // Message Length: 0 (no attributes)
        buffer.putShort(0)
        
        // Magic Cookie
        buffer.putInt(MAGIC_COOKIE)
        
        // Transaction ID (12 bytes)
        buffer.put(transactionId)
        
        return buffer.array()
    }
    
    /**
     * Parse a STUN Binding Response.
     */
    private fun parseBindingResponse(
        data: ByteArray,
        length: Int,
        expectedTransactionId: ByteArray
    ): Pair<String, Int>? {
        if (length < 20) {
            Timber.w("$TAG: Response too short")
            return null
        }
        
        val buffer = ByteBuffer.wrap(data, 0, length)
        
        // Check message type
        val messageType = buffer.short
        if (messageType != BINDING_RESPONSE) {
            Timber.w("$TAG: Unexpected message type: $messageType")
            return null
        }
        
        // Message length
        val messageLength = buffer.short.toInt() and 0xFFFF
        
        // Magic cookie
        val cookie = buffer.int
        if (cookie != MAGIC_COOKIE) {
            Timber.w("$TAG: Invalid magic cookie")
            return null
        }
        
        // Transaction ID
        val transactionId = ByteArray(12)
        buffer.get(transactionId)
        if (!transactionId.contentEquals(expectedTransactionId)) {
            Timber.w("$TAG: Transaction ID mismatch")
            return null
        }
        
        // Parse attributes
        var offset = 20
        while (offset + 4 <= 20 + messageLength) {
            val attrType = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            val attrLength = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            offset += 4
            
            when (attrType.toShort()) {
                ATTR_XOR_MAPPED_ADDRESS -> {
                    return parseXorMappedAddress(data, offset, attrLength)
                }
                ATTR_MAPPED_ADDRESS -> {
                    return parseMappedAddress(data, offset, attrLength)
                }
            }
            
            // Move to next attribute (4-byte aligned)
            offset += (attrLength + 3) and 0xFFFC.inv() + 4
        }
        
        Timber.w("$TAG: No mapped address in response")
        return null
    }
    
    /**
     * Parse XOR-MAPPED-ADDRESS attribute (RFC 5389).
     */
    private fun parseXorMappedAddress(data: ByteArray, offset: Int, length: Int): Pair<String, Int>? {
        if (length < 8) return null
        
        val family = data[offset + 1].toInt() and 0xFF
        if (family != 0x01) {
            // Only IPv4 supported for now
            Timber.w("$TAG: IPv6 not supported yet")
            return null
        }
        
        // XOR port with magic cookie upper 16 bits
        val xorPort = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
        val port = xorPort xor (MAGIC_COOKIE shr 16)
        
        // XOR IP with magic cookie
        val xorIp = ByteArray(4)
        System.arraycopy(data, offset + 4, xorIp, 0, 4)
        val magicBytes = ByteBuffer.allocate(4).putInt(MAGIC_COOKIE).array()
        val ip = "${(xorIp[0].toInt() and 0xFF) xor (magicBytes[0].toInt() and 0xFF)}." +
                 "${(xorIp[1].toInt() and 0xFF) xor (magicBytes[1].toInt() and 0xFF)}." +
                 "${(xorIp[2].toInt() and 0xFF) xor (magicBytes[2].toInt() and 0xFF)}." +
                 "${(xorIp[3].toInt() and 0xFF) xor (magicBytes[3].toInt() and 0xFF)}"
        
        return Pair(ip, port)
    }
    
    /**
     * Parse MAPPED-ADDRESS attribute (legacy, RFC 3489).
     */
    private fun parseMappedAddress(data: ByteArray, offset: Int, length: Int): Pair<String, Int>? {
        if (length < 8) return null
        
        val family = data[offset + 1].toInt() and 0xFF
        if (family != 0x01) return null // IPv4 only
        
        val port = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
        val ip = "${data[offset + 4].toInt() and 0xFF}." +
                 "${data[offset + 5].toInt() and 0xFF}." +
                 "${data[offset + 6].toInt() and 0xFF}." +
                 "${data[offset + 7].toInt() and 0xFF}"
        
        return Pair(ip, port)
    }
    
    /**
     * Get current NAT type (simplified detection).
     */
    suspend fun detectNatType(): NatType = withContext(Dispatchers.IO) {
        val socket1 = DatagramSocket()
        val socket2 = DatagramSocket()
        
        try {
            val result1 = discoverPublicAddress(socket1)
            val result2 = discoverPublicAddress(socket2)
            
            if (result1 == null || result2 == null) {
                return@withContext NatType.UNKNOWN
            }
            
            // Compare local and public ports
            val samePort1 = socket1.localPort == result1.publicPort
            val samePort2 = socket2.localPort == result2.publicPort
            
            when {
                samePort1 && samePort2 -> {
                    // Ports preserved - likely Full Cone or no NAT
                    Timber.d("$TAG: NAT Type: FULL_CONE (or no NAT)")
                    NatType.FULL_CONE
                }
                result1.publicIp == result2.publicIp -> {
                    // Same IP, different ports - likely Port Restricted
                    Timber.d("$TAG: NAT Type: PORT_RESTRICTED")
                    NatType.PORT_RESTRICTED
                }
                else -> {
                    // Symmetric NAT - hardest to punch through
                    Timber.d("$TAG: NAT Type: SYMMETRIC")
                    NatType.SYMMETRIC
                }
            }
        } finally {
            socket1.close()
            socket2.close()
        }
    }
    
    enum class NatType {
        FULL_CONE,          // Easy - direct connection works
        RESTRICTED_CONE,    // Medium - hole punching works
        PORT_RESTRICTED,    // Medium - hole punching works
        SYMMETRIC,          // Hard - need relay
        UNKNOWN
    }
}
