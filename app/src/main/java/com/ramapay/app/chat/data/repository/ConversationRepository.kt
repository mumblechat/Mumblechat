package com.ramapay.app.chat.data.repository

import com.ramapay.app.chat.data.dao.ConversationDao
import com.ramapay.app.chat.data.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for conversation operations.
 */
@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao
) {
    fun getConversations(walletAddress: String): Flow<List<ConversationEntity>> {
        return conversationDao.getAllForWallet(walletAddress)
    }

    suspend fun getConversationsSync(walletAddress: String): List<ConversationEntity> {
        return conversationDao.getAllForWalletSync(walletAddress)
    }

    suspend fun getById(id: String): ConversationEntity? {
        return conversationDao.getById(id)
    }

    suspend fun getByPeer(walletAddress: String, peerAddress: String): ConversationEntity? {
        return conversationDao.getByPeer(walletAddress, peerAddress)
    }

    /**
     * Get or create a conversation with a peer.
     */
    suspend fun getOrCreate(walletAddress: String, peerAddress: String): ConversationEntity {
        val existing = conversationDao.getByPeer(walletAddress, peerAddress)
        if (existing != null) {
            return existing
        }

        val conversationId = ConversationEntity.generateId(walletAddress, peerAddress)
        val newConversation = ConversationEntity(
            id = conversationId,
            walletAddress = walletAddress,
            peerAddress = peerAddress,
            peerPublicKey = null,
            createdAt = System.currentTimeMillis()
        )
        conversationDao.insert(newConversation)
        return newConversation
    }

    suspend fun updateLastMessage(
        conversationId: String,
        messageId: String?,
        preview: String?,
        timestamp: Long?
    ) {
        if (messageId != null && preview != null && timestamp != null) {
            conversationDao.updateLastMessage(conversationId, messageId, preview, timestamp)
        } else {
            conversationDao.clearLastMessage(conversationId)
        }
    }
    
    /**
     * Archive a conversation (for now, just delete it).
     * In future, could move to a separate archived table.
     */
    suspend fun archive(conversationId: String) {
        conversationDao.delete(conversationId)
    }

    suspend fun incrementUnread(conversationId: String) {
        conversationDao.incrementUnread(conversationId)
    }

    suspend fun markAsRead(conversationId: String) {
        conversationDao.markAsRead(conversationId)
    }

    suspend fun setPinned(conversationId: String, pinned: Boolean) {
        conversationDao.setPinned(conversationId, pinned)
    }

    suspend fun setMuted(conversationId: String, muted: Boolean) {
        conversationDao.setMuted(conversationId, muted)
    }

    suspend fun updatePeerPublicKey(conversationId: String, publicKey: ByteArray) {
        conversationDao.updatePeerPublicKey(conversationId, publicKey)
    }
    
    /**
     * Set custom name for a contact.
     */
    suspend fun setCustomName(conversationId: String, customName: String?) {
        conversationDao.setCustomName(conversationId, customName)
    }

    suspend fun delete(conversationId: String) {
        conversationDao.delete(conversationId)
    }

    fun getUnreadConversationCount(walletAddress: String): Flow<Int> {
        return conversationDao.getUnreadConversationCount(walletAddress)
    }

    fun getTotalUnreadCount(walletAddress: String): Flow<Int> {
        return conversationDao.getTotalUnreadCount(walletAddress)
    }
}
