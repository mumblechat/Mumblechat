package com.ramapay.app.chat.protocol

import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Binary Message Codec for MumbleChat Protocol
 * 
 * Wire Format:
 * ┌─────────────────────────────────────────────────────────────┐
 * │                     HEADER (20 bytes)                       │
 * ├─────────────────────────────────────────────────────────────┤
 * │ Magic (4)  │ Version (1) │ Type (1) │ Flags (2) │ Length (4)│
 * │ Sequence (4) │ Checksum (4)                                 │
 * ├─────────────────────────────────────────────────────────────┤
 * │                     ROUTING (68 bytes)                      │
 * ├─────────────────────────────────────────────────────────────┤
 * │ Source NodeID (32) │ Dest NodeID (32) │ TTL (1) │ Hops (1)  │
 * │ Reserved (2)                                                │
 * ├─────────────────────────────────────────────────────────────┤
 * │                     PAYLOAD (variable)                      │
 * ├─────────────────────────────────────────────────────────────┤
 * │                     SIGNATURE (64 bytes)                    │
 * └─────────────────────────────────────────────────────────────┘
 */
@Singleton
class MessageCodec @Inject constructor() {
    companion object {
        private const val TAG = "MessageCodec"
        
        // Magic bytes: "MCHT" = MumbleChat
        val MAGIC = byteArrayOf(0x4D, 0x43, 0x48, 0x54)
        
        // Current protocol version
        const val VERSION: Byte = 0x01
        
        // Header size
        const val HEADER_SIZE = 20
        const val ROUTING_SIZE = 68
        const val SIGNATURE_SIZE = 64
        
        // Maximum payload size (64 KB)
        const val MAX_PAYLOAD_SIZE = 65536
        
        // Default TTL
        const val DEFAULT_TTL: Byte = 16
        
        // Convenience flag constants for ChatService
        const val FLAG_ENCRYPTED: Short = 0x0001
    }
    
    /**
     * Message types
     */
    enum class MessageType(val value: Byte) {
        // Control messages (0x00-0x1F)
        PING(0x00),
        PONG(0x01),
        HANDSHAKE(0x02),
        HANDSHAKE_ACK(0x03),
        DISCONNECT(0x04),
        ACK(0x05),  // Generic acknowledgment
        
        // DHT messages (0x20-0x3F)
        FIND_NODE(0x20),
        FIND_NODE_RESPONSE(0x21),
        FIND_VALUE(0x22),
        FIND_VALUE_RESPONSE(0x23),
        STORE(0x24),
        STORE_ACK(0x25),
        
        // Chat messages (0x40-0x5F)
        CHAT_MESSAGE(0x40),
        CHAT_ACK(0x41),
        CHAT_READ(0x42),
        TYPING_INDICATOR(0x43),
        DATA(0x44),  // Generic data message
        
        // Relay messages (0x60-0x7F)
        RELAY_REQUEST(0x60),
        RELAY_RESPONSE(0x61),
        RELAY_DATA(0x62),
        RELAY_ACK(0x63),
        
        // Key exchange (0x80-0x9F) - Use negative bytes for values >= 0x80
        KEY_EXCHANGE_INIT(0x80.toByte()),
        KEY_EXCHANGE_RESPONSE(0x81.toByte()),
        KEY_RATCHET(0x82.toByte()),
        
        // NAT traversal (0xA0-0xBF)
        HOLE_PUNCH(0xA0.toByte()),
        HOLE_PUNCH_ACK(0xA1.toByte()),
        ENDPOINT_EXCHANGE(0xA2.toByte()),
        ENDPOINT_EXCHANGE_RESPONSE(0xA3.toByte());
        
        companion object {
            fun fromValue(value: Byte): MessageType? {
                return entries.find { it.value == value }
            }
        }
    }
    
    /**
     * Message flags
     */
    object Flags {
        const val ENCRYPTED: Short = 0x0001
        const val COMPRESSED: Short = 0x0002
        const val REQUIRE_ACK: Short = 0x0004
        const val IS_ACK: Short = 0x0008
        const val PRIORITY_HIGH: Short = 0x0010
        const val RELAY_ALLOWED: Short = 0x0020
        const val FRAGMENTED: Short = 0x0040
        const val LAST_FRAGMENT: Short = 0x0080
    }
    
    /**
     * Encoded message container
     */
    data class EncodedMessage(
        val bytes: ByteArray,
        val sequenceNumber: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EncodedMessage) return false
            return sequenceNumber == other.sequenceNumber && bytes.contentEquals(other.bytes)
        }
        
        override fun hashCode(): Int {
            return 31 * bytes.contentHashCode() + sequenceNumber
        }
    }
    
    /**
     * Decoded message container
     */
    data class DecodedMessage(
        val type: MessageType,
        val flags: Short,
        val sequenceNumber: Int,
        val sourceNodeId: ByteArray,
        val destNodeId: ByteArray,
        val ttl: Byte,
        val hops: Byte,
        val payload: ByteArray,
        val signature: ByteArray
    ) {
        val isEncrypted: Boolean get() = (flags.toInt() and Flags.ENCRYPTED.toInt()) != 0
        val isCompressed: Boolean get() = (flags.toInt() and Flags.COMPRESSED.toInt()) != 0
        val requiresAck: Boolean get() = (flags.toInt() and Flags.REQUIRE_ACK.toInt()) != 0
        val isAck: Boolean get() = (flags.toInt() and Flags.IS_ACK.toInt()) != 0
        val isHighPriority: Boolean get() = (flags.toInt() and Flags.PRIORITY_HIGH.toInt()) != 0
        
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DecodedMessage) return false
            return sequenceNumber == other.sequenceNumber && 
                   sourceNodeId.contentEquals(other.sourceNodeId)
        }
        
        override fun hashCode(): Int {
            return 31 * sourceNodeId.contentHashCode() + sequenceNumber
        }
    }
    
    private var sequenceCounter = 0
    
    /**
     * Encode a message for transmission.
     */
    fun encode(
        type: MessageType,
        payload: ByteArray,
        sourceNodeId: ByteArray,
        destNodeId: ByteArray,
        flags: Short = 0,
        ttl: Byte = DEFAULT_TTL,
        signature: ByteArray = ByteArray(SIGNATURE_SIZE)
    ): EncodedMessage {
        require(payload.size <= MAX_PAYLOAD_SIZE) { "Payload too large: ${payload.size}" }
        require(sourceNodeId.size == 32) { "Invalid source NodeID size" }
        require(destNodeId.size == 32) { "Invalid dest NodeID size" }
        
        val totalSize = HEADER_SIZE + ROUTING_SIZE + payload.size + SIGNATURE_SIZE
        val buffer = ByteBuffer.allocate(totalSize)
        buffer.order(ByteOrder.BIG_ENDIAN)
        
        val sequenceNumber = sequenceCounter++
        
        // Header
        buffer.put(MAGIC)                       // 4 bytes
        buffer.put(VERSION)                     // 1 byte
        buffer.put(type.value)                  // 1 byte
        buffer.putShort(flags)                  // 2 bytes
        buffer.putInt(payload.size)             // 4 bytes
        buffer.putInt(sequenceNumber)           // 4 bytes
        buffer.putInt(0)                        // Checksum placeholder (4 bytes)
        
        // Routing
        buffer.put(sourceNodeId)                // 32 bytes
        buffer.put(destNodeId)                  // 32 bytes
        buffer.put(ttl)                         // 1 byte
        buffer.put(0)                           // Hops (starts at 0)
        buffer.putShort(0)                      // Reserved (2 bytes)
        
        // Payload
        buffer.put(payload)
        
        // Signature
        buffer.put(signature)
        
        // Calculate and insert checksum
        val bytes = buffer.array()
        val checksum = calculateChecksum(bytes, HEADER_SIZE - 4)
        buffer.position(HEADER_SIZE - 4)
        buffer.putInt(checksum)
        
        return EncodedMessage(buffer.array(), sequenceNumber)
    }
    
    /**
     * Decode a received message.
     */
    fun decode(data: ByteArray): DecodedMessage? {
        if (data.size < HEADER_SIZE + ROUTING_SIZE + SIGNATURE_SIZE) {
            Timber.w("$TAG: Message too short: ${data.size} bytes")
            return null
        }
        
        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.BIG_ENDIAN)
        
        // Verify magic
        val magic = ByteArray(4)
        buffer.get(magic)
        if (!magic.contentEquals(MAGIC)) {
            Timber.w("$TAG: Invalid magic bytes")
            return null
        }
        
        // Parse header
        val version = buffer.get()
        if (version != VERSION) {
            Timber.w("$TAG: Unsupported version: $version")
            return null
        }
        
        val typeByte = buffer.get()
        val type = MessageType.fromValue(typeByte)
        if (type == null) {
            Timber.w("$TAG: Unknown message type: $typeByte")
            return null
        }
        
        val flags = buffer.short
        val payloadLength = buffer.int
        val sequenceNumber = buffer.int
        val receivedChecksum = buffer.int
        
        // Verify payload length
        val expectedSize = HEADER_SIZE + ROUTING_SIZE + payloadLength + SIGNATURE_SIZE
        if (data.size != expectedSize) {
            Timber.w("$TAG: Size mismatch: expected $expectedSize, got ${data.size}")
            return null
        }
        
        // Verify checksum
        val calculatedChecksum = calculateChecksum(data, HEADER_SIZE - 4)
        if (receivedChecksum != calculatedChecksum) {
            Timber.w("$TAG: Checksum mismatch")
            return null
        }
        
        // Parse routing
        val sourceNodeId = ByteArray(32)
        buffer.get(sourceNodeId)
        val destNodeId = ByteArray(32)
        buffer.get(destNodeId)
        val ttl = buffer.get()
        val hops = buffer.get()
        buffer.short // Reserved
        
        // Parse payload
        val payload = ByteArray(payloadLength)
        buffer.get(payload)
        
        // Parse signature
        val signature = ByteArray(SIGNATURE_SIZE)
        buffer.get(signature)
        
        return DecodedMessage(
            type = type,
            flags = flags,
            sequenceNumber = sequenceNumber,
            sourceNodeId = sourceNodeId,
            destNodeId = destNodeId,
            ttl = ttl,
            hops = hops,
            payload = payload,
            signature = signature
        )
    }
    
    // ========== ChatService Integration Methods ==========
    
    /**
     * Simple chat message for ChatService.
     */
    data class ChatMessage(
        val messageId: String,
        val payload: ByteArray,
        val flags: Short
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ChatMessage) return false
            return messageId == other.messageId
        }
        
        override fun hashCode(): Int = messageId.hashCode()
    }
    
    /**
     * Encode a message for ChatService (simplified interface).
     * Creates a DATA message with the provided payload.
     * 
     * @param messageId Unique message ID
     * @param payload The encrypted message payload
     * @param flags Message flags (e.g., FLAG_ENCRYPTED)
     * @return Encoded bytes ready for transmission
     */
    fun encodeMessage(
        messageId: String,
        payload: ByteArray,
        flags: Short = FLAG_ENCRYPTED
    ): ByteArray {
        // Create payload structure: [messageId length (2)] [messageId] [payload]
        val messageIdBytes = messageId.toByteArray(Charsets.UTF_8)
        val buffer = java.nio.ByteBuffer.allocate(2 + messageIdBytes.size + payload.size)
        buffer.order(java.nio.ByteOrder.BIG_ENDIAN)
        buffer.putShort(messageIdBytes.size.toShort())
        buffer.put(messageIdBytes)
        buffer.put(payload)
        return buffer.array()
    }
    
    /**
     * Decode a message for ChatService (simplified interface).
     * 
     * @param data Raw message bytes
     * @return ChatMessage with extracted messageId and payload
     */
    fun decodeMessage(data: ByteArray): ChatMessage {
        val buffer = java.nio.ByteBuffer.wrap(data)
        buffer.order(java.nio.ByteOrder.BIG_ENDIAN)
        
        val messageIdLength = buffer.short.toInt()
        require(messageIdLength > 0 && messageIdLength <= 128) { "Invalid messageId length: $messageIdLength" }
        
        val messageIdBytes = ByteArray(messageIdLength)
        buffer.get(messageIdBytes)
        val messageId = String(messageIdBytes, Charsets.UTF_8)
        
        val payload = ByteArray(buffer.remaining())
        buffer.get(payload)
        
        return ChatMessage(
            messageId = messageId,
            payload = payload,
            flags = FLAG_ENCRYPTED  // Default, actual flags would come from protocol header
        )
    }
    
    /**
     * Increment hop count and decrement TTL for forwarding.
     */
    fun incrementHops(data: ByteArray): ByteArray? {
        if (data.size < HEADER_SIZE + ROUTING_SIZE) return null
        
        val buffer = ByteBuffer.wrap(data.copyOf())
        buffer.order(ByteOrder.BIG_ENDIAN)
        
        // Read TTL and hops
        val ttlOffset = HEADER_SIZE + 64 // After source and dest NodeIDs
        val ttl = data[ttlOffset]
        val hops = data[ttlOffset + 1]
        
        // Check if we should forward
        if (ttl <= 0) {
            Timber.w("$TAG: TTL exhausted, dropping message")
            return null
        }
        
        // Update TTL and hops
        buffer.put(ttlOffset, (ttl - 1).toByte())
        buffer.put(ttlOffset + 1, (hops + 1).toByte())
        
        return buffer.array()
    }
    
    /**
     * Calculate checksum using first N bytes of the message.
     */
    private fun calculateChecksum(data: ByteArray, upToIndex: Int): Int {
        val digest = MessageDigest.getInstance("MD5")
        digest.update(data, 0, upToIndex)
        // Also include routing and payload in checksum
        if (data.size > HEADER_SIZE) {
            digest.update(data, HEADER_SIZE, data.size - HEADER_SIZE - SIGNATURE_SIZE)
        }
        val hash = digest.digest()
        return ByteBuffer.wrap(hash, 0, 4).int
    }
    
    // Payload builders
    
    /**
     * Create a PING payload.
     */
    fun createPingPayload(timestamp: Long = System.currentTimeMillis()): ByteArray {
        val buffer = ByteBuffer.allocate(8)
        buffer.putLong(timestamp)
        return buffer.array()
    }
    
    /**
     * Create a PONG payload.
     */
    fun createPongPayload(pingTimestamp: Long, localTimestamp: Long = System.currentTimeMillis()): ByteArray {
        val buffer = ByteBuffer.allocate(16)
        buffer.putLong(pingTimestamp)
        buffer.putLong(localTimestamp)
        return buffer.array()
    }
    
    /**
     * Create a FIND_NODE payload.
     */
    fun createFindNodePayload(targetNodeId: ByteArray): ByteArray {
        require(targetNodeId.size == 32)
        return targetNodeId.copyOf()
    }
    
    /**
     * Create a FIND_NODE_RESPONSE payload with a list of nodes.
     */
    fun createFindNodeResponsePayload(nodes: List<NodeInfo>): ByteArray {
        val nodeSize = 32 + 4 + 2 // NodeID + IP + Port
        val buffer = ByteBuffer.allocate(2 + nodes.size * nodeSize)
        buffer.putShort(nodes.size.toShort())
        
        for (node in nodes) {
            buffer.put(node.nodeId)
            buffer.put(node.ipBytes)
            buffer.putShort(node.port.toShort())
        }
        
        return buffer.array()
    }
    
    /**
     * Parse a FIND_NODE_RESPONSE payload.
     */
    fun parseFindNodeResponsePayload(payload: ByteArray): List<NodeInfo> {
        val buffer = ByteBuffer.wrap(payload)
        val count = buffer.short.toInt() and 0xFFFF
        val nodes = mutableListOf<NodeInfo>()
        
        repeat(count) {
            val nodeId = ByteArray(32)
            buffer.get(nodeId)
            val ipBytes = ByteArray(4)
            buffer.get(ipBytes)
            val port = buffer.short.toInt() and 0xFFFF
            
            nodes.add(NodeInfo(nodeId, ipBytes, port))
        }
        
        return nodes
    }
    
    /**
     * Create a CHAT_MESSAGE payload.
     */
    fun createChatMessagePayload(
        messageId: String,
        encryptedContent: ByteArray,
        timestamp: Long = System.currentTimeMillis()
    ): ByteArray {
        val messageIdBytes = messageId.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(8 + 2 + messageIdBytes.size + 4 + encryptedContent.size)
        
        buffer.putLong(timestamp)
        buffer.putShort(messageIdBytes.size.toShort())
        buffer.put(messageIdBytes)
        buffer.putInt(encryptedContent.size)
        buffer.put(encryptedContent)
        
        return buffer.array()
    }
    
    /**
     * Parse a CHAT_MESSAGE payload.
     */
    fun parseChatMessagePayload(payload: ByteArray): ChatMessagePayload? {
        try {
            val buffer = ByteBuffer.wrap(payload)
            
            val timestamp = buffer.long
            val messageIdLen = buffer.short.toInt() and 0xFFFF
            val messageIdBytes = ByteArray(messageIdLen)
            buffer.get(messageIdBytes)
            val messageId = String(messageIdBytes, Charsets.UTF_8)
            
            val contentLen = buffer.int
            val encryptedContent = ByteArray(contentLen)
            buffer.get(encryptedContent)
            
            return ChatMessagePayload(messageId, encryptedContent, timestamp)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to parse chat message payload")
            return null
        }
    }
    
    // Data classes
    
    data class NodeInfo(
        val nodeId: ByteArray,
        val ipBytes: ByteArray,
        val port: Int
    ) {
        val ipString: String
            get() = ipBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
            
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is NodeInfo) return false
            return nodeId.contentEquals(other.nodeId)
        }
        
        override fun hashCode(): Int = nodeId.contentHashCode()
    }
    
    data class ChatMessagePayload(
        val messageId: String,
        val encryptedContent: ByteArray,
        val timestamp: Long
    )
}
