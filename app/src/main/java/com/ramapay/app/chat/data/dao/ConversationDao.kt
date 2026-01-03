package com.ramapay.app.chat.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ramapay.app.chat.data.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for conversations.
 */
@Dao
interface ConversationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE walletAddress = :wallet ORDER BY lastMessageTime DESC")
    fun getAllForWallet(wallet: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE walletAddress = :wallet ORDER BY lastMessageTime DESC")
    suspend fun getAllForWalletSync(wallet: String): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE walletAddress = :wallet AND peerAddress = :peer")
    suspend fun getByPeer(wallet: String, peer: String): ConversationEntity?

    @Query("""
        UPDATE conversations 
        SET lastMessageId = :messageId, 
            lastMessagePreview = :preview, 
            lastMessageTime = :time
        WHERE id = :conversationId
    """)
    suspend fun updateLastMessage(conversationId: String, messageId: String, preview: String, time: Long)

    @Query("UPDATE conversations SET unreadCount = unreadCount + 1 WHERE id = :conversationId")
    suspend fun incrementUnread(conversationId: String)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :conversationId")
    suspend fun markAsRead(conversationId: String)

    @Query("UPDATE conversations SET isPinned = :pinned WHERE id = :conversationId")
    suspend fun setPinned(conversationId: String, pinned: Boolean)

    @Query("UPDATE conversations SET isMuted = :muted WHERE id = :conversationId")
    suspend fun setMuted(conversationId: String, muted: Boolean)

    @Query("UPDATE conversations SET peerPublicKey = :publicKey WHERE id = :conversationId")
    suspend fun updatePeerPublicKey(conversationId: String, publicKey: ByteArray)
    
    @Query("UPDATE conversations SET customName = :customName WHERE id = :conversationId")
    suspend fun setCustomName(conversationId: String, customName: String?)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM conversations WHERE walletAddress = :wallet AND unreadCount > 0")
    fun getUnreadConversationCount(wallet: String): Flow<Int>

    @Query("SELECT SUM(unreadCount) FROM conversations WHERE walletAddress = :wallet")
    fun getTotalUnreadCount(wallet: String): Flow<Int>
}
