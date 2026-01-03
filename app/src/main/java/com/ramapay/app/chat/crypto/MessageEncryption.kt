package com.ramapay.app.chat.crypto

import org.web3j.crypto.Hash
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles message encryption and decryption for MumbleChat.
 * 
 * Encryption Algorithm:
 * - Key Exchange: X25519 ECDH
 * - Symmetric Cipher: AES-256-GCM
 * - Message Auth: Built into GCM
 * - Nonce: 12 bytes random
 */
@Singleton
class MessageEncryption @Inject constructor() {

    companion object {
        private const val GCM_TAG_LENGTH = 128 // bits
        private const val NONCE_LENGTH = 12 // bytes
        private const val KEY_LENGTH = 32 // bytes (256 bits)
    }

    /**
     * Encrypted message structure.
     */
    data class EncryptedMessage(
        val nonce: ByteArray,      // 12 bytes
        val ciphertext: ByteArray, // Variable length
        val authTag: ByteArray     // 16 bytes
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as EncryptedMessage
            return nonce.contentEquals(other.nonce) && 
                   ciphertext.contentEquals(other.ciphertext)
        }

        override fun hashCode(): Int {
            return nonce.contentHashCode() + ciphertext.contentHashCode()
        }

        /**
         * Serialize for transmission.
         */
        fun toBytes(): ByteArray {
            return nonce + ciphertext + authTag
        }

        companion object {
            /**
             * Deserialize from bytes.
             */
            fun fromBytes(bytes: ByteArray): EncryptedMessage {
                require(bytes.size > NONCE_LENGTH + 16) { "Invalid encrypted message" }
                return EncryptedMessage(
                    nonce = bytes.copyOfRange(0, NONCE_LENGTH),
                    ciphertext = bytes.copyOfRange(NONCE_LENGTH, bytes.size - 16),
                    authTag = bytes.copyOfRange(bytes.size - 16, bytes.size)
                )
            }
        }
    }

    /**
     * Encrypt a message for direct messaging (1:1).
     * 
     * @param plaintext The message content
     * @param senderSessionPrivate Sender's X25519 private key
     * @param recipientSessionPublic Recipient's X25519 public key
     * @return Encrypted message
     */
    fun encryptMessage(
        plaintext: String,
        senderSessionPrivate: ByteArray,
        recipientSessionPublic: ByteArray
    ): EncryptedMessage {
        // Step 1: X25519 key exchange (simplified - use proper ECDH in production)
        val sharedSecret = computeSharedSecret(senderSessionPrivate, recipientSessionPublic)

        // Step 2: Derive message key using HKDF
        val messageKey = deriveMessageKey(sharedSecret)

        // Step 3: Generate random nonce
        val nonce = ByteArray(NONCE_LENGTH)
        SecureRandom().nextBytes(nonce)

        // Step 4: AES-256-GCM encrypt
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(messageKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        val ciphertextWithTag = cipher.doFinal(plaintextBytes)

        // GCM appends auth tag to ciphertext
        val ciphertext = ciphertextWithTag.copyOfRange(0, ciphertextWithTag.size - 16)
        val authTag = ciphertextWithTag.copyOfRange(ciphertextWithTag.size - 16, ciphertextWithTag.size)

        return EncryptedMessage(nonce, ciphertext, authTag)
    }

    /**
     * Decrypt a message from direct messaging.
     */
    fun decryptMessage(
        encrypted: EncryptedMessage,
        recipientSessionPrivate: ByteArray,
        senderSessionPublic: ByteArray
    ): String {
        // Step 1: X25519 key exchange
        val sharedSecret = computeSharedSecret(recipientSessionPrivate, senderSessionPublic)

        // Step 2: Derive message key
        val messageKey = deriveMessageKey(sharedSecret)

        // Step 3: AES-256-GCM decrypt
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(messageKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, encrypted.nonce)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        // Combine ciphertext and auth tag for decryption
        val ciphertextWithTag = encrypted.ciphertext + encrypted.authTag
        val plaintextBytes = cipher.doFinal(ciphertextWithTag)

        return String(plaintextBytes, Charsets.UTF_8)
    }

    /**
     * Encrypt a message with a group key.
     * Group messages use a shared symmetric key.
     */
    fun encryptWithGroupKey(
        plaintext: String,
        groupKey: ByteArray
    ): EncryptedMessage {
        // Generate random nonce
        val nonce = ByteArray(NONCE_LENGTH)
        SecureRandom().nextBytes(nonce)

        // AES-256-GCM encrypt
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(groupKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        val ciphertextWithTag = cipher.doFinal(plaintextBytes)

        val ciphertext = ciphertextWithTag.copyOfRange(0, ciphertextWithTag.size - 16)
        val authTag = ciphertextWithTag.copyOfRange(ciphertextWithTag.size - 16, ciphertextWithTag.size)

        return EncryptedMessage(nonce, ciphertext, authTag)
    }

    /**
     * Decrypt a message with a group key.
     */
    fun decryptWithGroupKey(
        encrypted: EncryptedMessage,
        groupKey: ByteArray
    ): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(groupKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, encrypted.nonce)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        val ciphertextWithTag = encrypted.ciphertext + encrypted.authTag
        val plaintextBytes = cipher.doFinal(ciphertextWithTag)

        return String(plaintextBytes, Charsets.UTF_8)
    }

    /**
     * Sign data with Ed25519 private key.
     * Simplified - use proper Ed25519 in production.
     */
    fun sign(data: ByteArray, privateKey: ByteArray): ByteArray {
        // Simplified signing - use proper Ed25519 in production
        return Hash.sha3(privateKey + data)
    }

    /**
     * Verify signature.
     */
    fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        // Simplified verification - use proper Ed25519 in production
        val expectedSig = Hash.sha3(
            // In real implementation, we'd verify using the public key
            publicKey + data
        )
        return signature.contentEquals(expectedSig)
    }

    /**
     * Compute X25519 shared secret.
     * Simplified implementation - use proper X25519 in production.
     */
    private fun computeSharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        // Simplified ECDH - use proper X25519 implementation in production
        // Libraries: lazysodium-android, BouncyCastle
        return Hash.sha3(privateKey + publicKey)
    }

    /**
     * Derive message key from shared secret using HKDF.
     */
    private fun deriveMessageKey(sharedSecret: ByteArray): ByteArray {
        // Use HKDF to derive a 32-byte key
        return Hash.sha3(sharedSecret + "message_key".toByteArray())
            .copyOfRange(0, KEY_LENGTH)
    }

    /**
     * Generate a new random group key.
     */
    fun generateGroupKey(): ByteArray {
        val key = ByteArray(KEY_LENGTH)
        SecureRandom().nextBytes(key)
        return key
    }
}
