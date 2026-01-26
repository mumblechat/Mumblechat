# MumbleChat Android System - Complete Architecture

**Last Updated:** January 12, 2026  
**Version:** V4 (Node Identity + Tier System)

---

## 📋 Overview

The MumbleChat Android app is a fully decentralized messaging application built on Ramestta blockchain. It supports:

- **End-to-end encrypted messaging** via X25519/ChaCha20-Poly1305
- **P2P direct connections** between users
- **Mobile relay node operation** for earning MCT rewards
- **Cross-node messaging** via the relay hub
- **Offline message delivery** via relay nodes

---

## 🏗️ Directory Structure

```
app/src/main/java/com/ramapay/app/chat/
├── MumbleChatContracts.kt       # Legacy hardcoded addresses (deprecated)
├── MumbleChatConfig.kt          # ✅ NEW: Dynamic configuration loader
│
├── blockchain/                   # Blockchain interactions
│   └── MumbleChatBlockchainService.kt  # Web3j service (1131 lines)
│
├── core/                         # Core services
│   ├── ChatConfig.kt            # Chat protocol constants
│   ├── ChatService.kt           # Main chat orchestration
│   └── WalletBridge.kt          # Wallet connection bridge
│
├── crypto/                       # Encryption
│   ├── ChatKeyManager.kt        # Key derivation
│   ├── ChatKeyStore.kt          # Secure key storage
│   └── MessageEncryption.kt     # E2E encryption
│
├── data/                         # Local database (Room)
│   ├── ChatDatabase.kt          # Room database definition
│   ├── dao/
│   │   ├── ContactDao.kt
│   │   ├── ConversationDao.kt
│   │   ├── GroupDao.kt
│   │   └── MessageDao.kt
│   ├── entity/
│   │   ├── ContactEntity.kt
│   │   ├── ConversationEntity.kt
│   │   ├── GroupEntity.kt
│   │   └── MessageEntity.kt
│   └── repository/
│       ├── ConversationRepository.kt
│       ├── GroupRepository.kt
│       └── MessageRepository.kt
│
├── network/                      # P2P networking
│   └── P2PManager.kt            # Fully decentralized P2P (1502 lines)
│
├── p2p/                          # P2P utilities
│   ├── BlockchainPeerResolver.kt  # Find peers from blockchain
│   ├── BootstrapManager.kt        # Bootstrap node discovery
│   ├── HolePuncher.kt             # NAT traversal
│   ├── KademliaDHT.kt             # DHT implementation
│   ├── P2PTransport.kt            # Transport layer
│   ├── PeerCache.kt               # Peer caching
│   ├── QRCodePeerExchange.kt      # QR peer sharing
│   └── RateLimiter.kt             # Rate limiting
│
├── relay/                        # Relay node functionality
│   ├── RelayConfig.kt           # Relay configuration & tiers
│   ├── RelayMessageService.kt   # Message relay handling
│   ├── RelayService.kt          # Foreground service (559 lines)
│   └── RelayStorage.kt          # Offline message storage
│
├── viewmodel/                    # ViewModels
│   ├── ChatViewModel.kt         # Chat UI state
│   ├── ConversationViewModel.kt # Conversation list
│   ├── GroupChatViewModel.kt    # Group messaging
│   ├── GroupInfoViewModel.kt    # Group details
│   ├── GroupViewModel.kt        # Group management
│   ├── ProfileViewModel.kt      # User profile
│   └── RelayNodeViewModel.kt    # Relay node UI (1209 lines)
│
├── ui/                           # Activities & Fragments
│   ├── MumbleChatFragment.kt    # Main chat list
│   ├── ProfileActivity.kt       # Profile screen
│   ├── ChatSettingsActivity.kt  # Chat settings
│   ├── BlockedContactsActivity.kt
│   ├── MobileRelaySettingsActivity.kt  # Relay node settings
│   ├── NotificationSettingsActivity.kt
│   ├── PrivacySettingsActivity.kt
│   ├── TierSelectionDialog.kt   # Tier selection UI
│   ├── adapter/
│   │   ├── ConversationListAdapter.kt
│   │   └── MessageListAdapter.kt
│   ├── conversation/
│   │   └── ConversationActivity.kt  # Chat screen
│   ├── dialog/
│   ├── group/
│   │   └── GroupChatActivity.kt     # Group chat screen
│   └── newchat/
│       ├── NewChatActivity.kt
│       └── NewChatViewModel.kt
│
├── nat/                          # NAT traversal
│   ├── HolePuncher.kt           # TCP hole punching
│   └── StunClient.kt            # STUN for IP discovery
│
├── backup/                       # Chat backup
│   └── ChatBackupManager.kt
│
├── file/                         # File transfers
│   └── FileTransferManager.kt
│
├── notification/                 # Notifications
│   └── NotificationStrategyManager.kt
│
├── protocol/                     # Protocol definitions
│
├── registry/                     # Identity management
│   └── RegistrationManager.kt
│
├── service/                      # Background services
│   └── NonceClearService.kt
│
└── sync/                         # Message sync
    └── MessageSyncManager.kt
```

---

## 🔧 Configuration System

### New: MumbleChatConfig.kt

All configuration is now loaded dynamically from:
1. `assets/mumblechat.properties` (primary)
2. `BuildConfig` fields (build-time override)
3. Default values (fallback)

```kotlin
// Usage anywhere in the app
val config = MumbleChatConfig.getInstance(context)

// Get contract addresses
val mctToken = config.mctTokenAddress
val registry = config.registryAddress
val relayManager = config.relayManagerAddress

// Get tier info
val stakeRequired = config.getStakeForTier(tier)
val uptimeRequired = config.getUptimeForTier(tier)
val multiplier = config.getMultiplierForTier(tier)
```

### Properties File Location
```
app/src/main/assets/mumblechat.properties
```

### Key Configuration Values
| Property | Value | Description |
|----------|-------|-------------|
| `CHAIN_ID` | 1370 | Ramestta Mainnet |
| `RPC_URL` | https://blockchain.ramestta.com | RPC endpoint |
| `MCT_TOKEN_ADDRESS` | 0xEfD7B65...f1dE | MCT Token contract |
| `REGISTRY_ADDRESS` | 0x4f8D49...8632e3 | Registry contract |
| `RELAY_MANAGER_ADDRESS` | 0xF78F84...f4b73 | V4 Relay Manager |
| `HUB_WS_URL` | wss://hub.mumblechat.com/node/connect | Hub WebSocket |

---

## 📱 Key Components

### 1. MumbleChatBlockchainService.kt

Web3j-based blockchain service for:
- Identity registration
- Relay node management
- MCT token operations
- Daily pool rewards
- Fee pool claims

**Key Functions:**
```kotlin
// Identity
getIdentity(address: String): IdentityInfo?
isRegistered(address: String): Boolean

// Relay Node
getRelayNode(address: String): RelayNodeStatus?
getActiveRelayNodes(): List<RelayNodeInfo>

// Daily Pool
getTodayPoolInfo(): TodayPoolInfo?
getClaimableReward(address: String, dayId: Long): Double
getMyTodayStats(address: String): MyTodayStats?

// Fee Pool
getFeePoolBalance(): Double

// MCT
getMCTBalance(address: String): BigInteger
```

### 2. RelayService.kt (Foreground Service)

Android foreground service for relay node operation:
- Runs in background with notification
- Sends heartbeats every 5 minutes
- Stores offline messages
- Delivers when recipients come online
- Tracks uptime for tier calculation

```kotlin
// Start relay service
RelayService.start(context, storageMB = 1024)

// Stop relay service
RelayService.stop(context)

// Check if running
RelayService.isRunning()
```

### 3. P2PManager.kt (Fully Decentralized)

100% decentralized P2P networking:
- **No central servers** required
- Kademlia DHT for peer discovery
- Direct TCP connections
- NAT traversal via hole punching
- Relay fallback for unreachable peers

```kotlin
// Initialize
p2pManager.initialize(chatKeys, walletAddress)
p2pManager.connect()

// Send message
p2pManager.sendMessage(recipientAddress, encryptedMessage)

// Receive messages
p2pManager.incomingMessages.collect { message ->
    // Handle incoming message
}
```

### 4. RelayNodeViewModel.kt

ViewModel for relay node UI with:
- Status loading
- Tier calculation
- Staking operations
- Reward claiming
- Transaction history

**State Flows:**
```kotlin
val isLoading: StateFlow<Boolean>
val relayStatus: StateFlow<RelayNodeStatus?>
val mctBalance: StateFlow<Double>
val isRegistered: StateFlow<Boolean>
val tierInfo: StateFlow<TierInfo?>
val dailyPoolStats: StateFlow<DailyPoolStats?>
val claimableReward: StateFlow<Double>
```

---

## 💰 Reward System

### Current Implementation (MumbleChatBlockchainService)

| Function | Status | Description |
|----------|--------|-------------|
| `getTodayPoolInfo()` | ✅ Done | Get daily pool status |
| `getClaimableReward()` | ✅ Done | Get claimable for day |
| `getMyTodayStats()` | ✅ Done | Get my relay stats |
| `getFeePoolBalance()` | ✅ Done | Get fee pool balance |

### To Add (RewardService.kt)

| Function | Status | Description |
|----------|--------|-------------|
| `claimDailyPoolReward()` | ❌ TODO | Claim daily pool reward |
| `claimFeeReward()` | ❌ TODO | Claim fee pool share |
| `submitRelayProof()` | ❌ TODO | Submit relay proof for minting |
| `getDailyMissedPool()` | ❌ TODO | Get missed rewards pool |
| `distributeMissedRewards()` | ❌ TODO | Trigger redistribution |
| `getTokenomicsInfo()` | ❌ TODO | Get all tokenomics data |

---

## 🔐 Security

### Key Management
- Keys derived from wallet signature
- Stored in Android Keystore
- X25519 for key exchange
- ChaCha20-Poly1305 for encryption

### Message Security
```
Sender → Derive shared secret (X25519)
       → Encrypt message (ChaCha20-Poly1305)
       → Sign with wallet
       → Send via P2P/Relay
```

---

## 🚀 Next Steps for Implementation

### 1. Create RewardService.kt
- Port all functions from desktop `RewardService.ts`
- Use Web3j for contract calls
- Handle transaction signing

### 2. Update RelayNodeViewModel
- Add reward claiming actions
- Add missed reward redistribution
- Show all claimable amounts

### 3. Update UI
- Add reward display cards
- Add claim buttons
- Show uptime progress
- Show tier upgrade requirements

### 4. Integration Testing
- Test with real MCT tokens
- Test reward claiming
- Test tier upgrades
- Test cross-node messaging

---

## 📊 Data Models

### RelayNodeStatus
```kotlin
data class RelayNodeStatus(
    val endpoint: String,
    val stakedAmount: Double,
    val registeredAt: Long,
    val messagesRelayed: Long,
    val rewardsEarned: Double,
    val isActive: Boolean,
    val dailyUptimeSeconds: Long,
    val storageMB: Int,
    val tier: Int,
    val rewardMultiplier: Double,
    val isOnline: Boolean
)
```

### TodayPoolInfo
```kotlin
data class TodayPoolInfo(
    val dayId: Long,
    val totalRelays: Long,
    val totalWeightedRelays: Long,
    val poolAmount: Double,
    val numContributors: Int
)
```

### MyTodayStats
```kotlin
data class MyTodayStats(
    val relayCount: Long,
    val weightedRelayCount: Long,
    val estimatedReward: Double
)
```

---

## 🔗 Dependencies

### Build Dependencies
```groovy
// Web3j for blockchain
implementation 'org.web3j:core:4.9.4'

// Room for local database
implementation "androidx.room:room-runtime:2.5.0"
kapt "androidx.room:room-compiler:2.5.0"

// Coroutines
implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4'

// Hilt for DI
implementation "com.google.dagger:hilt-android:2.44"
kapt "com.google.dagger:hilt-compiler:2.44"

// Timber for logging
implementation 'com.jakewharton.timber:timber:5.0.1'
```

---

## 📞 Quick Reference

| Item | Value |
|------|-------|
| Package | `com.ramapay.app.chat` |
| Config File | `assets/mumblechat.properties` |
| Config Class | `MumbleChatConfig` |
| Blockchain Service | `MumbleChatBlockchainService` |
| Relay Service | `RelayService` |
| P2P Manager | `P2PManager` |
| Main ViewModel | `RelayNodeViewModel` |
