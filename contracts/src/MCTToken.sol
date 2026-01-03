// SPDX-License-Identifier: MIT
pragma solidity ^0.8.22;

import "@openzeppelin/contracts-upgradeable/token/ERC20/ERC20Upgradeable.sol";
import "@openzeppelin/contracts-upgradeable/token/ERC20/extensions/ERC20BurnableUpgradeable.sol";
import "@openzeppelin/contracts-upgradeable/access/OwnableUpgradeable.sol";
import "@openzeppelin/contracts-upgradeable/proxy/utils/Initializable.sol";
import "@openzeppelin/contracts-upgradeable/proxy/utils/UUPSUpgradeable.sol";

/**
 * @title MCTToken (MumbleChat Token) V3
 * @dev ERC-20 token for MumbleChat Protocol on Ramestta blockchain
 * 
 * TOKENOMICS (V3 - Sustainable + Governance):
 * ════════════════════════════════════════════════════════════════
 * - Symbol: MCT
 * - Initial Supply: 1,000 MCT
 * - Max Supply: 1,000,000 MCT (upgradable via 90% node vote)
 * - Relay Reward: 0.001 MCT per 1000 messages relayed
 * - Halving: Every 100,000 MCT minted, reward halves
 * - Daily Cap: Max 100 MCT can be minted per day
 * - Transfer Fee: 0.1% fee redistributed to relay nodes
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
    
    // Base reward: 0.001 MCT per 1000 messages
    uint256 public constant BASE_REWARD_PER_1000_MSG = 1 * 10 ** 15; // 0.001 MCT
    
    // Halving threshold: reward halves every 100,000 MCT minted
    uint256 public constant HALVING_THRESHOLD = 100_000 * 10 ** 18;
    
    // Daily mint cap: 100 MCT
    uint256 public constant DAILY_MINT_CAP = 100 * 10 ** 18;
    
    // Messages required for one reward payout
    uint256 public constant MESSAGES_PER_REWARD = 1000;
    
    // Transfer fee: 0.1% (10 basis points)
    uint256 public constant TRANSFER_FEE_BPS = 10;
    
    // Governance: 90% vote required
    uint256 public constant GOVERNANCE_THRESHOLD = 90;
    
    // Minimum voting period: 7 days
    uint256 public constant VOTING_PERIOD = 7 days;
    
    // ============ State Variables ============
    
    // Max supply (can be changed via governance)
    uint256 public maxSupply;
    
    // Total MCT minted as rewards (for halving calculation)
    uint256 public totalRewardsMinted;
    
    // Daily tracking
    uint256 public currentDay;
    uint256 public mintedToday;
    
    // Fee pool for relay nodes (accumulated from transfers)
    uint256 public feePool;
    
    // Registry contract address (for relay node verification)
    address public registryContract;
    
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
    
    // Active relay nodes count (set by registry)
    uint256 public activeRelayCount;
    
    // ============ Events ============
    
    event RelayRewardMinted(address indexed relayNode, uint256 amount, uint256 messagesRelayed);
    event FeeRewardClaimed(address indexed relayNode, uint256 amount);
    event HalvingOccurred(uint256 newRewardAmount, uint256 halvingCount);
    event TransferFeeCollected(uint256 amount);
    event ProposalCreated(uint256 indexed proposalId, uint256 newMaxSupply, address proposer);
    event VoteCast(uint256 indexed proposalId, address indexed voter, bool support);
    event ProposalExecuted(uint256 indexed proposalId, uint256 newMaxSupply);
    event MaxSupplyChanged(uint256 oldMaxSupply, uint256 newMaxSupply);
    
    /// @custom:oz-upgrades-unsafe-allow constructor
    constructor() {
        _disableInitializers();
    }

    /**
     * @dev Initialize the token (called once via proxy)
     */
    function initialize(address initialOwner) public initializer {
        __ERC20_init("MumbleChat Token", "MCT");
        __ERC20Burnable_init();
        __Ownable_init(initialOwner);
        __UUPSUpgradeable_init();

        // Set initial max supply: 1,000,000 MCT
        maxSupply = 1_000_000 * 10 ** 18;
        
        // Mint initial supply: 1,000 MCT
        _mint(initialOwner, 1_000 * 10 ** decimals());
        
        // Initialize day tracking
        currentDay = block.timestamp / 1 days;
        mintedToday = 0;
    }
    
    /**
     * @dev Reinitialize for V3 upgrade
     */
    function initializeV3(address _registryContract) public reinitializer(3) {
        registryContract = _registryContract;
        maxSupply = 1_000_000 * 10 ** 18;
    }

    // ============ Transfer with Fee ============
    
    /**
     * @dev Override transfer to collect 0.1% fee for relay nodes
     */
    function transfer(address to, uint256 amount) public virtual override returns (bool) {
        address from = _msgSender();
        
        // Calculate fee (0.1%)
        uint256 fee = (amount * TRANSFER_FEE_BPS) / 10000;
        uint256 amountAfterFee = amount - fee;
        
        // Transfer amount minus fee
        _transfer(from, to, amountAfterFee);
        
        // Add fee to pool (keep in contract)
        if (fee > 0) {
            _transfer(from, address(this), fee);
            feePool += fee;
            emit TransferFeeCollected(fee);
        }
        
        return true;
    }
    
    /**
     * @dev Override transferFrom to collect 0.1% fee
     */
    function transferFrom(address from, address to, uint256 amount) public virtual override returns (bool) {
        address spender = _msgSender();
        _spendAllowance(from, spender, amount);
        
        // Calculate fee (0.1%)
        uint256 fee = (amount * TRANSFER_FEE_BPS) / 10000;
        uint256 amountAfterFee = amount - fee;
        
        // Transfer amount minus fee
        _transfer(from, to, amountAfterFee);
        
        // Add fee to pool
        if (fee > 0) {
            _transfer(from, address(this), fee);
            feePool += fee;
            emit TransferFeeCollected(fee);
        }
        
        return true;
    }

    // ============ Fee Pool Distribution (TIER-BASED) ============
    
    /**
     * @dev Claim share of fee pool based on TIER multiplier
     * Called by registry contract on behalf of relay nodes
     * @param relayNode The relay node address
     * @param tierMultiplier The tier multiplier in basis points (100 = 1x, 300 = 3x)
     * 
     * IMPORTANT: Minting rewards are always 1x (no tier bonus)
     * Tier bonuses ONLY apply to fee pool distribution
     * This keeps max supply controlled while rewarding high-tier nodes
     */
    function claimFeeReward(address relayNode, uint256 tierMultiplier) external returns (uint256) {
        require(msg.sender == registryContract, "Only registry can distribute");
        require(activeRelayCount > 0, "No active relays");
        require(feePool > 0, "No fees to distribute");
        require(tierMultiplier >= 100 && tierMultiplier <= 300, "Invalid multiplier");
        
        // Base share per node
        uint256 baseShare = feePool / activeRelayCount;
        
        // Apply tier multiplier (100 = 1x, 150 = 1.5x, 200 = 2x, 300 = 3x)
        uint256 tierShare = (baseShare * tierMultiplier) / 100;
        
        // Cap at available fee pool to prevent over-distribution
        if (tierShare > feePool) {
            tierShare = feePool;
        }
        
        if (tierShare > 0) {
            feePool -= tierShare;
            _transfer(address(this), relayNode, tierShare);
            emit FeeRewardClaimed(relayNode, tierShare);
        }
        
        return tierShare;
    }
    
    /**
     * @dev Legacy claim without tier (defaults to 1x)
     */
    function claimFeeReward(address relayNode) external returns (uint256) {
        require(msg.sender == registryContract, "Only registry can distribute");
        require(activeRelayCount > 0, "No active relays");
        require(feePool > 0, "No fees to distribute");
        
        uint256 share = feePool / activeRelayCount;
        
        if (share > 0) {
            feePool -= share;
            _transfer(address(this), relayNode, share);
            emit FeeRewardClaimed(relayNode, share);
        }
        
        return share;
    }
    
    /**
     * @dev Set active relay count (called by registry)
     */
    function setActiveRelayCount(uint256 count) external {
        require(msg.sender == registryContract || msg.sender == owner(), "Unauthorized");
        activeRelayCount = count;
    }
    
    /**
     * @dev Set registry contract address
     */
    function setRegistryContract(address _registry) external onlyOwner {
        registryContract = _registry;
    }

    // ============ Governance: Propose Max Supply Change ============
    
    /**
     * @dev Create proposal to change max supply (any relay node can propose)
     * @param newMaxSupply The proposed new max supply
     */
    function proposeMaxSupplyChange(uint256 newMaxSupply) external returns (uint256) {
        require(newMaxSupply > totalSupply(), "Must be greater than current supply");
        require(newMaxSupply <= 10_000_000 * 10 ** 18, "Max 10M absolute limit");
        
        proposalCount++;
        Proposal storage p = proposals[proposalCount];
        p.newMaxSupply = newMaxSupply;
        p.startTime = block.timestamp;
        p.endTime = block.timestamp + VOTING_PERIOD;
        p.executed = false;
        
        emit ProposalCreated(proposalCount, newMaxSupply, msg.sender);
        
        return proposalCount;
    }
    
    /**
     * @dev Vote on a proposal (relay nodes only, verified by registry)
     */
    function vote(uint256 proposalId, bool support) external {
        Proposal storage p = proposals[proposalId];
        require(block.timestamp >= p.startTime, "Voting not started");
        require(block.timestamp <= p.endTime, "Voting ended");
        require(!p.hasVoted[msg.sender], "Already voted");
        require(!p.executed, "Already executed");
        
        // Verify caller is active relay node (registry must confirm)
        // For now, require MCT stake as proof
        require(balanceOf(msg.sender) >= 100 * 10 ** 18, "Must hold 100 MCT to vote");
        
        p.hasVoted[msg.sender] = true;
        
        if (support) {
            p.yesVotes++;
        } else {
            p.noVotes++;
        }
        
        emit VoteCast(proposalId, msg.sender, support);
    }
    
    /**
     * @dev Execute proposal if 90% voted yes
     */
    function executeProposal(uint256 proposalId) external {
        Proposal storage p = proposals[proposalId];
        require(block.timestamp > p.endTime, "Voting still active");
        require(!p.executed, "Already executed");
        
        uint256 totalVotes = p.yesVotes + p.noVotes;
        require(totalVotes > 0, "No votes cast");
        
        // Calculate percentage (90% threshold)
        uint256 yesPercentage = (p.yesVotes * 100) / totalVotes;
        require(yesPercentage >= GOVERNANCE_THRESHOLD, "Need 90% approval");
        
        // Also require minimum participation (at least 10 votes or 50% of relay nodes)
        uint256 minVotes = activeRelayCount > 0 ? (activeRelayCount * 50) / 100 : 10;
        require(totalVotes >= minVotes, "Insufficient participation");
        
        // Execute: change max supply
        uint256 oldMaxSupply = maxSupply;
        maxSupply = p.newMaxSupply;
        p.executed = true;
        
        emit ProposalExecuted(proposalId, p.newMaxSupply);
        emit MaxSupplyChanged(oldMaxSupply, p.newMaxSupply);
    }
    
    /**
     * @dev Get proposal details
     */
    function getProposal(uint256 proposalId) external view returns (
        uint256 newMaxSupply,
        uint256 startTime,
        uint256 endTime,
        uint256 yesVotes,
        uint256 noVotes,
        bool executed,
        bool votingActive
    ) {
        Proposal storage p = proposals[proposalId];
        return (
            p.newMaxSupply,
            p.startTime,
            p.endTime,
            p.yesVotes,
            p.noVotes,
            p.executed,
            block.timestamp >= p.startTime && block.timestamp <= p.endTime
        );
    }

    // ============ Reward Calculation ============

    /**
     * @dev Get the current halving count
     */
    function getHalvingCount() public view returns (uint256) {
        return totalRewardsMinted / HALVING_THRESHOLD;
    }

    /**
     * @dev Calculate current reward per 1000 messages (considering halvings)
     */
    function calculateRewardPer1000Messages() public view returns (uint256) {
        uint256 halvings = getHalvingCount();
        uint256 reward = BASE_REWARD_PER_1000_MSG;
        
        // Apply halvings (max 10 halvings)
        for (uint256 i = 0; i < halvings && i < 10; i++) {
            reward = reward / 2;
        }
        
        // Minimum reward: 0.00001 MCT
        if (reward < 10 ** 13) {
            reward = 10 ** 13;
        }
        
        return reward;
    }

    /**
     * @dev Check remaining mintable today
     */
    function remainingDailyMint() public view returns (uint256) {
        uint256 today = block.timestamp / 1 days;
        if (today != currentDay) {
            return DAILY_MINT_CAP;
        }
        if (mintedToday >= DAILY_MINT_CAP) {
            return 0;
        }
        return DAILY_MINT_CAP - mintedToday;
    }

    /**
     * @dev Check if max supply would be exceeded
     */
    function canMint(uint256 amount) public view returns (bool) {
        return totalSupply() + amount <= maxSupply;
    }
    
    /**
     * @dev Check if max supply reached (fee-only mode)
     */
    function isMaxSupplyReached() public view returns (bool) {
        return totalSupply() >= maxSupply;
    }

    // ============ Relay Reward Functions ============

    /**
     * @dev Mint relay reward for 1000 messages relayed
     */
    function mintRelayReward(address relayNode, uint256 batchesOf1000) public onlyOwner {
        require(batchesOf1000 > 0, "Must relay at least 1000 messages");
        require(batchesOf1000 <= 100, "Max 100 batches per tx");
        
        // If max supply reached, no minting (nodes earn from fees instead)
        if (isMaxSupplyReached()) {
            // Emit event but don't mint
            emit RelayRewardMinted(relayNode, 0, batchesOf1000 * MESSAGES_PER_REWARD);
            return;
        }
        
        // Update day tracking
        uint256 today = block.timestamp / 1 days;
        if (today != currentDay) {
            currentDay = today;
            mintedToday = 0;
        }
        
        // Calculate reward
        uint256 rewardPerBatch = calculateRewardPer1000Messages();
        uint256 totalReward = rewardPerBatch * batchesOf1000;
        
        // Check daily cap
        if (mintedToday + totalReward > DAILY_MINT_CAP) {
            totalReward = DAILY_MINT_CAP - mintedToday;
        }
        
        // Check max supply
        if (totalSupply() + totalReward > maxSupply) {
            totalReward = maxSupply - totalSupply();
        }
        
        if (totalReward == 0) {
            return;
        }
        
        // Mint reward
        _mint(relayNode, totalReward);
        totalRewardsMinted += totalReward;
        mintedToday += totalReward;
        
        emit RelayRewardMinted(relayNode, totalReward, batchesOf1000 * MESSAGES_PER_REWARD);
        
        // Check if halving occurred
        uint256 newHalvingCount = getHalvingCount();
        if (newHalvingCount > (totalRewardsMinted - totalReward) / HALVING_THRESHOLD) {
            emit HalvingOccurred(calculateRewardPer1000Messages(), newHalvingCount);
        }
    }

    /**
     * @dev Batch mint relay rewards
     */
    function batchMintRelayRewards(
        address[] calldata relayNodes, 
        uint256[] calldata batchesOf1000
    ) public onlyOwner {
        require(relayNodes.length == batchesOf1000.length, "Array length mismatch");
        require(relayNodes.length <= 50, "Max 50 nodes per tx");
        
        for (uint256 i = 0; i < relayNodes.length; i++) {
            if (batchesOf1000[i] > 0) {
                mintRelayReward(relayNodes[i], batchesOf1000[i]);
            }
        }
    }

    // ============ View Functions ============

    /**
     * @dev Get tokenomics info
     */
    function getTokenomics() external view returns (
        uint256 currentSupply,
        uint256 _maxSupply,
        uint256 rewardPer1000Msg,
        uint256 halvingCount,
        uint256 dailyRemaining,
        uint256 totalRewarded,
        uint256 _feePool,
        bool maxReached
    ) {
        return (
            totalSupply(),
            maxSupply,
            calculateRewardPer1000Messages(),
            getHalvingCount(),
            remainingDailyMint(),
            totalRewardsMinted,
            feePool,
            isMaxSupplyReached()
        );
    }

    // ============ Admin Functions ============

    function mint(address to, uint256 amount) public onlyOwner {
        require(canMint(amount), "Would exceed max supply");
        _mint(to, amount);
    }

    function _authorizeUpgrade(address newImplementation) internal override onlyOwner {}

    function version() public pure returns (string memory) {
        return "3.0.0";
    }
}
