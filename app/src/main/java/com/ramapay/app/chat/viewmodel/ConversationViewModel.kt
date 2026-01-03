package com.ramapay.app.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ramapay.app.chat.core.ChatService
import com.ramapay.app.chat.core.WalletBridge
import com.ramapay.app.chat.data.entity.MessageEntity
import com.ramapay.app.chat.data.entity.MessageType
import com.ramapay.app.chat.data.repository.ConversationRepository
import com.ramapay.app.chat.data.repository.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Sending state for message submission.
 */
sealed class SendingState {
    object Idle : SendingState()
    object Sending : SendingState()
    object Sent : SendingState()
    data class Error(val message: String) : SendingState()
}

/**
 * ViewModel for conversation (1:1 chat) screen.
 */
@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val chatService: ChatService,
    private val walletBridge: WalletBridge,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository
) : ViewModel() {

    private var conversationId: String? = null
    private var peerAddress: String? = null

    private val _sendingState = MutableStateFlow<SendingState>(SendingState.Idle)
    val sendingState: StateFlow<SendingState> = _sendingState

    private val _peerTyping = MutableStateFlow(false)
    val peerTyping: StateFlow<Boolean> = _peerTyping

    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = _messages

    private val _conversation = MutableStateFlow<com.ramapay.app.chat.data.entity.ConversationEntity?>(null)
    val conversation: StateFlow<com.ramapay.app.chat.data.entity.ConversationEntity?> = _conversation

    val currentWalletAddress: String
        get() = walletBridge.getCurrentWalletAddress() ?: ""

    private var typingJob: Job? = null

    /**
     * Load conversation and messages.
     */
    fun loadConversation(convId: String, peer: String) {
        conversationId = convId
        peerAddress = peer

        // Load conversation details
        viewModelScope.launch {
            conversationRepository.getById(convId)?.let { conv ->
                _conversation.value = conv
            }
        }

        // Mark as read
        viewModelScope.launch {
            conversationRepository.markAsRead(convId)
        }

        // Observe messages
        viewModelScope.launch {
            messageRepository.getMessagesForConversation(convId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
                .collect { messageList ->
                    _messages.value = messageList
                }
        }
    }

    /**
     * Send a text message.
     */
    fun sendMessage(content: String) {
        val recipient = peerAddress ?: return
        
        viewModelScope.launch {
            _sendingState.value = SendingState.Sending
            
            val result = chatService.sendMessage(
                recipientAddress = recipient,
                content = content,
                contentType = MessageType.TEXT
            )
            
            result.fold(
                onSuccess = {
                    _sendingState.value = SendingState.Sent
                    // Reset to idle after short delay
                    delay(500)
                    _sendingState.value = SendingState.Idle
                },
                onFailure = { error ->
                    Timber.e(error, "Failed to send message")
                    _sendingState.value = SendingState.Error(error.message ?: "Send failed")
                    delay(2000)
                    _sendingState.value = SendingState.Idle
                }
            )
        }
    }

    /**
     * Handle typing state change.
     */
    fun onTyping(isTyping: Boolean) {
        typingJob?.cancel()
        
        if (isTyping) {
            typingJob = viewModelScope.launch {
                // Send typing indicator
                // In production, send via P2P
                delay(3000) // Stop after 3 seconds of no input
            }
        }
    }

    /**
     * Delete a message locally.
     */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            messageRepository.deleteMessage(messageId)
        }
    }

    /**
     * Retry sending a failed message.
     */
    fun retryMessage(message: MessageEntity) {
        viewModelScope.launch {
            val result = chatService.sendMessage(
                recipientAddress = message.recipientAddress ?: return@launch,
                content = message.content,
                contentType = MessageType.valueOf(message.contentType)
            )
            
            if (result.isSuccess) {
                // Delete the old failed message
                messageRepository.deleteMessage(message.id)
            }
        }
    }
}
