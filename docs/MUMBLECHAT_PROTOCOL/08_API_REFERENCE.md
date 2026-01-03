# MumbleChat Protocol - API Reference

## Part 8 of 8 | Updated January 2026

---

## 0. DEPLOYED CONTRACT ADDRESSES

### Ramestta Mainnet (Chain ID: 1370)

| Contract | Type | Proxy Address | Implementation |
|----------|------|---------------|----------------|
| **MCTToken V3** | UUPS Proxy | `0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE` | `0xC76ea6934D24615E9A348C5eF5Aed54E638A5AAD` |
| **MumbleChatRegistry V2** | UUPS Proxy | `0x4f8D4955F370881B05b68D2344345E749d8632e3` | `0xC69C387a67324A08d1410aEB770dB0AC18c9ad15` |

### Network Configuration

```javascript
const RAMESTTA_CONFIG = {
    chainId: 1370,
    rpcUrl: "https://blockchain.ramestta.com",
    explorer: "https://ramascan.com",
    nativeToken: "RAMA"
};
```

---

## 1. PROTOCOL MESSAGE FORMATS

### 1.1 Message Envelope

All messages in MumbleChat use a standard envelope format:

```protobuf
// protocol/messages.proto

syntax = "proto3";
package mumblechat.protocol;

// Standard message envelope
message MessageEnvelope {
    string version = 1;           // Protocol version "1.0"
    MessageType type = 2;         // Message type
    bytes payload = 3;            // Encrypted or plaintext payload
    bytes signature = 4;          // Ed25519 signature of payload
    string sender = 5;            // Sender wallet address
    int64 timestamp = 6;          // Unix timestamp (ms)
    string message_id = 7;        // Unique message ID (UUID)
}

enum MessageType {
    HANDSHAKE = 0;
    KEY_EXCHANGE = 1;
    DIRECT_MESSAGE = 2;
    GROUP_MESSAGE = 3;
    DELIVERY_ACK = 4;
    READ_RECEIPT = 5;
    TYPING_INDICATOR = 6;
    RELAY_FORWARD = 7;
    PEER_DISCOVERY = 8;
    GROUP_KEY_ROTATION = 9;
    SYNC_REQUEST = 10;
    SYNC_RESPONSE = 11;
}
```

### 1.2 Message Types

```protobuf
// Direct Message
message DirectMessage {
    string recipient = 1;         // Recipient wallet address
    bytes encrypted_content = 2;  // AES-256-GCM encrypted content
    bytes nonce = 3;              // GCM nonce (12 bytes)
    ContentType content_type = 4;
    string reply_to = 5;          // Optional: Reply to message ID
}

enum ContentType {
    TEXT = 0;
    IMAGE = 1;
    FILE = 2;
    AUDIO = 3;
    STICKER = 4;
}

// Group Message
message GroupMessage {
    string group_id = 1;
    bytes encrypted_content = 2;  // AES-256-GCM with group key
    bytes nonce = 3;
    uint32 key_version = 4;       // Group key version used
    ContentType content_type = 5;
    string reply_to = 6;
}

// Key Exchange
message KeyExchange {
    bytes ephemeral_public = 1;   // X25519 ephemeral key
    bytes identity_public = 2;    // Ed25519 identity key
    int64 expires_at = 3;         // Key expiration timestamp
}

// Delivery Acknowledgment
message DeliveryAck {
    string message_id = 1;        // ID of delivered message
    int64 delivered_at = 2;
}

// Read Receipt
message ReadReceipt {
    repeated string message_ids = 1;  // List of read message IDs
    int64 read_at = 2;
}

// Typing Indicator
message TypingIndicator {
    string conversation_id = 1;   // Conversation or group ID
    bool is_typing = 2;
}

// Relay Forward (for offline delivery)
message RelayForward {
    string final_recipient = 1;   // Ultimate recipient
    bytes encrypted_envelope = 2; // Original encrypted envelope
    int64 expires_at = 3;         // TTL for relay storage
    uint32 relay_hops = 4;        // Number of relay hops
}

// Peer Discovery
message PeerDiscovery {
    repeated PeerInfo known_peers = 1;
}

message PeerInfo {
    string wallet_address = 1;
    string multiaddr = 2;         // libp2p multiaddress
    int64 last_seen = 3;
    bool is_relay = 4;
}

// Group Key Rotation
message GroupKeyRotation {
    string group_id = 1;
    uint32 new_key_version = 2;
    repeated EncryptedGroupKey member_keys = 3;
}

message EncryptedGroupKey {
    string member_address = 1;
    bytes encrypted_key = 2;      // Group key encrypted to member's pubkey
}

// Sync Request (multi-device)
message SyncRequest {
    int64 since_timestamp = 1;    // Get messages since this time
    string device_id = 2;
    repeated string conversation_ids = 3; // Optional: specific conversations
}

// Sync Response
message SyncResponse {
    repeated MessageEnvelope messages = 1;
    int64 sync_timestamp = 2;
    bool has_more = 3;
}
```

---

## 2. KOTLIN INTERFACES

### 2.1 Chat Service Interface

```kotlin
// chat/core/ChatServiceInterface.kt

interface IChatService {
    
    /**
     * Initialize the chat service for the current wallet.
     */
    suspend fun initialize(): Result<Unit>
    
    /**
     * Shutdown and cleanup.
     */
    fun shutdown()
    
    /**
     * Check if service is initialized.
     */
    fun isInitialized(): Boolean
    
    /**
     * Check if current wallet is registered.
     */
    suspend fun isRegistered(): Boolean
    
    /**
     * Register current wallet for chat.
     */
    suspend fun register(): Result<String>
    
    // ============ Direct Messages ============
    
    /**
     * Send a direct message.
     */
    suspend fun sendMessage(
        recipientAddress: String,
        content: String,
        contentType: MessageType = MessageType.TEXT,
        replyTo: String? = null
    ): Result<MessageEntity>
    
    /**
     * Get messages for a conversation.
     */
    fun getMessages(conversationId: String): Flow<List<MessageEntity>>
    
    /**
     * Mark messages as read.
     */
    suspend fun markAsRead(conversationId: String)
    
    /**
     * Delete a message (local only).
     */
    suspend fun deleteMessage(messageId: String): Result<Unit>
    
    // ============ Conversations ============
    
    /**
     * Get all conversations.
     */
    fun getConversations(): Flow<List<ConversationEntity>>
    
    /**
     * Get or create conversation with a peer.
     */
    suspend fun getOrCreateConversation(peerAddress: String): ConversationEntity
    
    /**
     * Delete a conversation.
     */
    suspend fun deleteConversation(conversationId: String): Result<Unit>
    
    /**
     * Mute/unmute conversation.
     */
    suspend fun setConversationMuted(conversationId: String, muted: Boolean)
    
    // ============ Groups ============
    
    /**
     * Create a new group.
     */
    suspend fun createGroup(
        name: String,
        memberAddresses: List<String>,
        isPublic: Boolean = false
    ): Result<GroupEntity>
    
    /**
     * Send a group message.
     */
    suspend fun sendGroupMessage(
        groupId: String,
        content: String,
        contentType: MessageType = MessageType.TEXT,
        replyTo: String? = null
    ): Result<MessageEntity>
    
    /**
     * Get group info.
     */
    suspend fun getGroup(groupId: String): GroupEntity?
    
    /**
     * Get group messages.
     */
    fun getGroupMessages(groupId: String): Flow<List<MessageEntity>>
    
    /**
     * Add member to group.
     */
    suspend fun addGroupMember(groupId: String, memberAddress: String): Result<Unit>
    
    /**
     * Remove member from group.
     */
    suspend fun removeGroupMember(groupId: String, memberAddress: String): Result<Unit>
    
    /**
     * Leave group.
     */
    suspend fun leaveGroup(groupId: String): Result<Unit>
    
    /**
     * Get all groups.
     */
    fun getGroups(): Flow<List<GroupEntity>>
    
    // ============ Connection ============
    
    /**
     * Get connection state.
     */
    fun getConnectionState(): Flow<ConnectionState>
    
    /**
     * Get peer online status.
     */
    suspend fun isPeerOnline(address: String): Boolean
    
    /**
     * Send typing indicator.
     */
    suspend fun sendTypingIndicator(conversationId: String, isTyping: Boolean)
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

enum class MessageType {
    TEXT,
    IMAGE,
    FILE,
    AUDIO,
    STICKER
}
```

### 2.2 Encryption Interface

```kotlin
// chat/crypto/MessageEncryptionInterface.kt

interface IMessageEncryption {
    
    /**
     * Encrypt a message for a specific recipient.
     * Uses X25519 ECDH + AES-256-GCM.
     */
    fun encryptMessage(
        plaintext: String,
        senderPrivateKey: ByteArray,
        recipientPublicKey: ByteArray
    ): EncryptedMessage
    
    /**
     * Decrypt a message from a sender.
     */
    fun decryptMessage(
        encrypted: EncryptedMessage,
        recipientPrivateKey: ByteArray,
        senderPublicKey: ByteArray
    ): String
    
    /**
     * Encrypt with a group key.
     */
    fun encryptWithGroupKey(
        plaintext: String,
        groupKey: ByteArray
    ): EncryptedMessage
    
    /**
     * Decrypt with a group key.
     */
    fun decryptWithGroupKey(
        encrypted: EncryptedMessage,
        groupKey: ByteArray
    ): String
    
    /**
     * Generate a random group key.
     */
    fun generateGroupKey(): ByteArray
    
    /**
     * Encrypt a group key for a specific member.
     */
    fun encryptGroupKeyForMember(
        groupKey: ByteArray,
        senderPrivateKey: ByteArray,
        memberPublicKey: ByteArray
    ): ByteArray
    
    /**
     * Decrypt a group key.
     */
    fun decryptGroupKey(
        encryptedKey: ByteArray,
        recipientPrivateKey: ByteArray,
        senderPublicKey: ByteArray
    ): ByteArray
}

data class EncryptedMessage(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val ephemeralPublicKey: ByteArray? = null  // For forward secrecy
)
```

### 2.3 P2P Manager Interface

```kotlin
// chat/network/P2PManagerInterface.kt

interface IP2PManager {
    
    /**
     * Initialize with chat keys.
     */
    fun initialize(keys: ChatKeyPair)
    
    /**
     * Connect to P2P network.
     */
    suspend fun connect(): Result<Unit>
    
    /**
     * Disconnect from network.
     */
    fun disconnect()
    
    /**
     * Send a message to a peer.
     * Returns delivery status.
     */
    suspend fun sendMessage(
        recipientAddress: String,
        encrypted: EncryptedMessage,
        messageId: String
    ): DeliveryResult
    
    /**
     * Send a group message to a member.
     */
    suspend fun sendGroupMessage(
        memberAddress: String,
        groupId: String,
        encrypted: EncryptedMessage,
        signature: ByteArray
    ): DeliveryResult
    
    /**
     * Send delivery acknowledgment.
     */
    suspend fun sendDeliveryAck(peerAddress: String, messageId: String)
    
    /**
     * Send read receipt.
     */
    suspend fun sendReadReceipt(peerAddress: String, messageIds: List<String>)
    
    /**
     * Send typing indicator.
     */
    suspend fun sendTypingIndicator(peerAddress: String, conversationId: String, isTyping: Boolean)
    
    /**
     * Check if peer is online.
     */
    suspend fun isPeerOnline(address: String): Boolean
    
    /**
     * Get peer's multiaddress.
     */
    suspend fun getPeerAddress(walletAddress: String): String?
    
    /**
     * Flow of incoming messages.
     */
    val incomingMessages: Flow<IncomingMessage>
    
    /**
     * Flow of delivery acknowledgments.
     */
    val deliveryAcks: Flow<DeliveryAck>
    
    /**
     * Flow of typing indicators.
     */
    val typingIndicators: Flow<TypingIndicator>
    
    /**
     * Connection state.
     */
    val connectionState: StateFlow<ConnectionState>
}

data class DeliveryResult(
    val direct: Boolean,      // Delivered directly to peer
    val relayed: Boolean,     // Sent to relay for offline delivery
    val relayAddress: String? // Which relay is holding the message
)

data class IncomingMessage(
    val messageId: String,
    val senderAddress: String,
    val encrypted: EncryptedMessage,
    val contentType: String,
    val timestamp: Long,
    val groupId: String? = null
)

data class TypingIndicator(
    val senderAddress: String,
    val conversationId: String,
    val isTyping: Boolean
)
```

### 2.4 Backup Manager Interface

```kotlin
// chat/backup/BackupManagerInterface.kt

interface IBackupManager {
    
    /**
     * Create a full backup.
     */
    suspend fun createBackup(password: String): Result<BackupFile>
    
    /**
     * Restore from a backup file.
     */
    suspend fun restoreBackup(backupFile: Uri, password: String): Result<Unit>
    
    /**
     * Auto-discover backup files on device.
     */
    suspend fun discoverBackups(): List<BackupFileInfo>
    
    /**
     * Schedule automatic backups.
     */
    fun scheduleAutoBackup(interval: Duration)
    
    /**
     * Cancel automatic backups.
     */
    fun cancelAutoBackup()
    
    /**
     * Export backup to cloud.
     */
    suspend fun exportToCloud(backup: BackupFile, destination: CloudDestination): Result<Uri>
    
    /**
     * Import backup from cloud.
     */
    suspend fun importFromCloud(source: CloudDestination): Result<BackupFile>
}

data class BackupFile(
    val uri: Uri,
    val size: Long,
    val createdAt: Long,
    val walletAddress: String,
    val messageCount: Int,
    val conversationCount: Int,
    val checksum: String
)

data class BackupFileInfo(
    val uri: Uri,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val walletAddress: String?  // Null if can't determine
)

enum class CloudDestination {
    GOOGLE_DRIVE,
    LOCAL_STORAGE
}
```

---

## 3. CONTRACT INTERFACES

### 3.1 Registry Interface (Kotlin)

```kotlin
// chat/registry/RegistryInterface.kt

interface IChatRegistry {
    
    /**
     * Check if wallet is registered.
     */
    suspend fun isRegistered(address: String): Boolean
    
    /**
     * Register chat identity.
     */
    suspend fun register(
        identityPubKey: ByteArray,
        sessionPubKey: ByteArray
    ): Result<TransactionReceipt>
    
    /**
     * Update session key.
     */
    suspend fun updateSessionKey(newKey: ByteArray): Result<TransactionReceipt>
    
    /**
     * Get identity public key.
     */
    suspend fun getIdentityPubKey(address: String): ByteArray?
    
    /**
     * Get session public key.
     */
    suspend fun getSessionPubKey(address: String): ByteArray?
    
    /**
     * Batch get session keys.
     */
    suspend fun batchGetSessionKeys(addresses: List<String>): List<ByteArray?>
    
    /**
     * Deactivate identity.
     */
    suspend fun deactivate(): Result<TransactionReceipt>
    
    /**
     * Reactivate identity.
     */
    suspend fun reactivate(): Result<TransactionReceipt>
}
```

### 3.2 Relay Staking Interface (Kotlin)

```kotlin
// chat/relay/RelayStakingInterface.kt

interface IRelayStaking {
    
    /**
     * Get list of active relays.
     */
    suspend fun getActiveRelays(): List<RelayInfo>
    
    /**
     * Select best relays for a message.
     */
    suspend fun selectRelays(count: Int = 3): List<RelayInfo>
    
    /**
     * Get relay by address.
     */
    suspend fun getRelay(address: String): RelayNode?
    
    /**
     * Register as a relay (requires stake).
     */
    suspend fun registerAsRelay(
        stakeAmount: BigInteger,
        endpoint: String
    ): Result<TransactionReceipt>
    
    /**
     * Increase relay stake.
     */
    suspend fun increaseStake(amount: BigInteger): Result<TransactionReceipt>
    
    /**
     * Request stake unlock.
     */
    suspend fun requestUnlock(): Result<TransactionReceipt>
    
    /**
     * Withdraw stake after lockup.
     */
    suspend fun withdrawStake(): Result<TransactionReceipt>
    
    /**
     * Claim relay rewards.
     */
    suspend fun claimRewards(): Result<TransactionReceipt>
    
    /**
     * Send heartbeat.
     */
    suspend fun heartbeat(): Result<TransactionReceipt>
    
    /**
     * Get pending rewards.
     */
    suspend fun getPendingRewards(): BigInteger
    
    /**
     * Get total staked.
     */
    suspend fun getTotalStaked(): BigInteger
}

data class RelayInfo(
    val address: String,
    val endpoint: String,
    val stakedAmount: BigInteger,
    val successRate: Int,         // 0-10000 basis points
    val uptime: Int,              // 0-10000 basis points
    val isActive: Boolean
)

data class RelayNode(
    val owner: String,
    val stakedAmount: BigInteger,
    val stakedAt: Long,
    val unlockRequestTime: Long,
    val messagesRelayed: Long,
    val messagesDelivered: Long,
    val totalUptime: Long,
    val lastHeartbeat: Long,
    val pendingRewards: BigInteger,
    val totalRewardsClaimed: BigInteger,
    val isActive: Boolean,
    val isSlashed: Boolean,
    val slashedAmount: BigInteger
)
```

### 3.3 MCT Token Interface (Kotlin)

```kotlin
// chat/token/MCTTokenInterface.kt

interface IMCTToken {
    
    /**
     * Get token balance.
     */
    suspend fun balanceOf(address: String): BigInteger
    
    /**
     * Get total supply.
     */
    suspend fun totalSupply(): BigInteger
    
    /**
     * Transfer tokens.
     */
    suspend fun transfer(
        to: String,
        amount: BigInteger
    ): Result<TransactionReceipt>
    
    /**
     * Approve spender.
     */
    suspend fun approve(
        spender: String,
        amount: BigInteger
    ): Result<TransactionReceipt>
    
    /**
     * Get allowance.
     */
    suspend fun allowance(owner: String, spender: String): BigInteger
    
    /**
     * Get relay pool unlocked amount.
     */
    suspend fun getRelayPoolUnlocked(): BigInteger
    
    /**
     * Get dev pool unlocked amount.
     */
    suspend fun getDevPoolUnlocked(): BigInteger
}
```

---

## 4. ERROR CODES

### 4.1 Protocol Errors

| Code | Name | Description |
|------|------|-------------|
| 1001 | NOT_REGISTERED | Wallet not registered for chat |
| 1002 | RECIPIENT_NOT_REGISTERED | Recipient wallet not registered |
| 1003 | INVALID_SIGNATURE | Message signature verification failed |
| 1004 | MESSAGE_EXPIRED | Message TTL exceeded |
| 1005 | DECRYPTION_FAILED | Could not decrypt message |
| 1006 | INVALID_KEY | Invalid public/private key |
| 1007 | KEY_MISMATCH | Key doesn't match expected format |
| 1008 | INVALID_MESSAGE_FORMAT | Protocol buffer parse error |
| 1009 | UNSUPPORTED_VERSION | Protocol version not supported |
| 1010 | DUPLICATE_MESSAGE | Message ID already processed |

### 4.2 Network Errors

| Code | Name | Description |
|------|------|-------------|
| 2001 | PEER_NOT_FOUND | Cannot find peer in DHT |
| 2002 | CONNECTION_FAILED | Failed to connect to peer |
| 2003 | CONNECTION_TIMEOUT | Connection timed out |
| 2004 | RELAY_UNAVAILABLE | No relays available |
| 2005 | RELAY_REJECTED | Relay rejected message |
| 2006 | NETWORK_ERROR | General network error |
| 2007 | DHT_ERROR | DHT lookup failed |
| 2008 | HANDSHAKE_FAILED | P2P handshake failed |

### 4.3 Contract Errors

| Code | Name | Description |
|------|------|-------------|
| 3001 | ALREADY_REGISTERED | Wallet already registered |
| 3002 | INSUFFICIENT_STAKE | Not enough MCT staked |
| 3003 | STAKE_LOCKED | Stake still in lockup period |
| 3004 | NOT_ADMIN | Not a group admin |
| 3005 | GROUP_NOT_FOUND | Group doesn't exist |
| 3006 | TRANSACTION_FAILED | Blockchain transaction failed |
| 3007 | INSUFFICIENT_BALANCE | Not enough MCT balance |
| 3008 | GAS_ESTIMATION_FAILED | Could not estimate gas |

### 4.4 Backup Errors

| Code | Name | Description |
|------|------|-------------|
| 4001 | INVALID_PASSWORD | Wrong backup password |
| 4002 | CORRUPTED_BACKUP | Backup file is corrupted |
| 4003 | VERSION_MISMATCH | Backup from incompatible version |
| 4004 | WALLET_MISMATCH | Backup for different wallet |
| 4005 | STORAGE_FULL | Not enough storage space |
| 4006 | PERMISSION_DENIED | Storage permission denied |

---

## 5. EVENTS & CALLBACKS

### 5.1 Chat Event Listener

```kotlin
// chat/core/ChatEventListener.kt

interface ChatEventListener {
    
    /**
     * Called when a new message is received.
     */
    fun onMessageReceived(message: MessageEntity)
    
    /**
     * Called when message delivery is confirmed.
     */
    fun onMessageDelivered(messageId: String, timestamp: Long)
    
    /**
     * Called when message is read by recipient.
     */
    fun onMessageRead(messageId: String, timestamp: Long)
    
    /**
     * Called when peer starts/stops typing.
     */
    fun onTypingChanged(peerAddress: String, conversationId: String, isTyping: Boolean)
    
    /**
     * Called when peer comes online.
     */
    fun onPeerOnline(peerAddress: String)
    
    /**
     * Called when peer goes offline.
     */
    fun onPeerOffline(peerAddress: String)
    
    /**
     * Called on connection state change.
     */
    fun onConnectionStateChanged(state: ConnectionState)
    
    /**
     * Called when added to a group.
     */
    fun onGroupInvite(groupId: String, inviterAddress: String, groupName: String)
    
    /**
     * Called when removed from a group.
     */
    fun onGroupRemoved(groupId: String)
    
    /**
     * Called when group key is rotated.
     */
    fun onGroupKeyRotated(groupId: String, newVersion: Int)
    
    /**
     * Called on any error.
     */
    fun onError(code: Int, message: String)
}
```

### 5.2 Relay Event Listener

```kotlin
// chat/relay/RelayEventListener.kt

interface RelayEventListener {
    
    /**
     * Called when relay service starts.
     */
    fun onRelayStarted()
    
    /**
     * Called when relay service stops.
     */
    fun onRelayStopped()
    
    /**
     * Called when a message is stored for relay.
     */
    fun onMessageStored(messageHash: ByteArray, recipientAddress: String)
    
    /**
     * Called when a relayed message is delivered.
     */
    fun onMessageDelivered(messageHash: ByteArray, recipientAddress: String)
    
    /**
     * Called when a relayed message expires.
     */
    fun onMessageExpired(messageHash: ByteArray)
    
    /**
     * Called when rewards are earned.
     */
    fun onRewardEarned(amount: BigInteger, total: BigInteger)
    
    /**
     * Called on relay error.
     */
    fun onRelayError(code: Int, message: String)
}
```

---

## 6. CONFIGURATION

### 6.1 Chat Configuration

```kotlin
// chat/core/ChatConfig.kt

object ChatConfig {
    
    // Protocol
    const val PROTOCOL_VERSION = "1.0"
    const val MAX_MESSAGE_SIZE = 64 * 1024      // 64 KB
    const val MAX_CONTENT_SIZE = 32 * 1024      // 32 KB text
    const val MAX_GROUP_MEMBERS = 256
    
    // Timeouts
    const val CONNECTION_TIMEOUT_MS = 10_000L
    const val MESSAGE_TIMEOUT_MS = 30_000L
    const val HEARTBEAT_INTERVAL_MS = 60_000L
    const val TYPING_TIMEOUT_MS = 5_000L
    
    // Retry
    const val MAX_RETRY_ATTEMPTS = 3
    const val RETRY_DELAY_MS = 1_000L
    
    // TTL
    const val MESSAGE_TTL_HOURS = 72            // 3 days
    const val RELAY_MESSAGE_TTL_HOURS = 168     // 7 days
    
    // DHT
    const val DHT_BOOTSTRAP_NODES = listOf(
        "/ip4/dht1.mumblechat.io/tcp/4001/p2p/QmXx...",
        "/ip4/dht2.mumblechat.io/tcp/4001/p2p/QmYy...",
        "/ip4/dht3.mumblechat.io/tcp/4001/p2p/QmZz..."
    )
    const val DHT_BUCKET_SIZE = 20
    
    // Contract Addresses (Mainnet)
    object Mainnet {
        const val CHAIN_ID = 1370
        const val RPC_URL = "https://blockchain.ramestta.com"
        const val MCT_TOKEN = "0x..."
        const val REGISTRY = "0x..."
        const val RELAY_STAKING = "0x..."
        const val GROUP_REGISTRY = "0x..."
    }
    
    // Contract Addresses (Testnet)
    object Testnet {
        const val CHAIN_ID = 1369
        const val RPC_URL = "https://testnet.ramestta.com"
        const val MCT_TOKEN = "0x..."
        const val REGISTRY = "0x..."
        const val RELAY_STAKING = "0x..."
        const val GROUP_REGISTRY = "0x..."
    }
    
    // Backup
    const val BACKUP_FILE_EXTENSION = ".mumblechat.backup"
    const val AUTO_BACKUP_INTERVAL_HOURS = 24
    const val BACKUP_RETENTION_DAYS = 30
    
    // Relay
    const val MIN_RELAY_STAKE = 10_000          // 10,000 MCT
    const val RELAY_LOCKUP_DAYS = 7
    const val REWARD_PER_MESSAGE = 0.001        // MCT
}
```

---

## 7. MIGRATION GUIDE

### 7.1 From XMTP to MumbleChat

```kotlin
// Migration helper for existing XMTP users

class XMTPMigrationHelper @Inject constructor(
    private val chatService: IChatService,
    private val registrationManager: RegistrationManager
) {
    
    /**
     * Check if migration is needed.
     */
    suspend fun needsMigration(): Boolean {
        // Check if using old XMTP system
        return !registrationManager.isRegistered(walletAddress)
    }
    
    /**
     * Perform migration.
     * 1. Register on MumbleChat
     * 2. XMTP history is NOT migrated (different protocol)
     */
    suspend fun migrate(): Result<Unit> {
        return try {
            // Register with new system
            val txHash = chatService.register()
            
            // Show migration complete
            Result.success(Unit)
            
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

---

## 8. SMART CONTRACT API (V2/V3)

### 8.1 MCTToken V3 Interface

```solidity
// MCTToken V3 - Key Functions

// ============ Read Functions ============

function totalSupply() external view returns (uint256);
function maxSupply() external view returns (uint256);
function feePool() external view returns (uint256);
function totalRewardsMinted() external view returns (uint256);
function mintedToday() external view returns (uint256);
function activeRelayCount() external view returns (uint256);

// Get current reward per 1000 messages (accounts for halving)
function getCurrentRewardRate() external view returns (uint256);

// ============ Relay Node Functions (called by Registry) ============

// Mint reward for relaying messages (1x only, no tier bonus)
function mintRelayReward(address relayNode, uint256 messagesRelayed) 
    external returns (uint256 reward);

// Claim share of fee pool (tier bonus applies)
function claimFeeReward(address relayNode, uint256 tierMultiplier) 
    external returns (uint256 reward);

// ============ Governance ============

// Create proposal to change max supply (requires 90% vote)
function createProposal(uint256 newMaxSupply) external returns (uint256 proposalId);

// Vote on proposal
function vote(uint256 proposalId, bool support) external;

// Execute passed proposal
function executeProposal(uint256 proposalId) external;

// ============ Events ============

event RelayRewardMinted(address indexed relayNode, uint256 amount, uint256 messagesRelayed);
event FeeRewardClaimed(address indexed relayNode, uint256 amount);
event HalvingOccurred(uint256 newRewardAmount, uint256 halvingCount);
event TransferFeeCollected(uint256 amount);
event ProposalCreated(uint256 indexed proposalId, uint256 newMaxSupply, address proposer);
event ProposalExecuted(uint256 indexed proposalId, uint256 newMaxSupply);
```

### 8.2 MumbleChatRegistry V2 Interface

```solidity
// MumbleChatRegistry V2 - Key Functions

// ============ Enums ============

enum NodeTier { Bronze, Silver, Gold, Platinum }

// ============ Read Functions ============

// Get identity info
function identities(address wallet) external view returns (
    bytes32 publicKeyX,
    bytes32 publicKeyY,
    uint256 registeredAt,
    uint256 lastUpdated,
    bool isActive,
    string memory displayName
);

// Get relay node info
function relayNodes(address wallet) external view returns (
    string memory endpoint,
    uint256 stakedAmount,
    uint256 registeredAt,
    uint256 messagesRelayed,
    uint256 rewardsEarned,
    bool isActive,
    uint256 totalUptimeSeconds,
    uint256 lastHeartbeat,
    uint256 currentSessionStart,
    uint256 storageMB,
    uint256 dailyUptimeSeconds,
    uint256 lastDayReset,
    NodeTier tier
);

// Check if wallet is registered
function isRegistered(address wallet) external view returns (bool);

// Get total users
function totalUsers() external view returns (uint256);

// Get total relay nodes
function totalRelayNodes() external view returns (uint256);

// ============ Registration Functions ============

// Register identity
function register(bytes32 publicKeyX, string calldata displayName) external;

// Update display name
function updateDisplayName(string calldata newName) external;

// Update public key
function updatePublicKey(bytes32 newPublicKeyX) external;

// ============ Relay Node Functions ============

// Register as relay node
function registerAsRelay(string calldata endpoint, uint256 storageMB) external;

// Send heartbeat (for uptime tracking)
function heartbeat() external;

// Update storage capacity
function updateStorage(uint256 newStorageMB) external;

// Report messages relayed (triggers reward)
function reportMessagesRelayed(uint256 count) external returns (uint256 reward);

// Claim fee pool reward
function claimFeePoolReward() external returns (uint256 reward);

// Deactivate relay node
function deactivateRelay() external;

// ============ Events ============

event IdentityRegistered(address indexed wallet, bytes32 publicKeyX, uint256 timestamp);
event IdentityUpdated(address indexed wallet, bytes32 newPublicKeyX, uint256 timestamp);
event RelayNodeRegistered(address indexed node, string endpoint, uint256 stakedAmount, uint256 timestamp);
event HeartbeatReceived(address indexed node, uint256 uptimeSeconds, NodeTier tier);
event StorageUpdated(address indexed node, uint256 storageMB, NodeTier newTier);
event TierChanged(address indexed node, NodeTier oldTier, NodeTier newTier);
event MessageRelayed(address indexed relayNode, bytes32 messageHash, uint256 reward);
```

### 8.3 Tier Thresholds (Constants)

```solidity
// Uptime thresholds (seconds per day)
uint256 public constant BRONZE_UPTIME = 0;           // 0 hours
uint256 public constant SILVER_UPTIME = 4 hours;     // 14,400 seconds
uint256 public constant GOLD_UPTIME = 8 hours;       // 28,800 seconds
uint256 public constant PLATINUM_UPTIME = 16 hours;  // 57,600 seconds

// Storage thresholds (in MB)
uint256 public constant BRONZE_STORAGE = 0;
uint256 public constant SILVER_STORAGE = 50;
uint256 public constant GOLD_STORAGE = 200;
uint256 public constant PLATINUM_STORAGE = 500;

// Reward multipliers (in basis points, 100 = 1x)
uint256 public constant BRONZE_MULTIPLIER = 100;   // 1.0x
uint256 public constant SILVER_MULTIPLIER = 150;   // 1.5x
uint256 public constant GOLD_MULTIPLIER = 200;     // 2.0x
uint256 public constant PLATINUM_MULTIPLIER = 300; // 3.0x

// Heartbeat timeout
uint256 public constant HEARTBEAT_TIMEOUT = 5 minutes;
```

---

## 9. APPENDIX

### 8.1 Key Derivation Paths

```
Wallet Seed (BIP-39)
│
├── m/44'/60'/0'/0/0   (Ethereum address - existing)
│
└── MUMBLECHAT_KEY_DERIVATION_V1 (signed message)
    │
    └── keccak256(signature)
        │
        ├── HKDF-Expand("identity")  → Ed25519 seed → Identity Key Pair
        │                                            ├── Public: 32 bytes
        │                                            └── Private: 64 bytes
        │
        └── HKDF-Expand("session")   → X25519 seed  → Session Key Pair
                                                     ├── Public: 32 bytes
                                                     └── Private: 32 bytes
```

### 8.2 Message Encryption Flow

```
1. Sender prepares message
   ↓
2. Generate ephemeral X25519 key pair
   ↓
3. ECDH: ephemeral_private × recipient_public = shared_secret
   ↓
4. HKDF(shared_secret) = AES key (256 bits)
   ↓
5. AES-256-GCM encrypt(message, key, nonce)
   ↓
6. Envelope = { ciphertext, nonce, ephemeral_public }
   ↓
7. Sign envelope with identity key
   ↓
8. Send to recipient
```

### 8.3 Group Key Distribution

```
Admin creates group:
1. Generate random 256-bit group key
2. For each member:
   a. ECDH with member's session public key
   b. AES encrypt group key
   c. Store encrypted_key[member] = result
3. Broadcast to members

Member joins:
1. Receive encrypted group key
2. ECDH with admin's session public key
3. Decrypt to get group key
4. Store locally

Key rotation (member leaves):
1. Generate new group key
2. Increment key_version on chain
3. Distribute to remaining members
4. Old keys cannot decrypt new messages
```

---

## END OF DOCUMENTATION

This completes the MumbleChat Protocol documentation. All 8 parts cover:

1. **Overview** - Project introduction and architecture
2. **Identity & Crypto** - Key derivation and encryption
3. **Messaging Protocol** - P2P network and message flow
4. **Relay & Rewards** - Offline delivery and MCT token
5. **Backup System** - Backup, restore, and auto-discovery
6. **Android Implementation** - Kotlin code structure
7. **Smart Contracts** - Solidity contracts
8. **API Reference** - Protocol formats and interfaces

**Next Steps:**
1. Deploy smart contracts to Ramestta testnet
2. Implement Android module following doc #6
3. Test P2P connectivity
4. Integrate with existing RamaPay wallet
5. Launch on mainnet
