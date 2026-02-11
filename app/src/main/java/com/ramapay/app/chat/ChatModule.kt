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
import com.ramapay.app.chat.nat.HolePuncher
import com.ramapay.app.chat.nat.StunClient
import com.ramapay.app.chat.network.HubConnection
import com.ramapay.app.chat.network.HybridNetworkManager
import com.ramapay.app.chat.network.MobileRelayServer
import com.ramapay.app.chat.network.P2PManager
import com.ramapay.app.chat.p2p.BlockchainPeerResolver
import com.ramapay.app.chat.p2p.BootstrapManager
import com.ramapay.app.chat.p2p.KademliaDHT
import com.ramapay.app.chat.p2p.P2PTransport
import com.ramapay.app.chat.p2p.PeerCache
import com.ramapay.app.chat.p2p.QRCodePeerExchange
import com.ramapay.app.chat.notification.ChatNotificationHelper
import com.ramapay.app.chat.protocol.MessageCodec
import com.ramapay.app.chat.registry.RegistrationManager
import com.ramapay.app.chat.relay.RelayMessageService
import com.ramapay.app.chat.relay.RelayStorage
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
    fun provideChatNotificationHelper(
        @ApplicationContext context: Context
    ): ChatNotificationHelper {
        return ChatNotificationHelper(context)
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
        @ApplicationContext context: Context,
        walletBridge: WalletBridge
    ): MumbleChatBlockchainService {
        return MumbleChatBlockchainService(context, walletBridge)
    }

    @Provides
    @Singleton
    fun provideP2PManager(
        @ApplicationContext context: Context,
        chatKeyStore: ChatKeyStore,
        blockchainService: MumbleChatBlockchainService,
        relayMessageService: RelayMessageService
    ): P2PManager {
        return P2PManager(context, chatKeyStore, blockchainService, relayMessageService)
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
    fun provideRelayStorage(
        @ApplicationContext context: Context
    ): RelayStorage {
        return RelayStorage(context)
    }

    @Provides
    @Singleton
    fun provideRelayMessageService(
        @ApplicationContext context: Context,
        chatKeyStore: ChatKeyStore,
        blockchainService: MumbleChatBlockchainService
    ): RelayMessageService {
        return RelayMessageService(context, chatKeyStore, blockchainService)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // NEW P2P PROTOCOL COMPONENTS (MumbleChat Protocol v1.0)
    // ═══════════════════════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideStunClient(): StunClient {
        return StunClient()
    }

    @Provides
    @Singleton
    fun provideHolePuncher(stunClient: StunClient): HolePuncher {
        return HolePuncher(stunClient)
    }

    @Provides
    @Singleton
    fun providePeerCache(@ApplicationContext context: Context): PeerCache {
        return PeerCache(context)
    }

    @Provides
    @Singleton
    fun provideBlockchainPeerResolver(
        blockchainService: MumbleChatBlockchainService
    ): BlockchainPeerResolver {
        return BlockchainPeerResolver(blockchainService)
    }

    @Provides
    @Singleton
    fun provideBootstrapManager(
        @ApplicationContext context: Context,
        peerCache: PeerCache,
        blockchainPeerResolver: BlockchainPeerResolver
    ): BootstrapManager {
        return BootstrapManager(context, peerCache, blockchainPeerResolver)
    }

    @Provides
    @Singleton
    fun provideRateLimiter(): com.ramapay.app.chat.p2p.RateLimiter {
        return com.ramapay.app.chat.p2p.RateLimiter()
    }

    @Provides
    @Singleton
    fun provideNotificationStrategyManager(
        @ApplicationContext context: Context
    ): com.ramapay.app.chat.notification.NotificationStrategyManager {
        return com.ramapay.app.chat.notification.NotificationStrategyManager(context)
    }

    @Provides
    @Singleton
    fun provideKademliaDHT(
        rateLimiter: com.ramapay.app.chat.p2p.RateLimiter
    ): KademliaDHT {
        return KademliaDHT(rateLimiter)
    }

    @Provides
    @Singleton
    fun provideMessageCodec(): MessageCodec {
        return MessageCodec()
    }

    @Provides
    @Singleton
    fun provideP2PTransport(
        stunClient: StunClient,
        holePuncher: HolePuncher,
        bootstrapManager: BootstrapManager,
        dht: KademliaDHT,
        messageCodec: MessageCodec
    ): P2PTransport {
        return P2PTransport(stunClient, holePuncher, bootstrapManager, dht, messageCodec)
    }

    @Provides
    @Singleton
    fun provideQRCodePeerExchange(
        stunClient: StunClient,
        bootstrapManager: BootstrapManager
    ): QRCodePeerExchange {
        return QRCodePeerExchange(stunClient, bootstrapManager)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HUB CONNECTION & MOBILE RELAY (for web app compatibility)
    // ═══════════════════════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideHubConnection(
        @ApplicationContext context: Context
    ): HubConnection {
        return HubConnection(context)
    }

    @Provides
    @Singleton
    fun provideMobileRelayServer(
        @ApplicationContext context: Context,
        hubConnection: HubConnection
    ): MobileRelayServer {
        return MobileRelayServer(context, hubConnection)
    }

    @Provides
    @Singleton
    fun provideHybridNetworkManager(
        @ApplicationContext context: Context,
        hubConnection: HubConnection,
        p2pManager: P2PManager,
        mobileRelayServer: MobileRelayServer,
        messageEncryption: MessageEncryption
    ): HybridNetworkManager {
        return HybridNetworkManager(
            context,
            hubConnection,
            p2pManager,
            mobileRelayServer,
            messageEncryption
        )
    }

    // ═══════════════════════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideChatService(
        @ApplicationContext context: Context,
        p2pManager: P2PManager,
        p2pTransport: P2PTransport,
        qrCodePeerExchange: QRCodePeerExchange,
        messageCodec: MessageCodec,
        messageRepository: MessageRepository,
        conversationRepository: ConversationRepository,
        groupRepository: GroupRepository,
        chatKeyManager: ChatKeyManager,
        messageEncryption: MessageEncryption,
        walletBridge: WalletBridge,
        registrationManager: RegistrationManager,
        blockchainService: MumbleChatBlockchainService,
        contactDao: ContactDao,
        hubConnection: HubConnection,
        mobileRelayServer: MobileRelayServer,
        hybridNetworkManager: HybridNetworkManager,
        chatNotificationHelper: ChatNotificationHelper
    ): ChatService {
        return ChatService(
            context,
            p2pManager,
            p2pTransport,
            qrCodePeerExchange,
            messageCodec,
            messageRepository,
            conversationRepository,
            groupRepository,
            chatKeyManager,
            messageEncryption,
            walletBridge,
            registrationManager,
            blockchainService,
            contactDao,
            hubConnection,
            mobileRelayServer,
            hybridNetworkManager,
            chatNotificationHelper
        )
    }
}
