// SPDX-License-Identifier: MIT
pragma solidity ^0.8.22;

import "@openzeppelin/contracts-upgradeable/access/OwnableUpgradeable.sol";
import "@openzeppelin/contracts-upgradeable/proxy/utils/Initializable.sol";
import "@openzeppelin/contracts-upgradeable/proxy/utils/UUPSUpgradeable.sol";
import "@openzeppelin/contracts-upgradeable/utils/ReentrancyGuardUpgradeable.sol";

/**
 * @title MumbleChatRelayManager V1
 * @dev Relay node management for MumbleChat Protocol on Ramestta blockchain
 * UUPS Upgradeable - Works with MumbleChatRegistry
 * 
 * Features:
 * - V4 Node Identity System (multi-node per machine)
 * - Tier-based stake requirements (100/200/300/400 MCT)
 * - Daily Pool Reward System
 * - Relay Proof Verification
 * - Protection Protocol (slashing, reputation, blacklist)
 * - Proportional uptime rewards
 * 
 * TIER SYSTEM (Stake-Based + Uptime Required):
 * ════════════════════════════════════════════════════════════════════════════
 * | Tier     | Stake MCT | Uptime/Day | Storage | Fee Pool % | Multiplier   |
 * |----------|-----------|------------|---------|------------|--------------|
 * | Bronze   | 100 MCT   | 4+ hours   | 1 GB    | 10%        | 1.0x (base)  |
 * | Silver   | 200 MCT   | 8+ hours   | 2 GB    | 20%        | 1.5x         |
 * | Gold     | 300 MCT   | 12+ hours  | 4 GB    | 30%        | 2.0x         |
 * | Platinum | 400 MCT   | 16+ hours  | 8+ GB   | 40%        | 3.0x         |
 * ════════════════════════════════════════════════════════════════════════════
 */
contract MumbleChatRelayManager is 
    Initializable, 
    OwnableUpgradeable, 
    UUPSUpgradeable,
    ReentrancyGuardUpgradeable 
{
    // ============ Enums ============
    
    enum NodeTier { Bronze, Silver, Gold, Platinum }
    
    // ============ Structs ============
    
    struct RelayNode {
        string endpoint;         // P2P endpoint (multiaddr format)
        uint256 stakedAmount;    // MCT tokens staked
        uint256 registeredAt;    // Registration timestamp
        uint256 messagesRelayed; // Total messages relayed
        uint256 rewardsEarned;   // Total MCT rewards earned
        bool isActive;           // Whether node is active
        uint256 totalUptimeSeconds;    // Cumulative uptime
        uint256 lastHeartbeat;         // Last heartbeat timestamp
        uint256 currentSessionStart;   // Current session start
        uint256 storageMB;             // Storage provided in MB
        uint256 dailyUptimeSeconds;    // Uptime today
        uint256 lastDayReset;          // Last day uptime was reset
        NodeTier tier;                 // Current tier
    }
    
    struct NodeIdentity {
        bytes32 nodeId;              // Unique node ID (SHA256 hash)
        bytes32 machineIdHash;       // Hash of MAC address (privacy)
        bytes32 serialHash;          // Hash of hardware serial
        address walletAddress;       // Owner wallet
        uint256 instanceNumber;      // Instance # on this machine
        uint256 registeredAt;        // Registration timestamp
        bool isActive;               // Currently active
    }
    
    struct DailyNodeReward {
        uint256 potentialReward;     // Full reward if 100% uptime
        uint256 actualReward;        // Actual reward based on uptime %
        uint256 missedReward;        // Reward missed due to low uptime
        uint256 bonusReceived;       // Bonus from others' missed rewards
        uint256 uptimePercent;       // Uptime percentage (0-100)
        bool claimed;                // Whether claimed
    }
    
    struct DailyPool {
        uint256 dayId;
        uint256 totalRelays;
        uint256 totalWeightedRelays;
        uint256 poolAmount;
        bool distributed;
    }
    
    struct NodeDailyStats {
        uint256 relayCount;
        uint256 weightedRelayCount;
        bool claimed;
    }
    
    // ============ State Variables ============
    
    // MCT Token address
    address public mctToken;
    
    // Registry contract address
    address public registry;
    
    // Total active relay nodes
    uint256 public totalRelayNodes;
    
    // ============ Node Identity Mappings ============
    
    mapping(bytes32 => NodeIdentity) public nodeIdentities;
    mapping(bytes32 => address) public nodeIdToWallet;
    mapping(address => bytes32[]) public walletNodeIds;
    mapping(bytes32 => bytes32[]) public machineNodeIds;
    mapping(bytes32 => RelayNode) public nodeRelayData;
    bytes32[] public allNodeIds;
    
    // ============ Tier Constants ============
    
    uint256 public constant BRONZE_STAKE = 100 * 10**18;
    uint256 public constant SILVER_STAKE = 200 * 10**18;
    uint256 public constant GOLD_STAKE = 300 * 10**18;
    uint256 public constant PLATINUM_STAKE = 400 * 10**18;
    
    uint256 public constant BRONZE_UPTIME = 4 hours;
    uint256 public constant SILVER_UPTIME = 8 hours;
    uint256 public constant GOLD_UPTIME = 12 hours;
    uint256 public constant PLATINUM_UPTIME = 16 hours;
    
    uint256 public constant BRONZE_STORAGE = 1024;     // 1 GB
    uint256 public constant SILVER_STORAGE = 2048;     // 2 GB
    uint256 public constant GOLD_STORAGE = 4096;       // 4 GB
    uint256 public constant PLATINUM_STORAGE = 8192;   // 8 GB
    
    uint256 public constant BRONZE_MULTIPLIER = 100;   // 1.0x
    uint256 public constant SILVER_MULTIPLIER = 150;   // 1.5x
    uint256 public constant GOLD_MULTIPLIER = 200;     // 2.0x
    uint256 public constant PLATINUM_MULTIPLIER = 300; // 3.0x
    
    uint256 public constant BRONZE_FEE_PERCENT = 10;
    uint256 public constant SILVER_FEE_PERCENT = 20;
    uint256 public constant GOLD_FEE_PERCENT = 30;
    uint256 public constant PLATINUM_FEE_PERCENT = 40;
    
    uint256 public constant HEARTBEAT_TIMEOUT = 5 minutes;
    
    // ============ Base Reward Constants ============
    // Base reward: 0.001 MCT per 1000 messages (from MCTToken.sol)
    // This is used to cap pool rewards to prevent nodes from earning more than their message entitlement
    uint256 public constant BASE_REWARD_PER_1000_MSG = 1 * 10 ** 15; // 0.001 MCT
    uint256 public constant MESSAGES_PER_REWARD = 1000;
    
    // ============ Daily Pool Variables ============
    
    mapping(uint256 => uint256) public dailyMissedPool;
    mapping(uint256 => uint256) public dailyFullUptimeNodes;
    mapping(bytes32 => mapping(uint256 => DailyNodeReward)) public nodeDailyRewards;
    
    DailyPool public currentDayPool;
    mapping(uint256 => DailyPool) public dailyPools;
    mapping(address => mapping(uint256 => NodeDailyStats)) public nodeDailyStats;
    
    address[] private todayContributors;
    mapping(address => bool) private isContributorToday;
    
    uint256 public dailyPoolAmount;
    uint256 public relayRewardPerMessage;
    
    // ============ Relay Proof Variables ============
    
    mapping(bytes32 => bool) public relayProofClaimed;
    mapping(address => uint256) public relayProofNonce;
    mapping(address => uint256) public lastRelayProofTime;
    uint256 public constant RELAY_PROOF_COOLDOWN = 10 seconds;
    
    // ============ Protection Protocol Variables ============
    
    mapping(address => uint256) public reputationScore;
    mapping(address => uint256) public violationCount;
    mapping(address => bool) public isBlacklisted;
    mapping(address => uint256) public lastMessageTime;
    
    uint256 public constant MIN_MESSAGE_INTERVAL = 100;
    uint256 public constant SLASH_PERCENTAGE = 10;
    uint256 public constant MAX_VIOLATIONS = 5;
    uint256 public constant INITIAL_REPUTATION = 50;
    
    // ============ Events ============
    
    event NodeIdentityRegistered(
        bytes32 indexed nodeId,
        address indexed wallet,
        bytes32 machineIdHash,
        uint256 instanceNumber,
        uint256 timestamp
    );
    
    event NodeIdentityDeactivated(
        bytes32 indexed nodeId,
        address indexed wallet,
        uint256 timestamp
    );
    
    event NodeHeartbeatByNodeId(
        bytes32 indexed nodeId,
        uint256 uptimeSeconds,
        NodeTier tier
    );
    
    event ProportionalRewardCalculated(
        bytes32 indexed nodeId,
        uint256 indexed dayId,
        uint256 potentialReward,
        uint256 actualReward,
        uint256 missedReward,
        uint256 uptimePercent
    );
    
    event MissedRewardRedistributed(
        uint256 indexed dayId,
        uint256 totalMissed,
        uint256 eligibleNodes,
        uint256 bonusPerNode
    );
    
    event RelayProofSubmitted(
        address indexed relayNode,
        bytes32 indexed messageHash,
        address sender,
        address recipient,
        uint256 reward,
        uint256 timestamp
    );
    
    event DailyRelayRecorded(address indexed node, uint256 dayId, uint256 relayCount, uint256 weightedCount);
    event DailyPoolDistributed(uint256 indexed dayId, uint256 totalRelays, uint256 numNodes, uint256 poolAmount);
    event NodeDailyRewardClaimed(address indexed node, uint256 indexed dayId, uint256 relayCount, uint256 reward);
    
    event NodeSlashed(address indexed node, uint256 amount, string reason);
    event NodeBlacklisted(address indexed node, string reason);
    event ReputationChanged(address indexed node, uint256 oldScore, uint256 newScore);
    event ViolationReported(address indexed node, address reporter, string reason);
    event TierChanged(bytes32 indexed nodeId, NodeTier oldTier, NodeTier newTier);

    /// @custom:oz-upgrades-unsafe-allow constructor
    constructor() {
        _disableInitializers();
    }
    
    // ============ Initialize ============
    
    function initialize(address _mctToken, address _registry) public initializer {
        __Ownable_init(msg.sender);
        __UUPSUpgradeable_init();
        __ReentrancyGuard_init();
        
        mctToken = _mctToken;
        registry = _registry;
        dailyPoolAmount = 100 * 10**18; // 100 MCT per day
        relayRewardPerMessage = 1 * 10**15; // 0.001 MCT
    }
    
    // ============ Modifiers ============
    
    modifier onlyRegistered() {
        require(IMumbleChatRegistry(registry).isRegistered(msg.sender), "Not registered");
        _;
    }
    
    // ============ Node Registration Functions ============
    
    /**
     * @dev Register a relay node with unique Node ID
     */
    function registerNodeWithId(
        bytes32 nodeId,
        bytes32 machineIdHash,
        bytes32 serialHash,
        string calldata endpoint,
        uint256 storageMB,
        NodeTier tier
    ) external nonReentrant onlyRegistered {
        require(nodeId != bytes32(0), "Invalid node ID");
        require(nodeIdentities[nodeId].walletAddress == address(0), "Node ID already registered");
        require(!isBlacklisted[msg.sender], "Address is blacklisted");
        require(bytes(endpoint).length > 0, "Invalid endpoint");
        
        uint256 requiredStake = getStakeForTier(tier);
        
        require(
            IERC20(mctToken).transferFrom(msg.sender, address(this), requiredStake),
            "Stake transfer failed"
        );
        
        uint256 instanceNum = machineNodeIds[machineIdHash].length + 1;
        
        nodeIdentities[nodeId] = NodeIdentity({
            nodeId: nodeId,
            machineIdHash: machineIdHash,
            serialHash: serialHash,
            walletAddress: msg.sender,
            instanceNumber: instanceNum,
            registeredAt: block.timestamp,
            isActive: true
        });
        
        nodeRelayData[nodeId] = RelayNode({
            endpoint: endpoint,
            stakedAmount: requiredStake,
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
            tier: tier
        });
        
        nodeIdToWallet[nodeId] = msg.sender;
        walletNodeIds[msg.sender].push(nodeId);
        machineNodeIds[machineIdHash].push(nodeId);
        allNodeIds.push(nodeId);
        
        if (reputationScore[msg.sender] == 0) {
            reputationScore[msg.sender] = INITIAL_REPUTATION;
        }
        
        totalRelayNodes++;
        IMCTToken(mctToken).setActiveRelayCount(totalRelayNodes);
        
        emit NodeIdentityRegistered(nodeId, msg.sender, machineIdHash, instanceNum, block.timestamp);
    }
    
    /**
     * @dev Heartbeat by Node ID
     */
    function heartbeatByNodeId(bytes32 nodeId) external {
        require(nodeIdentities[nodeId].walletAddress == msg.sender, "Not node owner");
        require(nodeIdentities[nodeId].isActive, "Node not active");
        
        RelayNode storage r = nodeRelayData[nodeId];
        
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
        
        r.lastHeartbeat = block.timestamp;
        _updateTierByNodeId(nodeId);
        
        emit NodeHeartbeatByNodeId(nodeId, r.dailyUptimeSeconds, r.tier);
    }
    
    /**
     * @dev Deactivate node by Node ID
     */
    function deactivateNodeById(bytes32 nodeId) external nonReentrant {
        require(nodeIdentities[nodeId].walletAddress == msg.sender, "Not node owner");
        require(nodeIdentities[nodeId].isActive, "Node already inactive");
        
        RelayNode storage r = nodeRelayData[nodeId];
        uint256 stake = r.stakedAmount;
        
        nodeIdentities[nodeId].isActive = false;
        r.isActive = false;
        r.stakedAmount = 0;
        
        totalRelayNodes--;
        IMCTToken(mctToken).setActiveRelayCount(totalRelayNodes);
        
        require(IERC20(mctToken).transfer(msg.sender, stake), "Stake return failed");
        
        emit NodeIdentityDeactivated(nodeId, msg.sender, block.timestamp);
    }
    
    // ============ Relay Proof Functions ============
    
    /**
     * @dev Submit proof of message relay
     */
    function submitRelayProof(
        bytes32 nodeId,
        bytes32 messageHash,
        address sender,
        address recipient,
        uint256 timestamp,
        bytes calldata senderSignature
    ) external nonReentrant {
        require(nodeIdentities[nodeId].walletAddress == msg.sender, "Not node owner");
        require(nodeIdentities[nodeId].isActive, "Node not active");
        require(isNodeOnlineByNodeId(nodeId), "Node must be online");
        require(!isBlacklisted[msg.sender], "Node is blacklisted");
        
        require(
            block.timestamp >= lastRelayProofTime[msg.sender] + RELAY_PROOF_COOLDOWN,
            "Cooldown not elapsed"
        );
        
        require(timestamp <= block.timestamp, "Future timestamp");
        require(block.timestamp - timestamp <= 1 hours, "Timestamp too old");
        
        bytes32 proofHash = keccak256(abi.encodePacked(messageHash, nodeId, sender, recipient));
        require(!relayProofClaimed[proofHash], "Proof already claimed");
        
        require(IMumbleChatRegistry(registry).isRegistered(sender), "Sender not registered");
        
        uint256 nonce = relayProofNonce[msg.sender];
        
        bytes32 signedMessageHash = keccak256(abi.encodePacked(
            "\x19Ethereum Signed Message:\n32",
            keccak256(abi.encodePacked(messageHash, nodeId, timestamp, nonce))
        ));
        
        address recoveredSigner = _recoverSigner(signedMessageHash, senderSignature);
        require(recoveredSigner == sender, "Invalid sender signature");
        
        relayProofClaimed[proofHash] = true;
        relayProofNonce[msg.sender]++;
        lastRelayProofTime[msg.sender] = block.timestamp;
        
        nodeRelayData[nodeId].messagesRelayed++;
        
        if (reputationScore[msg.sender] < 100) {
            reputationScore[msg.sender] += 1;
            if (reputationScore[msg.sender] > 100) reputationScore[msg.sender] = 100;
        }
        
        _recordRelayForDailyPool(msg.sender, nodeId);
        
        emit RelayProofSubmitted(msg.sender, messageHash, sender, recipient, 0, timestamp);
    }
    
    /**
     * @dev Record relay for daily pool
     */
    function _recordRelayForDailyPool(address node, bytes32 nodeId) internal {
        uint256 today = block.timestamp / 1 days;
        
        if (currentDayPool.dayId != today) {
            if (currentDayPool.dayId > 0 && currentDayPool.totalRelays > 0 && !currentDayPool.distributed) {
                dailyPools[currentDayPool.dayId] = currentDayPool;
            }
            
            currentDayPool = DailyPool({
                dayId: today,
                totalRelays: 0,
                totalWeightedRelays: 0,
                poolAmount: dailyPoolAmount,
                distributed: false
            });
            
            delete todayContributors;
        }
        
        uint256 multiplier = getRewardMultiplierByNodeId(nodeId);
        
        NodeDailyStats storage stats = nodeDailyStats[node][today];
        stats.relayCount++;
        stats.weightedRelayCount += multiplier;
        
        currentDayPool.totalRelays++;
        currentDayPool.totalWeightedRelays += multiplier;
        
        if (!isContributorToday[node]) {
            todayContributors.push(node);
            isContributorToday[node] = true;
        }
        
        emit DailyRelayRecorded(node, today, stats.relayCount, stats.weightedRelayCount);
    }
    
    /**
     * @dev Claim daily pool reward
     */
    /**
     * @dev Claim daily pool reward with base reward cap
     * IMPORTANT: Nodes cannot earn more than their message entitlement
     * Reward = MIN(poolShare, baseRewardCap)
     * baseRewardCap = (relayCount / 1000) * 0.001 MCT
     */
    function claimDailyPoolReward(uint256 dayId) external nonReentrant {
        require(dayId < block.timestamp / 1 days, "Cannot claim for current day");
        
        DailyPool storage pool = dayId == currentDayPool.dayId ? currentDayPool : dailyPools[dayId];
        require(pool.totalRelays > 0, "No relays that day");
        
        NodeDailyStats storage stats = nodeDailyStats[msg.sender][dayId];
        require(stats.relayCount > 0, "No relays from this node");
        require(!stats.claimed, "Already claimed");
        
        stats.claimed = true;
        
        // Calculate pool share
        uint256 totalActualEarned = (pool.totalWeightedRelays * relayRewardPerMessage) / 100;
        uint256 effectivePool = totalActualEarned > pool.poolAmount ? pool.poolAmount : totalActualEarned;
        uint256 poolShare = (stats.weightedRelayCount * effectivePool) / pool.totalWeightedRelays;
        
        // Calculate base reward cap: (relayCount / 1000) * 0.001 MCT
        // This prevents nodes from earning more than their message entitlement
        uint256 baseRewardCap = (stats.relayCount * BASE_REWARD_PER_1000_MSG) / MESSAGES_PER_REWARD;
        
        // Reward = MIN(poolShare, baseRewardCap)
        // Node cannot earn more than what their relays entitle them to
        uint256 reward = poolShare < baseRewardCap ? poolShare : baseRewardCap;
        
        uint256 available = IERC20(mctToken).balanceOf(address(this));
        if (reward > available) {
            reward = available;
        }
        
        if (reward > 0) {
            IERC20(mctToken).transfer(msg.sender, reward);
        }
        
        emit NodeDailyRewardClaimed(msg.sender, dayId, stats.relayCount, reward);
    }
    
    // ============ Proportional Reward Functions ============
    
    /**
     * @dev Calculate tier reward with uptime
     */
    function calculateTierRewardWithUptime(
        bytes32 nodeId,
        uint256 dayId,
        uint256 dailyPool
    ) public returns (uint256 actualReward, uint256 missedReward) {
        require(nodeIdentities[nodeId].isActive, "Node not active");
        
        RelayNode storage r = nodeRelayData[nodeId];
        
        uint256 feePercent = getFeePercentForTier(r.tier);
        uint256 tierReward = (dailyPool * feePercent) / 100;
        
        uint256 requiredUptime = getUptimeForTier(r.tier);
        uint256 actualUptime = r.dailyUptimeSeconds;
        
        uint256 uptimePercent = actualUptime >= requiredUptime ? 100 : (actualUptime * 100) / requiredUptime;
        
        actualReward = (tierReward * uptimePercent) / 100;
        missedReward = tierReward - actualReward;
        
        nodeDailyRewards[nodeId][dayId] = DailyNodeReward({
            potentialReward: tierReward,
            actualReward: actualReward,
            missedReward: missedReward,
            bonusReceived: 0,
            uptimePercent: uptimePercent,
            claimed: false
        });
        
        if (missedReward > 0) {
            dailyMissedPool[dayId] += missedReward;
        }
        
        if (uptimePercent >= 100) {
            dailyFullUptimeNodes[dayId]++;
        }
        
        emit ProportionalRewardCalculated(nodeId, dayId, tierReward, actualReward, missedReward, uptimePercent);
        
        return (actualReward, missedReward);
    }
    
    /**
     * @dev Distribute missed rewards
     */
    function distributeMissedRewards(uint256 dayId) external onlyOwner {
        uint256 totalMissed = dailyMissedPool[dayId];
        uint256 eligibleNodes = dailyFullUptimeNodes[dayId];
        
        require(totalMissed > 0, "No missed rewards");
        require(eligibleNodes > 0, "No eligible nodes");
        
        uint256 bonusPerNode = totalMissed / eligibleNodes;
        
        for (uint256 i = 0; i < allNodeIds.length; i++) {
            bytes32 nodeId = allNodeIds[i];
            DailyNodeReward storage reward = nodeDailyRewards[nodeId][dayId];
            
            if (reward.uptimePercent >= 100 && nodeIdentities[nodeId].isActive) {
                reward.bonusReceived = bonusPerNode;
                address wallet = nodeIdentities[nodeId].walletAddress;
                IERC20(mctToken).transfer(wallet, bonusPerNode);
                nodeRelayData[nodeId].rewardsEarned += bonusPerNode;
            }
        }
        
        dailyMissedPool[dayId] = 0;
        
        emit MissedRewardRedistributed(dayId, totalMissed, eligibleNodes, bonusPerNode);
    }
    
    // ============ Protection Protocol Functions ============
    
    /**
     * @dev Report a violation
     */
    function reportViolation(bytes32 nodeId, string calldata reason) external onlyRegistered {
        address node = nodeIdentities[nodeId].walletAddress;
        require(nodeIdentities[nodeId].isActive, "Node not active");
        require(msg.sender != node, "Cannot self-report");
        
        violationCount[node]++;
        
        if (reputationScore[node] > 5) {
            uint256 oldScore = reputationScore[node];
            reputationScore[node] -= 5;
            emit ReputationChanged(node, oldScore, reputationScore[node]);
        }
        
        emit ViolationReported(node, msg.sender, reason);
        
        if (violationCount[node] >= MAX_VIOLATIONS) {
            _slashNode(nodeId, "Too many violations");
        }
    }
    
    /**
     * @dev Slash a node (owner only)
     */
    function slashNode(bytes32 nodeId, string calldata reason) external onlyOwner {
        _slashNode(nodeId, reason);
    }
    
    function _slashNode(bytes32 nodeId, string memory reason) internal {
        require(nodeIdentities[nodeId].isActive, "Node not active");
        
        RelayNode storage r = nodeRelayData[nodeId];
        uint256 stake = r.stakedAmount;
        uint256 slashAmount = (stake * SLASH_PERCENTAGE) / 100;
        
        r.stakedAmount -= slashAmount;
        
        IERC20(mctToken).transfer(mctToken, slashAmount);
        
        address wallet = nodeIdentities[nodeId].walletAddress;
        emit NodeSlashed(wallet, slashAmount, reason);
        
        if (r.stakedAmount < BRONZE_STAKE) {
            _forceDeactivateNode(nodeId);
        }
    }
    
    /**
     * @dev Blacklist a node
     */
    function blacklistNode(address wallet, string calldata reason) external onlyOwner {
        isBlacklisted[wallet] = true;
        
        bytes32[] memory nodeIds = walletNodeIds[wallet];
        for (uint256 i = 0; i < nodeIds.length; i++) {
            if (nodeIdentities[nodeIds[i]].isActive) {
                _forceDeactivateNode(nodeIds[i]);
            }
        }
        
        emit NodeBlacklisted(wallet, reason);
    }
    
    function _forceDeactivateNode(bytes32 nodeId) internal {
        if (!nodeIdentities[nodeId].isActive) return;
        
        nodeIdentities[nodeId].isActive = false;
        nodeRelayData[nodeId].isActive = false;
        totalRelayNodes--;
        
        IMCTToken(mctToken).setActiveRelayCount(totalRelayNodes);
        
        emit NodeIdentityDeactivated(nodeId, nodeIdentities[nodeId].walletAddress, block.timestamp);
    }
    
    // ============ Internal Functions ============
    
    function _updateTierByNodeId(bytes32 nodeId) internal {
        RelayNode storage r = nodeRelayData[nodeId];
        NodeTier oldTier = r.tier;
        NodeTier newTier;
        
        if (r.dailyUptimeSeconds >= PLATINUM_UPTIME && r.storageMB >= PLATINUM_STORAGE && r.stakedAmount >= PLATINUM_STAKE) {
            newTier = NodeTier.Platinum;
        } else if (r.dailyUptimeSeconds >= GOLD_UPTIME && r.storageMB >= GOLD_STORAGE && r.stakedAmount >= GOLD_STAKE) {
            newTier = NodeTier.Gold;
        } else if (r.dailyUptimeSeconds >= SILVER_UPTIME && r.storageMB >= SILVER_STORAGE && r.stakedAmount >= SILVER_STAKE) {
            newTier = NodeTier.Silver;
        } else {
            newTier = NodeTier.Bronze;
        }
        
        if (newTier != oldTier) {
            r.tier = newTier;
            emit TierChanged(nodeId, oldTier, newTier);
        }
    }
    
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
    
    // ============ View Functions ============
    
    function getStakeForTier(NodeTier tier) public pure returns (uint256) {
        if (tier == NodeTier.Platinum) return PLATINUM_STAKE;
        if (tier == NodeTier.Gold) return GOLD_STAKE;
        if (tier == NodeTier.Silver) return SILVER_STAKE;
        return BRONZE_STAKE;
    }
    
    function getUptimeForTier(NodeTier tier) public pure returns (uint256) {
        if (tier == NodeTier.Platinum) return PLATINUM_UPTIME;
        if (tier == NodeTier.Gold) return GOLD_UPTIME;
        if (tier == NodeTier.Silver) return SILVER_UPTIME;
        return BRONZE_UPTIME;
    }
    
    function getFeePercentForTier(NodeTier tier) public pure returns (uint256) {
        if (tier == NodeTier.Platinum) return PLATINUM_FEE_PERCENT;
        if (tier == NodeTier.Gold) return GOLD_FEE_PERCENT;
        if (tier == NodeTier.Silver) return SILVER_FEE_PERCENT;
        return BRONZE_FEE_PERCENT;
    }
    
    function getRewardMultiplierByNodeId(bytes32 nodeId) public view returns (uint256) {
        NodeTier tier = nodeRelayData[nodeId].tier;
        if (tier == NodeTier.Platinum) return PLATINUM_MULTIPLIER;
        if (tier == NodeTier.Gold) return GOLD_MULTIPLIER;
        if (tier == NodeTier.Silver) return SILVER_MULTIPLIER;
        return BRONZE_MULTIPLIER;
    }
    
    function isNodeOnlineByNodeId(bytes32 nodeId) public view returns (bool) {
        if (!nodeIdentities[nodeId].isActive) return false;
        return (block.timestamp - nodeRelayData[nodeId].lastHeartbeat) <= HEARTBEAT_TIMEOUT;
    }
    
    function getNodeByNodeId(bytes32 nodeId) external view returns (
        address walletAddress,
        bytes32 machineIdHash,
        uint256 instanceNumber,
        string memory endpoint,
        uint256 stakedAmount,
        uint256 messagesRelayed,
        uint256 rewardsEarned,
        bool isActive,
        uint256 dailyUptimeSeconds,
        uint256 storageMB,
        NodeTier tier,
        bool isOnline,
        uint256 registeredAt
    ) {
        NodeIdentity memory identity = nodeIdentities[nodeId];
        RelayNode memory r = nodeRelayData[nodeId];
        
        return (
            identity.walletAddress,
            identity.machineIdHash,
            identity.instanceNumber,
            r.endpoint,
            r.stakedAmount,
            r.messagesRelayed,
            r.rewardsEarned,
            identity.isActive,
            r.dailyUptimeSeconds,
            r.storageMB,
            r.tier,
            isNodeOnlineByNodeId(nodeId),
            identity.registeredAt
        );
    }
    
    function getNodeIdentity(bytes32 nodeId) external view returns (
        bytes32 id,
        bytes32 machineIdHash,
        bytes32 serialHash,
        address walletAddress,
        uint256 instanceNumber,
        uint256 registeredAt,
        bool isActive
    ) {
        NodeIdentity memory identity = nodeIdentities[nodeId];
        return (
            identity.nodeId,
            identity.machineIdHash,
            identity.serialHash,
            identity.walletAddress,
            identity.instanceNumber,
            identity.registeredAt,
            identity.isActive
        );
    }
    
    function getWalletNodeIds(address wallet) external view returns (bytes32[] memory) {
        return walletNodeIds[wallet];
    }
    
    function getNodeIdsByMachine(bytes32 machineIdHash) external view returns (bytes32[] memory) {
        return machineNodeIds[machineIdHash];
    }
    
    function getTotalNodeIds() external view returns (uint256) {
        return allNodeIds.length;
    }
    
    function getNodesOnMachine(bytes32 machineIdHash) external view returns (uint256) {
        return machineNodeIds[machineIdHash].length;
    }
    
    function getActiveNodeIds() external view returns (bytes32[] memory nodeIds, address[] memory wallets) {
        uint256 count = 0;
        for (uint256 i = 0; i < allNodeIds.length; i++) {
            if (nodeIdentities[allNodeIds[i]].isActive) {
                count++;
            }
        }
        
        nodeIds = new bytes32[](count);
        wallets = new address[](count);
        uint256 j = 0;
        
        for (uint256 i = 0; i < allNodeIds.length; i++) {
            if (nodeIdentities[allNodeIds[i]].isActive) {
                nodeIds[j] = allNodeIds[i];
                wallets[j] = nodeIdentities[allNodeIds[i]].walletAddress;
                j++;
            }
        }
        return (nodeIds, wallets);
    }
    
    function getNodeDailyReward(bytes32 nodeId, uint256 dayId) external view returns (
        uint256 potentialReward,
        uint256 actualReward,
        uint256 missedReward,
        uint256 bonusReceived,
        uint256 uptimePercent,
        bool claimed
    ) {
        DailyNodeReward memory reward = nodeDailyRewards[nodeId][dayId];
        return (
            reward.potentialReward,
            reward.actualReward,
            reward.missedReward,
            reward.bonusReceived,
            reward.uptimePercent,
            reward.claimed
        );
    }
    
    function getDailyMissedPool(uint256 dayId) external view returns (uint256 totalMissed, uint256 eligibleNodes) {
        return (dailyMissedPool[dayId], dailyFullUptimeNodes[dayId]);
    }
    
    function getTierInfo() external pure returns (
        uint256[4] memory stakes,
        uint256[4] memory uptimes,
        uint256[4] memory storages,
        uint256[4] memory feePercents,
        uint256[4] memory multipliers
    ) {
        stakes = [BRONZE_STAKE, SILVER_STAKE, GOLD_STAKE, PLATINUM_STAKE];
        uptimes = [BRONZE_UPTIME, SILVER_UPTIME, GOLD_UPTIME, PLATINUM_UPTIME];
        storages = [BRONZE_STORAGE, SILVER_STORAGE, GOLD_STORAGE, PLATINUM_STORAGE];
        feePercents = [BRONZE_FEE_PERCENT, SILVER_FEE_PERCENT, GOLD_FEE_PERCENT, PLATINUM_FEE_PERCENT];
        multipliers = [BRONZE_MULTIPLIER, SILVER_MULTIPLIER, GOLD_MULTIPLIER, PLATINUM_MULTIPLIER];
    }
    
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
    
    function getClaimableReward(address node, uint256 dayId) external view returns (uint256) {
        DailyPool storage pool = dayId == currentDayPool.dayId ? currentDayPool : dailyPools[dayId];
        if (pool.totalWeightedRelays == 0) return 0;
        
        NodeDailyStats storage stats = nodeDailyStats[node][dayId];
        if (stats.claimed || stats.relayCount == 0) return 0;
        
        uint256 totalActualEarned = (pool.totalWeightedRelays * relayRewardPerMessage) / 100;
        uint256 effectivePool = totalActualEarned > pool.poolAmount ? pool.poolAmount : totalActualEarned;
        
        return (stats.weightedRelayCount * effectivePool) / pool.totalWeightedRelays;
    }
    
    function getNodeSecurityInfo(address wallet) external view returns (
        uint256 reputation,
        uint256 violations,
        bool blacklisted,
        bool canOperate
    ) {
        return (
            reputationScore[wallet],
            violationCount[wallet],
            isBlacklisted[wallet],
            !isBlacklisted[wallet] && reputationScore[wallet] > 10
        );
    }
    
    function getRelayProofNonce(address relayNode) external view returns (uint256) {
        return relayProofNonce[relayNode];
    }
    
    // ============ Admin Functions ============
    
    function setRegistry(address _registry) external onlyOwner {
        require(_registry != address(0), "Invalid address");
        registry = _registry;
    }
    
    function setMCTToken(address _mctToken) external onlyOwner {
        require(_mctToken != address(0), "Invalid address");
        mctToken = _mctToken;
    }
    
    function setDailyPoolAmount(uint256 newAmount) external onlyOwner {
        dailyPoolAmount = newAmount;
    }
    
    function setRelayRewardPerMessage(uint256 newReward) external onlyOwner {
        relayRewardPerMessage = newReward;
    }
    
    function withdrawExcessMCT(uint256 amount) external onlyOwner {
        require(IERC20(mctToken).transfer(owner(), amount), "Transfer failed");
    }
    
    // ============ Decentralized Endpoint Discovery ============
    
    /// @notice Get all active node endpoints for P2P discovery (no bootstrap server needed)
    /// @return nodeIds Array of active node IDs
    /// @return endpoints Array of endpoints (IP:port or domain)
    /// @return wallets Array of wallet addresses
    function getActiveEndpoints() external view returns (
        bytes32[] memory nodeIds,
        string[] memory endpoints,
        address[] memory wallets
    ) {
        // Count active nodes
        uint256 count = 0;
        for (uint256 i = 0; i < allNodeIds.length; i++) {
            if (nodeIdentities[allNodeIds[i]].isActive) {
                count++;
            }
        }
        
        // Allocate arrays
        nodeIds = new bytes32[](count);
        endpoints = new string[](count);
        wallets = new address[](count);
        
        // Populate arrays
        uint256 j = 0;
        for (uint256 i = 0; i < allNodeIds.length; i++) {
            bytes32 nodeId = allNodeIds[i];
            if (nodeIdentities[nodeId].isActive) {
                nodeIds[j] = nodeId;
                endpoints[j] = nodeRelayData[nodeId].endpoint;
                wallets[j] = nodeIdentities[nodeId].walletAddress;
                j++;
            }
        }
        
        return (nodeIds, endpoints, wallets);
    }
    
    /// @notice Update your node's endpoint without re-registering
    /// @param nodeId Your registered node ID
    /// @param newEndpoint New endpoint (IP:port or domain:port)
    function updateEndpoint(bytes32 nodeId, string calldata newEndpoint) external {
        require(bytes(newEndpoint).length > 0, "Endpoint required");
        require(nodeIdentities[nodeId].walletAddress == msg.sender, "Not your node");
        require(nodeIdentities[nodeId].isActive, "Node not active");
        
        nodeRelayData[nodeId].endpoint = newEndpoint;
        
        emit EndpointUpdated(nodeId, msg.sender, newEndpoint);
    }
    
    /// @notice Get a single node's endpoint by wallet address
    function getEndpointByWallet(address wallet) external view returns (string memory endpoint, bool isActive) {
        bytes32[] storage nodeIds = walletNodeIds[wallet];
        if (nodeIds.length == 0) {
            return ("", false);
        }
        bytes32 nodeId = nodeIds[0]; // Return first node's endpoint
        return (nodeRelayData[nodeId].endpoint, nodeIdentities[nodeId].isActive);
    }
    
    /// @notice Get a single node's endpoint by node ID
    function getEndpointByNodeId(bytes32 nodeId) external view returns (string memory endpoint, address wallet, bool isActive) {
        return (nodeRelayData[nodeId].endpoint, nodeIdentities[nodeId].walletAddress, nodeIdentities[nodeId].isActive);
    }
    
    // Event for endpoint updates
    event EndpointUpdated(bytes32 indexed nodeId, address indexed wallet, string newEndpoint);
    
    function _authorizeUpgrade(address newImplementation) internal override onlyOwner {}
    
    function version() public pure returns (string memory) {
        return "3.0.0";
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

interface IMumbleChatRegistry {
    function isRegistered(address wallet) external view returns (bool);
}
