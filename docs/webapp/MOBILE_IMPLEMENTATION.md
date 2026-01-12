# MumbleChat Mobile App Implementation Guide

**Version:** 1.2.0  
**Date:** January 12, 2026  
**Status:** 📱 Ready for Mobile Implementation  

---

## Overview

This guide provides all necessary information to implement the MumbleChat mobile app (Android/iOS) based on the existing webapp architecture.

## Technology Stack for Mobile

### Android (Kotlin)

| Component | Library | Version |
|-----------|---------|---------|
| HTTP/WebSocket | OkHttp | 4.12+ |
| Crypto | Bouncy Castle | 1.77 |
| Blockchain | Web3j | 4.10+ |
| UI | Jetpack Compose | Latest |
| Local DB | Room | 2.6+ |
| Key Storage | Android Keystore | System |

### iOS (Swift)

| Component | Library | Version |
|-----------|---------|---------|
| WebSocket | URLSessionWebSocketTask | iOS 13+ |
| Crypto | CryptoKit | iOS 13+ |
| Blockchain | web3.swift | Latest |
| UI | SwiftUI | iOS 14+ |
| Local DB | Core Data / SQLite | System |
| Key Storage | Keychain Services | System |

## API Reference

### WebSocket Protocol

#### Connection

**Endpoint:** `wss://hub.mumblechat.com/node/{tunnelId}`

**Auto-connect Endpoint:** `wss://hub.mumblechat.com/user/connect` (load-balanced)

#### Message Types

##### 1. Authentication

```json
// Client → Server
{
    "type": "authenticate",
    "walletAddress": "0x1234...",
    "address": "0x1234...",
    "displayName": "Alice",
    "publicKey": "base64_encoded_ecdh_public_key",
    "timestamp": 1736567890123
}

// Server → Client
{
    "type": "authenticated",
    "success": true,
    "timestamp": 1736567890124
}
```

##### 2. Send Message

```json
// Client → Server
{
    "type": "relay",
    "from": "0xSenderAddress",
    "to": "0xRecipientAddress",
    "encryptedBlob": "base64_encrypted_message",
    "payload": "base64_encrypted_message",
    "encrypted": true,
    "algorithm": "ECDH-AES-256-GCM",
    "signature": "0x_wallet_signature",
    "senderPublicKey": "base64_public_key",
    "messageId": "msg_1736567890_abc123",
    "timestamp": 1736567890123
}

// Server → Client (ACK)
{
    "type": "relay_ack",
    "messageId": "msg_1736567890_abc123",
    "delivered": true,
    "timestamp": 1736567890124
}
```

##### 3. Receive Message

```json
// Server → Client
{
    "type": "message",
    "from": "0xSenderAddress",
    "senderAddress": "0xSenderAddress",
    "to": "0xRecipientAddress",
    "text": "decrypted_or_encrypted_content",
    "payload": "base64_encrypted_if_e2ee",
    "encryptedBlob": "base64_encrypted_if_e2ee",
    "encrypted": true,
    "signature": "0x_signature",
    "senderPublicKey": "base64_public_key",
    "messageId": "msg_1736567890_xyz789",
    "timestamp": 1736567890123,
    "isOfflineMessage": false
}
```

##### 4. Presence Updates

```json
// Server → Client
{
    "type": "presence",
    "address": "0xContactAddress",
    "status": "online"  // or "offline"
}
```

##### 5. Key Exchange

```json
// Client → Server (Request contact's key)
{
    "type": "key_request",
    "to": "0xContactAddress"
}

// Server → Client (Received key)
{
    "type": "public_key",
    "address": "0xContactAddress",
    "publicKey": "base64_ecdh_public_key"
}
```

##### 6. Offline Messages

```json
// Server → Client (on connect)
{
    "type": "offline_messages",
    "messages": [
        { /* message object */ },
        { /* message object */ }
    ]
}

// Server → Client (queued for offline user)
{
    "type": "message_queued",
    "messageId": "msg_original_id",
    "queuedId": "offline_123_abc",
    "status": "pending",
    "recipient": "0xOfflineUser",
    "expiresIn": "7 days"
}
```

##### 7. Delivery Status (NEW)

```json
// Server → Client (message delivered to recipient)
{
    "type": "relay_ack",
    "messageId": "msg_1736567890_abc123",
    "delivered": true,
    "to": "0xRecipientAddress",
    "timestamp": 1736567890124
}

// Server → Client (message queued - recipient offline)
{
    "type": "message_queued",
    "messageId": "msg_1736567890_abc123",
    "status": "pending",
    "recipient": "0xRecipientAddress",
    "reason": "recipient_offline"
}

// Server → Client (delivery confirmation from hub)
{
    "type": "delivery_receipt",
    "messageId": "msg_1736567890_abc123",
    "to": "0xRecipientAddress",
    "status": "delivered",
    "timestamp": 1736567890125
}

// Client → Server (send read receipt)
{
    "type": "read",
    "to": "0xSenderAddress",
    "from": "0xMyAddress",
    "messageId": "msg_1736567890_abc123"
}

// Server → Client (read receipt received)
{
    "type": "read_receipt",
    "messageId": "msg_1736567890_abc123",
    "from": "0xRecipientAddress",
    "timestamp": 1736567890126
}
```

##### 8. Sync/Fetch

```json
// Client → Server
{
    "type": "sync",
    "address": "0xMyAddress"
}

// Server → Client
{
    "type": "stored_messages",
    "messages": [ /* array of messages */ ],
    "timestamp": 1736567890123
}
```

##### 8. Ping/Pong

```json
// Client → Server
{ "type": "ping" }

// Server → Client
{ "type": "pong", "timestamp": 1736567890123 }
```

### REST API

#### Hub Stats

```
GET https://hub.mumblechat.com/api/stats

Response:
{
    "totalNodes": 3,
    "totalUsers": 45,
    "registeredUsers": 120,
    "hubFeePercent": 10,
    "nodes": [
        {
            "tunnelId": "76bc8b54",
            "endpoint": "hub.mumblechat.com/node/76bc8b54",
            "connectedUsers": 15,
            "messagesRelayed": 12500,
            "connectedAt": "2026-01-10T...",
            "lastHeartbeat": "2026-01-11T..."
        }
    ]
}
```

#### Get Best Endpoint

```
GET https://hub.mumblechat.com/api/endpoints

Response:
{
    "endpoints": [
        {
            "endpoint": "wss://hub.mumblechat.com/node/76bc8b54",
            "tunnelId": "76bc8b54",
            "users": 15,
            "load": 0.15
        }
    ]
}
```

#### Check User Online Status

```
GET https://hub.mumblechat.com/api/user/{address}

Response (online):
{
    "online": true,
    "nodeId": "76bc8b54",
    "nodeEndpoint": "hub.mumblechat.com/node/76bc8b54"
}

Response (offline):
{
    "online": false
}
```

#### Check Pending Messages

```
GET https://hub.mumblechat.com/api/user/{address}/pending

Response:
{
    "address": "0x1234...",
    "pendingCount": 3,
    "messages": [
        {
            "id": "offline_123_abc",
            "from": "0x5678...",
            "timestamp": 1736567890123,
            "expiresAt": 1737172690123
        }
    ]
}
```

## Encryption Implementation

### Required Libraries

**Android (Kotlin):**
```kotlin
// Use Android Keystore + Bouncy Castle
implementation 'org.bouncycastle:bcprov-jdk18on:1.77'
```

**iOS (Swift):**
```swift
// Use CryptoKit (iOS 13+)
import CryptoKit
```

### Key Generation

**Android:**
```kotlin
val keyPairGenerator = KeyPairGenerator.getInstance("EC")
keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"))
val keyPair = keyPairGenerator.generateKeyPair()
```

**iOS:**
```swift
let privateKey = P256.KeyAgreement.PrivateKey()
let publicKey = privateKey.publicKey
```

### ECDH Key Exchange

**Android:**
```kotlin
val keyAgreement = KeyAgreement.getInstance("ECDH")
keyAgreement.init(myPrivateKey)
keyAgreement.doPhase(contactPublicKey, true)
val sharedSecret = keyAgreement.generateSecret()
```

**iOS:**
```swift
let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(
    with: contactPublicKey
)
let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
    using: SHA256.self,
    salt: Data(),
    sharedInfo: Data(),
    outputByteCount: 32
)
```

### AES-256-GCM Encryption

**Android:**
```kotlin
val iv = ByteArray(12)
SecureRandom().nextBytes(iv)

val cipher = Cipher.getInstance("AES/GCM/NoPadding")
val gcmSpec = GCMParameterSpec(128, iv)
cipher.init(Cipher.ENCRYPT_MODE, aesKey, gcmSpec)
val ciphertext = cipher.doFinal(plaintext)

// Combine: iv + ciphertext
val encrypted = iv + ciphertext
```

**iOS:**
```swift
let nonce = AES.GCM.Nonce()
let sealedBox = try AES.GCM.seal(
    plaintext,
    using: symmetricKey,
    nonce: nonce
)
let encrypted = sealedBox.combined!
```

### Public Key Format

Export/Import as SPKI (SubjectPublicKeyInfo) format, then base64 encode:

```kotlin
// Android Export
val encoded = publicKey.encoded  // SPKI format
val base64 = Base64.encodeToString(encoded, Base64.NO_WRAP)

// Android Import
val keyFactory = KeyFactory.getInstance("EC")
val keySpec = X509EncodedKeySpec(Base64.decode(base64, Base64.NO_WRAP))
val publicKey = keyFactory.generatePublic(keySpec)
```

## Blockchain Integration

### Contract Addresses

```kotlin
object Contracts {
    const val REGISTRY = "0x4f8D4955F370881B05b68D2344345E749d8632e3"
    const val RELAY_MANAGER = "0xF78F840eF0e321512b09e98C76eA0229Affc4b73"
    const val MCT_TOKEN = "0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE"
}
```

### Network Configuration

```kotlin
object RamesttaNetwork {
    const val CHAIN_ID = 1370
    const val CHAIN_ID_HEX = "0x55A"
    const val RPC_URL = "https://blockchain.ramestta.com"
    const val EXPLORER_URL = "https://ramascan.com"
    const val CURRENCY_SYMBOL = "RAMA"
    const val CURRENCY_DECIMALS = 18
}
```

### Registry ABI (Simplified)

```json
[
    {
        "name": "register",
        "inputs": [
            { "name": "publicKeyX", "type": "bytes32" },
            { "name": "displayName", "type": "string" }
        ]
    },
    {
        "name": "identities",
        "inputs": [{ "name": "", "type": "address" }],
        "outputs": [
            { "name": "publicKeyX", "type": "bytes32" },
            { "name": "publicKeyY", "type": "bytes32" },
            { "name": "registeredAt", "type": "uint256" },
            { "name": "lastUpdated", "type": "uint256" },
            { "name": "isActive", "type": "bool" },
            { "name": "displayName", "type": "string" },
            { "name": "keyVersion", "type": "uint8" }
        ]
    }
]
```

## Data Models

### Message

```kotlin
data class Message(
    val id: String,
    val from: String,
    val to: String,
    val text: String,
    val encrypted: Boolean,
    val encryptedData: String?,
    val signature: String?,
    val senderPublicKey: String?,
    val timestamp: Long,
    val status: MessageStatus,
    val statusUpdatedAt: Long?,  // When status last changed
    val isOffline: Boolean
)

/**
 * Message Status Flow:
 * SENDING → SENT → PENDING (if offline) → DELIVERED → READ
 */
enum class MessageStatus {
    SENDING,    // 🕐 Being sent to relay
    SENT,       // ✓  Relay acknowledged
    PENDING,    // ⏳ Recipient offline, queued (yellow animated)
    DELIVERED,  // ✓✓ Delivered to recipient
    READ,       // ✓✓ Read by recipient (blue)
    FAILED      // ❌ Send failed
}

// Status icon mapping
fun MessageStatus.toIcon(): String = when (this) {
    SENDING -> "🕐"
    SENT -> "✓"
    PENDING -> "⏳"
    DELIVERED -> "✓✓"
    READ -> "✓✓"  // Blue color
    FAILED -> "❌"
}
```

### Contact

```kotlin
data class Contact(
    val address: String,
    val name: String,
    val publicKey: String?,
    val isOnline: Boolean,
    val lastMessage: String?,
    val lastMessageTime: String?,        // Display time "12:30"
    val lastMessageTimestamp: Long?,     // For sorting (epoch ms)
    val unreadCount: Int,
    val isPinned: Boolean,
    val isMuted: Boolean,
    val isBlocked: Boolean
)

/**
 * Contact List Sorting (WhatsApp/Telegram style):
 * 1. Pinned contacts first
 * 2. Unread messages higher priority
 * 3. Newest message timestamp (descending)
 */
fun List<Contact>.sortedForDisplay(): List<Contact> {
    return this.sortedWith(
        compareByDescending<Contact> { it.isPinned }
            .thenByDescending { it.unreadCount > 0 }
            .thenByDescending { it.lastMessageTimestamp ?: 0 }
    )
}
```

### User Identity

```kotlin
data class UserIdentity(
    val address: String,
    val displayName: String,
    val publicKeyX: String,
    val publicKeyY: String,
    val registeredAt: Long,
    val lastUpdated: Long,
    val isActive: Boolean,
    val keyVersion: Int
)
```

## Wallet Integration

### Recommended Libraries

**Android:**
- WalletConnect v2: `com.walletconnect:android-core`
- Web3j: `org.web3j:core`
- Reown (AppKit): For seamless wallet connection

**iOS:**
- WalletConnect Swift
- web3.swift

### Connection Flow (WalletConnect v2)

1. Initialize WalletConnect
2. Create pairing URI
3. User scans QR or opens wallet
4. Receive session approval
5. Store session for future use

### Signing Messages

```kotlin
// Request personal_sign
val signature = walletConnect.request(
    method = "personal_sign",
    params = listOf(messageHex, userAddress)
)
```

## Local Storage

### Required Persistence

| Key | Data | Encryption |
|-----|------|------------|
| `user_keys` | ECDH key pair | Encrypted with device keystore |
| `contacts` | Contact list with public keys | Optional |
| `messages` | Message history | Optional |
| `settings` | App preferences | None |

### Secure Storage

**Android:** Use EncryptedSharedPreferences or Android Keystore

**iOS:** Use Keychain Services

## Push Notifications

### FCM/APNs Integration

1. Register device token with relay on connect
2. Hub sends push when message for offline user
3. Push payload contains minimal data (sender, hasMessage)
4. App fetches actual messages on open

### Push Payload Example

```json
{
    "notification": {
        "title": "New Message",
        "body": "You have a new message"
    },
    "data": {
        "type": "new_message",
        "from": "0x1234...",
        "timestamp": "1736567890123"
    }
}
```

## Testing

### Test Endpoints

- **Hub Health:** `GET https://hub.mumblechat.com/health`
- **WebSocket Test:** Connect to any node endpoint

### Test Wallet Addresses

Use Ramestta testnet or local development addresses for testing.

## Implementation Checklist

### Core Features

- [ ] WebSocket connection management (reconnection, heartbeat)
- [ ] ECDH key generation and storage (P-256)
- [ ] AES-256-GCM encryption/decryption
- [ ] Message send/receive with E2EE
- [ ] Contact management with public keys
- [ ] Offline message handling
- [ ] Public key exchange protocol

### Message Status (NEW - WhatsApp style)

- [ ] Track message status (sending → sent → delivered → read)
- [ ] Handle `relay_ack` for sent confirmation
- [ ] Handle `message_queued` for pending (offline recipient)
- [ ] Handle `delivery_receipt` for delivered status
- [ ] Send `read` receipt when user opens conversation
- [ ] Handle `read_receipt` for blue tick status
- [ ] Display status icons (🕐 ✓ ⏳ ✓✓ ✓✓🔵 ❌)
- [ ] Animate pending status (yellow pulsing)

### Contact List Sorting (NEW - WhatsApp/Telegram style)

- [ ] Store `lastMessageTimestamp` (epoch ms) per contact
- [ ] Sort pinned contacts first
- [ ] Sort unread messages higher
- [ ] Sort by newest message timestamp (descending)
- [ ] Re-sort list when new message arrives
- [ ] Update list position in real-time

### Wallet Integration

- [ ] WalletConnect v2 setup
- [ ] Network switching to Ramestta (Chain ID 1370)
- [ ] Message signing
- [ ] Transaction signing (for registration)

### UI/UX

- [ ] Chat list view with sorting
- [ ] Conversation view with status icons
- [ ] New contact dialog
- [ ] Settings screen
- [ ] Connection status indicator
- [ ] Unread badge on contacts

### Advanced Features

- [ ] Push notifications (FCM/APNs)
- [ ] Background message sync
- [ ] Message search
- [ ] Media attachments (future)
- [ ] Group chat (future)

---

*Last Updated: January 2026*
