package com.ramapay.app.chat.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ramapay.app.chat.data.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for contacts.
 */
@Dao
interface ContactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: ContactEntity)

    @Update
    suspend fun update(contact: ContactEntity)

    @Query("SELECT * FROM contacts WHERE ownerWallet = :wallet ORDER BY nickname ASC, address ASC")
    fun getAllForWallet(wallet: String): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE ownerWallet = :wallet ORDER BY nickname ASC, address ASC")
    suspend fun getAllForWalletSync(wallet: String): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun getById(id: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE ownerWallet = :wallet AND address = :address")
    suspend fun getByAddress(wallet: String, address: String): ContactEntity?

    @Query("UPDATE contacts SET nickname = :nickname WHERE id = :id")
    suspend fun updateNickname(id: String, nickname: String?)

    @Query("UPDATE contacts SET isBlocked = :blocked WHERE id = :id")
    suspend fun setBlocked(id: String, blocked: Boolean)

    @Query("UPDATE contacts SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE contacts SET sessionPublicKey = :publicKey WHERE id = :id")
    suspend fun updatePublicKey(id: String, publicKey: ByteArray)

    @Query("SELECT * FROM contacts WHERE ownerWallet = :wallet AND isBlocked = 1")
    fun getBlockedContacts(wallet: String): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE ownerWallet = :wallet AND isFavorite = 1")
    fun getFavoriteContacts(wallet: String): Flow<List<ContactEntity>>

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM contacts WHERE ownerWallet = :wallet")
    suspend fun deleteAllForWallet(wallet: String)

    @Query("SELECT COUNT(*) FROM contacts WHERE ownerWallet = :wallet")
    suspend fun getContactCount(wallet: String): Int

    @Query("""
        SELECT * FROM contacts 
        WHERE ownerWallet = :wallet 
        AND (address LIKE '%' || :query || '%' OR nickname LIKE '%' || :query || '%')
    """)
    suspend fun search(wallet: String, query: String): List<ContactEntity>
}
