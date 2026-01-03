# MumbleChat Protocol - Messaging Protocol

## Part 3 of 8 | Updated January 2026

---

## 0. DECENTRALIZED NOTIFICATION SYSTEM

### 0.1 How Notifications Work (No Central Server)

```
┌─────────────────────────────────────────────────────────────────────────┐
│               DECENTRALIZED NOTIFICATION SYSTEM                          │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ═══════════════════════════════════════════════════════════════════    │
│  NO PUSH SERVERS NEEDED:                                                │
│  ═══════════════════════════════════════════════════════════════════    │
│                                                                          │
│  1. WHEN APP IS OPEN (Foreground):                                      │
│     ├── Direct P2P connection maintained                                │
│     ├── Messages arrive in real-time via WebSocket/TCP                  │
│     └── Local notification shown immediately                            │
│                                                                          │
│  2. WHEN APP IS IN BACKGROUND (Android Foreground Service):             │
│     ├── RelayService runs as foreground service                         │
│     ├── Maintains P2P connection with low battery impact                │
│     ├── Creates local notification on new message                       │
│     └── Wakes app when user taps notification                           │
│                                                                          │
│  3. WHEN APP IS CLOSED:                                                 │
│     ├── Messages stored on relay nodes (encrypted)                      │
│     ├── When app opens → syncs from relays                              │
│     ├── Messages delivered with delivery receipts                       │
│     └── Relay earns MCT for successful delivery                         │
│                                                                          │
│  ═══════════════════════════════════════════════════════════════════    │
│  WHY NO FIREBASE/APNs:                                                  │
│  • Firebase = Google's server (centralized, privacy concern)            │
│  • APNs = Apple's server (centralized, iOS only)                        │
│  • MumbleChat = P2P + Relay nodes (decentralized, privacy first)        │
│  ═══════════════════════════════════════════════════════════════════    │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 0.2 Background Service Architecture

```kotlin
// AndroidManifest.xml - Foreground Service Permission
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

// RelayService.kt (simplified)
class RelayService : Service() {
    override fun onCreate() {
        // Start foreground with persistent notification
        startForeground(NOTIFICATION_ID, buildNotification())
    }
    
    private fun onMessageReceived(message: EncryptedMessage) {
        // Decrypt message
        val decrypted = cryptoService.decrypt(message)
        
        // Save to local database
        messageRepository.insert(decrypted)
        
        // Show local notification
        notificationManager.notify(
            message.id.hashCode(),
            buildMessageNotification(decrypted)
        )
    }
}
```

---

## 0.3 MESSAGE STORAGE & DELETION

### Message Retention Policy

```
┌─────────────────────────────────────────────────────────────────────────┐
│                   MESSAGE STORAGE & RETENTION                            │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ═══════════════════════════════════════════════════════════════════    │
│  WHERE MESSAGES ARE STORED:                                             │
│  ═══════════════════════════════════════════════════════════════════    │
│                                                                          │
│  1. YOUR DEVICE (Local Room Database):                                  │
│     ├── Forever (until you delete)                                      │
│     ├── Encrypted with your device key                                  │
│     ├── Only YOU can read them                                          │
│     └── Backed up if you enable backup                                  │
│                                                                          │
│  2. RELAY NODES (Temporary):                                            │
│     ├── TTL: 7 days (default, configurable 1-30 days)                  │
│     ├── Encrypted - relay cannot read content                           │
│     ├── Deleted automatically after TTL expires                         │
│     └── Deleted immediately after successful delivery                   │
│                                                                          │
│  3. BLOCKCHAIN (Registry Only):                                         │
│     ├── Only PUBLIC KEYS stored (for encryption)                        │
│     ├── NO message content on blockchain                                │
│     └── Display name (optional, public)                                 │
│                                                                          │
│  ═══════════════════════════════════════════════════════════════════    │
│  IMPORTANT: Messages are NEVER stored on central servers!               │
│  ═══════════════════════════════════════════════════════════════════    │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Message Deletion Options

```
┌─────────────────────────────────────────────────────────────────────────┐
│                   MESSAGE DELETION OPTIONS                               │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  1. DELETE FOR ME (Local Only):                                         │
│     ├── Removes message from YOUR device only                           │
│     ├── Other person still has their copy                               │
│     ├── Instant - no network needed                                     │
│     └── Method: messageRepository.deleteMessage(messageId)              │
│                                                                          │
│  2. DELETE FOR EVERYONE (P2P Request):                                  │
│     ├── Sends DELETE_REQUEST to recipient                               │
│     ├── Recipient's app removes message IF:                             │
│     │   └── Within 24-hour window (configurable)                        │
│     ├── NOT guaranteed (recipient may be offline/ignore)                │
│     └── This is a REQUEST, not a force delete                           │
│                                                                          │
│  3. DISAPPEARING MESSAGES (Future Feature):                             │
│     ├── Messages auto-delete after set time                             │
│     ├── 5 min, 1 hour, 24 hours, 7 days options                        │
│     ├── Enforced locally on both devices                                │
│     └── Uses expiry timestamp in message envelope                       │
│                                                                          │
│  ═══════════════════════════════════════════════════════════════════    │
│  TRUTH: You cannot force-delete from someone else's device.             │
│  This is a fundamental limitation of any E2E encrypted system.          │
│  ═══════════════════════════════════════════════════════════════════    │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Delete Request Protocol

```protobuf
// DeleteRequest message
message DeleteRequest {
    repeated string message_ids = 1;  // IDs to delete
    int64 requested_at = 2;           // Timestamp
    bytes signature = 3;              // Sender's signature (must be original sender)
}

// Only original sender can request deletion
// Recipient honors if within time window and setting allows
```

---

## 1. NETWORK ARCHITECTURE

### 1.1 Full P2P Design (No Central Servers)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      P2P NETWORK TOPOLOGY                                │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│      ┌─────────┐           ┌─────────┐           ┌─────────┐           │
│      │ Node A  │◄─────────►│ Node B  │◄─────────►│ Node C  │           │
│      │ (Light) │           │ (Relay) │           │ (Light) │           │
│      └────┬────┘           └────┬────┘           └────┬────┘           │
│           │                     │                     │                 │
│           │    ┌────────────────┼────────────────┐    │                 │
│           │    │                │                │    │                 │
│           ▼    ▼                ▼                ▼    ▼                 │
│      ┌─────────────────────────────────────────────────────┐           │
│      │                   DHT (Kademlia)                     │           │
│      │                                                      │           │
│      │  - Peer Discovery                                   │           │
│      │  - Address Resolution                               │           │
│      │  - Relay Node Registry                              │           │
│      │                                                      │           │
│      └─────────────────────────────────────────────────────┘           │
│           ▲    ▲                ▲                ▲    ▲                 │
│           │    │                │                │    │                 │
│           │    └────────────────┼────────────────┘    │                 │
│           │                     │                     │                 │
│      ┌────┴────┐           ┌────┴────┐           ┌────┴────┐           │
│      │ Node D  │◄─────────►│ Node E  │◄─────────►│ Node F  │           │
│      │ (Light) │           │ (Relay) │           │ (Light) │           │
│      └─────────┘           └─────────┘           └─────────┘           │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Node Types

| Type | Description | Capabilities | Rewards |
|------|-------------|--------------|---------|
| **Light Node** | Default for all users | Send/receive own messages | None |
| **Relay Node** | Opt-in service providers | Store messages for offline users | MCT tokens |
| **Bootstrap Node** | Initial network entry | Help new nodes find peers | None (community service) |

### 1.3 Peer Discovery

```
┌─────────────────────────────────────────────────────────────┐
│               PEER DISCOVERY FLOW                            │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  1. APP STARTUP                                             │
│     ├── Load known peers from local storage                 │
│     ├── Connect to hardcoded bootstrap nodes                │
│     └── Join DHT network                                    │
│                                                              │
│  2. FIND RECIPIENT                                          │
│     ├── Hash recipient wallet address                       │
│     │   peerID = keccak256(walletAddress)[:20]             │
│     ├── Query DHT for peerID                               │
│     └── Get IP:Port of recipient (if online)               │
│                                                              │
│  3. IF RECIPIENT OFFLINE                                    │
│     ├── Query DHT for active relay nodes                   │
│     ├── Select relay(s) with best reputation               │
│     └── Send encrypted message to relay                     │
│                                                              │
│  4. MAINTAIN CONNECTIONS                                    │
│     ├── Periodic DHT refresh                               │
│     ├── Announce own presence                              │
│     └── Update peer list                                   │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 1.4 Bootstrap Nodes (Hardcoded)

```kotlin
// P2PConfig.kt
object P2PConfig {
    val BOOTSTRAP_NODES = listOf(
        "/ip4/bootstrap1.mumblechat.com/tcp/9000/p2p/QmBootstrap1...",
        "/ip4/bootstrap2.mumblechat.com/tcp/9000/p2p/QmBootstrap2...",
        "/ip4/bootstrap3.mumblechat.com/tcp/9000/p2p/QmBootstrap3...",
        // Community-run nodes
        "/ip4/node.ramestta.com/tcp/9000/p2p/QmRamestta..."
    )
    
    const val DHT_PROTOCOL = "/mumblechat/kad/1.0.0"
    const val CHAT_PROTOCOL = "/mumblechat/chat/1.0.0"
    const val RELAY_PROTOCOL = "/mumblechat/relay/1.0.0"
}
```

---

## 2. DIRECT MESSAGING (1:1)

### 2.1 Message Flow - Recipient Online

```
┌─────────────────────────────────────────────────────────────┐
│           DIRECT MESSAGE - RECIPIENT ONLINE                  │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  SENDER                                    RECIPIENT         │
│  ──────                                    ─────────         │
│                                                              │
│  1. Compose message                                         │
│     │                                                        │
│  2. Fetch recipient's session public key                    │
│     │  (from DHT or local cache)                            │
│     │                                                        │
│  3. Encrypt message                                         │
│     │  (X25519 + AES-256-GCM)                               │
│     │                                                        │
│  4. Sign envelope                                           │
│     │  (Ed25519 identity key)                               │
│     │                                                        │
│  5. Query DHT for recipient's address                       │
│     │                                                        │
│  6. ──────── Direct P2P Connection ────────►                │
│     │                                                        │
│  7. Send encrypted message                                  │
│     │                                                        │
│  8. ◄──────── Delivery ACK ─────────────────  Received      │
│     │                                                        │
│  9. Store in local DB                       Store in DB     │
│     (status: delivered)                     (status: new)   │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Message Flow - Recipient Offline

```
┌─────────────────────────────────────────────────────────────┐
│           DIRECT MESSAGE - RECIPIENT OFFLINE                 │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  SENDER                RELAY NODE              RECIPIENT     │
│  ──────                ──────────              ─────────     │
│                                                              │
│  1. Compose message                                         │
│     │                                                        │
│  2. Encrypt message                                         │
│     │                                                        │
│  3. Query DHT - recipient offline                           │
│     │                                                        │
│  4. Query DHT for relay nodes                               │
│     │                                                        │
│  5. ──── Send to relay(s) ────►                             │
│     │                          │                             │
│  6. ◄─── Storage ACK ──────────┤ Store encrypted            │
│     │                          │ blob with TTL              │
│  7. Store locally                                           │
│     (status: sent_to_relay)    │                             │
│                                │                             │
│  ═══════ TIME PASSES ══════════╪═════════════════════════   │
│                                │                             │
│                                │              Comes online   │
│                                │                     │       │
│                                │◄── Request pending ─┤       │
│                                │    messages         │       │
│                                │                     │       │
│                                ├── Deliver message ──►       │
│                                │                     │       │
│                                │◄── Delivery ACK ────┤       │
│                                │                     │       │
│                                │ Delete from storage │       │
│                                │ Claim MCT reward    │       │
│                                                      │       │
│                                               Decrypt & show │
│                                               Store locally  │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 2.3 Message Structure

```kotlin
// Message.kt
@Entity(tableName = "messages")
data class Message(
    @PrimaryKey
    val id: String,                    // UUID
    
    val conversationId: String,        // Hash of sorted wallet addresses
    val senderAddress: String,         // 0x...
    val recipientAddress: String,      // 0x...
    
    val contentType: MessageType,      // TEXT, IMAGE, FILE
    val encryptedContent: ByteArray,   // Encrypted payload
    val nonce: ByteArray,              // AES-GCM nonce
    
    val timestamp: Long,               // Unix millis
    val status: MessageStatus,         // SENDING, SENT, DELIVERED, READ
    
    val signature: ByteArray,          // Ed25519 signature
    val relayId: String? = null        // If sent via relay
)

enum class MessageType {
    TEXT, IMAGE, FILE, SYSTEM
}

enum class MessageStatus {
    PENDING,        // Not yet sent
    SENDING,        // In transit
    SENT_DIRECT,    // Delivered directly
    SENT_TO_RELAY,  // Stored on relay
    DELIVERED,      // Confirmed received
    READ,           // Read by recipient
    FAILED          // Delivery failed
}
```

### 2.4 Message Protocol

```protobuf
// mumblechat.proto
syntax = "proto3";

message ChatMessage {
    string version = 1;              // "1.0.0"
    string message_id = 2;           // UUID
    string sender = 3;               // 0x address
    string recipient = 4;            // 0x address (empty for groups)
    string group_id = 5;             // For group messages
    
    MessageType type = 6;
    bytes encrypted_content = 7;
    bytes nonce = 8;
    
    int64 timestamp = 9;
    bytes signature = 10;            // Ed25519 signature of fields 1-9
    
    // For relay storage
    int64 ttl = 11;                  // Seconds until expiry
    bytes recipient_key_hash = 12;   // For relay lookup
}

enum MessageType {
    TEXT = 0;
    IMAGE = 1;
    FILE = 2;
    KEY_EXCHANGE = 3;
    GROUP_KEY_UPDATE = 4;
    READ_RECEIPT = 5;
    TYPING_INDICATOR = 6;
}

message DeliveryAck {
    string message_id = 1;
    int64 timestamp = 2;
    bytes signature = 3;
}
```

---

## 3. GROUP CHAT

### 3.1 Group Structure

```
┌─────────────────────────────────────────────────────────────┐
│                    GROUP STRUCTURE                           │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  GROUP                                                       │
│  ─────                                                       │
│  ├── groupId: bytes32 (keccak256 hash)                      │
│  ├── name: string (encrypted)                               │
│  ├── description: string (encrypted)                        │
│  ├── createdAt: timestamp                                   │
│  ├── createdBy: wallet address                              │
│  │                                                          │
│  ├── MEMBERS                                                │
│  │   ├── member1: { address, role, joinedAt, publicKey }   │
│  │   ├── member2: { address, role, joinedAt, publicKey }   │
│  │   └── member3: { address, role, joinedAt, publicKey }   │
│  │                                                          │
│  ├── ENCRYPTION                                             │
│  │   ├── groupKey: bytes32 (current)                       │
│  │   ├── keyVersion: uint                                  │
│  │   └── encryptedKeysForMembers: map[address]bytes        │
│  │                                                          │
│  └── SETTINGS                                               │
│      ├── onlyAdminsCanPost: bool                           │
│      ├── onlyAdminsCanAddMembers: bool                     │
│      └── autoDeleteAfter: duration (optional)              │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 Group Roles

| Role | Permissions |
|------|-------------|
| **Owner** | All permissions, cannot be removed, can transfer ownership |
| **Admin** | Add/remove members, change settings, delete messages |
| **Member** | Send messages, view history, leave group |

### 3.3 Group Operations

```
┌─────────────────────────────────────────────────────────────┐
│                 CREATE GROUP                                 │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Creator:                                                    │
│  1. Generate random groupKey (32 bytes)                     │
│  2. Generate groupId = keccak256(creator + timestamp + key) │
│  3. For each member:                                        │
│     - Encrypt groupKey with member's session public key     │
│  4. Sign group metadata with identity key                   │
│  5. Broadcast GROUP_CREATED message to all members          │
│                                                              │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                 ADD MEMBER                                   │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Admin:                                                      │
│  1. Verify admin permissions                                │
│  2. Fetch new member's session public key                   │
│  3. Encrypt current groupKey for new member                 │
│  4. Sign MEMBER_ADDED message                               │
│  5. Broadcast to all members + new member                   │
│                                                              │
│  All Members:                                                │
│  - Update local member list                                 │
│                                                              │
│  New Member:                                                 │
│  - Decrypt groupKey                                         │
│  - Request history sync (optional)                          │
│                                                              │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                 REMOVE MEMBER / MEMBER LEAVES                │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Admin (or self for leaving):                               │
│  1. Verify permissions                                      │
│  2. Generate NEW groupKey                                   │
│  3. For each REMAINING member:                              │
│     - Encrypt new groupKey with their session public key    │
│  4. Increment keyVersion                                    │
│  5. Sign MEMBER_REMOVED + KEY_ROTATED message              │
│  6. Broadcast to remaining members                          │
│                                                              │
│  Remaining Members:                                          │
│  - Decrypt new groupKey                                     │
│  - Delete old groupKey                                      │
│  - Update local member list                                 │
│                                                              │
│  Removed Member:                                             │
│  - Cannot decrypt future messages                           │
│  - Local history preserved (can delete manually)            │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 3.4 Group Message Delivery

```
┌─────────────────────────────────────────────────────────────┐
│               GROUP MESSAGE DELIVERY                         │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  SENDER                                                      │
│  ──────                                                      │
│  1. Compose message                                         │
│  2. Encrypt with groupKey                                   │
│  3. Sign with identity key                                  │
│  4. For each member:                                        │
│     ├── If online: send directly via P2P                   │
│     └── If offline: send to relay nodes                     │
│                                                              │
│  RECEIVING MEMBERS                                           │
│  ─────────────────                                           │
│  1. Receive message (direct or from relay)                  │
│  2. Verify signature matches known member                   │
│  3. Decrypt with groupKey                                   │
│  4. Store locally                                           │
│  5. Send delivery ACK                                       │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 3.5 Group Database Schema

```kotlin
// Group.kt
@Entity(tableName = "groups")
data class Group(
    @PrimaryKey
    val id: String,                    // groupId (hex)
    
    val name: String,                  // Encrypted name
    val description: String?,          // Encrypted description
    val avatarUrl: String?,            // IPFS hash or URL
    
    val createdAt: Long,
    val createdBy: String,             // Wallet address
    
    val currentKeyVersion: Int,
    val encryptedGroupKey: ByteArray,  // Encrypted for current wallet
    
    val settings: GroupSettings,
    val myRole: GroupRole,
    
    val lastMessageAt: Long?,
    val unreadCount: Int = 0
)

@Entity(tableName = "group_members")
data class GroupMember(
    @PrimaryKey
    val id: String,                    // groupId + memberAddress
    
    val groupId: String,
    val memberAddress: String,
    val role: GroupRole,
    val joinedAt: Long,
    val sessionPublicKey: ByteArray,
    val displayName: String?           // Optional nickname
)

enum class GroupRole {
    OWNER, ADMIN, MEMBER
}

data class GroupSettings(
    val onlyAdminsCanPost: Boolean = false,
    val onlyAdminsCanAddMembers: Boolean = false,
    val onlyAdminsCanEditInfo: Boolean = true,
    val autoDeleteAfterSeconds: Long? = null
)
```

---

## 4. MESSAGE ORDERING & SYNC

### 4.1 Ordering Strategy

```
┌─────────────────────────────────────────────────────────────┐
│               MESSAGE ORDERING                               │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  PRIMARY: Sender Timestamp                                  │
│  ──────────────────────────                                 │
│  - Each message has sender's local timestamp                │
│  - Displayed in timestamp order                             │
│                                                              │
│  CONFLICT RESOLUTION                                        │
│  ────────────────────                                       │
│  If timestamps are within 1 second:                         │
│  - Secondary sort by message_id (UUID)                      │
│  - Ensures consistent ordering across devices               │
│                                                              │
│  CLOCK SKEW HANDLING                                        │
│  ────────────────────                                       │
│  - Accept messages with timestamp up to 5 minutes in future│
│  - Reject messages with timestamp > 24 hours in past       │
│  - Log warnings for significant skew                        │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 Message Sync

```
┌─────────────────────────────────────────────────────────────┐
│               MULTI-DEVICE SYNC                              │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  SAME WALLET, MULTIPLE DEVICES                              │
│  ─────────────────────────────                              │
│                                                              │
│  Device A                              Device B              │
│  ────────                              ────────              │
│  │                                          │               │
│  │  User sends message                      │               │
│  │       │                                  │               │
│  │       ▼                                  │               │
│  │  Encrypt & send to recipient             │               │
│  │       │                                  │               │
│  │       ├─────── Sync via P2P ────────────►│               │
│  │       │                                  │               │
│  │  Store locally                     Store locally         │
│  │                                          │               │
│  │                                          │               │
│  │                     User receives message│               │
│  │                                          │               │
│  │◄────────── Sync via P2P ─────────────────│               │
│  │                                          │               │
│  │  Store locally                     Store locally         │
│  │                                          │               │
│                                                              │
│  SYNC PROTOCOL                                              │
│  ─────────────                                              │
│  1. Devices with same wallet discover each other via DHT    │
│  2. Establish encrypted channel (same chat keys)            │
│  3. Exchange message IDs to find missing messages           │
│  4. Transfer missing messages                               │
│  5. Periodic sync every 5 minutes when online               │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## 5. READ RECEIPTS & TYPING INDICATORS

### 5.1 Read Receipts

```kotlin
// ReadReceipt.kt
data class ReadReceipt(
    val messageId: String,
    val readBy: String,        // Wallet address
    val readAt: Long,
    val signature: ByteArray   // Sign to prevent spoofing
)

// Sending read receipt
fun sendReadReceipt(message: Message) {
    val receipt = ReadReceipt(
        messageId = message.id,
        readBy = currentWallet.address,
        readAt = System.currentTimeMillis(),
        signature = signWithIdentityKey(...)
    )
    
    // Send to message sender via P2P or relay
    p2pManager.send(message.senderAddress, receipt)
}
```

### 5.2 Typing Indicators

```kotlin
// TypingIndicator.kt
data class TypingIndicator(
    val conversationId: String,
    val typingUser: String,
    val isTyping: Boolean,
    val timestamp: Long
)

// Ephemeral - not stored, not relayed
// Only sent via direct P2P connection
// Expires after 5 seconds without update
```

---

## 6. OFFLINE HANDLING

### 6.1 Message Queue

```kotlin
// MessageQueue.kt
class MessageQueue @Inject constructor(
    private val messageDao: MessageDao,
    private val p2pManager: P2PManager
) {
    // Messages waiting to be sent
    suspend fun enqueue(message: Message) {
        messageDao.insert(message.copy(status = MessageStatus.PENDING))
        processQueue()
    }
    
    // Process pending messages when online
    suspend fun processQueue() {
        val pending = messageDao.getPendingMessages()
        
        for (message in pending) {
            try {
                if (p2pManager.isRecipientOnline(message.recipientAddress)) {
                    // Direct delivery
                    p2pManager.sendDirect(message)
                    messageDao.updateStatus(message.id, MessageStatus.SENT_DIRECT)
                } else {
                    // Relay delivery
                    val relay = p2pManager.findBestRelay()
                    p2pManager.sendToRelay(relay, message)
                    messageDao.updateStatus(message.id, MessageStatus.SENT_TO_RELAY)
                }
            } catch (e: Exception) {
                // Will retry on next processQueue call
                Timber.e(e, "Failed to send message ${message.id}")
            }
        }
    }
    
    // Called when network becomes available
    fun onNetworkAvailable() {
        processQueue()
    }
}
```

### 6.2 Connection State Machine

```
┌─────────────────────────────────────────────────────────────┐
│               CONNECTION STATE MACHINE                       │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌───────────┐                                              │
│  │DISCONNECTED│                                              │
│  └─────┬─────┘                                              │
│        │ Network available                                  │
│        ▼                                                     │
│  ┌───────────┐                                              │
│  │CONNECTING │──── Timeout ────► DISCONNECTED               │
│  └─────┬─────┘                                              │
│        │ Bootstrap success                                  │
│        ▼                                                     │
│  ┌───────────┐                                              │
│  │ CONNECTED │──── Network lost ──► DISCONNECTED            │
│  └─────┬─────┘                                              │
│        │ DHT joined                                         │
│        ▼                                                     │
│  ┌───────────┐                                              │
│  │  SYNCING  │ Fetch pending from relays                    │
│  └─────┬─────┘                                              │
│        │ Sync complete                                      │
│        ▼                                                     │
│  ┌───────────┐                                              │
│  │   READY   │ Normal operation                             │
│  └───────────┘                                              │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```
