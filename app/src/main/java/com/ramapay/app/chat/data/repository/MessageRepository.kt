package com.ramapay.app.chat.data.repository

import com.ramapay.app.chat.data.dao.MessageDao
import com.ramapay.app.chat.data.entity.MessageEntity
import com.ramapay.app.chat.data.entity.MessageStatus
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for message operations.
 */
@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao
) {
    suspend fun insert(message: MessageEntity) {
        messageDao.insert(message)
    }

    suspend fun insertIfNotExists(message: MessageEntity) {
        messageDao.insertIfNotExists(message)
    }

    suspend fun updateStatus(messageId: String, status: MessageStatus) {
        messageDao.updateStatus(messageId, status)
    }

    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>> {
        return messageDao.getMessagesForConversation(conversationId)
    }

    fun getMessagesForGroup(groupId: String): Flow<List<MessageEntity>> {
        return messageDao.getMessagesForGroup(groupId)
    }

    suspend fun getMessageById(messageId: String): MessageEntity? {
        return messageDao.getMessageById(messageId)
    }

    suspend fun getPendingMessages(): List<MessageEntity> {
        return messageDao.getMessagesByStatus(MessageStatus.PENDING)
    }

    suspend fun getFailedMessages(): List<MessageEntity> {
        return messageDao.getMessagesByStatus(MessageStatus.FAILED)
    }

    suspend fun deleteMessage(messageId: String) {
        messageDao.softDelete(messageId)
    }

    suspend fun deleteOldMessages(beforeTimestamp: Long) {
        messageDao.deleteOlderThan(beforeTimestamp)
    }

    suspend fun getMessageCount(conversationId: String): Int {
        return messageDao.getMessageCount(conversationId)
    }

    suspend fun getLastMessage(conversationId: String): MessageEntity? {
        return messageDao.getLastMessage(conversationId)
    }

    suspend fun deleteAllForConversation(conversationId: String) {
        messageDao.deleteAllForConversation(conversationId)
    }

    suspend fun deleteAllForGroup(groupId: String) {
        messageDao.deleteAllForGroup(groupId)
    }
    
    /**
     * Get the last message received from a specific sender.
     * Used for sync to determine what messages we might have missed.
     */
    suspend fun getLastMessageFrom(senderAddress: String): MessageEntity? {
        return messageDao.getLastMessageFromSender(senderAddress)
    }
}
