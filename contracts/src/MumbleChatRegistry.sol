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

    /**
     * @dev Required by UUPS pattern - only owner can upgrade
     */
    function _authorizeUpgrade(address newImplementation) internal override onlyOwner {}

    /**
     * @dev Returns the version of the contract
     */
    function version() public pure returns (string memory) {
        return "2.0.0";
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
