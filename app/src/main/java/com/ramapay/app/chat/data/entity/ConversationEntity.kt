package com.ramapay.app.chat.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Conversation entity for 1:1 direct messaging.
 */
@Entity(
    tableName = "conversations",
    indices = [
        Index("walletAddress"),
        Index("peerAddress"),
        Index("lastMessageTime")
    ]
)
data class ConversationEntity(
    @PrimaryKey
    val id: String,                     // Hash of sorted participant addresses

    val walletAddress: String,          // Current user's wallet
    val peerAddress: String,            // Other party's wallet address
    val peerPublicKey: ByteArray?,      // Their session public key for encryption
    
    val customName: String? = null,     // Custom name for this contact (local only)

    val lastMessageId: String? = null,
    val lastMessagePreview: String? = null,
    val lastMessageTime: Long? = null,

    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,

    val createdAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ConversationEntity
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    companion object {
        /**
         * Generate conversation ID from two wallet addresses.
         * The ID is deterministic regardless of which user creates the conversation.
         */
        fun generateId(address1: String, address2: String): String {
            val sorted = listOf(address1.lowercase(), address2.lowercase()).sorted()
            return org.web3j.crypto.Hash.sha3String("${sorted[0]}:${sorted[1]}")
                .substring(0, 42) // Keep it address-like length
        }
    }
}
