package com.ramapay.app.chat.viewmodel

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ramapay.app.chat.MumbleChatContracts
import com.ramapay.app.chat.core.ChatService
import com.ramapay.app.chat.core.WalletBridge
import com.ramapay.app.chat.data.entity.ConversationEntity
import com.ramapay.app.chat.data.entity.GroupEntity
import com.ramapay.app.chat.data.repository.ConversationRepository
import com.ramapay.app.chat.data.repository.GroupRepository
import com.ramapay.app.chat.network.ConnectionState
import com.ramapay.app.entity.SignAuthenticationCallback
import com.ramapay.app.entity.TransactionReturn
import com.ramapay.app.entity.tokens.Token
import com.ramapay.app.interact.CreateTransactionInteract
import com.ramapay.app.repository.TransactionRepositoryType
import com.ramapay.app.service.KeyService
import com.ramapay.app.service.TokensService
import com.ramapay.app.service.TransactionSendHandlerInterface
import com.ramapay.app.web3.entity.Web3Transaction
import com.ramapay.hardware.SignatureFromKey
import dagger.hilt.android.lifecycle.HiltViewModel
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Registration state sealed class.
 */
sealed class RegistrationState {
    object Unknown : RegistrationState()
    object NotRegistered : RegistrationState()
    object Registering : RegistrationState()
    object Registered : RegistrationState()
    data class Error(val message: String) : RegistrationState()
}

/**
 * Combined chat item for display (conversation or group).
 */
sealed class ChatListItem {
    abstract val id: String
    abstract val lastMessageTime: Long?
    abstract val unreadCount: Int
    abstract val isPinned: Boolean

    data class Conversation(
        override val id: String,
        val peerAddress: String,
        val peerName: String?,
        val lastMessage: String?,
        override val lastMessageTime: Long?,
        override val unreadCount: Int,
        override val isPinned: Boolean,
        val isMuted: Boolean
    ) : ChatListItem()

    data class Group(
        override val id: String,
        val name: String,
        val memberCount: Int,
        val lastMessage: String?,
        override val lastMessageTime: Long?,
        override val unreadCount: Int,
        override val isPinned: Boolean,
        val isMuted: Boolean
    ) : ChatListItem()
}

/**
 * ViewModel for the main chat list screen.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatService: ChatService,
    private val walletBridge: WalletBridge,
    private val conversationRepository: ConversationRepository,
    private val groupRepository: GroupRepository,
    private val tokensService: TokensService,
    private val transactionRepository: TransactionRepositoryType,
    private val keyService: KeyService,
    private val createTransactionInteract: CreateTransactionInteract
) : ViewModel(), TransactionSendHandlerInterface {

    private val disposables = CompositeDisposable()
    
    // IMPORTANT: StateFlows must be declared BEFORE the init block!
    private val _registrationState = MutableStateFlow<RegistrationState>(RegistrationState.Unknown)
    val registrationState: StateFlow<RegistrationState> = _registrationState

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading
    
    // Transaction result callbacks
    private val _transactionResult = MutableStateFlow<TransactionReturn?>(null)
    val transactionResult: StateFlow<TransactionReturn?> = _transactionResult
    
    private val _transactionError = MutableStateFlow<TransactionReturn?>(null)
    val transactionError: StateFlow<TransactionReturn?> = _transactionError
    
    init {
        // Observe wallet changes and re-check registration
        viewModelScope.launch {
            walletBridge.observeWalletChanges().collect { wallet ->
                if (wallet != null) {
                    Timber.d("ChatViewModel: Wallet changed to ${wallet.address}, clearing cache and re-checking registration")
                    // Cleanup old wallet data and clear caches
                    chatService.cleanup()
                    checkAndUpdateRegistrationState(wallet.address)
                } else {
                    _registrationState.value = RegistrationState.Unknown
                }
            }
        }
    }
    
    /**
     * Check registration status for a wallet address and update state.
     * Uses forceCheckRegistration to bypass cache.
     */
    private suspend fun checkAndUpdateRegistrationState(address: String) {
        _registrationState.value = RegistrationState.Unknown
        _isLoading.value = true
        
        try {
            // Force check from blockchain to avoid stale cache
            val isRegistered = chatService.forceCheckRegistration(address)
            _registrationState.value = if (isRegistered) {
                Timber.d("ChatViewModel: Wallet $address is registered")
                RegistrationState.Registered
            } else {
                Timber.d("ChatViewModel: Wallet $address is NOT registered")
                RegistrationState.NotRegistered
            }
        } catch (e: Exception) {
            Timber.e(e, "ChatViewModel: Error checking registration for $address")
            _registrationState.value = RegistrationState.Error(e.message ?: "Unknown error")
        } finally {
            _isLoading.value = false
        }
    }
    
    /**
     * Force re-check registration status from blockchain.
     * Returns true if registered.
     */
    suspend fun forceCheckRegistration(address: String): Boolean {
        return chatService.forceCheckRegistration(address)
    }

    val connectionState: StateFlow<ConnectionState> = chatService.isInitialized
        .flatMapLatest { initialized ->
            if (initialized) {
                flowOf(ConnectionState.CONNECTED)
            } else {
                flowOf(ConnectionState.DISCONNECTED)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionState.DISCONNECTED)

    val conversations: StateFlow<List<ConversationEntity>> = walletBridge.observeWalletChanges()
        .flatMapLatest { wallet ->
            if (wallet != null) {
                conversationRepository.getConversations(wallet.address)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val groups: StateFlow<List<GroupEntity>> = walletBridge.observeWalletChanges()
        .flatMapLatest { wallet ->
            if (wallet != null) {
                groupRepository.getGroups(wallet.address)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentWalletAddress: String?
        get() = walletBridge.getCurrentWalletAddress()

    /**
     * Initialize chat service.
     */
    fun initialize() {
        viewModelScope.launch {
            _isLoading.value = true
            
            val result = chatService.initialize()
            
            result.fold(
                onSuccess = {
                    // Check blockchain registration status directly
                    val address = walletBridge.getCurrentWalletAddress()
                    if (address != null) {
                        try {
                            val isRegistered = chatService.isRegistered(address)
                            _registrationState.value = if (isRegistered) {
                                Timber.d("ChatViewModel: Initialized, wallet $address is registered")
                                RegistrationState.Registered
                            } else {
                                Timber.d("ChatViewModel: Initialized, wallet $address is NOT registered")
                                RegistrationState.NotRegistered
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "ChatViewModel: Error checking registration during init")
                            _registrationState.value = RegistrationState.NotRegistered
                        }
                    } else {
                        _registrationState.value = RegistrationState.Unknown
                    }
                },
                onFailure = { error ->
                    Timber.e(error, "Chat initialization failed")
                    _registrationState.value = RegistrationState.Error(error.message ?: "Unknown error")
                }
            )
            
            _isLoading.value = false
        }
    }

    /**
     * Check if an address is already registered on the blockchain.
     */
    suspend fun checkIfRegistered(address: String): Boolean {
        return chatService.isRegistered(address)
    }
    
    /**
     * Prepare registration transaction data.
     */
    suspend fun prepareRegistrationTransaction(): Result<String> {
        // Ensure chat service is initialized with keys before attempting registration
        val initResult = chatService.initialize()
        if (initResult.isFailure) {
            return Result.failure(
                Exception("Failed to initialize chat keys: ${initResult.exceptionOrNull()?.message}")
            )
        }
        
        return chatService.register()
    }

    /**
     * Get native token for gas payments.
     */
    fun getNativeToken(chainId: Long): Token? {
        return tokensService.getToken(chainId, tokensService.getCurrentAddress())
    }

    /**
     * Get the token service.
     */
    fun getTokenService(): TokensService = tokensService

    /**
     * Get authentication for transaction signing.
     */
    fun getAuthorisation(activity: Activity?, callback: SignAuthenticationCallback) {
        val wallet = walletBridge.getCurrentWallet()
        if (wallet == null || activity == null) {
            callback.gotAuthorisation(false)
            return
        }
        
        keyService.getAuthenticationForSignature(wallet, activity, callback)
    }

    /**
     * Verify that a registration transaction was actually confirmed on the blockchain.
     */
    suspend fun verifyRegistrationTransaction(txHash: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Wait a bit more for the transaction to be mined
                delay(5000)
                
                // Check if user is now registered on blockchain
                val address = currentWalletAddress ?: return@withContext false
                val isRegistered = chatService.isRegistered(address)
                
                if (isRegistered) {
                    // Update state
                    _registrationState.value = RegistrationState.Registered
                    Timber.d("Registration verified on blockchain")
                    true
                } else {
                    Timber.w("Transaction sent but registration not confirmed yet")
                    false
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to verify registration")
                false
            }
        }
    }

    /**
     * Complete registration after transaction is sent.
     */
    fun completeRegistration() {
        viewModelScope.launch {
            try {
                chatService.completeRegistration()
                _registrationState.value = RegistrationState.Registered
                Timber.d("Registration completed")
            } catch (e: Exception) {
                Timber.e(e, "Failed to complete registration")
                _registrationState.value = RegistrationState.Error(e.message ?: "Failed to complete registration")
            }
        }
    }

    /**
     * Delete a conversation.
     */
    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.delete(conversationId)
        }
    }

    /**
     * Pin/unpin a conversation.
     */
    fun togglePin(conversationId: String, currentlyPinned: Boolean) {
        viewModelScope.launch {
            conversationRepository.setPinned(conversationId, !currentlyPinned)
        }
    }

    /**
     * Mute/unmute a conversation.
     */
    fun toggleMute(conversationId: String, currentlyMuted: Boolean) {
        viewModelScope.launch {
            conversationRepository.setMuted(conversationId, !currentlyMuted)
        }
    }

    /**
     * Leave a group.
     */
    fun leaveGroup(groupId: String) {
        val walletAddress = walletBridge.getCurrentWalletAddress() ?: return
        viewModelScope.launch {
            groupRepository.leaveGroup(groupId, walletAddress)
        }
    }

    // ============ Transaction Sending ============

    /**
     * Request signature and send transaction to blockchain.
     * This is called from the ActionSheetDialog callback.
     */
    fun requestSignature(tx: Web3Transaction) {
        val wallet = walletBridge.getCurrentWallet() ?: return
        val chainId = MumbleChatContracts.CHAIN_ID
        Timber.d("Requesting signature for transaction to ${tx.recipient}")
        createTransactionInteract.requestSignature(tx, wallet, chainId, this)
    }
    
    /**
     * Send transaction with hardware wallet signature.
     */
    fun sendTransaction(tx: Web3Transaction, signature: SignatureFromKey) {
        val wallet = walletBridge.getCurrentWallet() ?: return
        val chainId = MumbleChatContracts.CHAIN_ID
        createTransactionInteract.sendTransaction(wallet, chainId, tx, signature)
    }

    // ============ TransactionSendHandlerInterface ============
    
    override fun transactionFinalised(txData: TransactionReturn) {
        Timber.d("Transaction finalised: ${txData.hash}")
        _transactionResult.value = txData
    }
    
    override fun transactionError(txError: TransactionReturn) {
        Timber.e("Transaction error: ${txError.throwable?.message}")
        _transactionError.value = txError
    }
    
    /**
     * Clear transaction result state.
     */
    fun clearTransactionResult() {
        _transactionResult.value = null
        _transactionError.value = null
    }
    
    /**
     * Clear pending transactions and reset nonce.
     * This helps recover from "nonce too low" errors.
     */
    suspend fun clearPendingTransactions() {
        withContext(Dispatchers.IO) {
            try {
                // Clear caches
                chatService.cleanup()
                
                // Clear any pending transaction states
                _transactionResult.value = null
                _transactionError.value = null
                _registrationState.value = RegistrationState.Unknown
                
                // Re-check registration status
                val address = walletBridge.getCurrentWalletAddress()
                if (address != null) {
                    val isRegistered = chatService.forceCheckRegistration(address)
                    withContext(Dispatchers.Main) {
                        _registrationState.value = if (isRegistered) {
                            RegistrationState.Registered
                        } else {
                            RegistrationState.NotRegistered
                        }
                    }
                }
                
                Timber.d("Cleared pending transactions and reset state")
            } catch (e: Exception) {
                Timber.e(e, "Failed to clear pending transactions")
                throw e
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        disposables.clear()
    }
}
