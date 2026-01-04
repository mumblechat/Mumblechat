# MumbleChat Protocol - Relay Nodes & MCT Rewards V2

## Part 4 of 8

---

## 1. RELAY NODE SYSTEM

### 1.1 Purpose

Relay nodes solve the **offline delivery problem** in a fully decentralized network:

```
┌─────────────────────────────────────────────────────────────┐
│                  THE OFFLINE PROBLEM                         │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  WITHOUT RELAYS:                                            │
│  ───────────────                                            │
│  Sender ────X────► Recipient (offline)                      │
│           │                                                  │
│           └── Message LOST                                  │
│                                                              │
│  WITH RELAYS:                                               │
│  ────────────                                               │
│  Sender ────────► Relay Node ────────► Recipient            │
│                   (stores)             (when online)        │
│           │                                                  │
│           └── Message DELIVERED                             │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 Relay Node Architecture (V2)

```
┌─────────────────────────────────────────────────────────────┐
│               RELAY NODE COMPONENTS (V2)                     │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │                    RELAY NODE                        │    │
│  ├─────────────────────────────────────────────────────┤    │
│  │                                                      │    │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ │    │
│  │  │   P2P       │  │   Message   │  │   Tier      │ │    │
│  │  │   Listener  │  │   Storage   │  │   Tracker   │ │    │
│  │  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘ │    │
│  │         │                │                │        │    │
│  │         ▼                ▼                ▼        │    │
│  │  ┌─────────────────────────────────────────────┐  │    │
│  │  │              Relay Service                   │  │    │
│  │  │                                              │  │    │
│  │  │  - Accept messages for offline users        │  │    │
│  │  │  - Store encrypted blobs with TTL           │  │    │
│  │  │  - Deliver when recipient comes online      │  │    │
│  │  │  - Send heartbeats (uptime tracking)        │  │    │
│  │  │  - Report storage usage                      │  │    │
│  │  │  - Claim minting rewards (1x)               │  │    │
│  │  │  - Claim fee pool rewards (TIER-based)      │  │    │
│  │  │                                              │  │    │
│  │  └─────────────────────────────────────────────┘  │    │
│  │                                                      │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 1.3 Becoming a Relay Node (V2 - Simplified)

```
┌─────────────────────────────────────────────────────────────┐
│           RELAY NODE ACTIVATION FLOW (V2)                    │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  1. PREREQUISITES                                           │
│     ├── Wallet must be registered in MumbleChatRegistry     │
│     ├── Minimum 100 MCT stake (optional, improves trust)   │
│     └── Must have RAMA for gas                             │
│                                                              │
│  2. REGISTRATION (On-Chain)                                 │
│     ├── Call Registry.registerAsRelay(endpoint, storage)   │
│     ├── Set P2P endpoint (multiaddr format)                │
│     └── Declare storage capacity (in MB)                   │
│                                                              │
│  3. TIER TRACKING (Automatic)                               │
│     ├── Heartbeats track uptime automatically              │
│     ├── Storage usage reported by node                     │
│     └── Tier calculated: Bronze → Silver → Gold → Platinum │
│                                                              │
│  4. ACTIVATION                                              │
│     ├── Start RelayService (foreground service on Android) │
│     ├── Announce as relay in DHT                           │
│     └── Begin accepting messages                           │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```
```

---

## 2. RELAY MESSAGE STORAGE

### 2.1 What Relays Store

```
┌─────────────────────────────────────────────────────────────┐
│              RELAY STORAGE STRUCTURE                         │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  STORED:                                                    │
│  ───────                                                    │
│  ├── recipientKeyHash: keccak256(recipientAddress)[:8]     │
│  │   (For lookup, not full address - privacy)              │
│  ├── encryptedBlob: bytes (opaque to relay)                │
│  ├── senderKeyHash: keccak256(senderAddress)[:8]           │
│  ├── receivedAt: timestamp                                  │
│  ├── expiresAt: timestamp (TTL)                            │
│  └── size: bytes                                           │
│                                                              │
│  NOT STORED:                                                │
│  ───────────                                                │
│  ├── Full wallet addresses                                  │
│  ├── Message content (encrypted)                           │
│  ├── Sender identity                                       │
│  └── Any metadata                                          │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Storage Limits & Rotation

```kotlin
// RelayStorage.kt
class RelayStorage @Inject constructor(
    private val context: Context,
    private val config: RelayConfig
) {
    private val storageDir = File(context.filesDir, "relay_messages")
    
    data class StoredMessage(
        val id: String,
        val recipientKeyHash: ByteArray,
        val encryptedBlob: ByteArray,
        val senderKeyHash: ByteArray,
        val receivedAt: Long,
        val expiresAt: Long,
        val size: Long
    )
    
    // Check if we can accept more messages
    fun canAccept(messageSize: Long): Boolean {
        val currentUsage = getCurrentStorageUsage()
        val limit = config.storageLimitBytes
        return currentUsage + messageSize <= limit
    }
    
    // Store message with TTL
    suspend fun store(message: StoredMessage): Boolean {
        if (!canAccept(message.size)) {
            // Try to make room by deleting expired
            cleanupExpired()
            
            if (!canAccept(message.size)) {
                return false // Storage full
            }
        }
        
        // Write to encrypted file
        val file = File(storageDir, message.id)
        file.writeBytes(serializeMessage(message))
        return true
    }
    
    // Get messages for recipient
    suspend fun getMessagesFor(recipientKeyHash: ByteArray): List<StoredMessage> {
        return storageDir.listFiles()
            ?.mapNotNull { deserializeMessage(it.readBytes()) }
            ?.filter { it.recipientKeyHash.contentEquals(recipientKeyHash) }
            ?.filter { it.expiresAt > System.currentTimeMillis() }
            ?: emptyList()
    }
    
    // Delete after successful delivery
    suspend fun delete(messageId: String) {
        File(storageDir, messageId).delete()
    }
    
    // Periodic cleanup
    suspend fun cleanupExpired() {
        val now = System.currentTimeMillis()
        storageDir.listFiles()?.forEach { file ->
            val message = deserializeMessage(file.readBytes())
            if (message != null && message.expiresAt < now) {
                file.delete()
            }
        }
    }
}
```

### 2.3 TTL Configuration

| Setting | Default | Min | Max |
|---------|---------|-----|-----|
| Message TTL | 7 days | 1 day | 30 days |
| Cleanup Interval | 1 hour | 15 min | 6 hours |
| Max Message Size | 1 MB | - | 10 MB |

---

## 3. MCT TOKEN REWARDS V3

### 3.1 Dual Reward System

```
┌─────────────────────────────────────────────────────────────┐
│             MCT REWARD MODEL V3 (SUSTAINABLE)                │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ════════════════════════════════════════════════════════   │
│  TWO REWARD SOURCES:                                        │
│  ════════════════════════════════════════════════════════   │
│                                                              │
│  1. MINTING REWARDS (During growth phase)                   │
│     ─────────────────────────────────────                   │
│     • Base: 0.001 MCT per 1,000 messages relayed            │
│     • Daily cap: 100 MCT max minted per day                 │
│     • Halving: Every 100,000 MCT minted, reward halves      │
│     • Max supply: 1,000,000 MCT (governance adjustable)     │
│     • NO TIER BONUS (always 1x to control inflation)        │
│                                                              │
│  2. FEE POOL REWARDS (Sustainable long-term)                │
│     ────────────────────────────────────                    │
│     • Source: 0.1% fee on all MCT transfers                 │
│     • Distribution: Proportional to relay nodes             │
│     • TIER BONUS APPLIES (1x to 3x based on tier)           │
│     • No daily cap (grows with network usage)               │
│                                                              │
│  ════════════════════════════════════════════════════════   │
│  POST-MINTING ERA (after max supply reached):               │
│  • No more minting rewards                                  │
│  • Nodes earn ONLY from fee pool                            │
│  • Higher tiers earn more from fees                         │
│  ════════════════════════════════════════════════════════   │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 Tier System

```
┌─────────────────────────────────────────────────────────────┐
│                  RELAY NODE TIER SYSTEM                      │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  TIER CALCULATION:                                          │
│  Tier = MAX(uptime_tier, storage_tier)                      │
│                                                              │
│  ┌─────────┬────────────┬──────────┬─────────┬───────────┐  │
│  │  Tier   │ Daily      │ Storage  │ Pool    │ Fee Pool  │  │
│  │         │ Uptime     │ Provided │ Share   │ Multiplier│  │
│  ├─────────┼────────────┼──────────┼─────────┼───────────┤  │
│  │🥉Bronze │ 4+ hours   │ 1 GB     │ 10%     │ 1.0x      │  │
│  │🥈Silver │ 8+ hours   │ 2 GB     │ 20%     │ 1.5x      │  │
│  │🥇Gold   │ 12+ hours  │ 4 GB     │ 30%     │ 2.0x      │  │
│  │💎Platinum│16+ hours  │ 8+ GB    │ 40%     │ 3.0x      │  │
│  └─────────┴────────────┴──────────┴─────────┴───────────┘  │
│                                                              │
│  IMPORTANT:                                                 │
│  ───────────                                                │
│  • Tier bonuses ONLY apply to fee pool distribution         │
│  • Minting rewards are always 1x (no tier bonus)            │
│  • This keeps max supply controlled                         │
│  • Tier is updated via heartbeat() and updateStorage()      │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 3.3 Uptime Tracking

```
┌─────────────────────────────────────────────────────────────┐
│                  UPTIME TRACKING SYSTEM                      │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  HEARTBEAT MECHANISM:                                       │
│  ────────────────────                                       │
│  • Relay nodes call heartbeat() every 5 minutes             │
│  • Smart contract tracks:                                   │
│    - lastHeartbeat timestamp                                │
│    - currentSessionStart                                    │
│    - dailyUptimeSeconds (resets at midnight UTC)            │
│    - totalUptimeSeconds (cumulative)                        │
│                                                              │
│  TIMEOUT:                                                   │
│  ────────                                                   │
│  • If no heartbeat for 5 minutes → node considered offline  │
│  • Session ends, uptime stops accumulating                  │
│  • Next heartbeat starts new session                        │
│                                                              │
│  TIER PROMOTION:                                            │
│  ───────────────                                            │
│  • dailyUptimeSeconds >= 4 hours → Silver                  │
│  • dailyUptimeSeconds >= 8 hours → Gold                    │
│  • dailyUptimeSeconds >= 16 hours → Platinum               │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 3.4 Reward Calculation Example

```
┌─────────────────────────────────────────────────────────────┐
│                  REWARD CALCULATION EXAMPLE                  │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  SCENARIO: Relay node relayed 10,000 messages today         │
│  TIER: Gold (2.0x fee pool bonus)                           │
│                                                              │
│  ═══════════════════════════════════════════════════════    │
│  MINTING REWARDS (no tier bonus):                           │
│  ─────────────────────────────────                          │
│  Messages: 10,000                                           │
│  Batches: 10,000 / 1,000 = 10 batches                       │
│  Reward per batch: 0.001 MCT (assuming no halvings yet)     │
│  Minting reward: 10 × 0.001 = 0.01 MCT                      │
│                                                              │
│  ═══════════════════════════════════════════════════════    │
│  FEE POOL REWARDS (tier bonus applies):                     │
│  ───────────────────────────────────────                    │
│  Fee pool: 1.0 MCT (accumulated from transfers)             │
│  Active relays: 10                                          │
│  Base share: 1.0 / 10 = 0.1 MCT                             │
│  Tier multiplier: 2.0x (Gold)                               │
│  Fee pool reward: 0.1 × 2.0 = 0.2 MCT                       │
│                                                              │
│  ═══════════════════════════════════════════════════════    │
│  TOTAL: 0.01 + 0.2 = 0.21 MCT                               │
│  ═══════════════════════════════════════════════════════    │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 3.5 Halving Schedule

```
┌─────────────────────────────────────────────────────────────┐
│                   MCT HALVING SCHEDULE                       │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Total Minted    │ Reward per 1000 msgs │ Daily Cap Effect  │
│  ─────────────────────────────────────────────────────────  │
│  0 - 100k MCT    │ 0.001 MCT            │ Early adopters    │
│  100k - 200k MCT │ 0.0005 MCT           │ Growing network   │
│  200k - 300k MCT │ 0.00025 MCT          │ Mature network    │
│  300k - 400k MCT │ 0.000125 MCT         │ Stable operation  │
│  ...             │ ...                   │ ...               │
│  900k - 1M MCT   │ ~0.000002 MCT        │ Near max supply   │
│  ─────────────────────────────────────────────────────────  │
│                                                              │
│  After 1M MCT minted:                                       │
│  • No more minting rewards                                  │
│  • Nodes earn only from 0.1% transfer fees                  │
│  • Fee pool grows with MCT usage                            │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```
            relayAddress.toByteArray() +
            recipientAddress.toByteArray() +
            storedAt.toByteArray() +
            deliveredAt.toByteArray() +
            messageSize.toByteArray()
        )
    }
    
    // Verify recipient actually signed this
    fun verify(recipientPublicKey: ByteArray): Boolean {
        return Ed25519.verify(
            recipientSignature,
            hash(),
            recipientPublicKey
        )
    }
}
```

---

## 4. RELAY STAKING & SLASHING

### 4.1 Staking Contract

```solidity
// RelayStaking.sol
// SPDX-License-Identifier: MIT
pragma solidity ^0.8.19;

import "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import "@openzeppelin/contracts/security/ReentrancyGuard.sol";

contract RelayStaking is ReentrancyGuard {
    IERC20 public mctToken;
    
    uint256 public constant MIN_STAKE = 1000 * 10**18;  // 1000 MCT
    uint256 public constant UNBONDING_PERIOD = 7 days;
    
    struct RelayInfo {
        uint256 stakedAmount;
        uint256 unbondingAmount;
        uint256 unbondingStartTime;
        bool isActive;
        uint256 reputation;         // 0-100
        uint256 deliveryCount;
        uint256 failureCount;
    }
    
    mapping(address => RelayInfo) public relays;
    
    event Staked(address indexed relay, uint256 amount);
    event UnstakeInitiated(address indexed relay, uint256 amount);
    event Unstaked(address indexed relay, uint256 amount);
    event Slashed(address indexed relay, uint256 amount, string reason);
    event RelayActivated(address indexed relay);
    event RelayDeactivated(address indexed relay);
    
    function stake(uint256 amount) external nonReentrant {
        require(amount >= MIN_STAKE, "Below minimum stake");
        require(mctToken.transferFrom(msg.sender, address(this), amount), "Transfer failed");
        
        relays[msg.sender].stakedAmount += amount;
        relays[msg.sender].isActive = true;
        relays[msg.sender].reputation = 50; // Start at 50%
        
        emit Staked(msg.sender, amount);
        emit RelayActivated(msg.sender);
    }
    
    function initiateUnstake(uint256 amount) external {
        RelayInfo storage relay = relays[msg.sender];
        require(relay.stakedAmount >= amount, "Insufficient stake");
        
        relay.stakedAmount -= amount;
        relay.unbondingAmount += amount;
        relay.unbondingStartTime = block.timestamp;
        
        if (relay.stakedAmount < MIN_STAKE) {
            relay.isActive = false;
            emit RelayDeactivated(msg.sender);
        }
        
        emit UnstakeInitiated(msg.sender, amount);
    }
    
    function completeUnstake() external nonReentrant {
        RelayInfo storage relay = relays[msg.sender];
        require(relay.unbondingAmount > 0, "Nothing to unstake");
        require(
            block.timestamp >= relay.unbondingStartTime + UNBONDING_PERIOD,
            "Unbonding period not complete"
        );
        
        uint256 amount = relay.unbondingAmount;
        relay.unbondingAmount = 0;
        relay.unbondingStartTime = 0;
        
        require(mctToken.transfer(msg.sender, amount), "Transfer failed");
        
        emit Unstaked(msg.sender, amount);
    }
    
    function slash(address relay, uint256 amount, string calldata reason) external {
        // Only callable by governance or automated slashing contract
        require(msg.sender == governance, "Not authorized");
        
        RelayInfo storage info = relays[relay];
        uint256 slashAmount = amount > info.stakedAmount ? info.stakedAmount : amount;
        
        info.stakedAmount -= slashAmount;
        info.reputation = info.reputation > 10 ? info.reputation - 10 : 0;
        
        if (info.stakedAmount < MIN_STAKE) {
            info.isActive = false;
            emit RelayDeactivated(relay);
        }
        
        // Slashed tokens go to treasury
        mctToken.transfer(treasury, slashAmount);
        
        emit Slashed(relay, slashAmount, reason);
    }
    
    function isActiveRelay(address relay) external view returns (bool) {
        return relays[relay].isActive && relays[relay].stakedAmount >= MIN_STAKE;
    }
    
    function getReputation(address relay) external view returns (uint256) {
        return relays[relay].reputation;
    }
}
```

### 4.2 Slashing Conditions

| Offense | Slash Amount | Reputation Impact |
|---------|--------------|-------------------|
| Message tampering | 50% of stake | -50 |
| Failing to deliver (proven) | 10% of stake | -20 |
| Spam/DoS behavior | 25% of stake | -30 |
| Lying about storage | 100% of stake | -100 (banned) |

---

## 5. ANDROID RELAY IMPLEMENTATION

### 5.1 Foreground Service

```kotlin
// RelayService.kt
@AndroidEntryPoint
class RelayService : Service() {
    
    @Inject lateinit var relayStorage: RelayStorage
    @Inject lateinit var p2pManager: P2PManager
    @Inject lateinit var rewardClaimer: RewardClaimer
    @Inject lateinit var config: RelayConfig
    
    private val binder = RelayBinder()
    private var isRunning = false
    
    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "relay_service"
        
        fun start(context: Context) {
            val intent = Intent(context, RelayService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
        
        fun stop(context: Context) {
            context.stopService(Intent(context, RelayService::class.java))
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        
        if (!isRunning) {
            isRunning = true
            startRelayOperations()
        }
        
        return START_STICKY
    }
    
    private fun startRelayOperations() {
        lifecycleScope.launch {
            // Announce as relay in DHT
            p2pManager.announceAsRelay()
            
            // Listen for incoming messages
            p2pManager.onRelayMessageReceived { message ->
                handleIncomingMessage(message)
            }
            
            // Listen for recipient queries
            p2pManager.onRecipientQuery { recipientHash ->
                handleRecipientQuery(recipientHash)
            }
            
            // Periodic tasks
            startPeriodicTasks()
        }
    }
    
    private suspend fun handleIncomingMessage(message: RelayMessage) {
        // Check if we can store
        if (!relayStorage.canAccept(message.size)) {
            p2pManager.rejectMessage(message.id, "Storage full")
            return
        }
        
        // Check if network conditions allow
        if (!isNetworkAllowed()) {
            p2pManager.rejectMessage(message.id, "Network not allowed")
            return
        }
        
        // Store the message
        val stored = relayStorage.store(message.toStoredMessage())
        
        if (stored) {
            p2pManager.acknowledgeMessage(message.id)
            updateNotification()
        } else {
            p2pManager.rejectMessage(message.id, "Storage failed")
        }
    }
    
    private suspend fun handleRecipientQuery(recipientHash: ByteArray) {
        val messages = relayStorage.getMessagesFor(recipientHash)
        
        for (message in messages) {
            // Attempt delivery
            val delivered = p2pManager.deliverToRecipient(message)
            
            if (delivered) {
                // Get proof of delivery
                val proof = p2pManager.awaitDeliveryProof(message.id)
                
                if (proof != null) {
                    // Delete from storage
                    relayStorage.delete(message.id)
                    
                    // Submit for reward
                    rewardClaimer.submitProof(proof)
                    
                    updateNotification()
                }
            }
        }
    }
    
    private fun isNetworkAllowed(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return when (config.networkPreference) {
            NetworkPreference.WIFI_ONLY -> {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
            NetworkPreference.ANY -> true
        }
    }
    
    private fun startPeriodicTasks() {
        // Cleanup expired messages every hour
        lifecycleScope.launch {
            while (isRunning) {
                delay(1.hours)
                relayStorage.cleanupExpired()
            }
        }
        
        // Claim pending rewards every 6 hours
        lifecycleScope.launch {
            while (isRunning) {
                delay(6.hours)
                rewardClaimer.claimPendingRewards()
            }
        }
    }
    
    private fun createNotification(): Notification {
        val stats = relayStorage.getStats()
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MumbleChat Relay Active")
            .setContentText("Storing ${stats.messageCount} messages (${stats.usedMB}MB)")
            .setSmallIcon(R.drawable.ic_relay)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_stop,
                "Stop Relay",
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, RelayService::class.java).apply {
                        action = "STOP"
                    },
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }
    
    override fun onBind(intent: Intent): IBinder = binder
    
    inner class RelayBinder : Binder() {
        fun getService(): RelayService = this@RelayService
    }
}
```

### 5.2 Battery Optimization

```kotlin
// RelayBatteryManager.kt
class RelayBatteryManager @Inject constructor(
    private val context: Context
) {
    // Check if we should continue relay operations
    fun shouldContinue(): Boolean {
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = batteryManager.isCharging
        
        return when {
            isCharging -> true                    // Always run when charging
            batteryLevel > 30 -> true             // Run normally above 30%
            batteryLevel > 15 -> {                // Reduced operations 15-30%
                // Only deliver, don't accept new
                true
            }
            else -> false                          // Stop below 15%
        }
    }
    
    // Adjust operations based on battery
    fun getOperationMode(): RelayOperationMode {
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = batteryManager.isCharging
        
        return when {
            isCharging -> RelayOperationMode.FULL
            batteryLevel > 50 -> RelayOperationMode.FULL
            batteryLevel > 30 -> RelayOperationMode.REDUCED
            batteryLevel > 15 -> RelayOperationMode.DELIVERY_ONLY
            else -> RelayOperationMode.STOPPED
        }
    }
}

enum class RelayOperationMode {
    FULL,           // Accept and deliver messages
    REDUCED,        // Lower frequency, smaller messages only
    DELIVERY_ONLY,  // Only deliver stored messages, don't accept new
    STOPPED         // Pause all relay operations
}
```

### 5.3 Relay Settings UI

```kotlin
// RelaySettingsFragment.kt
@AndroidEntryPoint
class RelaySettingsFragment : Fragment() {
    
    @Inject lateinit var relayConfig: RelayConfig
    @Inject lateinit var stakingManager: StakingManager
    
    private var _binding: FragmentRelaySettingsBinding? = null
    private val binding get() = _binding!!
    
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRelaySettingsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Enable/Disable Relay
        binding.switchEnableRelay.isChecked = relayConfig.isEnabled
        binding.switchEnableRelay.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                enableRelay()
            } else {
                disableRelay()
            }
        }
        
        // Storage Limit
        binding.sliderStorageLimit.value = relayConfig.storageLimitMB.toFloat()
        binding.sliderStorageLimit.addOnChangeListener { _, value, _ ->
            relayConfig.storageLimitMB = value.toInt()
            updateStorageText(value.toInt())
        }
        
        // Network Preference
        binding.radioGroupNetwork.check(
            when (relayConfig.networkPreference) {
                NetworkPreference.WIFI_ONLY -> R.id.radioWifiOnly
                NetworkPreference.ANY -> R.id.radioAnyNetwork
            }
        )
        binding.radioGroupNetwork.setOnCheckedChangeListener { _, checkedId ->
            relayConfig.networkPreference = when (checkedId) {
                R.id.radioWifiOnly -> NetworkPreference.WIFI_ONLY
                else -> NetworkPreference.ANY
            }
        }
        
        // Stats
        updateStats()
        
        // Stake Info
        updateStakeInfo()
    }
    
    private fun enableRelay() {
        lifecycleScope.launch {
            // Check stake
            val stake = stakingManager.getStake()
            if (stake < StakingManager.MIN_STAKE) {
                showStakeDialog()
                binding.switchEnableRelay.isChecked = false
                return@launch
            }
            
            relayConfig.isEnabled = true
            RelayService.start(requireContext())
        }
    }
    
    private fun disableRelay() {
        relayConfig.isEnabled = false
        RelayService.stop(requireContext())
    }
    
    private fun showStakeDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Stake Required")
            .setMessage("You need to stake at least 1000 MCT to run a relay node.")
            .setPositiveButton("Stake Now") { _, _ ->
                // Navigate to staking screen
                findNavController().navigate(R.id.action_relaySettings_to_staking)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun updateStats() {
        lifecycleScope.launch {
            val stats = RelayStats.get()
            binding.textMessagesStored.text = "${stats.messagesStored}"
            binding.textMessagesDelivered.text = "${stats.messagesDelivered}"
            binding.textStorageUsed.text = "${stats.storageUsedMB} MB / ${relayConfig.storageLimitMB} MB"
            binding.textRewardsEarned.text = "${stats.rewardsEarned} MCT"
        }
    }
}
```

---

## 6. RELAY SELECTION ALGORITHM

```kotlin
// RelaySelector.kt
class RelaySelector @Inject constructor(
    private val p2pManager: P2PManager
) {
    data class RelayCandidate(
        val address: String,
        val reputation: Int,
        val latency: Long,
        val availableStorage: Long
    )
    
    suspend fun selectBestRelays(
        messageSize: Long,
        count: Int = 3
    ): List<RelayCandidate> {
        // Query DHT for active relays
        val allRelays = p2pManager.getActiveRelays()
        
        // Filter and score
        return allRelays
            .filter { it.availableStorage >= messageSize }
            .sortedByDescending { calculateScore(it) }
            .take(count)
    }
    
    private fun calculateScore(relay: RelayCandidate): Double {
        // Higher reputation = better
        val reputationScore = relay.reputation / 100.0
        
        // Lower latency = better
        val latencyScore = 1.0 - (relay.latency.coerceAtMost(5000) / 5000.0)
        
        // More storage = better
        val storageScore = (relay.availableStorage / (5L * 1024 * 1024 * 1024)).coerceAtMost(1.0)
        
        // Weighted combination
        return (reputationScore * 0.5) + (latencyScore * 0.3) + (storageScore * 0.2)
    }
}
```

---

## 7. REWARD CLAIMING

```kotlin
// RewardClaimer.kt
class RewardClaimer @Inject constructor(
    private val web3j: Web3j,
    private val credentials: Credentials,
    private val contractAddress: String
) {
    private val pendingProofs = mutableListOf<DeliveryProof>()
    
    // Add proof to pending list
    fun submitProof(proof: DeliveryProof) {
        pendingProofs.add(proof)
    }
    
    // Batch claim rewards (to save gas)
    suspend fun claimPendingRewards() {
        if (pendingProofs.isEmpty()) return
        
        val proofsToClaim = pendingProofs.take(50) // Max 50 per batch
        
        try {
            val contract = RelayRewards.load(contractAddress, web3j, credentials, DefaultGasProvider())
            
            // Convert proofs to contract format
            val messageIds = proofsToClaim.map { it.messageId }
            val recipients = proofsToClaim.map { it.recipientAddress }
            val signatures = proofsToClaim.map { it.recipientSignature }
            val sizes = proofsToClaim.map { BigInteger.valueOf(it.messageSize) }
            val storageTimes = proofsToClaim.map { 
                BigInteger.valueOf(it.deliveredAt - it.storedAt) 
            }
            
            // Submit batch claim
            val tx = contract.claimRewards(
                messageIds,
                recipients,
                signatures,
                sizes,
                storageTimes
            ).send()
            
            if (tx.isStatusOK) {
                pendingProofs.removeAll(proofsToClaim.toSet())
                Timber.i("Claimed rewards for ${proofsToClaim.size} deliveries")
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to claim rewards")
        }
    }
}
```
