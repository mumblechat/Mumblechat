package com.ramapay.app.chat.data.repository

import com.ramapay.app.chat.crypto.MessageEncryption
import com.ramapay.app.chat.data.dao.GroupDao
import com.ramapay.app.chat.data.entity.GroupEntity
import com.ramapay.app.chat.data.entity.GroupMemberEntity
import com.ramapay.app.chat.data.entity.GroupRole
import kotlinx.coroutines.flow.Flow
import org.web3j.crypto.Hash
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for group operations.
 */
@Singleton
class GroupRepository @Inject constructor(
    private val groupDao: GroupDao,
    private val messageEncryption: MessageEncryption
) {
    fun getGroups(walletAddress: String): Flow<List<GroupEntity>> {
        return groupDao.getAllForWallet(walletAddress)
    }

    suspend fun getGroupsSync(walletAddress: String): List<GroupEntity> {
        return groupDao.getAllForWalletSync(walletAddress)
    }

    suspend fun getById(id: String): GroupEntity? {
        return groupDao.getById(id)
    }

    /**
     * Create a new group.
     */
    suspend fun createGroup(
        walletAddress: String,
        name: String,
        description: String?,
        memberAddresses: List<String>
    ): GroupEntity {
        val groupId = generateGroupId()
        val groupKey = messageEncryption.generateGroupKey()
        
        val group = GroupEntity(
            id = groupId,
            walletAddress = walletAddress,
            name = name,
            description = description,
            createdBy = walletAddress,
            createdAt = System.currentTimeMillis(),
            myRole = GroupRole.OWNER,
            currentKeyVersion = 1,
            encryptedGroupKey = groupKey // In production, encrypt per-member
        )

        val members = memberAddresses.map { address ->
            GroupMemberEntity(
                groupId = groupId,
                memberAddress = address,
                role = if (address == walletAddress) GroupRole.OWNER else GroupRole.MEMBER,
                sessionPublicKey = null,
                joinedAt = System.currentTimeMillis(),
                addedBy = walletAddress
            )
        }

        groupDao.createGroupWithMembers(group, members)
        return group
    }

    suspend fun updateLastMessage(
        groupId: String,
        messageId: String,
        preview: String,
        timestamp: Long
    ) {
        groupDao.updateLastMessage(groupId, messageId, preview, timestamp)
    }

    suspend fun incrementUnread(groupId: String) {
        groupDao.incrementUnread(groupId)
    }

    suspend fun markAsRead(groupId: String) {
        groupDao.markAsRead(groupId)
    }

    suspend fun getMembers(groupId: String): List<GroupMemberEntity> {
        return groupDao.getMembers(groupId)
    }

    fun getMembersFlow(groupId: String): Flow<List<GroupMemberEntity>> {
        return groupDao.getMembersFlow(groupId)
    }

    suspend fun addMember(groupId: String, memberAddress: String, addedBy: String): Boolean {
        val existing = groupDao.getMember(groupId, memberAddress)
        if (existing != null) return false

        val member = GroupMemberEntity(
            groupId = groupId,
            memberAddress = memberAddress,
            role = GroupRole.MEMBER,
            sessionPublicKey = null,
            joinedAt = System.currentTimeMillis(),
            addedBy = addedBy
        )
        groupDao.insertMember(member)
        return true
    }

    suspend fun removeMember(groupId: String, memberAddress: String) {
        groupDao.removeMember(groupId, memberAddress)
    }

    suspend fun updateMemberRole(groupId: String, memberAddress: String, role: GroupRole) {
        groupDao.updateMemberRole(groupId, memberAddress, role)
    }

    suspend fun leaveGroup(groupId: String, walletAddress: String) {
        groupDao.removeMember(groupId, walletAddress)
        groupDao.deleteGroup(groupId)
    }

    suspend fun deleteGroup(groupId: String) {
        groupDao.deleteGroupWithMembers(groupId)
    }

    /**
     * Get the decrypted group key for encryption/decryption.
     */
    suspend fun getGroupKey(groupId: String): ByteArray? {
        val group = groupDao.getById(groupId) ?: return null
        // In production, decrypt the group key using user's private key
        return group.encryptedGroupKey
    }

    suspend fun updateGroupKey(groupId: String, newKey: ByteArray, keyVersion: Int) {
        groupDao.updateGroupKey(groupId, newKey, keyVersion)
    }

    suspend fun getMemberCount(groupId: String): Int {
        return groupDao.getMemberCount(groupId)
    }

    private fun generateGroupId(): String {
        val uuid = UUID.randomUUID().toString()
        return Hash.sha3String(uuid).substring(2, 42) // 40 char hex
    }
}
