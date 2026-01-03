package com.ramapay.app.chat.core

import com.ramapay.app.entity.Wallet
import com.ramapay.app.repository.WalletRepositoryType
import com.ramapay.app.service.KeyService
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridge to access RamaPay wallet services from MumbleChat.
 * 
 * This properly observes wallet changes so chat data is wallet-specific.
 */
@Singleton
class WalletBridge @Inject constructor(
    private val keyService: KeyService,
    private val walletRepository: WalletRepositoryType
) {
    companion object {
        const val RAMESTTA_MAINNET_ID = 1370L
    }

    // Current wallet state flow - properly reactive
    private val _currentWallet = MutableStateFlow<Wallet?>(null)
    val currentWallet: StateFlow<Wallet?> = _currentWallet

    init {
        refreshWallet()
    }

    /**
     * Refresh the current wallet from repository.
     * Call this when wallet is changed/added/removed.
     */
    fun refreshWallet() {
        try {
            walletRepository.getDefaultWallet()
                .subscribeOn(Schedulers.io())
                .subscribe(
                    { wallet -> 
                        val oldAddress = _currentWallet.value?.address
                        _currentWallet.value = wallet
                        if (oldAddress != wallet.address) {
                            Timber.d("Wallet changed: $oldAddress -> ${wallet.address}")
                        }
                    },
                    { error -> 
                        Timber.e(error, "Failed to get default wallet")
                        _currentWallet.value = null
                    }
                )
        } catch (e: Exception) {
            Timber.e(e, "Error refreshing wallet")
        }
    }

    /**
     * Force update wallet - call when user switches wallet.
     */
    fun setCurrentWallet(wallet: Wallet?) {
        val oldAddress = _currentWallet.value?.address
        _currentWallet.value = wallet
        if (oldAddress != wallet?.address) {
            Timber.d("Wallet explicitly set: $oldAddress -> ${wallet?.address}")
        }
    }

    fun getCurrentWalletAddress(): String? = _currentWallet.value?.address

    fun getCurrentWallet(): Wallet? = _currentWallet.value

    suspend fun signMessage(message: String): ByteArray? {
        val wallet = getCurrentWallet() ?: return null
        Timber.d("Sign message requested for wallet: ${wallet.address}")
        return try {
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(message.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            Timber.e(e, "Failed to create message hash")
            null
        }
    }

    suspend fun signBytes(data: ByteArray): ByteArray? {
        val wallet = getCurrentWallet() ?: return null
        Timber.d("Sign bytes requested for wallet: ${wallet.address}")
        return try {
            java.security.MessageDigest.getInstance("SHA-256").digest(data)
        } catch (e: Exception) {
            Timber.e(e, "Failed to create data hash")
            null
        }
    }

    /**
     * Observe wallet changes reactively.
     * This is used to reload chat data when wallet changes.
     */
    fun observeWalletChanges(): Flow<Wallet?> {
        // First refresh to get latest
        refreshWallet()
        // Return the state flow which will emit on changes
        return _currentWallet
            .distinctUntilChanged { old, new -> old?.address == new?.address }
    }

    /**
     * Observe just the wallet address for simpler comparisons.
     */
    fun observeWalletAddress(): Flow<String?> {
        return _currentWallet.map { it?.address }
            .distinctUntilChanged()
    }

    fun isWatchOnly(): Boolean {
        val wallet = getCurrentWallet() ?: return true
        return wallet.type == com.ramapay.app.entity.WalletType.WATCH
    }
}
