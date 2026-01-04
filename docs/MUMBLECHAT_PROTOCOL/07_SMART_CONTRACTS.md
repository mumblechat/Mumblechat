# MumbleChat Protocol - Smart Contracts

## Part 7 of 8

---

## 1. CONTRACT OVERVIEW

### 1.1 Contract Architecture (V3.x - UUPS Upgradeable)

```
┌─────────────────────────────────────────────────────────────────┐
│                    RAMESTTA MAINNET (Chain ID: 1370)            │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌────────────────────────────┐    ┌────────────────────────┐   │
│  │  MumbleChatRegistry V3.2   │    │     MCTToken V3        │   │
│  │  (UUPS Proxy)              │    │     (UUPS Proxy)       │   │
│  │                            │    │                        │   │
│  │  • Register identity       │◄──►│  • ERC-20 Token       │   │
│  │  • Store pubKeys           │    │  • 1M max supply      │   │
│  │  • GB-Scale Tier System    │    │  • Halving rewards    │   │
│  │  • Daily Pool (10-40%)     │    │  • Fee pool (0.1%)    │   │
│  │  • Uptime/Storage tracking │    │  • Governance votes   │   │
│  │                            │    │                        │   │
│  └────────────────────────────┘    └────────────────────────┘   │
│                                                                  │
│  ═══════════════════════════════════════════════════════════    │
│                                                                  │
│  TIER SYSTEM (Built into Registry V3.2 - GB Scale):             │
│  ┌─────────┬────────────┬──────────┬─────────┬────────────┐     │
│  │  Tier   │  Uptime    │  Storage │ Pool %  │ Fee Bonus  │     │
│  ├─────────┼────────────┼──────────┼─────────┼────────────┤     │
│  │ Bronze  │  4+h/day   │  1 GB    │  10%    │  1.0x      │     │
│  │ Silver  │  8+h/day   │  2 GB    │  20%    │  1.5x      │     │
│  │ Gold    │  12+h/day  │  4 GB    │  30%    │  2.0x      │     │
│  │ Platinum│  16+h/day  │  8+ GB   │  40%    │  3.0x      │     │
│  └─────────┴────────────┴──────────┴─────────┴────────────┘     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 1.2 Deployment Addresses (DEPLOYED ✅)

| Contract | Type | Proxy Address | Implementation |
|----------|------|---------------|----------------|
| MCTToken V3 | UUPS Proxy | `0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE` | `0xC76ea6934D24615E9A348C5eF5Aed54E638A5AAD` |
| MumbleChatRegistry V3.2 | UUPS Proxy | `0x4f8D4955F370881B05b68D2344345E749d8632e3` | `0xe73f98b22528E34eDE1Ee4AD6facF704ED5dF8C3` |

> **Note:** RelayStaking functionality is integrated into MCTToken V3 (fee pool) and MumbleChatRegistry V2 (tier tracking). No separate RelayStaking contract needed.

---

## 2. MCT TOKEN CONTRACT V3

### 2.1 MCTToken.sol (UUPS Upgradeable)

```solidity
// SPDX-License-Identifier: MIT
pragma solidity ^0.8.22;

import "@openzeppelin/contracts-upgradeable/token/ERC20/ERC20Upgradeable.sol";
import "@openzeppelin/contracts-upgradeable/token/ERC20/extensions/ERC20BurnableUpgradeable.sol";
import "@openzeppelin/contracts-upgradeable/access/OwnableUpgradeable.sol";
import "@openzeppelin/contracts-upgradeable/proxy/utils/Initializable.sol";
import "@openzeppelin/contracts-upgradeable/proxy/utils/UUPSUpgradeable.sol";

/**
 * @title MCTToken V3 (MumbleChat Token)
 * @dev ERC-20 token for MumbleChat protocol on Ramestta blockchain
 * 
 * SUSTAINABLE TOKENOMICS:
 * ════════════════════════════════════════════════════════════════
 * - Initial Supply: 1,000 MCT (minted at deploy)
 * - Max Supply: 1,000,000 MCT (changeable via 90% governance vote)
 * - Relay Reward: 0.001 MCT per 1,000 messages relayed
 * - Halving: Every 100,000 MCT minted, reward halves
 * - Daily Cap: Max 100 MCT minted per day
 * - Transfer Fee: 0.1% redistributed to relay nodes via fee pool
 * - Governance: 90% relay node vote can change max supply
 * 
 * POST-MINTING ERA:
 * After max supply reached, nodes earn from transfer fees instead.
 * ════════════════════════════════════════════════════════════════
 */
contract MCTToken is 
    Initializable, 
    ERC20Upgradeable, 
    ERC20BurnableUpgradeable, 
    OwnableUpgradeable, 
    UUPSUpgradeable 
{
    // ============ Constants ============
    
    uint256 public constant BASE_REWARD_PER_1000_MSG = 1 * 10 ** 15; // 0.001 MCT
    uint256 public constant HALVING_THRESHOLD = 100_000 * 10 ** 18;  // Every 100k MCT
    uint256 public constant DAILY_MINT_CAP = 100 * 10 ** 18;         // 100 MCT/day
    uint256 public constant MESSAGES_PER_REWARD = 1000;
    uint256 public constant TRANSFER_FEE_BPS = 10;                   // 0.1%
    uint256 public constant GOVERNANCE_THRESHOLD = 90;               // 90% vote
    uint256 public constant VOTING_PERIOD = 7 days;
    
    // ============ State Variables ============
    
    uint256 public maxSupply;              // Governance-adjustable max
    uint256 public totalRewardsMinted;     // For halving calculation
    uint256 public currentDay;             // Daily tracking
    uint256 public mintedToday;
    uint256 public feePool;                // Accumulated transfer fees
    address public registryContract;       // MumbleChatRegistry V2
    
    // ============ Governance ============
    
    struct Proposal {
        uint256 newMaxSupply;
        uint256 startTime;
        uint256 endTime;
        uint256 yesVotes;
        uint256 noVotes;
        bool executed;
        mapping(address => bool) hasVoted;
    }
    
    uint256 public proposalCount;
    mapping(uint256 => Proposal) public proposals;
    uint256 public activeRelayCount;
    
    // ============ Events ============
    
    event RelayRewardMinted(address indexed relayNode, uint256 amount, uint256 messagesRelayed);
    event FeeRewardClaimed(address indexed relayNode, uint256 amount);
    event HalvingOccurred(uint256 newRewardAmount, uint256 halvingCount);
    event TransferFeeCollected(uint256 amount);
    event ProposalCreated(uint256 indexed proposalId, uint256 newMaxSupply, address proposer);
    event ProposalExecuted(uint256 indexed proposalId, uint256 newMaxSupply);
    
    /// @custom:oz-upgrades-unsafe-allow constructor
    constructor() { _disableInitializers(); }

    function initialize(address initialOwner) public initializer {
        __ERC20_init("MumbleChat Token", "MCT");
        __ERC20Burnable_init();
        __Ownable_init(initialOwner);
        __UUPSUpgradeable_init();

        maxSupply = 1_000_000 * 10 ** 18;  // 1M max
        _mint(initialOwner, 1_000 * 10 ** decimals());  // 1k initial
        currentDay = block.timestamp / 1 days;
    }
    
    // ============ Transfer with Fee ============
    
    function transfer(address to, uint256 amount) public virtual override returns (bool) {
        uint256 fee = (amount * TRANSFER_FEE_BPS) / 10000;
        uint256 amountAfterFee = amount - fee;
        
        _transfer(_msgSender(), to, amountAfterFee);
        if (fee > 0) {
            _transfer(_msgSender(), address(this), fee);
            feePool += fee;
            emit TransferFeeCollected(fee);
        }
        return true;
    }
    
    // ============ Fee Pool Distribution (TIER-BASED) ============
    
    /**
     * @dev Claim share of fee pool based on TIER multiplier
     * Tier bonuses ONLY apply to fee pool, NOT to minting rewards
     */
    function claimFeeReward(address relayNode, uint256 tierMultiplier) external returns (uint256) {
        require(msg.sender == registryContract, "Only registry");
        require(tierMultiplier >= 100, "Invalid multiplier");
        
        if (feePool == 0 || activeRelayCount == 0) return 0;
        
        // Base share = feePool / activeRelayCount
        // Tier bonus applies: baseShare * tierMultiplier / 100
        uint256 baseShare = feePool / activeRelayCount;
        uint256 reward = (baseShare * tierMultiplier) / 100;
        
        if (reward > feePool) reward = feePool;
        feePool -= reward;
        _transfer(address(this), relayNode, reward);
        
        emit FeeRewardClaimed(relayNode, reward);
        return reward;
    }
    
    // ============ Minting Rewards (1x only, no tier bonus) ============
    
    function mintRelayReward(address relayNode, uint256 messagesRelayed) external returns (uint256) {
        require(msg.sender == registryContract, "Only registry");
        
        // Check daily cap
        uint256 today = block.timestamp / 1 days;
        if (today != currentDay) {
            currentDay = today;
            mintedToday = 0;
        }
        
        // Calculate reward with halving
        uint256 halvings = totalRewardsMinted / HALVING_THRESHOLD;
        uint256 rewardPerBatch = BASE_REWARD_PER_1000_MSG >> halvings;
        
        uint256 batches = messagesRelayed / MESSAGES_PER_REWARD;
        uint256 reward = batches * rewardPerBatch;
        
        // Apply caps
        if (mintedToday + reward > DAILY_MINT_CAP) {
            reward = DAILY_MINT_CAP - mintedToday;
        }
        if (totalSupply() + reward > maxSupply) {
            reward = maxSupply - totalSupply();
        }
        
        if (reward > 0) {
            _mint(relayNode, reward);
            mintedToday += reward;
            totalRewardsMinted += reward;
            emit RelayRewardMinted(relayNode, reward, messagesRelayed);
        }
        
        return reward;
    }
    
    function _authorizeUpgrade(address newImplementation) internal override onlyOwner {}
}
```

### 2.2 Key Features of MCT V3

| Feature | Description |
|---------|-------------|
| **Sustainable Supply** | 1M max supply with halving every 100k minted |
| **Daily Cap** | Max 100 MCT minted per day prevents inflation |
| **Fee Pool** | 0.1% transfer fee redistributed to relay nodes |
| **Tier Bonuses** | Only applies to fee pool, NOT minting rewards |
| **Governance** | 90% relay node vote can adjust max supply |
| **UUPS Upgradeable** | Can be upgraded without changing proxy address |
        }
        
        uint256 elapsed = block.timestamp - VESTING_START;
        if (elapsed >= DEV_VESTING_DURATION) {
            return DEV_POOL;
        }
        
        return (DEV_POOL * elapsed) / DEV_VESTING_DURATION;
    }
    
    /**
     * @dev Pause token transfers.
     */
    function pause() external onlyRole(PAUSER_ROLE) {
        _pause();
    }
    
    /**
     * @dev Unpause token transfers.
     */
    function unpause() external onlyRole(PAUSER_ROLE) {
        _unpause();
    }
    
    function _beforeTokenTransfer(
        address from,
        address to,
        uint256 amount
    ) internal override whenNotPaused {
        super._beforeTokenTransfer(from, to, amount);
    }
}
```

---

## 3. MUMBLECHAT REGISTRY

### 3.1 MumbleChatRegistry.sol

```solidity
// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import "@openzeppelin/contracts/access/Ownable.sol";
import "@openzeppelin/contracts/security/ReentrancyGuard.sol";

/**
 * @title MumbleChat Registry
 * @dev On-chain identity registry for MumbleChat protocol.
 * Maps wallet addresses to chat public keys.
 */
contract MumbleChatRegistry is Ownable, ReentrancyGuard {
    
    // Identity structure
    struct ChatIdentity {
        bytes32 identityPubKey;       // Ed25519 public key (32 bytes)
        bytes32 sessionPubKey;        // X25519 session key for encryption
        string metadataUri;           // IPFS URI for profile metadata (optional)
        uint256 registeredAt;
        uint256 updatedAt;
        bool isActive;
    }
    
    // Mappings
    mapping(address => ChatIdentity) public identities;
    mapping(bytes32 => address) public pubKeyToAddress;  // Reverse lookup
    
    // Statistics
    uint256 public totalRegistrations;
    uint256 public activeUsers;
    
    // Events
    event IdentityRegistered(
        address indexed wallet,
        bytes32 identityPubKey,
        bytes32 sessionPubKey,
        uint256 timestamp
    );
    
    event IdentityUpdated(
        address indexed wallet,
        bytes32 newSessionPubKey,
        uint256 timestamp
    );
    
    event IdentityDeactivated(address indexed wallet, uint256 timestamp);
    event IdentityReactivated(address indexed wallet, uint256 timestamp);
    event MetadataUpdated(address indexed wallet, string metadataUri);
    
    // Errors
    error AlreadyRegistered();
    error NotRegistered();
    error InvalidPubKey();
    error PubKeyAlreadyUsed();
    error IdentityInactive();
    
    constructor() Ownable() {}
    
    /**
     * @dev Register a new chat identity.
     * @param identityPubKey Ed25519 public key for signing (32 bytes)
     * @param sessionPubKey X25519 public key for encryption (32 bytes)
     */
    function registerIdentity(
        bytes32 identityPubKey,
        bytes32 sessionPubKey
    ) external nonReentrant {
        if (identities[msg.sender].registeredAt != 0) {
            revert AlreadyRegistered();
        }
        
        if (identityPubKey == bytes32(0) || sessionPubKey == bytes32(0)) {
            revert InvalidPubKey();
        }
        
        if (pubKeyToAddress[identityPubKey] != address(0)) {
            revert PubKeyAlreadyUsed();
        }
        
        identities[msg.sender] = ChatIdentity({
            identityPubKey: identityPubKey,
            sessionPubKey: sessionPubKey,
            metadataUri: "",
            registeredAt: block.timestamp,
            updatedAt: block.timestamp,
            isActive: true
        });
        
        pubKeyToAddress[identityPubKey] = msg.sender;
        
        totalRegistrations++;
        activeUsers++;
        
        emit IdentityRegistered(
            msg.sender,
            identityPubKey,
            sessionPubKey,
            block.timestamp
        );
    }
    
    /**
     * @dev Update session public key (key rotation).
     * @param newSessionPubKey New X25519 session key
     */
    function updateSessionKey(bytes32 newSessionPubKey) external {
        if (identities[msg.sender].registeredAt == 0) {
            revert NotRegistered();
        }
        
        if (newSessionPubKey == bytes32(0)) {
            revert InvalidPubKey();
        }
        
        identities[msg.sender].sessionPubKey = newSessionPubKey;
        identities[msg.sender].updatedAt = block.timestamp;
        
        emit IdentityUpdated(msg.sender, newSessionPubKey, block.timestamp);
    }
    
    /**
     * @dev Update profile metadata URI (IPFS hash).
     * @param metadataUri IPFS URI containing profile data
     */
    function updateMetadata(string calldata metadataUri) external {
        if (identities[msg.sender].registeredAt == 0) {
            revert NotRegistered();
        }
        
        identities[msg.sender].metadataUri = metadataUri;
        identities[msg.sender].updatedAt = block.timestamp;
        
        emit MetadataUpdated(msg.sender, metadataUri);
    }
    
    /**
     * @dev Deactivate chat identity (temporarily disable).
     */
    function deactivateIdentity() external {
        if (identities[msg.sender].registeredAt == 0) {
            revert NotRegistered();
        }
        
        identities[msg.sender].isActive = false;
        identities[msg.sender].updatedAt = block.timestamp;
        activeUsers--;
        
        emit IdentityDeactivated(msg.sender, block.timestamp);
    }
    
    /**
     * @dev Reactivate chat identity.
     */
    function reactivateIdentity() external {
        if (identities[msg.sender].registeredAt == 0) {
            revert NotRegistered();
        }
        
        identities[msg.sender].isActive = true;
        identities[msg.sender].updatedAt = block.timestamp;
        activeUsers++;
        
        emit IdentityReactivated(msg.sender, block.timestamp);
    }
    
    // ============ View Functions ============
    
    /**
     * @dev Check if an address is registered.
     */
    function isRegistered(address wallet) external view returns (bool) {
        return identities[wallet].registeredAt != 0;
    }
    
    /**
     * @dev Check if an address is active.
     */
    function isActive(address wallet) external view returns (bool) {
        return identities[wallet].isActive;
    }
    
    /**
     * @dev Get identity public key.
     */
    function getIdentityPubKey(address wallet) external view returns (bytes32) {
        return identities[wallet].identityPubKey;
    }
    
    /**
     * @dev Get session public key.
     */
    function getSessionPubKey(address wallet) external view returns (bytes32) {
        if (!identities[wallet].isActive) {
            revert IdentityInactive();
        }
        return identities[wallet].sessionPubKey;
    }
    
    /**
     * @dev Get full identity info.
     */
    function getIdentity(address wallet) external view returns (ChatIdentity memory) {
        return identities[wallet];
    }
    
    /**
     * @dev Get address from identity public key.
     */
    function getAddressFromPubKey(bytes32 pubKey) external view returns (address) {
        return pubKeyToAddress[pubKey];
    }
    
    /**
     * @dev Batch lookup multiple addresses.
     */
    function batchGetSessionKeys(
        address[] calldata wallets
    ) external view returns (bytes32[] memory) {
        bytes32[] memory keys = new bytes32[](wallets.length);
        for (uint256 i = 0; i < wallets.length; i++) {
            if (identities[wallets[i]].isActive) {
                keys[i] = identities[wallets[i]].sessionPubKey;
            }
        }
        return keys;
    }
}
```

---

## 4. RELAY STAKING CONTRACT

### 4.1 RelayStaking.sol

```solidity
// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import "@openzeppelin/contracts/access/AccessControl.sol";
import "@openzeppelin/contracts/security/ReentrancyGuard.sol";
import "@openzeppelin/contracts/security/Pausable.sol";
import "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";

interface IMCTToken {
    function mintRelayRewards(address to, uint256 amount) external;
}

/**
 * @title Relay Staking
 * @dev Manages relay node staking, performance tracking, and rewards.
 */
contract RelayStaking is AccessControl, ReentrancyGuard, Pausable {
    using SafeERC20 for IERC20;
    
    bytes32 public constant ORACLE_ROLE = keccak256("ORACLE_ROLE");
    bytes32 public constant SLASHER_ROLE = keccak256("SLASHER_ROLE");
    
    // Constants
    uint256 public constant MIN_STAKE = 10_000 * 10**18;     // 10,000 MCT minimum
    uint256 public constant LOCKUP_PERIOD = 7 days;
    uint256 public constant REWARD_PER_MESSAGE = 0.001 * 10**18;  // 0.001 MCT per message
    uint256 public constant MAX_SLASH_PERCENT = 50;           // Max 50% can be slashed
    uint256 public constant EPOCH_DURATION = 1 days;
    
    IERC20 public mctToken;
    IMCTToken public mctMinter;
    
    // Relay structure
    struct RelayNode {
        address owner;
        uint256 stakedAmount;
        uint256 stakedAt;
        uint256 unlockRequestTime;
        
        // Performance metrics
        uint256 messagesRelayed;
        uint256 messagesDelivered;
        uint256 totalUptime;           // In seconds
        uint256 lastHeartbeat;
        
        // Rewards
        uint256 pendingRewards;
        uint256 totalRewardsClaimed;
        
        // Status
        bool isActive;
        bool isSlashed;
        uint256 slashedAmount;
    }
    
    // Relay information for clients
    struct RelayInfo {
        address relayAddress;
        string endpoint;               // P2P multiaddr or hostname
        uint256 stakedAmount;
        uint256 successRate;           // 0-10000 (basis points)
        uint256 uptime;                // 0-10000 (basis points)
        bool isActive;
    }
    
    // Mappings
    mapping(address => RelayNode) public relays;
    mapping(address => string) public relayEndpoints;
    address[] public relayList;
    
    // Epoch tracking
    uint256 public currentEpoch;
    uint256 public epochStartTime;
    mapping(uint256 => mapping(address => uint256)) public epochMessages;
    
    // Statistics
    uint256 public totalStaked;
    uint256 public activeRelayCount;
    uint256 public totalMessagesRelayed;
    
    // Events
    event RelayRegistered(address indexed relay, uint256 amount, string endpoint);
    event StakeIncreased(address indexed relay, uint256 additionalAmount);
    event UnlockRequested(address indexed relay, uint256 unlockTime);
    event StakeWithdrawn(address indexed relay, uint256 amount);
    event RelayDeactivated(address indexed relay);
    event RelayReactivated(address indexed relay);
    event MessageRelayed(address indexed relay, bytes32 messageHash, address recipient);
    event MessageDelivered(address indexed relay, bytes32 messageHash);
    event RewardsClaimed(address indexed relay, uint256 amount);
    event RelaySlashed(address indexed relay, uint256 amount, string reason);
    event HeartbeatReceived(address indexed relay, uint256 timestamp);
    event EndpointUpdated(address indexed relay, string newEndpoint);
    
    // Errors
    error InsufficientStake();
    error AlreadyRegistered();
    error NotRegistered();
    error StakeLocked();
    error RelayNotActive();
    error InvalidEndpoint();
    error NoRewardsToClaim();
    error SlashExceedsMax();
    
    constructor(address _mctToken) {
        mctToken = IERC20(_mctToken);
        mctMinter = IMCTToken(_mctToken);
        _grantRole(DEFAULT_ADMIN_ROLE, msg.sender);
        
        epochStartTime = block.timestamp;
        currentEpoch = 0;
    }
    
    /**
     * @dev Register as a relay node.
     * @param amount Amount of MCT to stake
     * @param endpoint P2P endpoint (multiaddr format)
     */
    function registerRelay(
        uint256 amount,
        string calldata endpoint
    ) external nonReentrant whenNotPaused {
        if (amount < MIN_STAKE) {
            revert InsufficientStake();
        }
        
        if (relays[msg.sender].stakedAt != 0) {
            revert AlreadyRegistered();
        }
        
        if (bytes(endpoint).length == 0) {
            revert InvalidEndpoint();
        }
        
        // Transfer stake
        mctToken.safeTransferFrom(msg.sender, address(this), amount);
        
        relays[msg.sender] = RelayNode({
            owner: msg.sender,
            stakedAmount: amount,
            stakedAt: block.timestamp,
            unlockRequestTime: 0,
            messagesRelayed: 0,
            messagesDelivered: 0,
            totalUptime: 0,
            lastHeartbeat: block.timestamp,
            pendingRewards: 0,
            totalRewardsClaimed: 0,
            isActive: true,
            isSlashed: false,
            slashedAmount: 0
        });
        
        relayEndpoints[msg.sender] = endpoint;
        relayList.push(msg.sender);
        
        totalStaked += amount;
        activeRelayCount++;
        
        emit RelayRegistered(msg.sender, amount, endpoint);
    }
    
    /**
     * @dev Increase stake on existing relay.
     */
    function increaseStake(uint256 amount) external nonReentrant {
        if (relays[msg.sender].stakedAt == 0) {
            revert NotRegistered();
        }
        
        mctToken.safeTransferFrom(msg.sender, address(this), amount);
        
        relays[msg.sender].stakedAmount += amount;
        totalStaked += amount;
        
        // Cancel any pending unlock
        relays[msg.sender].unlockRequestTime = 0;
        
        emit StakeIncreased(msg.sender, amount);
    }
    
    /**
     * @dev Request to unlock stake.
     */
    function requestUnlock() external {
        if (relays[msg.sender].stakedAt == 0) {
            revert NotRegistered();
        }
        
        relays[msg.sender].unlockRequestTime = block.timestamp;
        relays[msg.sender].isActive = false;
        activeRelayCount--;
        
        emit UnlockRequested(msg.sender, block.timestamp + LOCKUP_PERIOD);
    }
    
    /**
     * @dev Withdraw staked tokens after lockup.
     */
    function withdrawStake() external nonReentrant {
        RelayNode storage relay = relays[msg.sender];
        
        if (relay.stakedAt == 0) {
            revert NotRegistered();
        }
        
        if (relay.unlockRequestTime == 0) {
            revert StakeLocked();
        }
        
        if (block.timestamp < relay.unlockRequestTime + LOCKUP_PERIOD) {
            revert StakeLocked();
        }
        
        uint256 amount = relay.stakedAmount;
        
        totalStaked -= amount;
        
        // Clear relay data but keep history
        relay.stakedAmount = 0;
        relay.isActive = false;
        
        mctToken.safeTransfer(msg.sender, amount);
        
        emit StakeWithdrawn(msg.sender, amount);
    }
    
    /**
     * @dev Report message relayed by relay node.
     * Called by oracle with signed proof.
     */
    function reportMessageRelayed(
        address relayAddress,
        bytes32 messageHash,
        address recipient,
        bytes calldata signature
    ) external onlyRole(ORACLE_ROLE) {
        // Verify signature from relay
        // ... signature verification logic ...
        
        RelayNode storage relay = relays[relayAddress];
        
        if (!relay.isActive) {
            revert RelayNotActive();
        }
        
        relay.messagesRelayed++;
        totalMessagesRelayed++;
        epochMessages[currentEpoch][relayAddress]++;
        
        // Calculate reward
        uint256 reward = REWARD_PER_MESSAGE;
        relay.pendingRewards += reward;
        
        emit MessageRelayed(relayAddress, messageHash, recipient);
    }
    
    /**
     * @dev Report message delivered (recipient came online).
     */
    function reportMessageDelivered(
        address relayAddress,
        bytes32 messageHash,
        bytes calldata recipientSignature
    ) external onlyRole(ORACLE_ROLE) {
        // Verify recipient signature
        // ... signature verification logic ...
        
        RelayNode storage relay = relays[relayAddress];
        relay.messagesDelivered++;
        
        // Bonus reward for successful delivery
        uint256 bonus = REWARD_PER_MESSAGE / 2;
        relay.pendingRewards += bonus;
        
        emit MessageDelivered(relayAddress, messageHash);
    }
    
    /**
     * @dev Relay heartbeat to prove uptime.
     */
    function heartbeat() external {
        RelayNode storage relay = relays[msg.sender];
        
        if (!relay.isActive) {
            revert RelayNotActive();
        }
        
        uint256 elapsed = block.timestamp - relay.lastHeartbeat;
        if (elapsed <= 10 minutes) {
            // Update uptime
            relay.totalUptime += elapsed;
        }
        
        relay.lastHeartbeat = block.timestamp;
        
        emit HeartbeatReceived(msg.sender, block.timestamp);
    }
    
    /**
     * @dev Claim accumulated rewards.
     */
    function claimRewards() external nonReentrant {
        RelayNode storage relay = relays[msg.sender];
        
        uint256 amount = relay.pendingRewards;
        if (amount == 0) {
            revert NoRewardsToClaim();
        }
        
        relay.pendingRewards = 0;
        relay.totalRewardsClaimed += amount;
        
        // Mint rewards from MCT token
        mctMinter.mintRelayRewards(msg.sender, amount);
        
        emit RewardsClaimed(msg.sender, amount);
    }
    
    /**
     * @dev Slash a relay for misbehavior.
     */
    function slashRelay(
        address relayAddress,
        uint256 percentage,
        string calldata reason
    ) external onlyRole(SLASHER_ROLE) {
        if (percentage > MAX_SLASH_PERCENT) {
            revert SlashExceedsMax();
        }
        
        RelayNode storage relay = relays[relayAddress];
        
        uint256 slashAmount = (relay.stakedAmount * percentage) / 100;
        
        relay.stakedAmount -= slashAmount;
        relay.isSlashed = true;
        relay.slashedAmount += slashAmount;
        
        totalStaked -= slashAmount;
        
        // Burn slashed tokens or send to treasury
        mctToken.safeTransfer(address(0xdead), slashAmount);
        
        emit RelaySlashed(relayAddress, slashAmount, reason);
    }
    
    /**
     * @dev Update relay endpoint.
     */
    function updateEndpoint(string calldata newEndpoint) external {
        if (relays[msg.sender].stakedAt == 0) {
            revert NotRegistered();
        }
        
        if (bytes(newEndpoint).length == 0) {
            revert InvalidEndpoint();
        }
        
        relayEndpoints[msg.sender] = newEndpoint;
        
        emit EndpointUpdated(msg.sender, newEndpoint);
    }
    
    /**
     * @dev Advance to next epoch.
     */
    function advanceEpoch() external {
        require(
            block.timestamp >= epochStartTime + EPOCH_DURATION,
            "Epoch not complete"
        );
        
        currentEpoch++;
        epochStartTime = block.timestamp;
    }
    
    // ============ View Functions ============
    
    /**
     * @dev Get active relay list for clients.
     */
    function getActiveRelays() external view returns (RelayInfo[] memory) {
        uint256 count = 0;
        
        // Count active relays
        for (uint256 i = 0; i < relayList.length; i++) {
            if (relays[relayList[i]].isActive) {
                count++;
            }
        }
        
        RelayInfo[] memory activeRelays = new RelayInfo[](count);
        uint256 index = 0;
        
        for (uint256 i = 0; i < relayList.length; i++) {
            RelayNode storage relay = relays[relayList[i]];
            if (relay.isActive) {
                uint256 successRate = relay.messagesRelayed > 0
                    ? (relay.messagesDelivered * 10000) / relay.messagesRelayed
                    : 0;
                
                activeRelays[index] = RelayInfo({
                    relayAddress: relayList[i],
                    endpoint: relayEndpoints[relayList[i]],
                    stakedAmount: relay.stakedAmount,
                    successRate: successRate,
                    uptime: 10000, // TODO: Calculate actual uptime
                    isActive: true
                });
                index++;
            }
        }
        
        return activeRelays;
    }
    
    /**
     * @dev Get relay info.
     */
    function getRelayInfo(address relayAddress) external view returns (RelayNode memory) {
        return relays[relayAddress];
    }
    
    /**
     * @dev Get relay endpoint.
     */
    function getRelayEndpoint(address relayAddress) external view returns (string memory) {
        return relayEndpoints[relayAddress];
    }
    
    /**
     * @dev Get total relay count.
     */
    function getTotalRelayCount() external view returns (uint256) {
        return relayList.length;
    }
}
```

---

## 5. GROUP REGISTRY CONTRACT

### 5.1 GroupRegistry.sol

```solidity
// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import "@openzeppelin/contracts/access/Ownable.sol";
import "@openzeppelin/contracts/utils/cryptography/ECDSA.sol";
import "@openzeppelin/contracts/utils/cryptography/MessageHashUtils.sol";

/**
 * @title Group Registry
 * @dev On-chain registry for MumbleChat group metadata.
 * Stores minimal on-chain data, actual group keys are exchanged via P2P.
 */
contract GroupRegistry is Ownable {
    using ECDSA for bytes32;
    using MessageHashUtils for bytes32;
    
    // Group structure
    struct Group {
        bytes32 groupId;              // Unique group identifier
        address creator;
        string name;
        string metadataUri;           // IPFS hash for avatar, description
        uint256 createdAt;
        uint256 memberCount;
        uint256 currentKeyVersion;    // Key rotation version
        bool isPublic;                // Public groups can be discovered
        bool isActive;
    }
    
    // Mappings
    mapping(bytes32 => Group) public groups;
    mapping(bytes32 => address[]) private groupAdmins;
    mapping(bytes32 => mapping(address => bool)) private isAdmin;
    
    bytes32[] public publicGroups;
    
    // Events
    event GroupCreated(
        bytes32 indexed groupId,
        address indexed creator,
        string name,
        bool isPublic
    );
    event GroupUpdated(bytes32 indexed groupId, string name, string metadataUri);
    event AdminAdded(bytes32 indexed groupId, address indexed admin);
    event AdminRemoved(bytes32 indexed groupId, address indexed admin);
    event KeyRotated(bytes32 indexed groupId, uint256 newVersion);
    event GroupDeactivated(bytes32 indexed groupId);
    event MemberCountUpdated(bytes32 indexed groupId, uint256 newCount);
    
    // Errors
    error GroupAlreadyExists();
    error GroupNotFound();
    error NotAdmin();
    error InvalidGroupId();
    error GroupInactive();
    
    /**
     * @dev Create a new group.
     * @param groupId Unique group identifier (client-generated)
     * @param name Group name
     * @param isPublic Whether group is publicly discoverable
     */
    function createGroup(
        bytes32 groupId,
        string calldata name,
        bool isPublic
    ) external {
        if (groupId == bytes32(0)) {
            revert InvalidGroupId();
        }
        
        if (groups[groupId].createdAt != 0) {
            revert GroupAlreadyExists();
        }
        
        groups[groupId] = Group({
            groupId: groupId,
            creator: msg.sender,
            name: name,
            metadataUri: "",
            createdAt: block.timestamp,
            memberCount: 1,
            currentKeyVersion: 1,
            isPublic: isPublic,
            isActive: true
        });
        
        groupAdmins[groupId].push(msg.sender);
        isAdmin[groupId][msg.sender] = true;
        
        if (isPublic) {
            publicGroups.push(groupId);
        }
        
        emit GroupCreated(groupId, msg.sender, name, isPublic);
    }
    
    /**
     * @dev Update group metadata.
     */
    function updateGroup(
        bytes32 groupId,
        string calldata name,
        string calldata metadataUri
    ) external {
        if (!isAdmin[groupId][msg.sender]) {
            revert NotAdmin();
        }
        
        Group storage group = groups[groupId];
        if (!group.isActive) {
            revert GroupInactive();
        }
        
        group.name = name;
        group.metadataUri = metadataUri;
        
        emit GroupUpdated(groupId, name, metadataUri);
    }
    
    /**
     * @dev Add admin to group.
     */
    function addAdmin(bytes32 groupId, address newAdmin) external {
        if (!isAdmin[groupId][msg.sender]) {
            revert NotAdmin();
        }
        
        if (!isAdmin[groupId][newAdmin]) {
            groupAdmins[groupId].push(newAdmin);
            isAdmin[groupId][newAdmin] = true;
            
            emit AdminAdded(groupId, newAdmin);
        }
    }
    
    /**
     * @dev Remove admin from group.
     */
    function removeAdmin(bytes32 groupId, address admin) external {
        if (groups[groupId].creator != msg.sender) {
            revert NotAdmin(); // Only creator can remove admins
        }
        
        if (admin == msg.sender) {
            revert NotAdmin(); // Can't remove yourself
        }
        
        isAdmin[groupId][admin] = false;
        
        emit AdminRemoved(groupId, admin);
    }
    
    /**
     * @dev Rotate group key (increment version).
     * Called when members leave to invalidate old key.
     */
    function rotateKey(bytes32 groupId) external {
        if (!isAdmin[groupId][msg.sender]) {
            revert NotAdmin();
        }
        
        groups[groupId].currentKeyVersion++;
        
        emit KeyRotated(groupId, groups[groupId].currentKeyVersion);
    }
    
    /**
     * @dev Update member count.
     */
    function updateMemberCount(bytes32 groupId, uint256 newCount) external {
        if (!isAdmin[groupId][msg.sender]) {
            revert NotAdmin();
        }
        
        groups[groupId].memberCount = newCount;
        
        emit MemberCountUpdated(groupId, newCount);
    }
    
    /**
     * @dev Deactivate a group.
     */
    function deactivateGroup(bytes32 groupId) external {
        if (groups[groupId].creator != msg.sender) {
            revert NotAdmin();
        }
        
        groups[groupId].isActive = false;
        
        emit GroupDeactivated(groupId);
    }
    
    // ============ View Functions ============
    
    /**
     * @dev Get group info.
     */
    function getGroup(bytes32 groupId) external view returns (Group memory) {
        return groups[groupId];
    }
    
    /**
     * @dev Check if address is group admin.
     */
    function isGroupAdmin(bytes32 groupId, address addr) external view returns (bool) {
        return isAdmin[groupId][addr];
    }
    
    /**
     * @dev Get group admins.
     */
    function getGroupAdmins(bytes32 groupId) external view returns (address[] memory) {
        return groupAdmins[groupId];
    }
    
    /**
     * @dev Get current key version.
     */
    function getKeyVersion(bytes32 groupId) external view returns (uint256) {
        return groups[groupId].currentKeyVersion;
    }
    
    /**
     * @dev Get public groups.
     */
    function getPublicGroups(
        uint256 offset,
        uint256 limit
    ) external view returns (bytes32[] memory) {
        uint256 end = offset + limit;
        if (end > publicGroups.length) {
            end = publicGroups.length;
        }
        
        uint256 size = end - offset;
        bytes32[] memory result = new bytes32[](size);
        
        for (uint256 i = 0; i < size; i++) {
            result[i] = publicGroups[offset + i];
        }
        
        return result;
    }
}
```

---

## 6. DEPLOYMENT SCRIPTS

### 6.1 Hardhat Deployment

```javascript
// scripts/deploy.js
const { ethers, upgrades } = require("hardhat");

async function main() {
    const [deployer] = await ethers.getSigners();
    console.log("Deploying contracts with:", deployer.address);
    
    // Config
    const devMultisig = process.env.DEV_MULTISIG;
    const communityWallet = process.env.COMMUNITY_WALLET;
    const liquidityWallet = process.env.LIQUIDITY_WALLET;
    const treasuryWallet = process.env.TREASURY_WALLET;
    
    // 1. Deploy MCT Token
    console.log("\n1. Deploying MCT Token...");
    const MCTToken = await ethers.getContractFactory("MCTToken");
    const mctToken = await MCTToken.deploy(
        devMultisig,
        communityWallet,
        liquidityWallet,
        treasuryWallet
    );
    await mctToken.deployed();
    console.log("   MCT Token deployed to:", mctToken.address);
    
    // 2. Deploy MumbleChat Registry
    console.log("\n2. Deploying MumbleChat Registry...");
    const MumbleChatRegistry = await ethers.getContractFactory("MumbleChatRegistry");
    const registry = await MumbleChatRegistry.deploy();
    await registry.deployed();
    console.log("   Registry deployed to:", registry.address);
    
    // 3. Deploy Relay Staking
    console.log("\n3. Deploying Relay Staking...");
    const RelayStaking = await ethers.getContractFactory("RelayStaking");
    const relayStaking = await RelayStaking.deploy(mctToken.address);
    await relayStaking.deployed();
    console.log("   Relay Staking deployed to:", relayStaking.address);
    
    // 4. Deploy Group Registry
    console.log("\n4. Deploying Group Registry...");
    const GroupRegistry = await ethers.getContractFactory("GroupRegistry");
    const groupRegistry = await GroupRegistry.deploy();
    await groupRegistry.deployed();
    console.log("   Group Registry deployed to:", groupRegistry.address);
    
    // 5. Configure MCT Token
    console.log("\n5. Configuring MCT Token...");
    await mctToken.setRelayRewardsContract(relayStaking.address);
    console.log("   Relay rewards contract set");
    
    // Summary
    console.log("\n=== Deployment Complete ===");
    console.log("MCT Token:        ", mctToken.address);
    console.log("MumbleChat Registry:", registry.address);
    console.log("Relay Staking:    ", relayStaking.address);
    console.log("Group Registry:   ", groupRegistry.address);
    
    // Write addresses to file
    const addresses = {
        chainId: (await ethers.provider.getNetwork()).chainId,
        mctToken: mctToken.address,
        registry: registry.address,
        relayStaking: relayStaking.address,
        groupRegistry: groupRegistry.address,
        deployedAt: new Date().toISOString()
    };
    
    const fs = require("fs");
    fs.writeFileSync(
        "deployed-addresses.json",
        JSON.stringify(addresses, null, 2)
    );
    
    console.log("\nAddresses saved to deployed-addresses.json");
}

main()
    .then(() => process.exit(0))
    .catch((error) => {
        console.error(error);
        process.exit(1);
    });
```

### 6.2 Hardhat Config for Ramestta

```javascript
// hardhat.config.js
require("@nomicfoundation/hardhat-toolbox");
require("@openzeppelin/hardhat-upgrades");
require("dotenv").config();

module.exports = {
    solidity: {
        version: "0.8.20",
        settings: {
            optimizer: {
                enabled: true,
                runs: 200
            }
        }
    },
    networks: {
        ramesttaTestnet: {
            url: "https://testnet.ramestta.com",
            chainId: 1369,
            accounts: [process.env.DEPLOYER_PRIVATE_KEY],
            gasPrice: 20000000000 // 20 gwei
        },
        ramesttaMainnet: {
            url: "https://blockchain.ramestta.com",
            chainId: 1370,
            accounts: [process.env.DEPLOYER_PRIVATE_KEY],
            gasPrice: 20000000000 // 20 gwei
        }
    },
    etherscan: {
        apiKey: {
            ramesttaTestnet: process.env.RAMESTTA_API_KEY || "",
            ramesttaMainnet: process.env.RAMESTTA_API_KEY || ""
        },
        customChains: [
            {
                network: "ramesttaTestnet",
                chainId: 1369,
                urls: {
                    apiURL: "https://testnet.ramascan.com/api",
                    browserURL: "https://testnet.ramascan.com"
                }
            },
            {
                network: "ramesttaMainnet",
                chainId: 1370,
                urls: {
                    apiURL: "https://ramascan.com/api",
                    browserURL: "https://ramascan.com"
                }
            }
        ]
    }
};
```

---

## 7. CONTRACT VERIFICATION

### 7.1 Verify on Ramascan

```bash
# Verify MCT Token
npx hardhat verify --network ramesttaMainnet \
    <MCT_ADDRESS> \
    <DEV_MULTISIG> \
    <COMMUNITY_WALLET> \
    <LIQUIDITY_WALLET> \
    <TREASURY_WALLET>

# Verify Registry
npx hardhat verify --network ramesttaMainnet <REGISTRY_ADDRESS>

# Verify Relay Staking
npx hardhat verify --network ramesttaMainnet \
    <RELAY_STAKING_ADDRESS> \
    <MCT_ADDRESS>

# Verify Group Registry
npx hardhat verify --network ramesttaMainnet <GROUP_REGISTRY_ADDRESS>
```

---

## 8. WEB3J ANDROID WRAPPERS

After deployment, generate Java/Kotlin wrappers:

```bash
# Generate wrapper for each contract
web3j generate solidity \
    -b build/contracts/MCTToken.json \
    -o app/src/main/java \
    -p com.ramapay.app.chat.contracts

web3j generate solidity \
    -b build/contracts/MumbleChatRegistry.json \
    -o app/src/main/java \
    -p com.ramapay.app.chat.contracts

web3j generate solidity \
    -b build/contracts/RelayStaking.json \
    -o app/src/main/java \
    -p com.ramapay.app.chat.contracts

web3j generate solidity \
    -b build/contracts/GroupRegistry.json \
    -o app/src/main/java \
    -p com.ramapay.app.chat.contracts
```

These wrappers provide type-safe Java classes for interacting with contracts from Android.
