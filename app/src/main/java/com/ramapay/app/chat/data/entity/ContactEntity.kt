package com.ramapay.app.chat.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Contact entity for managing chat contacts.
 */
@Entity(
    tableName = "contacts",
    indices = [
        Index("ownerWallet"),
        Index("address")
    ]
)
data class ContactEntity(
    @PrimaryKey
    val id: String,                     // = address

    val ownerWallet: String,            // Current user's wallet
    val address: String,                // Contact's wallet address

    val nickname: String? = null,       // Custom name for contact
    val sessionPublicKey: ByteArray?,   // Their public key for encryption

    val isBlocked: Boolean = false,
    val isFavorite: Boolean = false,

    val addedAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ContactEntity
        return id == other.id && ownerWallet == other.ownerWallet
    }

    override fun hashCode(): Int {
        return id.hashCode() + ownerWallet.hashCode()
    }
}
