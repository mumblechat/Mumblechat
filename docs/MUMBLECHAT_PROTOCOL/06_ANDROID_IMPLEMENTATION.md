# MumbleChat Protocol - Android Implementation

## Part 6 of 8

---

## 1. MODULE STRUCTURE

### 1.1 Complete Package Layout

```
app/src/main/java/com/ramapay/app/
│
├── [EXISTING - NO CHANGES]
│   ├── service/
│   │   ├── KeyService.java           # Wallet key management
│   │   ├── KeystoreAccountService.java
│   │   └── ... (other services)
│   ├── repository/
│   │   ├── EthereumNetworkBase.java  # Network configs
│   │   └── ... (other repos)
│   ├── entity/
│   │   ├── Wallet.java               # Wallet entity
│   │   └── ... (other entities)
│   └── ui/
│       ├── HomeActivity.java         # Main activity
│       └── ... (other activities)
│
├── [NEW - MUMBLECHAT MODULE]
│   └── chat/
│       │
│       ├── ChatModule.kt             # Hilt DI module
│       │
│       ├── core/
│       │   ├── ChatService.kt        # Main chat orchestrator
│       │   ├── ChatInitializer.kt    # Initialize on app start
│       │   └── ChatConfig.kt         # Configuration
│       │
│       ├── crypto/
│       │   ├── ChatKeyManager.kt     # Key derivation
│       │   ├── ChatKeyStore.kt       # Secure key storage
│       │   ├── MessageEncryption.kt  # E2E encryption
│       │   ├── KeyExchange.kt        # X25519 ECDH
│       │   └── GroupKeyManager.kt    # Group encryption
│       │
│       ├── data/
│       │   ├── ChatDatabase.kt       # Room database
│       │   ├── dao/
│       │   │   ├── MessageDao.kt
│       │   │   ├── ConversationDao.kt
│       │   │   ├── GroupDao.kt
│       │   │   └── ContactDao.kt
│       │   ├── entity/
│       │   │   ├── MessageEntity.kt
│       │   │   ├── ConversationEntity.kt
│       │   │   ├── GroupEntity.kt
│       │   │   ├── GroupMemberEntity.kt
│       │   │   └── ContactEntity.kt
│       │   └── repository/
│       │       ├── MessageRepository.kt
│       │       ├── ConversationRepository.kt
│       │       └── GroupRepository.kt
│       │
│       ├── network/
│       │   ├── P2PManager.kt         # Peer-to-peer layer
│       │   ├── PeerDiscovery.kt      # DHT peer discovery
│       │   ├── ConnectionManager.kt  # Connection handling
│       │   ├── MessageRouter.kt      # Route messages
│       │   └── protocol/
│       │       ├── ChatProtocol.kt   # Protocol definitions
│       │       └── ProtocolHandler.kt
│       │
│       ├── relay/
│       │   ├── RelayService.kt       # Foreground service
│       │   ├── RelayStorage.kt       # Message storage
│       │   ├── RelayConfig.kt        # Relay settings
│       │   ├── RelaySelector.kt      # Select best relays
│       │   └── RewardClaimer.kt      # Claim MCT
│       │
│       ├── registry/
│       │   ├── ChatRegistry.kt       # Contract interaction
│       │   ├── RegistrationManager.kt
│       │   └── MCTTokenManager.kt    # Token operations
│       │
│       ├── backup/
│       │   ├── BackupManager.kt      # Create backups
│       │   ├── RestoreManager.kt     # Restore from backup
│       │   ├── BackupDiscovery.kt    # Find backup files
│       │   ├── BackupExporter.kt     # Export to cloud
│       │   └── AutoBackupWorker.kt   # Scheduled backup
│       │
│       ├── sync/
│       │   ├── MessageSyncManager.kt # Sync messages
│       │   ├── DeviceSyncManager.kt  # Multi-device sync
│       │   └── SyncWorker.kt         # Background sync
│       │
│       ├── ui/
│       │   ├── ChatFragment.kt       # REPLACES existing
│       │   ├── conversation/
│       │   │   ├── ConversationActivity.kt
│       │   │   ├── ConversationViewModel.kt
│       │   │   └── MessageAdapter.kt
│       │   ├── newchat/
│       │   │   ├── NewChatActivity.kt
│       │   │   └── NewChatViewModel.kt
│       │   ├── group/
│       │   │   ├── GroupInfoActivity.kt
│       │   │   ├── CreateGroupActivity.kt
│       │   │   └── GroupAdapter.kt
│       │   ├── settings/
│       │   │   ├── ChatSettingsFragment.kt
│       │   │   ├── RelaySettingsFragment.kt
│       │   │   └── BackupSettingsFragment.kt
│       │   ├── adapter/
│       │   │   ├── ConversationListAdapter.kt
│       │   │   └── MessageListAdapter.kt
│       │   └── components/
│       │       ├── MessageBubble.kt
│       │       ├── MessageInput.kt
│       │       └── ContactAvatar.kt
│       │
│       └── viewmodel/
│           ├── ChatViewModel.kt      # Chat list
│           ├── ConversationViewModel.kt
│           ├── GroupViewModel.kt
│           └── RelayViewModel.kt
```

---

## 2. INTEGRATION WITH RAMAPAY

### 2.1 Key Principle: No Modification to Wallet System

```
┌─────────────────────────────────────────────────────────────┐
│              INTEGRATION BOUNDARIES                          │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  RAMAPAY WALLET (Read-Only Access)                          │
│  ─────────────────────────────────                          │
│  │                                                          │
│  ├── KeyService                                             │
│  │   └── MumbleChat calls: signPersonalMessage()            │
│  │       (Does NOT access private keys directly)            │
│  │                                                          │
│  ├── WalletRepository                                       │
│  │   └── MumbleChat calls: getCurrentWallet()               │
│  │       (Gets wallet address only)                         │
│  │                                                          │
│  ├── EthereumNetworkRepository                              │
│  │   └── MumbleChat uses: Ramestta network config           │
│  │                                                          │
│  └── HomeActivity                                           │
│      └── Hosts ChatFragment in bottom navigation            │
│          (Already exists, just replace content)             │
│                                                              │
│  MUMBLECHAT (Completely Separate)                           │
│  ────────────────────────────────                           │
│  │                                                          │
│  ├── Own Database (ChatDatabase)                            │
│  │   └── Separate from wallet database                      │
│  │                                                          │
│  ├── Own Services (ChatService, RelayService)               │
│  │   └── Independent lifecycle                              │
│  │                                                          │
│  ├── Own Storage (EncryptedSharedPreferences)               │
│  │   └── Separate preference file                           │
│  │                                                          │
│  └── Own Network Layer (P2PManager)                         │
│      └── Separate from wallet HTTP clients                  │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Dependency Injection Setup

```kotlin
// chat/ChatModule.kt
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
    @Singleton
    fun provideChatKeyStore(@ApplicationContext context: Context): ChatKeyStore {
        return ChatKeyStore(context)
    }
    
    @Provides
    @Singleton
    fun provideP2PManager(
        @ApplicationContext context: Context,
        chatKeyStore: ChatKeyStore
    ): P2PManager {
        return P2PManager(context, chatKeyStore)
    }
    
    @Provides
    @Singleton
    fun provideChatService(
        p2pManager: P2PManager,
        messageRepository: MessageRepository,
        conversationRepository: ConversationRepository,
        chatKeyManager: ChatKeyManager
    ): ChatService {
        return ChatService(p2pManager, messageRepository, conversationRepository, chatKeyManager)
    }
}
```

### 2.3 Access to Wallet Services

```kotlin
// chat/core/WalletBridge.kt
/**
 * Bridge to access RamaPay wallet services from MumbleChat.
 * All access is READ-ONLY - we never modify wallet data.
 */
@Singleton
class WalletBridge @Inject constructor(
    private val keyService: KeyService,
    private val walletRepository: WalletRepository,
    private val networkRepository: EthereumNetworkRepositoryType
) {
    /**
     * Get current active wallet address.
     */
    fun getCurrentWalletAddress(): String? {
        return walletRepository.getDefaultWallet()?.address
    }
    
    /**
     * Get current wallet entity.
     */
    fun getCurrentWallet(): Wallet? {
        return walletRepository.getDefaultWallet()
    }
    
    /**
     * Sign a message using the wallet's private key.
     * This is the ONLY way MumbleChat interacts with wallet keys.
     */
    suspend fun signMessage(message: String): ByteArray? {
        val wallet = getCurrentWallet() ?: return null
        
        return withContext(Dispatchers.IO) {
            try {
                val result = keyService.signPersonalMessage(
                    wallet,
                    message.toByteArray(Charsets.UTF_8)
                ).blockingGet()
                result.signature
            } catch (e: Exception) {
                Timber.e(e, "Failed to sign message")
                null
            }
        }
    }
    
    /**
     * Get Ramestta network configuration.
     */
    fun getRamesttatNetwork(): NetworkInfo {
        return networkRepository.getNetworkByChain(RAMESTTA_MAINNET_ID)
    }
    
    /**
     * Get Web3j instance for Ramestta.
     */
    fun getWeb3j(): Web3j {
        val network = getRamesttatNetwork()
        return Web3j.build(HttpService(network.rpcServerUrl))
    }
    
    /**
     * Listen for wallet changes.
     */
    fun observeWalletChanges(): Flow<Wallet?> {
        return walletRepository.observeDefaultWallet()
    }
}
```

---

## 3. DATABASE SCHEMA

### 3.1 Room Database

```kotlin
// chat/data/ChatDatabase.kt
@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        ContactEntity::class,
        PendingMessageEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun groupDao(): GroupDao
    abstract fun contactDao(): ContactDao
    abstract fun pendingMessageDao(): PendingMessageDao
}

// Converters for Room
class Converters {
    @TypeConverter
    fun fromByteArray(bytes: ByteArray?): String? {
        return bytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
    }
    
    @TypeConverter
    fun toByteArray(string: String?): ByteArray? {
        return string?.let { Base64.decode(it, Base64.NO_WRAP) }
    }
    
    @TypeConverter
    fun fromMessageStatus(status: MessageStatus): String = status.name
    
    @TypeConverter
    fun toMessageStatus(value: String): MessageStatus = MessageStatus.valueOf(value)
    
    @TypeConverter
    fun fromGroupRole(role: GroupRole): String = role.name
    
    @TypeConverter
    fun toGroupRole(value: String): GroupRole = GroupRole.valueOf(value)
    
    @TypeConverter
    fun fromStringList(list: List<String>): String = Gson().toJson(list)
    
    @TypeConverter
    fun toStringList(value: String): List<String> = 
        Gson().fromJson(value, object : TypeToken<List<String>>() {}.type)
}
```

### 3.2 Entities

```kotlin
// chat/data/entity/MessageEntity.kt
@Entity(
    tableName = "messages",
    indices = [
        Index("conversationId"),
        Index("timestamp"),
        Index("status")
    ],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,
    
    val conversationId: String,
    val groupId: String? = null,
    
    val senderAddress: String,
    val recipientAddress: String?,  // null for group messages
    
    val contentType: String,        // TEXT, IMAGE, FILE
    val content: String,            // Decrypted content
    val encryptedContent: ByteArray?,// Original encrypted (for forwarding)
    
    val timestamp: Long,
    val status: MessageStatus,
    
    val replyToId: String? = null,
    val isDeleted: Boolean = false,
    
    val signature: ByteArray? = null
)

// chat/data/entity/ConversationEntity.kt
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    val id: String,                  // Hash of participants
    
    val walletAddress: String,       // Current user's wallet
    val peerAddress: String,         // Other party's address
    val peerPublicKey: ByteArray?,   // Their session public key
    
    val lastMessageId: String? = null,
    val lastMessagePreview: String? = null,
    val lastMessageTime: Long? = null,
    
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    
    val createdAt: Long
)

// chat/data/entity/GroupEntity.kt
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey
    val id: String,                  // Group ID
    
    val walletAddress: String,       // Current user's wallet
    
    val name: String,
    val description: String? = null,
    val avatarHash: String? = null,  // IPFS hash
    
    val createdBy: String,           // Creator's address
    val createdAt: Long,
    
    val myRole: GroupRole,
    
    val currentKeyVersion: Int,
    val encryptedGroupKey: ByteArray,
    
    val lastMessageId: String? = null,
    val lastMessagePreview: String? = null,
    val lastMessageTime: Long? = null,
    
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false
)

// chat/data/entity/GroupMemberEntity.kt
@Entity(
    tableName = "group_members",
    primaryKeys = ["groupId", "memberAddress"]
)
data class GroupMemberEntity(
    val groupId: String,
    val memberAddress: String,
    
    val role: GroupRole,
    val displayName: String? = null,
    val sessionPublicKey: ByteArray?,
    
    val joinedAt: Long,
    val addedBy: String? = null
)

// chat/data/entity/ContactEntity.kt
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey
    val id: String,                  // = walletAddress
    
    val ownerWallet: String,         // Current user's wallet
    val address: String,             // Contact's address
    
    val nickname: String? = null,
    val sessionPublicKey: ByteArray?,
    
    val isBlocked: Boolean = false,
    val isFavorite: Boolean = false,
    
    val addedAt: Long
)
```

### 3.3 DAOs

```kotlin
// chat/data/dao/MessageDao.kt
@Dao
interface MessageDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNotExists(message: MessageEntity)
    
    @Update
    suspend fun update(message: MessageEntity)
    
    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateStatus(messageId: String, status: MessageStatus)
    
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>
    
    @Query("SELECT * FROM messages WHERE groupId = :groupId ORDER BY timestamp ASC")
    fun getMessagesForGroup(groupId: String): Flow<List<MessageEntity>>
    
    @Query("SELECT * FROM messages WHERE status = :status")
    suspend fun getMessagesByStatus(status: MessageStatus): List<MessageEntity>
    
    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): MessageEntity?
    
    @Query("UPDATE messages SET isDeleted = 1 WHERE id = :messageId")
    suspend fun softDelete(messageId: String)
    
    @Query("DELETE FROM messages WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long)
    
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun getMessageCount(conversationId: String): Int
}

// chat/data/dao/ConversationDao.kt
@Dao
interface ConversationDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity)
    
    @Update
    suspend fun update(conversation: ConversationEntity)
    
    @Query("SELECT * FROM conversations WHERE walletAddress = :wallet ORDER BY lastMessageTime DESC")
    fun getAllForWallet(wallet: String): Flow<List<ConversationEntity>>
    
    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): ConversationEntity?
    
    @Query("SELECT * FROM conversations WHERE walletAddress = :wallet AND peerAddress = :peer")
    suspend fun getByPeer(wallet: String, peer: String): ConversationEntity?
    
    @Query("""
        UPDATE conversations 
        SET lastMessageId = :messageId, 
            lastMessagePreview = :preview, 
            lastMessageTime = :time,
            unreadCount = unreadCount + 1
        WHERE id = :conversationId
    """)
    suspend fun updateLastMessage(conversationId: String, messageId: String, preview: String, time: Long)
    
    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :conversationId")
    suspend fun markAsRead(conversationId: String)
    
    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)
}
```

---

## 4. CORE SERVICES

### 4.1 Chat Service

```kotlin
// chat/core/ChatService.kt
@Singleton
class ChatService @Inject constructor(
    private val p2pManager: P2PManager,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val groupRepository: GroupRepository,
    private val chatKeyManager: ChatKeyManager,
    private val messageEncryption: MessageEncryption,
    private val walletBridge: WalletBridge,
    private val registrationManager: RegistrationManager,
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var isInitialized = false
    private var chatKeys: ChatKeyManager.ChatKeyPair? = null
    
    /**
     * Initialize chat service for current wallet.
     * Called when app starts or wallet changes.
     */
    suspend fun initialize(): Result<Unit> {
        val wallet = walletBridge.getCurrentWallet()
            ?: return Result.failure(Exception("No wallet available"))
        
        return try {
            // 1. Derive chat keys from wallet
            chatKeys = chatKeyManager.deriveChatKeys(wallet)
            
            // 2. Check registration status
            val isRegistered = registrationManager.isRegistered(wallet.address)
            
            if (!isRegistered) {
                // Will need to register before chatting
                return Result.success(Unit)
            }
            
            // 3. Connect to P2P network
            p2pManager.initialize(chatKeys!!)
            p2pManager.connect()
            
            // 4. Start listening for incoming messages
            startMessageListener()
            
            // 5. Sync pending messages from relays
            syncPendingMessages()
            
            isInitialized = true
            Result.success(Unit)
            
        } catch (e: Exception) {
            Timber.e(e, "Chat initialization failed")
            Result.failure(e)
        }
    }
    
    /**
     * Send a direct message.
     */
    suspend fun sendMessage(
        recipientAddress: String,
        content: String,
        contentType: MessageType = MessageType.TEXT
    ): Result<MessageEntity> {
        val keys = chatKeys ?: return Result.failure(Exception("Not initialized"))
        
        return try {
            // 1. Get or create conversation
            val conversation = conversationRepository.getOrCreate(
                walletBridge.getCurrentWalletAddress()!!,
                recipientAddress
            )
            
            // 2. Get recipient's public key
            val recipientPubKey = getRecipientPublicKey(recipientAddress)
                ?: return Result.failure(Exception("Recipient not registered"))
            
            // 3. Create message entity
            val message = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversation.id,
                senderAddress = walletBridge.getCurrentWalletAddress()!!,
                recipientAddress = recipientAddress,
                contentType = contentType.name,
                content = content,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.SENDING
            )
            
            // 4. Save locally first
            messageRepository.insert(message)
            
            // 5. Encrypt message
            val encrypted = messageEncryption.encryptMessage(
                content,
                keys.sessionPrivate,
                recipientPubKey
            )
            
            // 6. Send via P2P or relay
            val sent = p2pManager.sendMessage(
                recipientAddress,
                encrypted,
                message.id
            )
            
            // 7. Update status
            val newStatus = when {
                sent.direct -> MessageStatus.SENT_DIRECT
                sent.relayed -> MessageStatus.SENT_TO_RELAY
                else -> MessageStatus.FAILED
            }
            messageRepository.updateStatus(message.id, newStatus)
            
            // 8. Update conversation
            conversationRepository.updateLastMessage(
                conversation.id,
                message.id,
                content.take(100),
                message.timestamp
            )
            
            Result.success(message.copy(status = newStatus))
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to send message")
            Result.failure(e)
        }
    }
    
    /**
     * Send a group message.
     */
    suspend fun sendGroupMessage(
        groupId: String,
        content: String,
        contentType: MessageType = MessageType.TEXT
    ): Result<MessageEntity> {
        val keys = chatKeys ?: return Result.failure(Exception("Not initialized"))
        
        return try {
            // 1. Get group
            val group = groupRepository.getById(groupId)
                ?: return Result.failure(Exception("Group not found"))
            
            // 2. Decrypt group key
            val groupKey = groupRepository.getGroupKey(groupId)
            
            // 3. Create message
            val message = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = groupId,
                groupId = groupId,
                senderAddress = walletBridge.getCurrentWalletAddress()!!,
                recipientAddress = null,
                contentType = contentType.name,
                content = content,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.SENDING
            )
            
            // 4. Save locally
            messageRepository.insert(message)
            
            // 5. Encrypt with group key
            val encrypted = messageEncryption.encryptWithGroupKey(content, groupKey)
            
            // 6. Sign with identity key
            val signature = signMessage(encrypted, keys.identityPrivate)
            
            // 7. Send to all members
            val members = groupRepository.getMembers(groupId)
            for (member in members) {
                if (member.memberAddress != walletBridge.getCurrentWalletAddress()) {
                    p2pManager.sendGroupMessage(member.memberAddress, groupId, encrypted, signature)
                }
            }
            
            // 8. Update status
            messageRepository.updateStatus(message.id, MessageStatus.SENT_DIRECT)
            
            Result.success(message)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to send group message")
            Result.failure(e)
        }
    }
    
    private fun startMessageListener() {
        scope.launch {
            p2pManager.incomingMessages.collect { incoming ->
                handleIncomingMessage(incoming)
            }
        }
    }
    
    private suspend fun handleIncomingMessage(incoming: IncomingMessage) {
        try {
            val keys = chatKeys ?: return
            
            // Get sender's public key
            val senderPubKey = getRecipientPublicKey(incoming.senderAddress) ?: return
            
            // Decrypt message
            val decrypted = messageEncryption.decryptMessage(
                incoming.encrypted,
                keys.sessionPrivate,
                senderPubKey
            )
            
            // Get or create conversation
            val conversation = conversationRepository.getOrCreate(
                walletBridge.getCurrentWalletAddress()!!,
                incoming.senderAddress
            )
            
            // Save message
            val message = MessageEntity(
                id = incoming.messageId,
                conversationId = conversation.id,
                senderAddress = incoming.senderAddress,
                recipientAddress = walletBridge.getCurrentWalletAddress(),
                contentType = incoming.contentType,
                content = decrypted,
                timestamp = incoming.timestamp,
                status = MessageStatus.DELIVERED
            )
            
            messageRepository.insertIfNotExists(message)
            
            // Update conversation
            conversationRepository.updateLastMessage(
                conversation.id,
                message.id,
                decrypted.take(100),
                message.timestamp
            )
            conversationRepository.incrementUnread(conversation.id)
            
            // Send delivery ACK
            p2pManager.sendDeliveryAck(incoming.senderAddress, incoming.messageId)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to handle incoming message")
        }
    }
    
    /**
     * Cleanup when wallet changes or app closes.
     */
    fun shutdown() {
        scope.cancel()
        p2pManager.disconnect()
        chatKeys = null
        isInitialized = false
    }
}
```

### 4.2 Registration Manager

```kotlin
// chat/registry/RegistrationManager.kt
@Singleton
class RegistrationManager @Inject constructor(
    private val walletBridge: WalletBridge,
    private val chatKeyManager: ChatKeyManager
) {
    private val registryAddress = "0x..." // MumbleChatRegistry on Ramestta
    
    /**
     * Check if a wallet is registered.
     */
    suspend fun isRegistered(walletAddress: String): Boolean {
        val web3j = walletBridge.getWeb3j()
        val registry = MumbleChatRegistry.load(registryAddress, web3j, null, DefaultGasProvider())
        
        return try {
            registry.isRegistered(walletAddress).send()
        } catch (e: Exception) {
            Timber.e(e, "Failed to check registration")
            false
        }
    }
    
    /**
     * Register current wallet for chat.
     */
    suspend fun register(wallet: Wallet): Result<String> {
        return try {
            // 1. Derive chat keys
            val chatKeys = chatKeyManager.deriveChatKeys(wallet)
            
            // 2. Prepare transaction
            val web3j = walletBridge.getWeb3j()
            val credentials = getCredentials(wallet) // From KeyService
            
            val registry = MumbleChatRegistry.load(
                registryAddress, 
                web3j, 
                credentials, 
                DefaultGasProvider()
            )
            
            // 3. Call register function
            val tx = registry.registerChatIdentity(chatKeys.identityPublic).send()
            
            if (tx.isStatusOK) {
                Result.success(tx.transactionHash)
            } else {
                Result.failure(Exception("Transaction failed"))
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Registration failed")
            Result.failure(e)
        }
    }
    
    /**
     * Get chat public key for an address.
     */
    suspend fun getChatPublicKey(walletAddress: String): ByteArray? {
        val web3j = walletBridge.getWeb3j()
        val registry = MumbleChatRegistry.load(registryAddress, web3j, null, DefaultGasProvider())
        
        return try {
            val key = registry.getChatPubKey(walletAddress).send()
            if (key.isNotEmpty()) key else null
        } catch (e: Exception) {
            Timber.e(e, "Failed to get public key")
            null
        }
    }
}
```

---

## 5. UI COMPONENTS

### 5.1 ChatFragment (Replaces Existing)

```kotlin
// chat/ui/ChatFragment.kt
@AndroidEntryPoint
class ChatFragment : Fragment() {
    
    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: ChatViewModel by viewModels()
    private lateinit var conversationAdapter: ConversationListAdapter
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupRecyclerView()
        setupFab()
        observeViewModel()
        
        // Initialize chat
        viewModel.initialize()
    }
    
    private fun setupRecyclerView() {
        conversationAdapter = ConversationListAdapter { conversation ->
            // Open conversation
            val intent = Intent(requireContext(), ConversationActivity::class.java).apply {
                putExtra("conversationId", conversation.id)
                putExtra("peerAddress", conversation.peerAddress)
            }
            startActivity(intent)
        }
        
        binding.recyclerConversations.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = conversationAdapter
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        }
    }
    
    private fun setupFab() {
        binding.fabNewChat.setOnClickListener {
            startActivity(Intent(requireContext(), NewChatActivity::class.java))
        }
    }
    
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.conversations.collect { conversations ->
                        conversationAdapter.submitList(conversations)
                        binding.emptyState.isVisible = conversations.isEmpty()
                    }
                }
                
                launch {
                    viewModel.connectionState.collect { state ->
                        updateConnectionStatus(state)
                    }
                }
                
                launch {
                    viewModel.registrationState.collect { state ->
                        handleRegistrationState(state)
                    }
                }
            }
        }
    }
    
    private fun handleRegistrationState(state: RegistrationState) {
        when (state) {
            is RegistrationState.NotRegistered -> {
                showRegistrationDialog()
            }
            is RegistrationState.Registering -> {
                binding.progressBar.isVisible = true
            }
            is RegistrationState.Registered -> {
                binding.progressBar.isVisible = false
            }
            is RegistrationState.Error -> {
                binding.progressBar.isVisible = false
                Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun showRegistrationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Register for MumbleChat")
            .setMessage("To use MumbleChat, you need to register your wallet on the Ramestta blockchain. This is a one-time transaction.")
            .setPositiveButton("Register") { _, _ ->
                viewModel.register()
            }
            .setNegativeButton("Later", null)
            .show()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
```

### 5.2 Conversation Activity

```kotlin
// chat/ui/conversation/ConversationActivity.kt
@AndroidEntryPoint
class ConversationActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityConversationBinding
    private val viewModel: ConversationViewModel by viewModels()
    private lateinit var messageAdapter: MessageListAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConversationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        val conversationId = intent.getStringExtra("conversationId")!!
        val peerAddress = intent.getStringExtra("peerAddress")!!
        
        setupToolbar(peerAddress)
        setupRecyclerView()
        setupMessageInput()
        
        viewModel.loadConversation(conversationId)
        observeViewModel()
    }
    
    private fun setupToolbar(peerAddress: String) {
        binding.toolbar.title = formatAddress(peerAddress)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }
    
    private fun setupRecyclerView() {
        messageAdapter = MessageListAdapter(viewModel.currentWalletAddress)
        
        binding.recyclerMessages.apply {
            layoutManager = LinearLayoutManager(this@ConversationActivity).apply {
                stackFromEnd = true
            }
            adapter = messageAdapter
        }
    }
    
    private fun setupMessageInput() {
        binding.buttonSend.setOnClickListener {
            val text = binding.editMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                viewModel.sendMessage(text)
                binding.editMessage.text?.clear()
            }
        }
        
        // Typing indicator
        binding.editMessage.addTextChangedListener {
            viewModel.onTyping(it?.isNotEmpty() == true)
        }
    }
    
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.messages.collect { messages ->
                        messageAdapter.submitList(messages)
                        // Scroll to bottom on new message
                        if (messages.isNotEmpty()) {
                            binding.recyclerMessages.smoothScrollToPosition(messages.size - 1)
                        }
                    }
                }
                
                launch {
                    viewModel.peerTyping.collect { isTyping ->
                        binding.typingIndicator.isVisible = isTyping
                    }
                }
                
                launch {
                    viewModel.sendingState.collect { state ->
                        binding.buttonSend.isEnabled = state !is SendingState.Sending
                    }
                }
            }
        }
    }
    
    private fun formatAddress(address: String): String {
        return "${address.take(6)}...${address.takeLast(4)}"
    }
}
```

---

## 6. MANIFEST & PERMISSIONS

```xml
<!-- AndroidManifest.xml additions -->
<manifest>
    <!-- Permissions for P2P networking -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
    
    <!-- For relay service -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    
    <!-- For backup -->
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" 
        android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" 
        android:maxSdkVersion="29" />
    
    <application>
        <!-- Relay Service -->
        <service
            android:name=".chat.relay.RelayService"
            android:foregroundServiceType="dataSync"
            android:exported="false" />
        
        <!-- Chat Activities -->
        <activity
            android:name=".chat.ui.conversation.ConversationActivity"
            android:parentActivityName=".ui.HomeActivity"
            android:windowSoftInputMode="adjustResize" />
        
        <activity
            android:name=".chat.ui.newchat.NewChatActivity"
            android:parentActivityName=".ui.HomeActivity" />
        
        <activity
            android:name=".chat.ui.group.CreateGroupActivity"
            android:parentActivityName=".ui.HomeActivity" />
        
        <activity
            android:name=".chat.ui.group.GroupInfoActivity"
            android:parentActivityName=".chat.ui.conversation.ConversationActivity" />
        
        <!-- Background Workers -->
        <provider
            android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            android:exported="false"
            tools:node="merge">
            <meta-data
                android:name="com.ramapay.app.chat.sync.SyncWorkerInitializer"
                android:value="androidx.startup" />
        </provider>
    </application>
</manifest>
```

---

## 7. GRADLE DEPENDENCIES

```kotlin
// app/build.gradle additions
dependencies {
    // Room Database
    implementation "androidx.room:room-runtime:2.6.1"
    implementation "androidx.room:room-ktx:2.6.1"
    kapt "androidx.room:room-compiler:2.6.1"
    
    // Encrypted Storage
    implementation "androidx.security:security-crypto:1.1.0-alpha06"
    
    // Cryptography
    implementation "org.bouncycastle:bcprov-jdk15on:1.70"
    implementation "org.whispersystems:curve25519-java:0.5.0"
    
    // P2P Networking (options)
    // Option 1: Custom implementation
    implementation "io.netty:netty-all:4.1.100.Final"
    
    // Option 2: libp2p (if using)
    // implementation "io.libp2p:jvm-libp2p:0.x.x"
    
    // Protocol Buffers
    implementation "com.google.protobuf:protobuf-kotlin-lite:3.24.0"
    
    // WorkManager for background sync
    implementation "androidx.work:work-runtime-ktx:2.9.0"
    
    // Web3j for contract interaction
    implementation "org.web3j:core:4.9.8"
    
    // Image loading (for avatars)
    implementation "io.coil-kt:coil:2.5.0"
}
```
