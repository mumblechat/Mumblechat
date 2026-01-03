package com.ramapay.app.chat.crypto

import com.ramapay.app.chat.core.ChatConfig
import com.ramapay.app.chat.core.WalletBridge
import org.web3j.crypto.Hash
import timber.log.Timber
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages chat key derivation and storage.
 * 
 * SECURITY RULES:
 * - NEVER expose wallet private key to chat system
 * - NEVER reuse wallet private key for encryption  
 * - Use wallet signature to derive separate chat keys
 * - Chat keys are deterministic (same wallet = same keys)
 * 
 * Key Types:
 * - Identity Key (Ed25519): Sign messages, P2P identity
 * - Session Key (X25519): Key exchange, E2E encryption
 * - Backup Key (AES-256): Encrypt backup files
 */
@Singleton
class ChatKeyManager @Inject constructor(
    private val walletBridge: WalletBridge,
    private val chatKeyStore: ChatKeyStore
) {
    /**
     * Complete set of chat keys for a wallet.
     */
    data class ChatKeyPair(
        val identityPrivate: ByteArray,  // Ed25519 private (32 bytes)
        val identityPublic: ByteArray,   // Ed25519 public (32 bytes)
        val sessionPrivate: ByteArray,   // X25519 private (32 bytes)
        val sessionPublic: ByteArray,    // X25519 public (32 bytes)
        val backupKey: ByteArray         // AES-256 key (32 bytes)
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ChatKeyPair
            return identityPublic.contentEquals(other.identityPublic)
        }

        override fun hashCode(): Int {
            return identityPublic.contentHashCode()
        }
    }

    private var cachedKeys: ChatKeyPair? = null

    /**
     * Derive chat keys from the current wallet.
     * 
     * Process:
     * 1. Sign derivation message with wallet key
     * 2. Hash signature to get seed
     * 3. Use HKDF to derive individual keys
     */
    suspend fun deriveChatKeys(): ChatKeyPair? {
        // Check cache first
        val walletAddress = walletBridge.getCurrentWalletAddress() ?: return null
        
        // Check if we have stored keys
        val storedKeys = chatKeyStore.getKeys(walletAddress)
        if (storedKeys != null) {
            cachedKeys = storedKeys
            return storedKeys
        }

        return try {
            // Step 1: Sign the derivation message
            val signature = walletBridge.signMessage(ChatConfig.DERIVATION_MESSAGE)
                ?: return null

            // Step 2: Hash signature to get seed
            val seed = Hash.sha3(signature)

            // Step 3: Derive keys using HKDF
            val identityPrivate = hkdfExpand(seed, "identity", 32)
            val sessionPrivate = hkdfExpand(seed, "session", 32)
            val backupKey = hkdfExpand(seed, "backup", 32)

            // Step 4: Generate public keys
            val identityPublic = deriveEd25519PublicKey(identityPrivate)
            val sessionPublic = deriveX25519PublicKey(sessionPrivate)

            val keys = ChatKeyPair(
                identityPrivate = identityPrivate,
                identityPublic = identityPublic,
                sessionPrivate = sessionPrivate,
                sessionPublic = sessionPublic,
                backupKey = backupKey
            )

            // Store keys securely
            chatKeyStore.storeKeys(walletAddress, keys)
            cachedKeys = keys

            keys
        } catch (e: Exception) {
            Timber.e(e, "Failed to derive chat keys")
            null
        }
    }

    /**
     * Get cached keys without re-derivation.
     */
    fun getCachedKeys(): ChatKeyPair? = cachedKeys

    /**
     * Clear cached keys (on wallet change or logout).
     */
    fun clearCache() {
        cachedKeys = null
    }

    /**
     * Derive backup key for backup encryption.
     */
    suspend fun deriveBackupKey(): ByteArray? {
        val signature = walletBridge.signMessage(ChatConfig.BACKUP_KEY_MESSAGE)
            ?: return null
        val seed = Hash.sha3(signature)
        return hkdfExpand(seed, "backup", 32)
    }

    /**
     * HKDF-SHA256 key derivation using standard Java crypto.
     * Implements RFC 5869 HKDF using HMAC-SHA256.
     */
    private fun hkdfExpand(seed: ByteArray, info: String, length: Int): ByteArray {
        val salt = ChatConfig.HKDF_SALT.toByteArray(Charsets.UTF_8)
        val infoBytes = info.toByteArray(Charsets.UTF_8)
        
        // HKDF-Extract: PRK = HMAC-Hash(salt, IKM)
        val prk = hmacSha256(salt, seed)
        
        // HKDF-Expand
        val hashLen = 32 // SHA-256 output length
        val n = (length + hashLen - 1) / hashLen
        var t = ByteArray(0)
        val okm = ByteArray(length)
        var okmOffset = 0
        
        for (i in 1..n) {
            val input = t + infoBytes + byteArrayOf(i.toByte())
            t = hmacSha256(prk, input)
            val copyLen = minOf(hashLen, length - okmOffset)
            System.arraycopy(t, 0, okm, okmOffset, copyLen)
            okmOffset += copyLen
        }
        
        return okm
    }
    
    /**
     * HMAC-SHA256 implementation using standard Java crypto.
     */
    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key, "HmacSHA256")
        mac.init(secretKey)
        return mac.doFinal(data)
    }

    /**
     * Derive Ed25519 public key from private key.
     * Using a simplified approach - in production, use proper Ed25519 library.
     */
    private fun deriveEd25519PublicKey(privateKey: ByteArray): ByteArray {
        // For now, we'll use a hash-based derivation as a placeholder
        // In production, use a proper Ed25519 implementation like:
        // - libsodium/lazysodium
        // - BouncyCastle Ed25519
        return Hash.sha3(privateKey + "ed25519_public".toByteArray())
            .copyOfRange(0, 32)
    }

    /**
     * Derive X25519 public key from private key.
     * Using a simplified approach - in production, use proper X25519 library.
     */
    private fun deriveX25519PublicKey(privateKey: ByteArray): ByteArray {
        // For now, we'll use a hash-based derivation as a placeholder
        // In production, use a proper X25519 implementation like:
        // - libsodium/lazysodium
        // - BouncyCastle X25519
        return Hash.sha3(privateKey + "x25519_public".toByteArray())
            .copyOfRange(0, 32)
    }
}
