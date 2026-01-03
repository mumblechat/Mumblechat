// SPDX-License-Identifier: MIT
pragma solidity ^0.8.22;

import "@openzeppelin/contracts-upgradeable/access/OwnableUpgradeable.sol";
import "@openzeppelin/contracts-upgradeable/proxy/utils/Initializable.sol";
import "@openzeppelin/contracts-upgradeable/proxy/utils/UUPSUpgradeable.sol";
import "@openzeppelin/contracts-upgradeable/utils/ReentrancyGuardUpgradeable.sol";

/**
 * @title MumbleChatRegistry V2
 * @dev On-chain identity registry for MumbleChat Protocol on Ramestta blockchain
 * UUPS Upgradeable
 * 
 * Features:
 * - Register wallet address with public key for E2E encryption
 * - Update public keys (key rotation)
 * - Lookup public keys by wallet address
 * - Relay node registration with TIER-BASED REWARDS
 * - Uptime & Storage tracking for bonus rewards
 * 
 * TIER SYSTEM:
 * ════════════════════════════════════════════════════════════════
 * | Tier     | Uptime/Day | Storage  | Reward Multiplier |
 * |----------|------------|----------|-------------------|
 * | Bronze   | 0-4 hours  | 0-50 MB  | 1.0x (base)       |
 * | Silver   | 4-8 hours  | 50-200MB | 1.5x              |
 * | Gold     | 8-16 hours | 200-500MB| 2.0x              |
 * | Platinum | 16+ hours  | 500MB+   | 3.0x              |
 * ════════════════════════════════════════════════════════════════
 */
contract MumbleChatRegistry is 
    Initializable, 
    OwnableUpgradeable, 
    UUPSUpgradeable,
    ReentrancyGuardUpgradeable 
{
    
    // ============ Enums ============
    
    enum NodeTier { Bronze, Silver, Gold, Platinum }
    
    // ============ Structs ============
    
    struct Identity {
        bytes32 publicKeyX;      // X25519 public key (32 bytes) - X coordinate
        bytes32 publicKeyY;      // X25519 public key (32 bytes) - Y coordinate (or 0 for X25519)
        uint256 registeredAt;    // Registration timestamp
        uint256 lastUpdated;     // Last key update timestamp
        bool isActive;           // Whether identity is active
        string displayName;      // Optional display name
    }
    
    struct RelayNode {
        string endpoint;         // P2P endpoint (multiaddr format)
        uint256 stakedAmount;    // MCT tokens staked
        uint256 registeredAt;    // Registration timestamp
        uint256 messagesRelayed; // Total messages relayed
        uint256 rewardsEarned;   // Total MCT rewards earned
        bool isActive;           // Whether node is active
        
        // V2: Tier-based tracking
        uint256 totalUptimeSeconds;    // Cumulative uptime in seconds
        uint256 lastHeartbeat;         // Last heartbeat timestamp
        uint256 currentSessionStart;   // Current session start time
        uint256 storageMB;             // Storage provided in MB
        uint256 dailyUptimeSeconds;    // Uptime today (resets daily)
        uint256 lastDayReset;          // Last day uptime was reset
        NodeTier tier;                 // Current tier
    }
    
    // ============ State Variables ============
    
    // Wallet address => Identity
    mapping(address => Identity) public identities;
    
    // Wallet address => RelayNode
    mapping(address => RelayNode) public relayNodes;
    
    // Array of all registered addresses (for enumeration)
    address[] public registeredAddresses;
    
    // Array of active relay nodes
    address[] public activeRelayNodes;
    
    // Minimum stake required to become a relay node (in MCT wei)
    uint256 public minRelayStake;
    
    // Total registered users
    uint256 public totalUsers;
    
    // Total active relay nodes
    uint256 public totalRelayNodes;
    
    // MCT Token address for staking
    address public mctToken;
    
    // ============ Tier Thresholds ============
    
    // Uptime thresholds (seconds per day)
    uint256 public constant BRONZE_UPTIME = 0;
    uint256 public constant SILVER_UPTIME = 4 hours;
    uint256 public constant GOLD_UPTIME = 8 hours;
    uint256 public constant PLATINUM_UPTIME = 16 hours;
    
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
    
    // Heartbeat timeout (consider offline after this)
    uint256 public constant HEARTBEAT_TIMEOUT = 5 minutes;
    
    // ============ Events ============
    
    event IdentityRegistered(
        address indexed wallet,
        bytes32 publicKeyX,
        uint256 timestamp
    );
    
    event IdentityUpdated(
        address indexed wallet,
        bytes32 newPublicKeyX,
        uint256 timestamp
    );
    
    event IdentityDeactivated(
        address indexed wallet,
        uint256 timestamp
    );
    
    event RelayNodeRegistered(
        address indexed node,
        string endpoint,
        uint256 stakedAmount,
        uint256 timestamp
    );
    
    event RelayNodeDeactivated(
        address indexed node,
        uint256 timestamp
    );
    
    event MessageRelayed(
        address indexed relayNode,
        bytes32 messageHash,
        uint256 reward
    );
    
    event HeartbeatReceived(
        address indexed node,
        uint256 uptimeSeconds,
        NodeTier tier
    );
    
    event StorageUpdated(
        address indexed node,
        uint256 storageMB,
        NodeTier newTier
    );
    
    event TierChanged(
        address indexed node,
        NodeTier oldTier,
        NodeTier newTier
    );

    /// @custom:oz-upgrades-unsafe-allow constructor
    constructor() {
        _disableInitializers();
    }
    
    // ============ Initialize ============
    
    function initialize(address _mctToken) public initializer {
        __Ownable_init(msg.sender);
        __UUPSUpgradeable_init();
        __ReentrancyGuard_init();
        
        mctToken = _mctToken;
        minRelayStake = 100 * 10**18; // 100 MCT
    }
    
    /**
     * @dev Reinitializer for V3.1 - Fair Daily Pool System
     * Called once after upgrade to initialize new state variables
     */
    function initializeV3_1() public reinitializer(2) {
        // Initialize V3 relay reward
        relayRewardPerMessage = 1 * 10**15; // 0.001 MCT per message
        
        // Initialize V3.1 daily pool
        dailyPoolAmount = 100 * 10**18; // 100 MCT per day
    }
    
    // ============ Registration Functions ============
    
    /**
     * @dev Register a new identity with public key
     * @param publicKeyX X25519 public key (32 bytes)
     * @param displayName Optional display name
     */
    function register(
        bytes32 publicKeyX,
        string calldata displayName
    ) external {
        require(publicKeyX != bytes32(0), "Invalid public key");
        require(!identities[msg.sender].isActive, "Already registered");
        
        identities[msg.sender] = Identity({
            publicKeyX: publicKeyX,
            publicKeyY: bytes32(0),
            registeredAt: block.timestamp,
            lastUpdated: block.timestamp,
            isActive: true,
            displayName: displayName
        });
        
        registeredAddresses.push(msg.sender);
        totalUsers++;
        
        emit IdentityRegistered(msg.sender, publicKeyX, block.timestamp);
    }
    
    /**
     * @dev Update public key (key rotation)
     * @param newPublicKeyX New X25519 public key
     */
    function updatePublicKey(bytes32 newPublicKeyX) external {
        require(identities[msg.sender].isActive, "Not registered");
        require(newPublicKeyX != bytes32(0), "Invalid public key");
        
        identities[msg.sender].publicKeyX = newPublicKeyX;
        identities[msg.sender].lastUpdated = block.timestamp;
        
        emit IdentityUpdated(msg.sender, newPublicKeyX, block.timestamp);
    }
    
    /**
     * @dev Update display name
     * @param newDisplayName New display name
     */
    function updateDisplayName(string calldata newDisplayName) external {
        require(identities[msg.sender].isActive, "Not registered");
        identities[msg.sender].displayName = newDisplayName;
        identities[msg.sender].lastUpdated = block.timestamp;
    }
    
    /**
     * @dev Deactivate identity
     */
    function deactivate() external {
        require(identities[msg.sender].isActive, "Not registered");
        identities[msg.sender].isActive = false;
        totalUsers--;
        
        emit IdentityDeactivated(msg.sender, block.timestamp);
    }
    
    // ============ Relay Node Functions ============
    
    /**
     * @dev Register as a relay node (requires MCT stake)
     * @param endpoint P2P endpoint in multiaddr format
     * @param storageMB Storage capacity in MB
     */
    function registerAsRelay(string calldata endpoint, uint256 storageMB) external nonReentrant {
        require(identities[msg.sender].isActive, "Must register identity first");
        require(!relayNodes[msg.sender].isActive, "Already a relay node");
        require(!isBlacklisted[msg.sender], "Address is blacklisted");
        require(bytes(endpoint).length > 0, "Invalid endpoint");
        
        // Transfer MCT stake from user
        // Note: User must approve this contract first
        require(
            IERC20(mctToken).transferFrom(msg.sender, address(this), minRelayStake),
            "Stake transfer failed"
        );
        
        relayNodes[msg.sender] = RelayNode({
            endpoint: endpoint,
            stakedAmount: minRelayStake,
            registeredAt: block.timestamp,
            messagesRelayed: 0,
            rewardsEarned: 0,
            isActive: true,
            totalUptimeSeconds: 0,
            lastHeartbeat: block.timestamp,
            currentSessionStart: block.timestamp,
            storageMB: storageMB,
            dailyUptimeSeconds: 0,
            lastDayReset: block.timestamp / 1 days,
            tier: NodeTier.Bronze
        });
        
        // Initialize reputation
        _initReputation(msg.sender);
        
        activeRelayNodes.push(msg.sender);
        totalRelayNodes++;
        
        // Update MCT token active relay count
        IMCTToken(mctToken).setActiveRelayCount(totalRelayNodes);
        
        emit RelayNodeRegistered(msg.sender, endpoint, minRelayStake, block.timestamp);
    }
    
    /**
     * @dev Legacy function for backward compatibility
     */
    function registerAsRelay(string calldata endpoint) external nonReentrant {
        require(identities[msg.sender].isActive, "Must register identity first");
        require(!relayNodes[msg.sender].isActive, "Already a relay node");
        require(!isBlacklisted[msg.sender], "Address is blacklisted");
        require(bytes(endpoint).length > 0, "Invalid endpoint");
        
        require(
            IERC20(mctToken).transferFrom(msg.sender, address(this), minRelayStake),
            "Stake transfer failed"
        );
        
        relayNodes[msg.sender] = RelayNode({
            endpoint: endpoint,
            stakedAmount: minRelayStake,
            registeredAt: block.timestamp,
            messagesRelayed: 0,
            rewardsEarned: 0,
            isActive: true,
            totalUptimeSeconds: 0,
            lastHeartbeat: block.timestamp,
            currentSessionStart: block.timestamp,
            storageMB: 50, // Default 50 MB
            dailyUptimeSeconds: 0,
            lastDayReset: block.timestamp / 1 days,
            tier: NodeTier.Bronze
        });
        
        // Initialize reputation
        _initReputation(msg.sender);
        
        activeRelayNodes.push(msg.sender);
        totalRelayNodes++;
        
        IMCTToken(mctToken).setActiveRelayCount(totalRelayNodes);
        
        emit RelayNodeRegistered(msg.sender, endpoint, minRelayStake, block.timestamp);
    }
    
    /**
     * @dev Deactivate relay node and return stake
     */
    function deactivateRelay() external nonReentrant {
        require(relayNodes[msg.sender].isActive, "Not a relay node");
        
        // Calculate final uptime for current session
        _updateUptime(msg.sender);
        
        uint256 stake = relayNodes[msg.sender].stakedAmount;
        relayNodes[msg.sender].isActive = false;
        relayNodes[msg.sender].stakedAmount = 0;
        totalRelayNodes--;
        
        // Update MCT token active relay count
        IMCTToken(mctToken).setActiveRelayCount(totalRelayNodes);
        
        // Return stake
        require(
            IERC20(mctToken).transfer(msg.sender, stake),
            "Stake return failed"
        );
        
        emit RelayNodeDeactivated(msg.sender, block.timestamp);
    }
    
    // ============ Heartbeat & Uptime Functions ============
    
    /**
     * @dev Send heartbeat to prove node is online
     * Called periodically by relay node (every 1-5 minutes)
     */
    function heartbeat() external {
        require(relayNodes[msg.sender].isActive, "Not an active relay");
        
        _updateUptime(msg.sender);
        _updateTier(msg.sender);
        
        relayNodes[msg.sender].lastHeartbeat = block.timestamp;
        
        emit HeartbeatReceived(
            msg.sender, 
            relayNodes[msg.sender].dailyUptimeSeconds,
            relayNodes[msg.sender].tier
        );
    }
    
    /**
     * @dev Update storage capacity
     * @param storageMB New storage in MB
     */
    function updateStorage(uint256 storageMB) external {
        require(relayNodes[msg.sender].isActive, "Not an active relay");
        
        relayNodes[msg.sender].storageMB = storageMB;
        
        NodeTier oldTier = relayNodes[msg.sender].tier;
        _updateTier(msg.sender);
        
        emit StorageUpdated(msg.sender, storageMB, relayNodes[msg.sender].tier);
    }
    
    /**
     * @dev Internal: Update uptime tracking
     */
    function _updateUptime(address node) internal {
        RelayNode storage r = relayNodes[node];
        
        // Reset daily uptime if new day
        uint256 today = block.timestamp / 1 days;
        if (today != r.lastDayReset) {
            r.dailyUptimeSeconds = 0;
            r.lastDayReset = today;
            r.currentSessionStart = block.timestamp;
        }
        
        // Calculate uptime since last heartbeat (max 5 min to prevent cheating)
        if (r.lastHeartbeat > 0) {
            uint256 elapsed = block.timestamp - r.lastHeartbeat;
            if (elapsed <= HEARTBEAT_TIMEOUT) {
                r.dailyUptimeSeconds += elapsed;
                r.totalUptimeSeconds += elapsed;
            }
            // If elapsed > timeout, node was offline, don't count
        }
    }
    
    /**
     * @dev Internal: Calculate and update tier based on uptime + storage
     */
    function _updateTier(address node) internal {
        RelayNode storage r = relayNodes[node];
        NodeTier oldTier = r.tier;
        NodeTier newTier;
        
        // Calculate tier based on BOTH uptime and storage
        // Node must meet BOTH thresholds for a tier
        
        if (r.dailyUptimeSeconds >= PLATINUM_UPTIME && r.storageMB >= PLATINUM_STORAGE) {
            newTier = NodeTier.Platinum;
        } else if (r.dailyUptimeSeconds >= GOLD_UPTIME && r.storageMB >= GOLD_STORAGE) {
            newTier = NodeTier.Gold;
        } else if (r.dailyUptimeSeconds >= SILVER_UPTIME && r.storageMB >= SILVER_STORAGE) {
            newTier = NodeTier.Silver;
        } else {
            newTier = NodeTier.Bronze;
        }
        
        if (newTier != oldTier) {
            r.tier = newTier;
            emit TierChanged(node, oldTier, newTier);
        }
    }
    
    /**
     * @dev Get reward multiplier for a node (in basis points)
     */
    function getRewardMultiplier(address node) public view returns (uint256) {
        NodeTier tier = relayNodes[node].tier;
        
        if (tier == NodeTier.Platinum) return PLATINUM_MULTIPLIER;
        if (tier == NodeTier.Gold) return GOLD_MULTIPLIER;
        if (tier == NodeTier.Silver) return SILVER_MULTIPLIER;
        return BRONZE_MULTIPLIER;
    }
    
    /**
     * @dev Check if node is currently online (heartbeat within timeout)
     */
    function isNodeOnline(address node) public view returns (bool) {
        if (!relayNodes[node].isActive) return false;
        return (block.timestamp - relayNodes[node].lastHeartbeat) <= HEARTBEAT_TIMEOUT;
    }
    
    /**
     * @dev Record a relayed message and pay FIXED reward (NO tier multiplier)
     * 
     * IMPORTANT: Minting rewards are always 1x to control max supply
     * Tier bonuses ONLY apply to fee pool claims
     * 
     * @param relayNode Address of the relay node
     * @param messageHash Hash of the relayed message
     * @param baseReward Base MCT reward amount (always 1x, no multiplier)
     */
    function recordRelayedMessage(
        address relayNode,
        bytes32 messageHash,
        uint256 baseReward
    ) external onlyOwner {
        require(relayNodes[relayNode].isActive, "Not an active relay");
        
        // NO tier multiplier for minting - keeps supply controlled!
        // Tier bonus comes from fee pool only
        
        relayNodes[relayNode].messagesRelayed++;
        relayNodes[relayNode].rewardsEarned += baseReward;
        
        // Pay reward from contract balance
        if (baseReward > 0 && IERC20(mctToken).balanceOf(address(this)) >= baseReward) {
            IERC20(mctToken).transfer(relayNode, baseReward);
        }
        
        emit MessageRelayed(relayNode, messageHash, baseReward);
    }
    
    /**
     * @dev Claim fee rewards from MCT token with TIER multiplier
     * This is where tier bonuses apply!
     */
    function claimFeeReward() external nonReentrant {
        require(relayNodes[msg.sender].isActive, "Not an active relay");
        require(isNodeOnline(msg.sender), "Node must be online to claim");
        
        // Get tier multiplier
        uint256 multiplier = getRewardMultiplier(msg.sender);
        
        // Call MCT token to claim fee share with tier multiplier
        uint256 reward = IMCTToken(mctToken).claimFeeReward(msg.sender, multiplier);
        
        if (reward > 0) {
            relayNodes[msg.sender].rewardsEarned += reward;
        }
    }
    
    // ============ DECENTRALIZED RELAY PROOF SYSTEM ============
    
    // Mapping to prevent replay attacks: messageHash => already claimed
    mapping(bytes32 => bool) public relayProofClaimed;
    
    // Relay proof nonces to prevent replay
    mapping(address => uint256) public relayProofNonce;
    
    // Minimum time between relay proof submissions (prevent spam)
    uint256 public constant RELAY_PROOF_COOLDOWN = 10 seconds;
    mapping(address => uint256) public lastRelayProofTime;
    
    // Reward per relayed message (in MCT wei) - small reward to prevent spam
    uint256 public relayRewardPerMessage; // 0.001 MCT per message (set in reinitializer)
    
    // Event for relay proof submission
    event RelayProofSubmitted(
        address indexed relayNode,
        bytes32 indexed messageHash,
        address sender,
        address recipient,
        uint256 reward,
        uint256 timestamp
    );
    
    /**
     * @dev Submit proof of message relay (DECENTRALIZED - no owner required)
     * 
     * The relay node collects a signed receipt from the sender when relaying.
     * This proves the relay actually happened without needing a central authority.
     * 
     * @param messageHash Hash of the relayed message (keccak256 of encrypted content)
     * @param sender Original message sender address
     * @param recipient Message recipient address
     * @param timestamp When the relay occurred
     * @param senderSignature Sender's signature proving they sent via this relay
     * 
     * Signature covers: keccak256(abi.encodePacked(messageHash, relayNode, timestamp, nonce))
     */
    function submitRelayProof(
        bytes32 messageHash,
        address sender,
        address recipient,
        uint256 timestamp,
        bytes calldata senderSignature
    ) external nonReentrant {
        // 1. Verify relay node is active
        require(relayNodes[msg.sender].isActive, "Not an active relay");
        require(isNodeOnline(msg.sender), "Node must be online");
        require(!isBlacklisted[msg.sender], "Node is blacklisted");
        
        // 2. Check cooldown (prevent spam)
        require(
            block.timestamp >= lastRelayProofTime[msg.sender] + RELAY_PROOF_COOLDOWN,
            "Cooldown not elapsed"
        );
        
        // 3. Verify timestamp is reasonable (within last hour, not future)
        require(timestamp <= block.timestamp, "Future timestamp");
        require(block.timestamp - timestamp <= 1 hours, "Timestamp too old");
        
        // 4. Verify proof hasn't been claimed already
        bytes32 proofHash = keccak256(abi.encodePacked(messageHash, msg.sender, sender, recipient));
        require(!relayProofClaimed[proofHash], "Proof already claimed");
        
        // 5. Verify sender is registered
        require(identities[sender].isActive, "Sender not registered");
        
        // 6. Get current nonce for replay protection
        uint256 nonce = relayProofNonce[msg.sender];
        
        // 7. Reconstruct the message that sender signed
        bytes32 signedMessageHash = keccak256(abi.encodePacked(
            "\x19Ethereum Signed Message:\n32",
            keccak256(abi.encodePacked(messageHash, msg.sender, timestamp, nonce))
        ));
        
        // 8. Recover signer from signature
        address recoveredSigner = _recoverSigner(signedMessageHash, senderSignature);
        require(recoveredSigner == sender, "Invalid sender signature");
        
        // 9. Mark proof as claimed
        relayProofClaimed[proofHash] = true;
        relayProofNonce[msg.sender]++;
        lastRelayProofTime[msg.sender] = block.timestamp;
        
        // 10. Update relay node stats
        relayNodes[msg.sender].messagesRelayed++;
        
        // 11. Increase reputation for successful relay
        if (reputationScore[msg.sender] < 100) {
            reputationScore[msg.sender] += 1;
            if (reputationScore[msg.sender] > 100) reputationScore[msg.sender] = 100;
        }
        
        // 12. Record for daily pool (V3.1 - Fair Distribution)
        // Instead of paying immediately, relays are recorded and pool is distributed daily
        _recordRelayForDailyPool(msg.sender);
        
        emit RelayProofSubmitted(msg.sender, messageHash, sender, recipient, 0, timestamp);
    }
    
    /**
     * @dev Submit batch of relay proofs (for efficiency)
     */
    function submitBatchRelayProofs(
        bytes32[] calldata messageHashes,
        address[] calldata senders,
        address[] calldata recipients,
        uint256[] calldata timestamps,
        bytes[] calldata signatures
    ) external nonReentrant {
        require(messageHashes.length == senders.length, "Length mismatch");
        require(messageHashes.length == recipients.length, "Length mismatch");
        require(messageHashes.length == timestamps.length, "Length mismatch");
        require(messageHashes.length == signatures.length, "Length mismatch");
        require(messageHashes.length <= 50, "Max 50 proofs per batch");
        
        require(relayNodes[msg.sender].isActive, "Not an active relay");
        require(isNodeOnline(msg.sender), "Node must be online");
        require(!isBlacklisted[msg.sender], "Node is blacklisted");
        
        uint256 validProofs = 0;
        uint256 currentNonce = relayProofNonce[msg.sender];
        
        for (uint256 i = 0; i < messageHashes.length; i++) {
            // Skip invalid proofs instead of reverting entire batch
            if (!_validateAndProcessProof(
                messageHashes[i],
                senders[i],
                recipients[i],
                timestamps[i],
                signatures[i],
                currentNonce + i
            )) {
                continue;
            }
            
            validProofs++;
        }
        
        require(validProofs > 0, "No valid proofs");
        
        // Update nonce and stats
        relayProofNonce[msg.sender] = currentNonce + validProofs;
        lastRelayProofTime[msg.sender] = block.timestamp;
        relayNodes[msg.sender].messagesRelayed += validProofs;
        
        // Increase reputation
        uint256 repIncrease = validProofs > 10 ? 10 : validProofs;
        if (reputationScore[msg.sender] + repIncrease <= 100) {
            reputationScore[msg.sender] += repIncrease;
        } else {
            reputationScore[msg.sender] = 100;
        }
        
        // Record all valid proofs for daily pool (V3.1 - Fair Distribution)
        for (uint256 j = 0; j < validProofs; j++) {
            _recordRelayForDailyPool(msg.sender);
        }
    }
    
    /**
     * @dev Internal function to validate and process a single relay proof
     */
    function _validateAndProcessProof(
        bytes32 messageHash,
        address sender,
        address recipient,
        uint256 timestamp,
        bytes calldata signature,
        uint256 nonce
    ) internal returns (bool) {
        // Check timestamp
        if (timestamp > block.timestamp || block.timestamp - timestamp > 1 hours) {
            return false;
        }
        
        // Check not already claimed
        bytes32 proofHash = keccak256(abi.encodePacked(messageHash, msg.sender, sender, recipient));
        if (relayProofClaimed[proofHash]) {
            return false;
        }
        
        // Check sender is registered
        if (!identities[sender].isActive) {
            return false;
        }
        
        // Verify signature
        bytes32 signedMessageHash = keccak256(abi.encodePacked(
            "\x19Ethereum Signed Message:\n32",
            keccak256(abi.encodePacked(messageHash, msg.sender, timestamp, nonce))
        ));
        
        address recoveredSigner = _recoverSigner(signedMessageHash, signature);
        if (recoveredSigner != sender) {
            return false;
        }
        
        // Mark as claimed
        relayProofClaimed[proofHash] = true;
        
        emit RelayProofSubmitted(msg.sender, messageHash, sender, recipient, relayRewardPerMessage, timestamp);
        
        return true;
    }
    
    /**
     * @dev Recover signer from signature (ECDSA)
     */
    function _recoverSigner(bytes32 messageHash, bytes memory signature) internal pure returns (address) {
        require(signature.length == 65, "Invalid signature length");
        
        bytes32 r;
        bytes32 s;
        uint8 v;
        
        assembly {
            r := mload(add(signature, 32))
            s := mload(add(signature, 64))
            v := byte(0, mload(add(signature, 96)))
        }
        
        if (v < 27) {
            v += 27;
        }
        
        require(v == 27 || v == 28, "Invalid signature v value");
        
        return ecrecover(messageHash, v, r, s);
    }
    
    /**
     * @dev Get relay proof nonce for a relay node (needed by sender to sign)
     */
    function getRelayProofNonce(address relayNode) external view returns (uint256) {
        return relayProofNonce[relayNode];
    }
    
    /**
     * @dev Update relay reward per message (owner only)
     */
    function setRelayRewardPerMessage(uint256 newReward) external onlyOwner {
        relayRewardPerMessage = newReward;
    }
    
    // ============ FAIR DAILY REWARD POOL SYSTEM ============
    // 
    // Solves the problem: "If 100 MCT cap is reached in 1 hour, how do other nodes earn?"
    //
    // NEW SYSTEM (V3.1):
    // 1. Nodes submit relay proofs throughout the day
    // 2. Proofs are COUNTED but NOT paid immediately
    // 3. At end of day (or when triggered), the daily 100 MCT pool is divided
    // 4. Each node gets: (their_relays / total_relays) * 100 MCT * tier_multiplier
    //
    // ADVANTAGES:
    // - Fair: All relays in a day get proportional share
    // - Scalable: Works whether there are 10 nodes or 10,000 nodes
    // - Tier Bonus: High-tier nodes still get 1.5x-3x multiplier
    
    // Daily pool tracking
    struct DailyPool {
        uint256 dayId;                          // Day identifier (block.timestamp / 1 days)
        uint256 totalRelays;                    // Total relays submitted this day
        uint256 totalWeightedRelays;            // Sum of (relays * tier_multiplier) for all nodes
        uint256 poolAmount;                     // MCT available for distribution (100 MCT cap)
        bool distributed;                       // Whether pool has been distributed
    }
    
    // Node's daily contribution
    struct NodeDailyStats {
        uint256 relayCount;                     // Number of relays this day
        uint256 weightedRelayCount;             // relayCount * tier_multiplier
        bool claimed;                           // Whether node claimed for this day
    }
    
    // Current day's pool
    DailyPool public currentDayPool;
    
    // Historical pools (dayId => DailyPool)
    mapping(uint256 => DailyPool) public dailyPools;
    
    // Node stats per day (node => dayId => stats)
    mapping(address => mapping(uint256 => NodeDailyStats)) public nodeDailyStats;
    
    // List of nodes that contributed today (for enumeration)
    address[] private todayContributors;
    mapping(address => bool) private isContributorToday;
    
    // Daily pool amount (can be adjusted)
    uint256 public dailyPoolAmount; // 100 MCT per day (set in reinitializer)
    
    // Events for daily pool
    event DailyRelayRecorded(address indexed node, uint256 dayId, uint256 relayCount, uint256 weightedCount);
    event DailyPoolDistributed(uint256 indexed dayId, uint256 totalRelays, uint256 numNodes, uint256 poolAmount);
    event NodeDailyRewardClaimed(address indexed node, uint256 indexed dayId, uint256 relayCount, uint256 reward);
    
    /**
     * @dev Record a relay for the daily pool (internal, called by submitRelayProof)
     */
    function _recordRelayForDailyPool(address node) internal {
        uint256 today = block.timestamp / 1 days;
        
        // Check if we need to start a new day
        if (currentDayPool.dayId != today) {
            // Archive current day if it has relays and wasn't distributed
            if (currentDayPool.dayId > 0 && currentDayPool.totalRelays > 0 && !currentDayPool.distributed) {
                dailyPools[currentDayPool.dayId] = currentDayPool;
            }
            
            // Start new day
            currentDayPool = DailyPool({
                dayId: today,
                totalRelays: 0,
                totalWeightedRelays: 0,
                poolAmount: dailyPoolAmount,
                distributed: false
            });
            
            // Clear today's contributors list
            delete todayContributors;
            for (uint256 i = 0; i < todayContributors.length; i++) {
                isContributorToday[todayContributors[i]] = false;
            }
        }
        
        // Get node's tier multiplier
        uint256 multiplier = getRewardMultiplier(node);
        
        // Update node's daily stats
        NodeDailyStats storage stats = nodeDailyStats[node][today];
        stats.relayCount++;
        stats.weightedRelayCount += multiplier;
        
        // Update pool totals
        currentDayPool.totalRelays++;
        currentDayPool.totalWeightedRelays += multiplier;
        
        // Track contributor
        if (!isContributorToday[node]) {
            todayContributors.push(node);
            isContributorToday[node] = true;
        }
        
        emit DailyRelayRecorded(node, today, stats.relayCount, stats.weightedRelayCount);
    }
    
    /**
     * @dev Claim daily pool reward for a specific day
     * Nodes call this after the day ends to get their proportional share
     * 
     * FAIR DISTRIBUTION LOGIC:
     * - Each relay earns 0.001 MCT base (relayRewardPerMessage)
     * - With tier multiplier: Bronze 1x, Silver 1.5x, Gold 2x, Platinum 3x
     * - Total earned = sum of all (relays × multiplier × 0.001)
     * - If total earned > 100 MCT cap: distribute 100 MCT proportionally
     * - If total earned < 100 MCT cap: distribute only what was earned (no inflation)
     */
    function claimDailyPoolReward(uint256 dayId) external nonReentrant {
        require(relayNodes[msg.sender].isActive, "Not an active relay");
        require(dayId < block.timestamp / 1 days, "Cannot claim for current day");
        
        // Get the pool for that day
        DailyPool storage pool = dayId == currentDayPool.dayId ? currentDayPool : dailyPools[dayId];
        require(pool.totalRelays > 0, "No relays that day");
        
        // Get node's stats
        NodeDailyStats storage stats = nodeDailyStats[msg.sender][dayId];
        require(stats.relayCount > 0, "No relays from this node");
        require(!stats.claimed, "Already claimed");
        
        // Mark as claimed
        stats.claimed = true;
        
        // Calculate actual earned amount (not inflated)
        // totalWeightedRelays is in basis points (100 = 1x multiplier)
        // So if node did 100 relays at Bronze (100 bp), weightedRelayCount = 10000
        // Actual earned = 10000 / 100 * 0.001 MCT = 1 MCT
        uint256 totalActualEarned = (pool.totalWeightedRelays * relayRewardPerMessage) / 100;
        
        // Effective pool = min(dailyPoolAmount, totalActualEarned)
        // This prevents inflation when activity is low
        uint256 effectivePool = totalActualEarned > pool.poolAmount ? pool.poolAmount : totalActualEarned;
        
        // Calculate proportional reward from effective pool
        // reward = (nodeWeightedRelays / totalWeightedRelays) * effectivePool
        uint256 reward = (stats.weightedRelayCount * effectivePool) / pool.totalWeightedRelays;
        
        // Cap at available balance (safety)
        uint256 available = IERC20(mctToken).balanceOf(address(this));
        if (reward > available) {
            reward = available;
        }
        
        if (reward > 0) {
            relayNodes[msg.sender].rewardsEarned += reward;
            IERC20(mctToken).transfer(msg.sender, reward);
        }
        
        emit NodeDailyRewardClaimed(msg.sender, dayId, stats.relayCount, reward);
    }
    
    /**
     * @dev Get claimable reward for a node for a specific day
     */
    function getClaimableReward(address node, uint256 dayId) external view returns (uint256) {
        DailyPool storage pool = dayId == currentDayPool.dayId ? currentDayPool : dailyPools[dayId];
        if (pool.totalWeightedRelays == 0) return 0;
        
        NodeDailyStats storage stats = nodeDailyStats[node][dayId];
        if (stats.claimed || stats.relayCount == 0) return 0;
        
        // Calculate effective pool (capped by actual work)
        uint256 totalActualEarned = (pool.totalWeightedRelays * relayRewardPerMessage) / 100;
        uint256 effectivePool = totalActualEarned > pool.poolAmount ? pool.poolAmount : totalActualEarned;
        
        return (stats.weightedRelayCount * effectivePool) / pool.totalWeightedRelays;
    }
    
    /**
     * @dev Get today's pool info
     */
    function getTodayPoolInfo() external view returns (
        uint256 dayId,
        uint256 totalRelays,
        uint256 totalWeightedRelays,
        uint256 poolAmount,
        uint256 numContributors
    ) {
        return (
            currentDayPool.dayId,
            currentDayPool.totalRelays,
            currentDayPool.totalWeightedRelays,
            currentDayPool.poolAmount,
            todayContributors.length
        );
    }
    
    /**
     * @dev Get node's stats for today
     */
    function getMyTodayStats() external view returns (
        uint256 relayCount,
        uint256 weightedRelayCount,
        uint256 estimatedReward
    ) {
        uint256 today = block.timestamp / 1 days;
        NodeDailyStats storage stats = nodeDailyStats[msg.sender][today];
        
        uint256 estimated = 0;
        if (currentDayPool.totalWeightedRelays > 0) {
            // Calculate effective pool (capped by actual work)
            uint256 totalActualEarned = (currentDayPool.totalWeightedRelays * relayRewardPerMessage) / 100;
            uint256 effectivePool = totalActualEarned > currentDayPool.poolAmount ? currentDayPool.poolAmount : totalActualEarned;
            estimated = (stats.weightedRelayCount * effectivePool) / currentDayPool.totalWeightedRelays;
        }
        
        return (stats.relayCount, stats.weightedRelayCount, estimated);
    }
    
    /**
     * @dev Update daily pool amount (owner only)
     */
    function setDailyPoolAmount(uint256 newAmount) external onlyOwner {
        dailyPoolAmount = newAmount;
    }
    
    // ============ View Functions ============
    
    /**
     * @dev Check if an address is registered
     */
    function isRegistered(address wallet) external view returns (bool) {
        return identities[wallet].isActive;
    }
    
    /**
     * @dev Get public key for an address
     */
    function getPublicKey(address wallet) external view returns (bytes32) {
        require(identities[wallet].isActive, "Not registered");
        return identities[wallet].publicKeyX;
    }
    
    /**
     * @dev Get full identity info
     */
    function getIdentity(address wallet) external view returns (
        bytes32 publicKeyX,
        uint256 registeredAt,
        uint256 lastUpdated,
        bool isActive,
        string memory displayName
    ) {
        Identity memory id = identities[wallet];
        return (id.publicKeyX, id.registeredAt, id.lastUpdated, id.isActive, id.displayName);
    }
    
    /**
     * @dev Get list of active relay nodes
     */
    function getActiveRelayNodes() external view returns (address[] memory) {
        uint256 count = 0;
        for (uint256 i = 0; i < activeRelayNodes.length; i++) {
            if (relayNodes[activeRelayNodes[i]].isActive) {
                count++;
            }
        }
        
        address[] memory active = new address[](count);
        uint256 j = 0;
        for (uint256 i = 0; i < activeRelayNodes.length; i++) {
            if (relayNodes[activeRelayNodes[i]].isActive) {
                active[j] = activeRelayNodes[i];
                j++;
            }
        }
        return active;
    }
    
    /**
     * @dev Get relay node info with tier data
     */
    function getRelayNode(address node) external view returns (
        string memory endpoint,
        uint256 stakedAmount,
        uint256 messagesRelayed,
        uint256 rewardsEarned,
        bool isActive,
        uint256 dailyUptimeSeconds,
        uint256 storageMB,
        NodeTier tier,
        uint256 rewardMultiplier,
        bool isOnline
    ) {
        RelayNode memory r = relayNodes[node];
        return (
            r.endpoint, 
            r.stakedAmount, 
            r.messagesRelayed, 
            r.rewardsEarned, 
            r.isActive,
            r.dailyUptimeSeconds,
            r.storageMB,
            r.tier,
            getRewardMultiplier(node),
            isNodeOnline(node)
        );
    }
    
    /**
     * @dev Get tier requirements info
     */
    function getTierRequirements() external pure returns (
        uint256[4] memory uptimeThresholds,
        uint256[4] memory storageThresholds,
        uint256[4] memory multipliers
    ) {
        uptimeThresholds = [BRONZE_UPTIME, SILVER_UPTIME, GOLD_UPTIME, PLATINUM_UPTIME];
        storageThresholds = [BRONZE_STORAGE, SILVER_STORAGE, GOLD_STORAGE, PLATINUM_STORAGE];
        multipliers = [BRONZE_MULTIPLIER, SILVER_MULTIPLIER, GOLD_MULTIPLIER, PLATINUM_MULTIPLIER];
    }
    
    // ============ Admin Functions ============
    
    /**
     * @dev Update minimum relay stake
     */
    function setMinRelayStake(uint256 newMinStake) external onlyOwner {
        minRelayStake = newMinStake;
    }
    
    /**
     * @dev Update MCT token address
     */
    function setMCTToken(address newMCTToken) external onlyOwner {
        require(newMCTToken != address(0), "Invalid address");
        mctToken = newMCTToken;
    }
    
    /**
     * @dev Withdraw excess MCT from contract (rewards pool)
     */
    function withdrawExcessMCT(uint256 amount) external onlyOwner {
        require(
            IERC20(mctToken).transfer(owner(), amount),
            "Transfer failed"
        );
    }
    
    // ============ PROTECTION PROTOCOL ============
    
    // Slashing: Malicious relay nodes lose stake
    // Reputation: Track node behavior
    // Rate Limiting: Prevent spam/DDoS
    
    // Reputation scores (0-100, starts at 50)
    mapping(address => uint256) public reputationScore;
    
    // Violation count
    mapping(address => uint256) public violationCount;
    
    // Blacklisted addresses
    mapping(address => bool) public isBlacklisted;
    
    // Rate limiting: last action timestamp
    mapping(address => uint256) public lastMessageTime;
    
    // Constants for protection
    uint256 public constant MIN_MESSAGE_INTERVAL = 100; // 100ms between messages
    uint256 public constant SLASH_PERCENTAGE = 10; // 10% stake slashed per violation
    uint256 public constant MAX_VIOLATIONS = 5; // Auto-deactivate after 5 violations
    uint256 public constant INITIAL_REPUTATION = 50;
    
    // Events for protection
    event NodeSlashed(address indexed node, uint256 amount, string reason);
    event NodeBlacklisted(address indexed node, string reason);
    event ReputationChanged(address indexed node, uint256 oldScore, uint256 newScore);
    event ViolationReported(address indexed node, address reporter, string reason);
    
    /**
     * @dev Report a malicious relay node (anyone can report)
     * Multiple reports from different addresses needed for action
     */
    function reportViolation(address node, string calldata reason) external {
        require(relayNodes[node].isActive, "Node not active");
        require(identities[msg.sender].isActive, "Reporter must be registered");
        require(msg.sender != node, "Cannot self-report");
        
        violationCount[node]++;
        
        // Decrease reputation
        if (reputationScore[node] > 5) {
            uint256 oldScore = reputationScore[node];
            reputationScore[node] -= 5;
            emit ReputationChanged(node, oldScore, reputationScore[node]);
        }
        
        emit ViolationReported(node, msg.sender, reason);
        
        // Auto-action if too many violations
        if (violationCount[node] >= MAX_VIOLATIONS) {
            _slashNode(node, "Too many violations");
        }
    }
    
    /**
     * @dev Slash a malicious node (owner only for now, can be decentralized later)
     */
    function slashNode(address node, string calldata reason) external onlyOwner {
        _slashNode(node, reason);
    }
    
    /**
     * @dev Internal slash function
     */
    function _slashNode(address node, string memory reason) internal {
        require(relayNodes[node].isActive, "Node not active");
        
        uint256 stake = relayNodes[node].stakedAmount;
        uint256 slashAmount = (stake * SLASH_PERCENTAGE) / 100;
        
        // Reduce stake
        relayNodes[node].stakedAmount -= slashAmount;
        
        // If stake below minimum, deactivate
        if (relayNodes[node].stakedAmount < minRelayStake) {
            _forceDeactivateNode(node);
        }
        
        // Slashed tokens go to fee pool for honest nodes
        IERC20(mctToken).transfer(mctToken, slashAmount);
        
        emit NodeSlashed(node, slashAmount, reason);
    }
    
    /**
     * @dev Blacklist a node permanently
     */
    function blacklistNode(address node, string calldata reason) external onlyOwner {
        isBlacklisted[node] = true;
        
        if (relayNodes[node].isActive) {
            _forceDeactivateNode(node);
        }
        
        emit NodeBlacklisted(node, reason);
    }
    
    /**
     * @dev Force deactivate a node (no stake return for malicious nodes)
     */
    function _forceDeactivateNode(address node) internal {
        if (!relayNodes[node].isActive) return;
        
        relayNodes[node].isActive = false;
        totalRelayNodes--;
        
        // Update MCT token active relay count
        IMCTToken(mctToken).setActiveRelayCount(totalRelayNodes);
        
        emit RelayNodeDeactivated(node, block.timestamp);
    }
    
    /**
     * @dev Increase reputation for good behavior (called after successful relay)
     */
    function increaseReputation(address node) external onlyOwner {
        if (reputationScore[node] < 100) {
            uint256 oldScore = reputationScore[node];
            reputationScore[node] += 1;
            if (reputationScore[node] > 100) reputationScore[node] = 100;
            emit ReputationChanged(node, oldScore, reputationScore[node]);
        }
    }
    
    /**
     * @dev Initialize reputation for new node
     */
    function _initReputation(address node) internal {
        if (reputationScore[node] == 0) {
            reputationScore[node] = INITIAL_REPUTATION;
        }
    }
    
    /**
     * @dev Check if message rate is within limits (spam protection)
     */
    function checkRateLimit(address sender) external view returns (bool) {
        uint256 elapsed = block.timestamp - lastMessageTime[sender];
        return elapsed >= MIN_MESSAGE_INTERVAL;
    }
    
    /**
     * @dev Update last message time (called after message sent)
     */
    function updateMessageTime(address sender) external onlyOwner {
        lastMessageTime[sender] = block.timestamp;
    }
    
    /**
     * @dev Get node security info
     */
    function getNodeSecurityInfo(address node) external view returns (
        uint256 reputation,
        uint256 violations,
        bool blacklisted,
        bool canOperate
    ) {
        return (
            reputationScore[node],
            violationCount[node],
            isBlacklisted[node],
            relayNodes[node].isActive && !isBlacklisted[node] && reputationScore[node] > 10
        );
    }

    // ============ USER BLOCKING ============
    
    // User A blocks User B: blockedUsers[A][B] = true
    mapping(address => mapping(address => bool)) public blockedUsers;
    
    // List of users blocked by each address (for enumeration)
    mapping(address => address[]) private blockedUsersList;
    
    // Events for blocking
    event UserBlocked(address indexed blocker, address indexed blocked, uint256 timestamp);
    event UserUnblocked(address indexed blocker, address indexed unblocked, uint256 timestamp);
    
    /**
     * @dev Block a user from sending messages to you
     * @param userToBlock Address to block
     */
    function blockUser(address userToBlock) external {
        require(identities[msg.sender].isActive, "Not registered");
        require(userToBlock != msg.sender, "Cannot block yourself");
        require(userToBlock != address(0), "Invalid address");
        require(!blockedUsers[msg.sender][userToBlock], "Already blocked");
        
        blockedUsers[msg.sender][userToBlock] = true;
        blockedUsersList[msg.sender].push(userToBlock);
        
        emit UserBlocked(msg.sender, userToBlock, block.timestamp);
    }
    
    /**
     * @dev Unblock a previously blocked user
     * @param userToUnblock Address to unblock
     */
    function unblockUser(address userToUnblock) external {
        require(identities[msg.sender].isActive, "Not registered");
        require(blockedUsers[msg.sender][userToUnblock], "Not blocked");
        
        blockedUsers[msg.sender][userToUnblock] = false;
        
        // Remove from list (swap and pop for gas efficiency)
        address[] storage list = blockedUsersList[msg.sender];
        for (uint256 i = 0; i < list.length; i++) {
            if (list[i] == userToUnblock) {
                list[i] = list[list.length - 1];
                list.pop();
                break;
            }
        }
        
        emit UserUnblocked(msg.sender, userToUnblock, block.timestamp);
    }
    
    /**
     * @dev Check if a user is blocked
     * @param blocker The user who may have blocked
     * @param blocked The user who may be blocked
     * @return True if blocked
     */
    function isBlocked(address blocker, address blocked) external view returns (bool) {
        return blockedUsers[blocker][blocked];
    }
    
    /**
     * @dev Get list of all blocked users for an address
     * @param user The address to check
     * @return Array of blocked addresses
     */
    function getBlockedUsers(address user) external view returns (address[] memory) {
        return blockedUsersList[user];
    }
    
    /**
     * @dev Get count of blocked users
     * @param user The address to check
     * @return Number of blocked users
     */
    function getBlockedUsersCount(address user) external view returns (uint256) {
        return blockedUsersList[user].length;
    }
    
    /**
     * @dev Check if sender can message recipient (not blocked)
     * @param sender The message sender
     * @param recipient The message recipient
     * @return True if message can be sent
     */
    function canSendMessage(address sender, address recipient) external view returns (bool) {
        // Both must be registered
        if (!identities[sender].isActive || !identities[recipient].isActive) {
            return false;
        }
        // Sender must not be blocked by recipient
        if (blockedUsers[recipient][sender]) {
            return false;
        }
        return true;
    }

    /**
     * @dev Required by UUPS pattern - only owner can upgrade
     */
    function _authorizeUpgrade(address newImplementation) internal override onlyOwner {}

    /**
     * @dev Returns the version of the contract
     */
    function version() public pure returns (string memory) {
        return "3.2.0";
    }
}

// ============ Interfaces ============

interface IERC20 {
    function transfer(address to, uint256 amount) external returns (bool);
    function transferFrom(address from, address to, uint256 amount) external returns (bool);
    function balanceOf(address account) external view returns (uint256);
}

interface IMCTToken {
    function setActiveRelayCount(uint256 count) external;
    function claimFeeReward(address relayNode, uint256 tierMultiplier) external returns (uint256);
}
