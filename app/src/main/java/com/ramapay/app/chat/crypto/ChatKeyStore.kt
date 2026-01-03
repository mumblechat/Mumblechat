package com.ramapay.app.chat.crypto

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage for chat keys using EncryptedSharedPreferences.
 * 
 * Keys are stored encrypted and tied to the wallet address.
 * When wallet changes, keys are re-derived.
 */
@Singleton
class ChatKeyStore @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val PREF_FILE = "mumblechat_keys"
        private const val KEY_IDENTITY_PRIVATE = "identity_private"
        private const val KEY_IDENTITY_PUBLIC = "identity_public"
        private const val KEY_SESSION_PRIVATE = "session_private"
        private const val KEY_SESSION_PUBLIC = "session_public"
        private const val KEY_BACKUP = "backup_key"
    }

    private val encryptedPrefs by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREF_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to create encrypted preferences")
            null
        }
    }

    /**
     * Store chat keys for a wallet address.
     */
    fun storeKeys(walletAddress: String, keys: ChatKeyManager.ChatKeyPair): Boolean {
        val prefs = encryptedPrefs ?: return false
        val prefix = getPrefix(walletAddress)

        return try {
            prefs.edit()
                .putString("${prefix}_${KEY_IDENTITY_PRIVATE}", encode(keys.identityPrivate))
                .putString("${prefix}_${KEY_IDENTITY_PUBLIC}", encode(keys.identityPublic))
                .putString("${prefix}_${KEY_SESSION_PRIVATE}", encode(keys.sessionPrivate))
                .putString("${prefix}_${KEY_SESSION_PUBLIC}", encode(keys.sessionPublic))
                .putString("${prefix}_${KEY_BACKUP}", encode(keys.backupKey))
                .apply()
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to store keys")
            false
        }
    }

    /**
     * Retrieve chat keys for a wallet address.
     */
    fun getKeys(walletAddress: String): ChatKeyManager.ChatKeyPair? {
        val prefs = encryptedPrefs ?: return null
        val prefix = getPrefix(walletAddress)

        return try {
            val identityPrivate = decode(prefs.getString("${prefix}_${KEY_IDENTITY_PRIVATE}", null))
            val identityPublic = decode(prefs.getString("${prefix}_${KEY_IDENTITY_PUBLIC}", null))
            val sessionPrivate = decode(prefs.getString("${prefix}_${KEY_SESSION_PRIVATE}", null))
            val sessionPublic = decode(prefs.getString("${prefix}_${KEY_SESSION_PUBLIC}", null))
            val backupKey = decode(prefs.getString("${prefix}_${KEY_BACKUP}", null))

            if (identityPrivate != null && identityPublic != null &&
                sessionPrivate != null && sessionPublic != null && backupKey != null) {
                ChatKeyManager.ChatKeyPair(
                    identityPrivate = identityPrivate,
                    identityPublic = identityPublic,
                    sessionPrivate = sessionPrivate,
                    sessionPublic = sessionPublic,
                    backupKey = backupKey
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to retrieve keys")
            null
        }
    }

    /**
     * Check if keys exist for a wallet address.
     */
    fun hasKeys(walletAddress: String): Boolean {
        val prefs = encryptedPrefs ?: return false
        val prefix = getPrefix(walletAddress)
        return prefs.contains("${prefix}_${KEY_IDENTITY_PRIVATE}")
    }

    /**
     * Delete keys for a wallet address.
     */
    fun deleteKeys(walletAddress: String): Boolean {
        val prefs = encryptedPrefs ?: return false
        val prefix = getPrefix(walletAddress)

        return try {
            prefs.edit()
                .remove("${prefix}_${KEY_IDENTITY_PRIVATE}")
                .remove("${prefix}_${KEY_IDENTITY_PUBLIC}")
                .remove("${prefix}_${KEY_SESSION_PRIVATE}")
                .remove("${prefix}_${KEY_SESSION_PUBLIC}")
                .remove("${prefix}_${KEY_BACKUP}")
                .apply()
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete keys")
            false
        }
    }

    /**
     * Delete all stored keys.
     */
    fun clearAll(): Boolean {
        val prefs = encryptedPrefs ?: return false
        return try {
            prefs.edit().clear().apply()
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear all keys")
            false
        }
    }

    private fun getPrefix(walletAddress: String): String {
        // Use first 8 chars of address hash for privacy
        return org.web3j.crypto.Hash.sha3String(walletAddress.lowercase())
            .substring(0, 16)
    }

    private fun encode(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decode(string: String?): ByteArray? {
        return string?.let { Base64.decode(it, Base64.NO_WRAP) }
    }
}
