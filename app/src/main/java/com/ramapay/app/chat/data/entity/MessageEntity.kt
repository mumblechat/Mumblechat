package com.ramapay.app.chat.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Message status enum.
 */
enum class MessageStatus {
    PENDING,        // Not yet sent
    SENDING,        // In transit
    SENT_DIRECT,    // Delivered directly via P2P
    SENT_TO_RELAY,  // Stored on relay node
    DELIVERED,      // Confirmed received by recipient
    READ,           // Read by recipient
    FAILED          // Delivery failed
}

/**
 * Message content type enum.
 */
enum class MessageType {
    TEXT,
    IMAGE,
    FILE,
    SYSTEM,
    KEY_EXCHANGE,
    GROUP_KEY_UPDATE,
    READ_RECEIPT,
    TYPING_INDICATOR
}

/**
 * Message entity for Room database.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index("conversationId"),
        Index("groupId"),
        Index("timestamp"),
        Index("status"),
        Index("senderAddress")
    ],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,                     // UUID

    val conversationId: String,         // Hash of sorted wallet addresses (for DM) or groupId
    val groupId: String? = null,        // Set if this is a group message

    val senderAddress: String,          // Sender's wallet address (0x...)
    val recipientAddress: String?,      // Recipient's wallet (null for group messages)

    val contentType: String,            // TEXT, IMAGE, FILE, etc.
    val content: String,                // Decrypted content

    val encryptedContent: ByteArray?,   // Original encrypted (for forwarding)
    
    val timestamp: Long,                // Unix milliseconds
    val status: MessageStatus,          // Message delivery status

    val replyToId: String? = null,      // ID of message being replied to
    val isDeleted: Boolean = false,     // Soft delete flag

    val signature: ByteArray? = null    // Ed25519 signature for verification
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MessageEntity
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}
