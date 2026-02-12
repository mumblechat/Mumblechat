package com.ramapay.app.chat.data.repository

import com.ramapay.app.chat.data.dao.ContactDao
import com.ramapay.app.chat.data.entity.ContactEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for contact operations.
 */
@Singleton
class ContactRepository @Inject constructor(
    private val contactDao: ContactDao
) {
    /**
     * Get all contacts for a wallet.
     */
    fun getContacts(walletAddress: String): Flow<List<ContactEntity>> {
        return contactDao.getAllForWallet(walletAddress)
    }

    /**
     * Get a contact by address.
     */
    suspend fun getByAddress(walletAddress: String, address: String): ContactEntity? {
        return contactDao.getByAddress(walletAddress, address)
    }

    /**
     * Get a contact by ID.
     */
    suspend fun getById(id: String): ContactEntity? {
        return contactDao.getById(id)
    }

    /**
     * Add or update a contact with a nickname.
     * If contact exists, only updates the nickname.
     * If contact doesn't exist, creates a new one.
     */
    suspend fun addOrUpdateContact(
        ownerWallet: String,
        contactAddress: String,
        nickname: String? = null
    ): ContactEntity {
        val existingContact = contactDao.getByAddress(ownerWallet, contactAddress)
        
        if (existingContact != null) {
            // Update nickname if provided
            if (!nickname.isNullOrBlank()) {
                contactDao.updateNickname(existingContact.id, nickname)
                return existingContact.copy(nickname = nickname)
            }
            return existingContact
        }

        // Create new contact
        val contactId = contactAddress.lowercase() // Use address as ID
        val newContact = ContactEntity(
            id = contactId,
            ownerWallet = ownerWallet,
            address = contactAddress,
            nickname = nickname,
            sessionPublicKey = null,
            isBlocked = false,
            isFavorite = false,
            addedAt = System.currentTimeMillis()
        )
        contactDao.insert(newContact)
        return newContact
    }

    /**
     * Update nickname for a contact.
     */
    suspend fun updateNickname(contactId: String, nickname: String?) {
        contactDao.updateNickname(contactId, nickname)
    }

    /**
     * Block or unblock a contact.
     */
    suspend fun setBlocked(contactId: String, blocked: Boolean) {
        contactDao.setBlocked(contactId, blocked)
    }

    /**
     * Set favorite status for a contact.
     */
    suspend fun setFavorite(contactId: String, favorite: Boolean) {
        contactDao.setFavorite(contactId, favorite)
    }

    /**
     * Delete a contact.
     */
    suspend fun delete(contactId: String) {
        contactDao.delete(contactId)
    }

    /**
     * Get blocked contacts.
     */
    fun getBlockedContacts(walletAddress: String): Flow<List<ContactEntity>> {
        return contactDao.getBlockedContacts(walletAddress)
    }

    /**
     * Get favorite contacts.
     */
    fun getFavoriteContacts(walletAddress: String): Flow<List<ContactEntity>> {
        return contactDao.getFavoriteContacts(walletAddress)
    }

    /**
     * Search contacts by name or address.
     */
    suspend fun search(walletAddress: String, query: String): List<ContactEntity> {
        return contactDao.search(walletAddress, query)
    }
}
