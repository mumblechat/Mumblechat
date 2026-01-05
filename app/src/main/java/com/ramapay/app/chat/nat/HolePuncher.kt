package com.ramapay.app.chat.nat

import kotlinx.coroutines.*
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UDP Hole Puncher for NAT Traversal
 * 
 * Enables direct P2P connections between phones behind NAT.
 * Works for ~90% of NAT types (Full Cone, Restricted Cone, Port Restricted).
 * 
 * How it works:
 * 1. Both peers discover their public IP:PORT via STUN
 * 2. They exchange this info (via DHT, relay, or QR code)
 * 3. Both simultaneously send UDP packets to each other
 * 4. This "punches holes" in their NATs
 * 5. Direct communication is now possible!
 * 
 * For Symmetric NAT (~10% of cases), we fall back to relay.
 */
@Singleton
class HolePuncher @Inject constructor(
    private val stunClient: StunClient
) {
    companion object {
        private const val TAG = "HolePuncher"
        
        // Hole punch packet magic bytes
        private val PUNCH_MAGIC = byteArrayOf(0x4D, 0x43, 0x48, 0x50) // "MCHP" = MumbleChat Hole Punch
        
        // Timing
        private const val PUNCH_INTERVAL_MS = 100L
        private const val PUNCH_DURATION_MS = 5000L
        private const val PUNCH_TIMEOUT_MS = 10000L
    }
    
    data class PeerEndpoint(
        val publicIp: String,
        val publicPort: Int,
        val walletAddress: String
    )
    
    data class HolePunchResult(
        val success: Boolean,
        val socket: DatagramSocket?,
        val peerAddress: InetSocketAddress?,
        val localPort: Int,
        val method: String
    )
    
    /**
     * Attempt to punch a hole to a peer.
     * 
     * @param peerEndpoint The peer's public endpoint info
     * @param myWalletAddress Our wallet address (for identification)
     * @return HolePunchResult with socket if successful
     */
    suspend fun punchHole(
        peerEndpoint: PeerEndpoint,
        myWalletAddress: String
    ): HolePunchResult = withContext(Dispatchers.IO) {
        Timber.d("$TAG: Starting hole punch to ${peerEndpoint.publicIp}:${peerEndpoint.publicPort}")
        
        val socket = DatagramSocket()
        socket.soTimeout = 1000 // 1 second for individual receives
        
        try {
            // First, discover our own public endpoint
            val myEndpoint = stunClient.discoverPublicAddress(socket)
            if (myEndpoint == null) {
                Timber.e("$TAG: Failed to discover own public address")
                socket.close()
                return@withContext HolePunchResult(false, null, null, 0, "stun_failed")
            }
            
            Timber.d("$TAG: Our public endpoint: ${myEndpoint.publicIp}:${myEndpoint.publicPort}")
            
            val peerAddress = InetSocketAddress(
                InetAddress.getByName(peerEndpoint.publicIp),
                peerEndpoint.publicPort
            )
            
            // Start hole punching
            val result = performHolePunch(socket, peerAddress, myWalletAddress, peerEndpoint.walletAddress)
            
            if (result) {
                Timber.i("$TAG: Hole punch successful!")
                HolePunchResult(
                    success = true,
                    socket = socket,
                    peerAddress = peerAddress,
                    localPort = socket.localPort,
                    method = "hole_punch"
                )
            } else {
                Timber.w("$TAG: Hole punch failed")
                socket.close()
                HolePunchResult(false, null, null, 0, "punch_failed")
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Hole punch error")
            socket.close()
            HolePunchResult(false, null, null, 0, "error: ${e.message}")
        }
    }
    
    /**
     * Perform the actual hole punching.
     * Sends and receives packets simultaneously.
     */
    private suspend fun performHolePunch(
        socket: DatagramSocket,
        peerAddress: InetSocketAddress,
        myWallet: String,
        peerWallet: String
    ): Boolean = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var punchReceived = false
        var responseReceived = false
        
        // Create punch packet
        val punchPacket = createPunchPacket(myWallet)
        
        // Start sending in background
        val sendJob = launch {
            var packetsSent = 0
            while (isActive && System.currentTimeMillis() - startTime < PUNCH_DURATION_MS) {
                try {
                    val packet = DatagramPacket(
                        punchPacket,
                        punchPacket.size,
                        peerAddress.address,
                        peerAddress.port
                    )
                    socket.send(packet)
                    packetsSent++
                    
                    if (packetsSent % 10 == 0) {
                        Timber.d("$TAG: Sent $packetsSent punch packets")
                    }
                    
                    delay(PUNCH_INTERVAL_MS)
                } catch (e: Exception) {
                    Timber.w("$TAG: Send error: ${e.message}")
                }
            }
            Timber.d("$TAG: Finished sending $packetsSent packets")
        }
        
        // Receive loop
        val receiveBuffer = ByteArray(256)
        val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
        
        while (System.currentTimeMillis() - startTime < PUNCH_TIMEOUT_MS) {
            try {
                socket.receive(receivePacket)
                
                // Verify it's from our peer
                if (receivePacket.address == peerAddress.address) {
                    // Check if it's a valid punch packet
                    if (isValidPunchPacket(receiveBuffer, receivePacket.length, peerWallet)) {
                        punchReceived = true
                        Timber.d("$TAG: Received punch packet from peer!")
                        
                        // Send a few more packets to confirm
                        for (i in 0..5) {
                            val confirmPacket = DatagramPacket(
                                punchPacket,
                                punchPacket.size,
                                peerAddress.address,
                                peerAddress.port
                            )
                            socket.send(confirmPacket)
                            delay(50)
                        }
                        
                        responseReceived = true
                        break
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                // Normal timeout, continue
            } catch (e: Exception) {
                Timber.w("$TAG: Receive error: ${e.message}")
            }
        }
        
        sendJob.cancel()
        
        punchReceived && responseReceived
    }
    
    /**
     * Create a hole punch packet.
     * Format: MAGIC (4) + WALLET_HASH (8) + TIMESTAMP (8) + NONCE (4)
     */
    private fun createPunchPacket(walletAddress: String): ByteArray {
        val buffer = ByteBuffer.allocate(24)
        
        // Magic bytes
        buffer.put(PUNCH_MAGIC)
        
        // Wallet hash (first 8 bytes of SHA256)
        val walletHash = walletAddress.lowercase().toByteArray()
            .let { java.security.MessageDigest.getInstance("SHA-256").digest(it) }
        buffer.put(walletHash, 0, 8)
        
        // Timestamp
        buffer.putLong(System.currentTimeMillis())
        
        // Random nonce
        buffer.putInt(java.util.Random().nextInt())
        
        return buffer.array()
    }
    
    /**
     * Validate a received punch packet.
     */
    private fun isValidPunchPacket(data: ByteArray, length: Int, expectedWallet: String): Boolean {
        if (length < 24) return false
        
        // Check magic
        if (!data.sliceArray(0..3).contentEquals(PUNCH_MAGIC)) {
            return false
        }
        
        // Check wallet hash
        val expectedHash = expectedWallet.lowercase().toByteArray()
            .let { java.security.MessageDigest.getInstance("SHA-256").digest(it) }
        val receivedHash = data.sliceArray(4..11)
        
        if (!receivedHash.contentEquals(expectedHash.sliceArray(0..7))) {
            return false
        }
        
        // Check timestamp is reasonable (within 5 minutes)
        val buffer = ByteBuffer.wrap(data, 12, 8)
        val timestamp = buffer.long
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(now - timestamp) > 5 * 60 * 1000) {
            Timber.w("$TAG: Punch packet timestamp too old")
            return false
        }
        
        return true
    }
    
    /**
     * Exchange endpoint info with a peer via a relay.
     * Used when we don't have direct connectivity yet.
     */
    suspend fun exchangeEndpointViaRelay(
        relaySocket: DatagramSocket,
        relayAddress: InetSocketAddress,
        myWallet: String,
        peerWallet: String
    ): PeerEndpoint? = withContext(Dispatchers.IO) {
        // Get our public endpoint
        val myEndpoint = stunClient.discoverPublicAddress() ?: return@withContext null
        
        // Send our endpoint to relay with target peer info
        val exchangePacket = createEndpointExchangePacket(
            myWallet,
            peerWallet,
            myEndpoint.publicIp,
            myEndpoint.publicPort
        )
        
        val sendPacket = DatagramPacket(
            exchangePacket,
            exchangePacket.size,
            relayAddress.address,
            relayAddress.port
        )
        relaySocket.send(sendPacket)
        
        // Wait for peer's endpoint from relay
        val receiveBuffer = ByteArray(256)
        val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
        
        repeat(30) { // Try for 30 seconds
            try {
                relaySocket.soTimeout = 1000
                relaySocket.receive(receivePacket)
                
                val peerEndpoint = parseEndpointExchangePacket(receiveBuffer, receivePacket.length)
                if (peerEndpoint != null && peerEndpoint.walletAddress.equals(peerWallet, ignoreCase = true)) {
                    return@withContext peerEndpoint
                }
            } catch (e: java.net.SocketTimeoutException) {
                // Continue waiting
            }
        }
        
        null
    }
    
    /**
     * Create endpoint exchange packet for relay.
     */
    private fun createEndpointExchangePacket(
        myWallet: String,
        targetWallet: String,
        publicIp: String,
        publicPort: Int
    ): ByteArray {
        // Simple format: TYPE(1) + MY_WALLET(42) + TARGET_WALLET(42) + IP(4) + PORT(2)
        val buffer = ByteBuffer.allocate(128)
        
        buffer.put(0x10) // ENDPOINT_EXCHANGE type
        
        val myWalletBytes = myWallet.toByteArray(Charsets.UTF_8)
        buffer.put(myWalletBytes.size.toByte())
        buffer.put(myWalletBytes)
        
        val targetWalletBytes = targetWallet.toByteArray(Charsets.UTF_8)
        buffer.put(targetWalletBytes.size.toByte())
        buffer.put(targetWalletBytes)
        
        // IP as 4 bytes
        val ipParts = publicIp.split(".")
        ipParts.forEach { buffer.put(it.toInt().toByte()) }
        
        // Port
        buffer.putShort(publicPort.toShort())
        
        val result = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(result)
        return result
    }
    
    /**
     * Parse endpoint exchange packet from relay.
     */
    private fun parseEndpointExchangePacket(data: ByteArray, length: Int): PeerEndpoint? {
        if (length < 10) return null
        
        try {
            val buffer = ByteBuffer.wrap(data, 0, length)
            
            val type = buffer.get()
            if (type != 0x11.toByte()) return null // ENDPOINT_RESPONSE type
            
            val walletLen = buffer.get().toInt() and 0xFF
            val walletBytes = ByteArray(walletLen)
            buffer.get(walletBytes)
            val wallet = String(walletBytes, Charsets.UTF_8)
            
            val ip = "${buffer.get().toInt() and 0xFF}." +
                     "${buffer.get().toInt() and 0xFF}." +
                     "${buffer.get().toInt() and 0xFF}." +
                     "${buffer.get().toInt() and 0xFF}"
            
            val port = buffer.short.toInt() and 0xFFFF
            
            return PeerEndpoint(ip, port, wallet)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to parse endpoint packet")
            return null
        }
    }
}
