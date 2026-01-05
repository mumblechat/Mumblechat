package com.ramapay.app.chat.registry

import com.ramapay.app.chat.MumbleChatContracts
import com.ramapay.app.chat.blockchain.MumbleChatBlockchainService
import com.ramapay.app.chat.core.WalletBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes32
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages chat identity registration on the MumbleChatRegistry contract.
 * 
 * Every wallet must register on-chain before chatting:
 * 1. User opens chat for first time
 * 2. App derives chat public key from wallet
 * 3. App calls MumbleChatRegistry.register(publicKey)
 * 4. User signs transaction (pays RAMA gas)
 * 5. Identity registered on Ramestta blockchain
 * 6. User can now send/receive messages
 */
@Singleton
class RegistrationManager @Inject constructor(
    private val walletBridge: WalletBridge,
    val blockchainService: MumbleChatBlockchainService
) {
    // Cache of known registrations
    private val registrationCache = mutableMapOf<String, Boolean>()
    private val publicKeyCache = mutableMapOf<String, ByteArray>()

    /**
     * Check if a wallet address is registered for chat.
     */
    suspend fun isRegistered(walletAddress: String): Boolean {
        // Check cache first
        registrationCache[walletAddress]?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val isRegistered = blockchainService.isRegistered(walletAddress)
                registrationCache[walletAddress] = isRegistered
                isRegistered
            } catch (e: Exception) {
                Timber.e(e, "Failed to check registration for $walletAddress")
                false
            }
        }
    }

    /**
     * Register current wallet for chat.
     * Returns transaction data for signing.
     */
    suspend fun getRegistrationTxData(
        identityPublicKey: ByteArray,
        displayName: String = ""
    ): String {
        // Build registration transaction data
        val function = Function(
            "register",
            listOf(
                Bytes32(identityPublicKey.copyOf(32)),
                Utf8String(displayName)
            ),
            emptyList()
        )
        return FunctionEncoder.encode(function)
    }

    /**
     * Mark wallet as registered (after tx confirmation).
     */
    fun markRegistered(walletAddress: String) {
        registrationCache[walletAddress] = true
    }

    /**
     * Get chat public key for an address.
     */
    suspend fun getPublicKey(walletAddress: String): ByteArray? {
        // Check cache first
        publicKeyCache[walletAddress]?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val publicKey = blockchainService.getPublicKey(walletAddress)
                if (publicKey != null && publicKey.isNotEmpty()) {
                    publicKeyCache[walletAddress] = publicKey
                }
                publicKey
            } catch (e: Exception) {
                Timber.e(e, "Failed to get public key for $walletAddress")
                null
            }
        }
    }

    /**
     * Get update public key transaction data.
     * Used for key rotation to update the on-chain public key.
     * 
     * @param newPublicKey The new public key (32 bytes)
     * @param keyVersion Optional key version for tracking rotations
     * @return Encoded transaction data for signing
     */
    fun getUpdateKeyTxData(newPublicKey: ByteArray, keyVersion: Int = 1): String {
        // Include version in the function if contract supports it
        // For now, we just update the public key
        val function = Function(
            "updatePublicKey",
            listOf(Bytes32(newPublicKey.copyOf(32))),
            emptyList()
        )
        Timber.i("Generating updatePublicKey tx data, keyVersion=$keyVersion")
        return FunctionEncoder.encode(function)
    }
    
    /**
     * Get update display name transaction data.
     */
    fun getUpdateDisplayNameTxData(newDisplayName: String): String {
        val function = Function(
            "updateDisplayName",
            listOf(Utf8String(newDisplayName)),
            emptyList()
        )
        return FunctionEncoder.encode(function)
    }

    /**
     * Get register as relay node transaction data.
     * Requires MCT approval first!
     */
    fun getRegisterRelayTxData(endpoint: String): String {
        val function = Function(
            "registerAsRelay",
            listOf(Utf8String(endpoint)),
            emptyList()
        )
        return FunctionEncoder.encode(function)
    }

    /**
     * Get MCT approval transaction data (for relay staking).
     */
    fun getMCTApprovalTxData(amount: java.math.BigInteger): String {
        val function = Function(
            "approve",
            listOf(
                org.web3j.abi.datatypes.Address(MumbleChatContracts.REGISTRY_PROXY),
                org.web3j.abi.datatypes.generated.Uint256(amount)
            ),
            emptyList()
        )
        return FunctionEncoder.encode(function)
    }
    
    /**
     * Clear caches (call when wallet changes).
     */
    fun clearCache() {
        registrationCache.clear()
        publicKeyCache.clear()
    }
}