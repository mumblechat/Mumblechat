// SPDX-License-Identifier: MIT
pragma solidity ^0.8.22;

import "@openzeppelin/contracts-upgradeable/access/OwnableUpgradeable.sol";
import "@openzeppelin/contracts-upgradeable/proxy/utils/Initializable.sol";
import "@openzeppelin/contracts-upgradeable/proxy/utils/UUPSUpgradeable.sol";
import "@openzeppelin/contracts-upgradeable/utils/ReentrancyGuardUpgradeable.sol";

/**
 * @title MumbleChatRegistry V4
 * @dev Core identity registry for MumbleChat Protocol on Ramestta blockchain
 * UUPS Upgradeable
 * 
 * Features:
 * - Register wallet address with public key for E2E encryption
 * - Update public keys (key rotation)
 * - Lookup public keys by wallet address
 * - Legacy relay node registration (basic)
 * - User blocking system
 * 
 * Note: V4 Node Identity System is in MumbleChatRelayManager contract
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
        bytes32 publicKeyX;      // X25519 public key (32 bytes)
        bytes32 publicKeyY;      // Reserved for future use
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
        uint256 totalUptimeSeconds;
        uint256 lastHeartbeat;
        uint256 currentSessionStart;
        uint256 storageMB;
        uint256 dailyUptimeSeconds;
        uint256 lastDayReset;
        NodeTier tier;
    }
    
    // ============ State Variables ============
    
    mapping(address => Identity) public identities;
    mapping(address => RelayNode) public relayNodes;
    address[] public registeredAddresses;
    address[] public activeRelayNodes;
    
    uint256 public minRelayStake;
    uint256 public totalUsers;
    uint256 public totalRelayNodes;
    address public mctToken;
    
    // Relay Manager contract address (V4)
    address public relayManager;
    
    // ============ Tier Constants ============
    
    uint256 public constant BRONZE_UPTIME = 4 hours;
    uint256 public constant SILVER_UPTIME = 8 hours;
    uint256 public constant GOLD_UPTIME = 12 hours;
    uint256 public constant PLATINUM_UPTIME = 16 hours;
    
    uint256 public constant BRONZE_STORAGE = 1024;
    uint256 public constant SILVER_STORAGE = 2048;
    uint256 public constant GOLD_STORAGE = 4096;
    uint256 public constant PLATINUM_STORAGE = 8192;
    
    uint256 public constant BRONZE_MULTIPLIER = 100;
    uint256 public constant SILVER_MULTIPLIER = 150;
    uint256 public constant GOLD_MULTIPLIER = 200;
    uint256 public constant PLATINUM_MULTIPLIER = 300;
    
    uint256 public constant HEARTBEAT_TIMEOUT = 6 hours;
    
    // ============ User Blocking ============
    
    mapping(address => mapping(address => bool)) public blockedUsers;
    mapping(address => address[]) private blockedUsersList;
    
    // ============ Legacy Protection (for backward compatibility) ============
    
    mapping(address => uint256) public reputationScore;
    mapping(address => bool) public isBlacklisted;
    uint256 public constant INITIAL_REPUTATION = 50;
    
    // ============ Events ============
    
    event IdentityRegistered(address indexed wallet, bytes32 publicKeyX, uint256 timestamp);
    event IdentityUpdated(address indexed wallet, bytes32 newPublicKeyX, uint256 timestamp);
    event IdentityDeactivated(address indexed wallet, uint256 timestamp);
    event RelayNodeRegistered(address indexed node, string endpoint, uint256 stakedAmount, uint256 timestamp);
    event RelayNodeDeactivated(address indexed node, uint256 timestamp);
    event HeartbeatReceived(address indexed node, uint256 uptimeSeconds, NodeTier tier);
    event TierChanged(address indexed node, NodeTier oldTier, NodeTier newTier);
    event UserBlocked(address indexed blocker, address indexed blocked, uint256 timestamp);
    event UserUnblocked(address indexed blocker, address indexed unblocked, uint256 timestamp);

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
     * @dev Reinitializer for V4 - Sets relay manager address
     */
    function initializeV4(address _relayManager) public reinitializer(4) {
        relayManager = _relayManager;
    }
    
    // ============ Identity Functions ============
    
    /**
     * @dev Register a new identity with public key
     */
    function register(bytes32 publicKeyX, string calldata displayName) external {
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
    
    // ============ Legacy Relay Node Functions ============
    
    /**
     * @dev Register as a relay node (legacy - use RelayManager for V4)
     */
    function registerAsRelay(string calldata endpoint, uint256 storageMB) external nonReentrant {
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
            storageMB: storageMB,
            dailyUptimeSeconds: 0,
            lastDayReset: block.timestamp / 1 days,
            tier: NodeTier.Bronze
        });
        
        if (reputationScore[msg.sender] == 0) {
            reputationScore[msg.sender] = INITIAL_REPUTATION;
        }
        
        activeRelayNodes.push(msg.sender);
        totalRelayNodes++;
        
        IMCTToken(mctToken).setActiveRelayCount(totalRelayNodes);
        
        emit RelayNodeRegistered(msg.sender, endpoint, minRelayStake, block.timestamp);
    }
    
    /**
     * @dev Legacy registerAsRelay without storageMB
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
            storageMB: 50,
            dailyUptimeSeconds: 0,
            lastDayReset: block.timestamp / 1 days,
            tier: NodeTier.Bronze
        });
        
        if (reputationScore[msg.sender] == 0) {
            reputationScore[msg.sender] = INITIAL_REPUTATION;
        }
        
        activeRelayNodes.push(msg.sender);
        totalRelayNodes++;
        
        IMCTToken(mctToken).setActiveRelayCount(totalRelayNodes);
        
        emit RelayNodeRegistered(msg.sender, endpoint, minRelayStake, block.timestamp);
    }
    
    /**
     * @dev Deactivate relay node
     */
    function deactivateRelay() external nonReentrant {
        require(relayNodes[msg.sender].isActive, "Not a relay node");
        
        _updateUptime(msg.sender);
        
        uint256 stake = relayNodes[msg.sender].stakedAmount;
        relayNodes[msg.sender].isActive = false;
        relayNodes[msg.sender].stakedAmount = 0;
        totalRelayNodes--;
        
        IMCTToken(mctToken).setActiveRelayCount(totalRelayNodes);
        
        require(IERC20(mctToken).transfer(msg.sender, stake), "Stake return failed");
        
        emit RelayNodeDeactivated(msg.sender, block.timestamp);
    }
    
    /**
     * @dev Send heartbeat
     */
    function heartbeat() external {
        require(relayNodes[msg.sender].isActive, "Not an active relay");
        
        _updateUptime(msg.sender);
        _updateTier(msg.sender);
        
        relayNodes[msg.sender].lastHeartbeat = block.timestamp;
        
        emit HeartbeatReceived(msg.sender, relayNodes[msg.sender].dailyUptimeSeconds, relayNodes[msg.sender].tier);
    }
    
    function _updateUptime(address node) internal {
        RelayNode storage r = relayNodes[node];
        
        uint256 today = block.timestamp / 1 days;
        if (today != r.lastDayReset) {
            r.dailyUptimeSeconds = 0;
            r.lastDayReset = today;
            r.currentSessionStart = block.timestamp;
        }
        
        if (r.lastHeartbeat > 0) {
            uint256 elapsed = block.timestamp - r.lastHeartbeat;
            if (elapsed <= HEARTBEAT_TIMEOUT) {
                r.dailyUptimeSeconds += elapsed;
                r.totalUptimeSeconds += elapsed;
            }
        }
    }
    
    function _updateTier(address node) internal {
        RelayNode storage r = relayNodes[node];
        NodeTier oldTier = r.tier;
        NodeTier newTier;
        
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
    
    // ============ User Blocking Functions ============
    
    /**
     * @dev Block a user
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
     * @dev Unblock a user
     */
    function unblockUser(address userToUnblock) external {
        require(identities[msg.sender].isActive, "Not registered");
        require(blockedUsers[msg.sender][userToUnblock], "Not blocked");
        
        blockedUsers[msg.sender][userToUnblock] = false;
        
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
     * @dev Check if blocked
     */
    function isBlocked(address blocker, address blocked) external view returns (bool) {
        return blockedUsers[blocker][blocked];
    }
    
    /**
     * @dev Get blocked users list
     */
    function getBlockedUsers(address user) external view returns (address[] memory) {
        return blockedUsersList[user];
    }
    
    /**
     * @dev Get blocked users count
     */
    function getBlockedUsersCount(address user) external view returns (uint256) {
        return blockedUsersList[user].length;
    }
    
    /**
     * @dev Check if sender can message recipient
     */
    function canSendMessage(address sender, address recipient) external view returns (bool) {
        if (!identities[sender].isActive || !identities[recipient].isActive) {
            return false;
        }
        if (blockedUsers[recipient][sender]) {
            return false;
        }
        return true;
    }
    
    // ============ View Functions ============
    
    /**
     * @dev Check if registered
     */
    function isRegistered(address wallet) external view returns (bool) {
        return identities[wallet].isActive;
    }
    
    /**
     * @dev Get public key
     */
    function getPublicKey(address wallet) external view returns (bytes32) {
        require(identities[wallet].isActive, "Not registered");
        return identities[wallet].publicKeyX;
    }
    
    /**
     * @dev Get full identity
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
     * @dev Get reward multiplier for a node
     */
    function getRewardMultiplier(address node) public view returns (uint256) {
        NodeTier tier = relayNodes[node].tier;
        if (tier == NodeTier.Platinum) return PLATINUM_MULTIPLIER;
        if (tier == NodeTier.Gold) return GOLD_MULTIPLIER;
        if (tier == NodeTier.Silver) return SILVER_MULTIPLIER;
        return BRONZE_MULTIPLIER;
    }
    
    /**
     * @dev Check if node is online
     */
    function isNodeOnline(address node) public view returns (bool) {
        if (!relayNodes[node].isActive) return false;
        return (block.timestamp - relayNodes[node].lastHeartbeat) <= HEARTBEAT_TIMEOUT;
    }
    
    /**
     * @dev Get active relay nodes
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
     * @dev Get relay node info (V4.1: includes registeredAt)
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
        bool isOnline,
        uint256 registeredAt
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
            isNodeOnline(node),
            r.registeredAt
        );
    }
    
    /**
     * @dev Get tier requirements
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
     * @dev Set minimum relay stake
     */
    function setMinRelayStake(uint256 newMinStake) external onlyOwner {
        minRelayStake = newMinStake;
    }
    
    /**
     * @dev Set MCT token address
     */
    function setMCTToken(address newMCTToken) external onlyOwner {
        require(newMCTToken != address(0), "Invalid address");
        mctToken = newMCTToken;
    }
    
    /**
     * @dev Set relay manager address
     */
    function setRelayManager(address _relayManager) external onlyOwner {
        require(_relayManager != address(0), "Invalid address");
        relayManager = _relayManager;
    }
    
    /**
     * @dev Withdraw excess MCT
     */
    function withdrawExcessMCT(uint256 amount) external onlyOwner {
        require(IERC20(mctToken).transfer(owner(), amount), "Transfer failed");
    }
    
    /**
     * @dev Blacklist an address
     */
    function blacklistAddress(address wallet) external onlyOwner {
        isBlacklisted[wallet] = true;
    }
    
    /**
     * @dev Remove from blacklist
     */
    function removeFromBlacklist(address wallet) external onlyOwner {
        isBlacklisted[wallet] = false;
    }
    
    function _authorizeUpgrade(address newImplementation) internal override onlyOwner {}
    
    function version() public pure returns (string memory) {
        return "4.0.0";
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
