package com.ramapay.app.chat

import android.content.Context
import androidx.room.Room
import com.ramapay.app.chat.blockchain.MumbleChatBlockchainService
import com.ramapay.app.chat.core.ChatService
import com.ramapay.app.chat.core.WalletBridge
import com.ramapay.app.chat.crypto.ChatKeyManager
import com.ramapay.app.chat.crypto.ChatKeyStore
import com.ramapay.app.chat.crypto.MessageEncryption
import com.ramapay.app.chat.data.ChatDatabase
import com.ramapay.app.chat.data.MIGRATION_1_2
import com.ramapay.app.chat.data.dao.ContactDao
import com.ramapay.app.chat.data.dao.ConversationDao
import com.ramapay.app.chat.data.dao.GroupDao
import com.ramapay.app.chat.data.dao.MessageDao
import com.ramapay.app.chat.data.repository.ConversationRepository
import com.ramapay.app.chat.data.repository.GroupRepository
import com.ramapay.app.chat.data.repository.MessageRepository
import com.ramapay.app.chat.file.FileTransferManager
import com.ramapay.app.chat.network.P2PManager
import com.ramapay.app.chat.registry.RegistrationManager
import com.ramapay.app.repository.WalletRepositoryType
import com.ramapay.app.service.KeyService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt Dependency Injection module for MumbleChat.
 * 
 * This module provides all the dependencies needed for the MumbleChat protocol.
 * It is completely separate from the wallet system and only accesses wallet
 * services through the WalletBridge (read-only access).
 */
@Module
@InstallIn(SingletonComponent::class)
object ChatModule {

    @Provides
    @Singleton
    fun provideChatDatabase(@ApplicationContext context: Context): ChatDatabase {
        return Room.databaseBuilder(
            context,
            ChatDatabase::class.java,
            "mumblechat_database"
        )
            .addMigrations(MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideMessageDao(database: ChatDatabase): MessageDao {
        return database.messageDao()
    }

    @Provides
    fun provideConversationDao(database: ChatDatabase): ConversationDao {
        return database.conversationDao()
    }

    @Provides
    fun provideGroupDao(database: ChatDatabase): GroupDao {
        return database.groupDao()
    }

    @Provides
    fun provideContactDao(database: ChatDatabase): ContactDao {
        return database.contactDao()
    }

    @Provides
    @Singleton
    fun provideChatKeyStore(@ApplicationContext context: Context): ChatKeyStore {
        return ChatKeyStore(context)
    }

    @Provides
    @Singleton
    fun provideChatKeyManager(
        walletBridge: WalletBridge,
        chatKeyStore: ChatKeyStore
    ): ChatKeyManager {
        return ChatKeyManager(walletBridge, chatKeyStore)
    }

    @Provides
    @Singleton
    fun provideMessageEncryption(): MessageEncryption {
        return MessageEncryption()
    }

    @Provides
    @Singleton
    fun provideFileTransferManager(
        @ApplicationContext context: Context,
        messageEncryption: MessageEncryption
    ): FileTransferManager {
        return FileTransferManager(context, messageEncryption)
    }

    @Provides
    @Singleton
    fun provideWalletBridge(
        keyService: KeyService,
        walletRepository: WalletRepositoryType
    ): WalletBridge {
        return WalletBridge(keyService, walletRepository)
    }

    @Provides
    @Singleton
    fun provideMessageRepository(messageDao: MessageDao): MessageRepository {
        return MessageRepository(messageDao)
    }

    @Provides
    @Singleton
    fun provideConversationRepository(conversationDao: ConversationDao): ConversationRepository {
        return ConversationRepository(conversationDao)
    }

    @Provides
    @Singleton
    fun provideGroupRepository(
        groupDao: GroupDao,
        messageEncryption: MessageEncryption
    ): GroupRepository {
        return GroupRepository(groupDao, messageEncryption)
    }

    @Provides
    @Singleton
    fun provideBlockchainService(
        @ApplicationContext context: Context
    ): MumbleChatBlockchainService {
        return MumbleChatBlockchainService(context)
    }

    @Provides
    @Singleton
    fun provideP2PManager(
        @ApplicationContext context: Context,
        chatKeyStore: ChatKeyStore,
        blockchainService: MumbleChatBlockchainService
    ): P2PManager {
        return P2PManager(context, chatKeyStore, blockchainService)
    }

    @Provides
    @Singleton
    fun provideRegistrationManager(
        walletBridge: WalletBridge,
        blockchainService: MumbleChatBlockchainService
    ): RegistrationManager {
        return RegistrationManager(walletBridge, blockchainService)
    }

    @Provides
    @Singleton
    fun provideChatService(
        @ApplicationContext context: Context,
        p2pManager: P2PManager,
        messageRepository: MessageRepository,
        conversationRepository: ConversationRepository,
        groupRepository: GroupRepository,
        chatKeyManager: ChatKeyManager,
        messageEncryption: MessageEncryption,
        walletBridge: WalletBridge,
        registrationManager: RegistrationManager
    ): ChatService {
        return ChatService(
            context,
            p2pManager,
            messageRepository,
            conversationRepository,
            groupRepository,
            chatKeyManager,
            messageEncryption,
            walletBridge,
            registrationManager
        )
    }
}
