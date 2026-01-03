package com.ramapay.app.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ramapay.app.chat.core.ChatService
import com.ramapay.app.chat.core.WalletBridge
import com.ramapay.app.chat.data.entity.GroupEntity
import com.ramapay.app.chat.data.entity.MessageEntity
import com.ramapay.app.chat.data.entity.MessageType
import com.ramapay.app.chat.data.repository.GroupRepository
import com.ramapay.app.chat.data.repository.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for group chat conversation.
 */
@HiltViewModel
class GroupChatViewModel @Inject constructor(
    private val chatService: ChatService,
    private val walletBridge: WalletBridge,
    private val groupRepository: GroupRepository,
    private val messageRepository: MessageRepository
) : ViewModel() {

    private var groupId: String? = null

    val currentWalletAddress: String
        get() = walletBridge.getCurrentWalletAddress() ?: ""

    private val _group = MutableStateFlow<GroupEntity?>(null)
    val group: StateFlow<GroupEntity?> = _group

    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = _messages

    private val _memberCount = MutableStateFlow(0)
    val memberCount: StateFlow<Int> = _memberCount

    private val _typingMembers = MutableStateFlow<List<String>>(emptyList())
    val typingMembers: StateFlow<List<String>> = _typingMembers

    private val _sendingState = MutableStateFlow<SendingState>(SendingState.Idle)
    val sendingState: StateFlow<SendingState> = _sendingState

    private val _leftGroup = MutableStateFlow(false)
    val leftGroup: StateFlow<Boolean> = _leftGroup

    /**
     * Load group and messages.
     */
    fun loadGroup(id: String) {
        groupId = id

        viewModelScope.launch {
            // Load group info
            groupRepository.getById(id)?.let { g ->
                _group.value = g
            }

            // Load member count
            _memberCount.value = groupRepository.getMemberCount(id)

            // Mark as read
            groupRepository.markAsRead(id)
        }

        // Observe messages
        viewModelScope.launch {
            messageRepository.getMessagesForGroup(id)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
                .collect { messageList ->
                    _messages.value = messageList
                }
        }
    }

    /**
     * Send a message to the group.
     */
    fun sendMessage(content: String) {
        val gId = groupId ?: return

        viewModelScope.launch {
            _sendingState.value = SendingState.Sending

            try {
                val result = chatService.sendGroupMessage(
                    groupId = gId,
                    content = content,
                    contentType = MessageType.TEXT
                )

                result.fold(
                    onSuccess = {
                        _sendingState.value = SendingState.Sent
                        delay(500)
                        _sendingState.value = SendingState.Idle
                    },
                    onFailure = { error ->
                        Timber.e(error, "Failed to send group message")
                        _sendingState.value = SendingState.Error(error.message ?: "Send failed")
                        delay(2000)
                        _sendingState.value = SendingState.Idle
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to send group message")
                _sendingState.value = SendingState.Error(e.message ?: "Send failed")
                delay(2000)
                _sendingState.value = SendingState.Idle
            }
        }
    }

    /**
     * Retry sending a failed message.
     */
    fun retryMessage(message: MessageEntity) {
        viewModelScope.launch {
            messageRepository.deleteMessage(message.id)
            sendMessage(message.content)
        }
    }

    /**
     * Delete a message locally.
     */
    fun deleteMessage(message: MessageEntity) {
        viewModelScope.launch {
            messageRepository.deleteMessage(message.id)
        }
    }

    /**
     * Handle typing indicator.
     */
    fun onTyping(isTyping: Boolean) {
        // TODO: Send typing indicator to group members
    }

    /**
     * Toggle group mute status.
     */
    fun toggleMute() {
        val gId = groupId ?: return
        val currentGroup = _group.value ?: return

        viewModelScope.launch {
            groupRepository.getById(gId)?.let { g ->
                // Update muted status (would need a DAO method)
                _group.value = g.copy(isMuted = !g.isMuted)
            }
        }
    }

    /**
     * Leave the group.
     */
    fun leaveGroup() {
        val gId = groupId ?: return
        val wallet = walletBridge.getCurrentWalletAddress() ?: return

        viewModelScope.launch {
            try {
                groupRepository.leaveGroup(gId, wallet)
                _leftGroup.value = true
            } catch (e: Exception) {
                Timber.e(e, "Failed to leave group")
            }
        }
    }
}
