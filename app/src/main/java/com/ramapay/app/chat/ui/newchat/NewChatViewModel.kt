package com.ramapay.app.chat.ui.newchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ramapay.app.chat.blockchain.MumbleChatBlockchainService
import com.ramapay.app.chat.core.WalletBridge
import com.ramapay.app.chat.data.repository.ContactRepository
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
    data class AddressVerified(
        val address: String, 
        val isRegistered: Boolean,
        val onChainDisplayName: String? = null  // Display name set by the address owner
    ) : NewChatState()
}

/**
 * ViewModel for new chat activity.
 */
@HiltViewModel
class NewChatViewModel @Inject constructor(
    private val walletBridge: WalletBridge,
    private val conversationRepository: ConversationRepository,
    private val contactRepository: ContactRepository,
    private val registrationManager: RegistrationManager,
    private val blockchainService: MumbleChatBlockchainService
) : ViewModel() {

    private val _state = MutableStateFlow<NewChatState>(NewChatState.Idle)
    val state: StateFlow<NewChatState> = _state
    
    /**
     * Get current wallet address.
     */
    fun getMyAddress(): String? {
        return walletBridge.getCurrentWalletAddress()
    }
    
    /**
     * Check if an address is registered for MumbleChat.
     * Also fetches the on-chain display name if available.
     */
    fun checkIfRegistered(address: String) {
        viewModelScope.launch {
            try {
                // Check registration and get on-chain display name
                val isRegistered = registrationManager.isRegistered(address)
                
                // Fetch on-chain display name (set by address owner)
                val onChainName = if (isRegistered) {
                    blockchainService.getOnChainDisplayName(address)
                } else {
                    null
                }
                
                _state.value = NewChatState.AddressVerified(
                    address = address, 
                    isRegistered = isRegistered,
                    onChainDisplayName = onChainName
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to check registration for $address")
                _state.value = NewChatState.AddressVerified(address, false)
            }
        }
    }

    /**
     * Start a conversation with a wallet address.
     * @param peerAddress The wallet address to chat with
     * @param nickname Optional display name to save for the contact
     */
    fun startConversation(peerAddress: String, nickname: String? = null) {
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
                
                // Save nickname if provided
                if (!nickname.isNullOrBlank()) {
                    contactRepository.addOrUpdateContact(walletAddress, peerAddress, nickname)
                }

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
