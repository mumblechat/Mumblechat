package com.ramapay.app.chat.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Group role enum.
 */
enum class GroupRole {
    OWNER,      // Created the group, full control
    ADMIN,      // Can add/remove members
    MEMBER      // Regular member
}

/**
 * Group entity for group chats.
 */
@Entity(
    tableName = "groups",
    indices = [
        Index("walletAddress"),
        Index("lastMessageTime")
    ]
)
data class GroupEntity(
    @PrimaryKey
    val id: String,                     // Group ID (hash)

    val walletAddress: String,          // Current user's wallet

    val name: String,
    val description: String? = null,
    val avatarHash: String? = null,     // IPFS hash for group avatar

    val createdBy: String,              // Creator's wallet address
    val createdAt: Long,

    val myRole: GroupRole,              // Current user's role

    val currentKeyVersion: Int,         // For key rotation
    val encryptedGroupKey: ByteArray,   // Group key encrypted for this user

    val lastMessageId: String? = null,
    val lastMessagePreview: String? = null,
    val lastMessageTime: Long? = null,

    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,

    // Group settings
    val onlyAdminsCanPost: Boolean = false,
    val onlyAdminsCanAddMembers: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GroupEntity
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}

/**
 * Group member entity.
 */
@Entity(
    tableName = "group_members",
    primaryKeys = ["groupId", "memberAddress"],
    indices = [
        Index("groupId"),
        Index("memberAddress")
    ]
)
data class GroupMemberEntity(
    val groupId: String,
    val memberAddress: String,

    val role: GroupRole,
    val displayName: String? = null,
    val sessionPublicKey: ByteArray?,

    val joinedAt: Long,
    val addedBy: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GroupMemberEntity
        return groupId == other.groupId && memberAddress == other.memberAddress
    }

    override fun hashCode(): Int {
        return groupId.hashCode() + memberAddress.hashCode()
    }
}
