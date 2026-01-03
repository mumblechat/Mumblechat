package com.ramapay.app.chat.viewmodel

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ramapay.app.chat.MumbleChatContracts
import com.ramapay.app.chat.core.WalletBridge
import com.ramapay.app.chat.registry.RegistrationManager
import com.ramapay.app.entity.SignAuthenticationCallback
import com.ramapay.app.entity.TransactionReturn
import com.ramapay.app.interact.CreateTransactionInteract
import com.ramapay.app.service.KeyService
import com.ramapay.app.service.TokensService
import com.ramapay.app.service.TransactionSendHandlerInterface
import com.ramapay.app.web3.entity.Address
import com.ramapay.app.web3.entity.Web3Transaction
import com.ramapay.hardware.SignatureFromKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.math.BigInteger
import javax.inject.Inject

/**
 * ViewModel for user profile.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val walletBridge: WalletBridge,
    private val registrationManager: RegistrationManager,
    private val tokensService: TokensService,
    private val keyService: KeyService,
    private val createTransactionInteract: CreateTransactionInteract
) : ViewModel(), TransactionSendHandlerInterface {

    data class ProfileData(
        val walletAddress: String,
        val publicKey: String?,
        val displayName: String,
        val registeredAt: Long,
        val lastUpdated: Long,
        val isActive: Boolean
    )

    private val _profileData = MutableStateFlow<ProfileData?>(null)
    val profileData: StateFlow<ProfileData?> = _profileData

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _updateSuccess = MutableStateFlow(false)
    val updateSuccess: StateFlow<Boolean> = _updateSuccess

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    
    // Pending transaction for display name update
    private var pendingDisplayNameTx: Web3Transaction? = null
    private var pendingNewName: String? = null

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
     * Load user profile from blockchain.
     */
    fun loadProfile() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                val wallet = walletBridge.getCurrentWallet()
                if (wallet == null) {
                    _error.value = "No wallet found"
                    _isLoading.value = false
                    return@launch
                }

                // Get identity from blockchain
                val identity = withContext(Dispatchers.IO) {
                    try {
                        val blockchainService = registrationManager.blockchainService
                        blockchainService.getIdentity(wallet.address)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to load identity")
                        null
                    }
                }

                if (identity != null) {
                    _profileData.value = ProfileData(
                        walletAddress = wallet.address,
                        publicKey = identity.publicKey,
                        displayName = identity.displayName,
                        registeredAt = identity.registeredAt,
                        lastUpdated = identity.lastUpdated,
                        isActive = identity.isActive
                    )
                } else {
                    _profileData.value = ProfileData(
                        walletAddress = wallet.address,
                        publicKey = null,
                        displayName = "",
                        registeredAt = 0,
                        lastUpdated = 0,
                        isActive = false
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load profile")
                _error.value = e.message ?: "Failed to load profile"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Prepare display name update transaction.
     * Returns the transaction for signing through ActionSheet.
     */
    fun prepareDisplayNameUpdate(newName: String): Web3Transaction? {
        val wallet = walletBridge.getCurrentWallet() ?: return null
        
        try {
            val txData = registrationManager.getUpdateDisplayNameTxData(newName)
            
            val tx = Web3Transaction(
                Address(wallet.address),
                Address(MumbleChatContracts.REGISTRY_PROXY),
                BigInteger.ZERO,
                BigInteger.ZERO,
                BigInteger.valueOf(150000),
                -1,
                txData
            )
            
            pendingDisplayNameTx = tx
            pendingNewName = newName
            return tx
        } catch (e: Exception) {
            Timber.e(e, "Failed to prepare display name update")
            _error.value = e.message
            return null
        }
    }

    /**
     * Send the prepared transaction.
     */
    fun sendTransaction(tx: Web3Transaction) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _updateSuccess.value = false

            try {
                val wallet = walletBridge.getCurrentWallet()
                if (wallet == null) {
                    _error.value = "No wallet found"
                    _isLoading.value = false
                    return@launch
                }

                createTransactionInteract.requestSignature(tx, wallet, MumbleChatContracts.CHAIN_ID, this@ProfileViewModel)

            } catch (e: Exception) {
                Timber.e(e, "Failed to send transaction")
                _error.value = e.message ?: "Failed to send transaction"
                _isLoading.value = false
            }
        }
    }

    /**
     * Update display name on-chain (legacy method, may fail on some wallets).
     */
    fun updateDisplayName(newName: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _updateSuccess.value = false

            try {
                val wallet = walletBridge.getCurrentWallet()
                if (wallet == null) {
                    _error.value = "No wallet found"
                    _isLoading.value = false
                    return@launch
                }

                // Get transaction data
                val txData = registrationManager.getUpdateDisplayNameTxData(newName)

                // Create transaction
                val tx = Web3Transaction(
                    Address(wallet.address),
                    Address(MumbleChatContracts.REGISTRY_PROXY),
                    BigInteger.ZERO,
                    BigInteger.ZERO,
                    BigInteger.valueOf(150000),
                    -1,
                    txData
                )
                
                pendingNewName = newName

                // Request signature and send
                createTransactionInteract.requestSignature(tx, wallet, MumbleChatContracts.CHAIN_ID, this@ProfileViewModel)

            } catch (e: Exception) {
                Timber.e(e, "Failed to update display name")
                _error.value = e.message ?: "Failed to update display name"
                _isLoading.value = false
            }
        }
    }

    // TransactionSendHandlerInterface implementation
    override fun transactionFinalised(txData: TransactionReturn) {
        Timber.d("Display name update transaction finalised: ${txData.hash}")
        _isLoading.value = false
        _updateSuccess.value = true
    }

    override fun transactionError(txError: TransactionReturn) {
        Timber.e("Display name update failed: ${txError.throwable?.message}")
        _error.value = txError.throwable?.message ?: "Transaction failed"
        _isLoading.value = false
    }
}
