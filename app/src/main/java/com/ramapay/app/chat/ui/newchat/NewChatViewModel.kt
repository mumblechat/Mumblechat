package com.ramapay.app.chat.ui.newchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ramapay.app.chat.core.WalletBridge
import com.ramapay.app.chat.data.repository.ConversationRepository
import com.ramapay.app.chat.registry.RegistrationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * State for new chat creation.
 */
sealed class NewChatState {
    object Idle : NewChatState()
    object Loading : NewChatState()
    data class Success(val conversationId: String, val peerAddress: String) : NewChatState()
    data class Error(val message: String) : NewChatState()
}

/**
 * ViewModel for new chat activity.
 */
@HiltViewModel
class NewChatViewModel @Inject constructor(
    private val walletBridge: WalletBridge,
    private val conversationRepository: ConversationRepository,
    private val registrationManager: RegistrationManager
) : ViewModel() {

    private val _state = MutableStateFlow<NewChatState>(NewChatState.Idle)
    val state: StateFlow<NewChatState> = _state

    /**
     * Start a conversation with a wallet address.
     */
    fun startConversation(peerAddress: String) {
        val walletAddress = walletBridge.getCurrentWalletAddress()
        if (walletAddress == null) {
            _state.value = NewChatState.Error("No wallet connected")
            return
        }

        if (peerAddress.equals(walletAddress, ignoreCase = true)) {
            _state.value = NewChatState.Error("Cannot chat with yourself")
            return
        }

        viewModelScope.launch {
            _state.value = NewChatState.Loading

            try {
                // Check if peer is registered
                val isRegistered = registrationManager.isRegistered(peerAddress)
                if (!isRegistered) {
                    _state.value = NewChatState.Error("This address is not registered for MumbleChat")
                    return@launch
                }

                // Create or get conversation
                val conversation = conversationRepository.getOrCreate(walletAddress, peerAddress)

                _state.value = NewChatState.Success(
                    conversationId = conversation.id,
                    peerAddress = peerAddress
                )

            } catch (e: Exception) {
                Timber.e(e, "Failed to start conversation")
                _state.value = NewChatState.Error(e.message ?: "Failed to start conversation")
            }
        }
    }

    /**
     * Reset state.
     */
    fun resetState() {
        _state.value = NewChatState.Idle
    }
}
