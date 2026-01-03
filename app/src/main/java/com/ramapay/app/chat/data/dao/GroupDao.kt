package com.ramapay.app.chat.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.ramapay.app.chat.data.entity.GroupEntity
import com.ramapay.app.chat.data.entity.GroupMemberEntity
import com.ramapay.app.chat.data.entity.GroupRole
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for groups and group members.
 */
@Dao
interface GroupDao {

    // ============ Group Operations ============

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: GroupEntity)

    @Update
    suspend fun updateGroup(group: GroupEntity)

    @Query("SELECT * FROM groups WHERE walletAddress = :wallet ORDER BY lastMessageTime DESC")
    fun getAllForWallet(wallet: String): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE walletAddress = :wallet ORDER BY lastMessageTime DESC")
    suspend fun getAllForWalletSync(wallet: String): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE id = :id")
    suspend fun getById(id: String): GroupEntity?

    @Query("""
        UPDATE groups 
        SET lastMessageId = :messageId, 
            lastMessagePreview = :preview, 
            lastMessageTime = :time
        WHERE id = :groupId
    """)
    suspend fun updateLastMessage(groupId: String, messageId: String, preview: String, time: Long)

    @Query("UPDATE groups SET unreadCount = unreadCount + 1 WHERE id = :groupId")
    suspend fun incrementUnread(groupId: String)

    @Query("UPDATE groups SET unreadCount = 0 WHERE id = :groupId")
    suspend fun markAsRead(groupId: String)

    @Query("UPDATE groups SET isPinned = :pinned WHERE id = :groupId")
    suspend fun setPinned(groupId: String, pinned: Boolean)

    @Query("UPDATE groups SET isMuted = :muted WHERE id = :groupId")
    suspend fun setMuted(groupId: String, muted: Boolean)

    @Query("UPDATE groups SET name = :name WHERE id = :groupId")
    suspend fun updateName(groupId: String, name: String)

    @Query("UPDATE groups SET description = :description WHERE id = :groupId")
    suspend fun updateDescription(groupId: String, description: String?)

    @Query("""
        UPDATE groups 
        SET encryptedGroupKey = :encryptedKey, 
            currentKeyVersion = :keyVersion 
        WHERE id = :groupId
    """)
    suspend fun updateGroupKey(groupId: String, encryptedKey: ByteArray, keyVersion: Int)

    @Query("DELETE FROM groups WHERE id = :id")
    suspend fun deleteGroup(id: String)

    // ============ Member Operations ============

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMember(member: GroupMemberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<GroupMemberEntity>)

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    suspend fun getMembers(groupId: String): List<GroupMemberEntity>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    fun getMembersFlow(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND memberAddress = :address")
    suspend fun getMember(groupId: String, address: String): GroupMemberEntity?

    @Query("UPDATE group_members SET role = :role WHERE groupId = :groupId AND memberAddress = :address")
    suspend fun updateMemberRole(groupId: String, address: String, role: GroupRole)

    @Query("UPDATE group_members SET displayName = :displayName WHERE groupId = :groupId AND memberAddress = :address")
    suspend fun updateMemberDisplayName(groupId: String, address: String, displayName: String?)

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND memberAddress = :address")
    suspend fun removeMember(groupId: String, address: String)

    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun removeAllMembers(groupId: String)

    @Query("SELECT COUNT(*) FROM group_members WHERE groupId = :groupId")
    suspend fun getMemberCount(groupId: String): Int

    // ============ Combined Operations ============

    @Transaction
    suspend fun createGroupWithMembers(group: GroupEntity, members: List<GroupMemberEntity>) {
        insertGroup(group)
        insertMembers(members)
    }

    @Transaction
    suspend fun deleteGroupWithMembers(groupId: String) {
        removeAllMembers(groupId)
        deleteGroup(groupId)
    }
}
