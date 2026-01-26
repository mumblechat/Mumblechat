/**
 * MumbleChat Desktop Relay Node - Reward Service (V3)
 * 
 * Comprehensive reward management implementing all features from relay-nodes.html:
 * - Daily Pool Rewards (100 MCT distributed at midnight UTC)
 * - Fee Pool Rewards (0.1% of all MCT transfers)
 * - Minting Rewards (0.001 MCT per 1,000 messages)
 * - Tier-based multipliers (Bronze 1x, Silver 1.5x, Gold 2x, Platinum 3x)
 * - Uptime tracking and missed reward redistribution
 * 
 * V3 REWARD CAP FIX:
 * - Rewards are capped at base entitlement: MIN(poolShare, baseRewardCap)
 * - baseRewardCap = (relayCount / 1000) * 0.001 MCT
 * - Prevents nodes from earning more than their message count entitles them to
 * - Contract: 0x2233A9e60BE7aF129B76FD87427fe7228bA1E6d2
 */

import { ethers } from 'ethers';
import { getLogger } from '../utils/logger';
import { RelayTier, getTierName } from '../config';

// Extended RelayManager ABI for reward functions (V3 - Reward Cap System)
const RELAY_MANAGER_REWARD_ABI = [
  // ===== V3 REWARD CAP CONSTANTS =====
  'function BASE_REWARD_PER_1000_MSG() view returns (uint256)',  // 0.001 MCT
  'function MESSAGES_PER_REWARD() view returns (uint256)',       // 1000
  
  // ===== DAILY POOL REWARDS =====
  'function claimDailyPoolReward(uint256 dayId)',
  'function getTodayPoolInfo() view returns (uint256 dayId, uint256 totalRelays, uint256 totalWeightedRelays, uint256 poolAmount, bool distributed)',
  'function currentDayPool() view returns (uint256)',
  'function dailyPoolAmount() view returns (uint256)',
  'function dailyPools(uint256 dayId) view returns (uint256 totalRelays, uint256 totalWeightedRelays, uint256 poolAmount, bool distributed)',
  'function getNodeDailyReward(bytes32 nodeId, uint256 dayId) view returns (uint256)',
  'function nodeDailyRewards(bytes32 nodeId, uint256 dayId) view returns (uint256)',
  'function nodeDailyStats(bytes32 nodeId, uint256 dayId) view returns (uint256 relayCount, uint256 weightedRelays, uint256 uptimeSeconds, bool rewardClaimed)',
  
  // ===== CLAIMABLE REWARDS =====
  'function getClaimableReward(bytes32 nodeId) view returns (uint256 dailyPoolReward, uint256 feePoolReward, uint256 mintingReward)',
  'function calculateTierRewardWithUptime(bytes32 nodeId, uint256 dayId, uint256 dailyPool) view returns (uint256 actualReward, uint256 missedReward)',
  
  // ===== MISSED REWARDS REDISTRIBUTION =====
  'function getDailyMissedPool(uint256 dayId) view returns (uint256)',
  'function dailyMissedPool(uint256 dayId) view returns (uint256)',
  'function dailyFullUptimeNodes(uint256 dayId) view returns (bytes32[])',
  'function distributeMissedRewards(uint256 dayId)',
  
  // ===== TIER MULTIPLIERS =====
  'function getRewardMultiplierByNodeId(bytes32 nodeId) view returns (uint256)',
  'function getFeePercentForTier(uint8 tier) view returns (uint256)',
  'function relayRewardPerMessage() view returns (uint256)',
  
  // ===== UPTIME =====
  'function getUptimeForTier(uint8 tier) view returns (uint256)',
  'function BRONZE_UPTIME() view returns (uint256)',
  'function SILVER_UPTIME() view returns (uint256)',
  'function GOLD_UPTIME() view returns (uint256)',
  'function PLATINUM_UPTIME() view returns (uint256)',
  
  // ===== RELAY PROOF =====
  'function submitRelayProof(bytes32 nodeId, uint256 messageCount, bytes32 proofHash)',
  'function relayProofClaimed(bytes32 nodeId, uint256 dayId) view returns (bool)',
  'function getRelayProofNonce(bytes32 nodeId) view returns (uint256)',
  
  // ===== EVENTS =====
  'event DailyPoolDistributed(uint256 indexed dayId, uint256 totalAmount, uint256 nodeCount)',
  'event MissedRewardRedistributed(uint256 indexed dayId, uint256 totalMissed, uint256 eligibleNodes)',
  'event NodeDailyRewardClaimed(bytes32 indexed nodeId, uint256 indexed dayId, uint256 reward)',
  'event RelayProofSubmitted(bytes32 indexed nodeId, uint256 messageCount, uint256 dayId)',
];

// Extended MCT Token ABI for reward functions
const MCT_REWARD_ABI = [
  // ===== FEE POOL =====
  'function feePool() view returns (uint256)',
  'function TRANSFER_FEE_BPS() view returns (uint256)',
  'function claimFeeReward(address relayNode)',
  
  // ===== MINTING REWARDS =====
  'function mintRelayReward(address relayNode, uint256 messageCount)',
  'function batchMintRelayRewards(address[] relayNodes, uint256[] messageCounts)',
  'function BASE_REWARD_PER_1000_MSG() view returns (uint256)',
  'function MESSAGES_PER_REWARD() view returns (uint256)',
  'function DAILY_MINT_CAP() view returns (uint256)',
  'function mintedToday() view returns (uint256)',
  'function remainingDailyMint() view returns (uint256)',
  'function canMint(uint256 amount) view returns (bool)',
  'function calculateRewardPer1000Messages() view returns (uint256)',
  'function totalRewardsMinted() view returns (uint256)',
  
  // ===== HALVING =====
  'function getHalvingCount() view returns (uint256)',
  'function HALVING_THRESHOLD() view returns (uint256)',
  
  // ===== RELAY COUNT =====
  'function activeRelayCount() view returns (uint256)',
  
  // ===== EVENTS =====
  'event RelayRewardMinted(address indexed relay, uint256 amount, uint256 messageCount)',
  'event FeeRewardClaimed(address indexed relay, uint256 amount)',
];

/**
 * Pool Info structure
 */
export interface DailyPoolInfo {
  dayId: bigint;
  totalRelays: bigint;
  totalWeightedRelays: bigint;
  poolAmount: bigint;
  distributed: boolean;
}

/**
 * Node daily stats structure
 */
export interface NodeDailyStats {
  relayCount: bigint;
  weightedRelays: bigint;
  uptimeSeconds: bigint;
  rewardClaimed: boolean;
}

/**
 * Claimable rewards structure
 */
export interface ClaimableRewards {
  dailyPoolReward: bigint;
  feePoolReward: bigint;
  mintingReward: bigint;
  total: bigint;
}

/**
 * Reward calculation result
 */
export interface TierRewardCalculation {
  actualReward: bigint;
  missedReward: bigint;
  uptimePercent: number;
}

/**
 * Tokenomics info
 */
export interface TokenomicsInfo {
  feePool: bigint;
  transferFeeBps: bigint;
  baseRewardPer1000: bigint;
  messagesPerReward: bigint;
  dailyMintCap: bigint;
  mintedToday: bigint;
  remainingDailyMint: bigint;
  totalRewardsMinted: bigint;
  halvingCount: bigint;
  halvingThreshold: bigint;
  currentRewardRate: bigint;
  activeRelayCount: bigint;
}

/**
 * Tier uptime requirements (in seconds)
 */
export interface TierUptimeRequirements {
  bronze: bigint;  // 4 hours = 14400 seconds
  silver: bigint;  // 8 hours = 28800 seconds
  gold: bigint;    // 12 hours = 43200 seconds
  platinum: bigint; // 16 hours = 57600 seconds
}

export class RewardService {
  private provider: ethers.JsonRpcProvider;
  private wallet: ethers.Wallet;
  private relayManager: ethers.Contract;
  private mctToken: ethers.Contract;
  private logger = getLogger();
  
  constructor(
    rpcUrl: string,
    relayManagerAddress: string,
    mctTokenAddress: string,
    privateKey: string
  ) {
    this.provider = new ethers.JsonRpcProvider(rpcUrl);
    this.wallet = new ethers.Wallet(privateKey, this.provider);
    this.relayManager = new ethers.Contract(relayManagerAddress, RELAY_MANAGER_REWARD_ABI, this.wallet);
    this.mctToken = new ethers.Contract(mctTokenAddress, MCT_REWARD_ABI, this.wallet);
    
    this.logger.info('RewardService initialized');
  }

  // ===============================
  // DAILY POOL REWARDS
  // ===============================

  /**
   * Get today's pool info
   */
  async getTodayPoolInfo(): Promise<DailyPoolInfo> {
    const info = await this.relayManager.getTodayPoolInfo();
    return {
      dayId: info.dayId,
      totalRelays: info.totalRelays,
      totalWeightedRelays: info.totalWeightedRelays,
      poolAmount: info.poolAmount,
      distributed: info.distributed,
    };
  }

  /**
   * Get daily pool info for a specific day
   */
  async getDailyPoolInfo(dayId: bigint): Promise<DailyPoolInfo> {
    const info = await this.relayManager.dailyPools(dayId);
    return {
      dayId,
      totalRelays: info.totalRelays,
      totalWeightedRelays: info.totalWeightedRelays,
      poolAmount: info.poolAmount,
      distributed: info.distributed,
    };
  }

  /**
   * Get the current day ID (days since Unix epoch)
   */
  getCurrentDayId(): bigint {
    return BigInt(Math.floor(Date.now() / 1000 / 86400));
  }

  /**
   * Get yesterday's day ID
   */
  getYesterdayDayId(): bigint {
    return this.getCurrentDayId() - 1n;
  }

  /**
   * Get node's daily stats for a specific day
   */
  async getNodeDailyStats(nodeId: string, dayId: bigint): Promise<NodeDailyStats> {
    const stats = await this.relayManager.nodeDailyStats(nodeId, dayId);
    return {
      relayCount: stats.relayCount,
      weightedRelays: stats.weightedRelays,
      uptimeSeconds: stats.uptimeSeconds,
      rewardClaimed: stats.rewardClaimed,
    };
  }

  /**
   * Get node's earned daily reward for a specific day
   */
  async getNodeDailyReward(nodeId: string, dayId: bigint): Promise<bigint> {
    return await this.relayManager.getNodeDailyReward(nodeId, dayId);
  }

  /**
   * Claim daily pool reward for a specific day
   */
  async claimDailyPoolReward(nodeId: string, dayId: bigint): Promise<string> {
    this.logger.info(`Claiming daily pool reward for day ${dayId}...`);
    const tx = await this.relayManager.claimDailyPoolReward(dayId);
    await tx.wait();
    this.logger.info(`Daily pool reward claimed: ${tx.hash}`);
    return tx.hash;
  }

  /**
   * Claim yesterday's daily pool reward (most common use case)
   */
  async claimYesterdayReward(nodeId: string): Promise<string> {
    const yesterday = this.getYesterdayDayId();
    return await this.claimDailyPoolReward(nodeId, yesterday);
  }

  // ===============================
  // CLAIMABLE REWARDS
  // ===============================

  /**
   * Get all claimable rewards for a node
   */
  async getClaimableRewards(nodeId: string): Promise<ClaimableRewards> {
    const rewards = await this.relayManager.getClaimableReward(nodeId);
    return {
      dailyPoolReward: rewards.dailyPoolReward,
      feePoolReward: rewards.feePoolReward,
      mintingReward: rewards.mintingReward,
      total: rewards.dailyPoolReward + rewards.feePoolReward + rewards.mintingReward,
    };
  }

  /**
   * Calculate tier reward with uptime consideration
   */
  async calculateTierRewardWithUptime(nodeId: string, dayId: bigint, dailyPool: bigint): Promise<TierRewardCalculation> {
    const result = await this.relayManager.calculateTierRewardWithUptime(nodeId, dayId, dailyPool);
    
    // Calculate uptime percent
    const stats = await this.getNodeDailyStats(nodeId, dayId);
    const requiredUptime = await this.getNodeRequiredUptime(nodeId);
    const uptimePercent = requiredUptime > 0n ? 
      Number(stats.uptimeSeconds * 100n / requiredUptime) : 100;
    
    return {
      actualReward: result.actualReward,
      missedReward: result.missedReward,
      uptimePercent: Math.min(uptimePercent, 100),
    };
  }

  // ===============================
  // MISSED REWARDS REDISTRIBUTION
  // ===============================

  /**
   * Get missed reward pool for a day
   */
  async getDailyMissedPool(dayId: bigint): Promise<bigint> {
    return await this.relayManager.getDailyMissedPool(dayId);
  }

  /**
   * Get nodes with 100% uptime for a day (eligible for bonus)
   */
  async getFullUptimeNodes(dayId: bigint): Promise<string[]> {
    return await this.relayManager.dailyFullUptimeNodes(dayId);
  }

  /**
   * Distribute missed rewards to eligible nodes
   */
  async distributeMissedRewards(dayId: bigint): Promise<string> {
    this.logger.info(`Distributing missed rewards for day ${dayId}...`);
    const tx = await this.relayManager.distributeMissedRewards(dayId);
    await tx.wait();
    this.logger.info(`Missed rewards distributed: ${tx.hash}`);
    return tx.hash;
  }

  // ===============================
  // TIER & MULTIPLIERS
  // ===============================

  /**
   * Get reward multiplier for a node (based on tier)
   */
  async getNodeRewardMultiplier(nodeId: string): Promise<bigint> {
    return await this.relayManager.getRewardMultiplierByNodeId(nodeId);
  }

  /**
   * Get fee pool percentage for a tier
   */
  async getFeePercentForTier(tier: RelayTier): Promise<bigint> {
    return await this.relayManager.getFeePercentForTier(tier);
  }

  /**
   * Get relay reward per message
   */
  async getRelayRewardPerMessage(): Promise<bigint> {
    return await this.relayManager.relayRewardPerMessage();
  }

  // ===============================
  // UPTIME REQUIREMENTS
  // ===============================

  /**
   * Get uptime requirement for a tier (in seconds)
   */
  async getUptimeForTier(tier: RelayTier): Promise<bigint> {
    return await this.relayManager.getUptimeForTier(tier);
  }

  /**
   * Get all tier uptime requirements
   */
  async getAllTierUptimes(): Promise<TierUptimeRequirements> {
    const [bronze, silver, gold, platinum] = await Promise.all([
      this.relayManager.BRONZE_UPTIME(),
      this.relayManager.SILVER_UPTIME(),
      this.relayManager.GOLD_UPTIME(),
      this.relayManager.PLATINUM_UPTIME(),
    ]);
    return { bronze, silver, gold, platinum };
  }

  /**
   * Get required uptime for a specific node
   */
  async getNodeRequiredUptime(nodeId: string): Promise<bigint> {
    // Get node tier first
    const multiplier = await this.getNodeRewardMultiplier(nodeId);
    
    // Map multiplier to tier and get uptime
    if (multiplier >= 300n) return await this.relayManager.PLATINUM_UPTIME();
    if (multiplier >= 200n) return await this.relayManager.GOLD_UPTIME();
    if (multiplier >= 150n) return await this.relayManager.SILVER_UPTIME();
    return await this.relayManager.BRONZE_UPTIME();
  }

  // ===============================
  // RELAY PROOF SUBMISSION
  // ===============================

  /**
   * Submit relay proof (messages relayed)
   */
  async submitRelayProof(nodeId: string, messageCount: bigint, proofHash: string): Promise<string> {
    this.logger.info(`Submitting relay proof: ${messageCount} messages`);
    const tx = await this.relayManager.submitRelayProof(nodeId, messageCount, proofHash);
    await tx.wait();
    this.logger.info(`Relay proof submitted: ${tx.hash}`);
    return tx.hash;
  }

  /**
   * Generate proof hash for message relay
   */
  generateRelayProofHash(nodeId: string, messageCount: bigint, nonce: bigint): string {
    const packed = ethers.solidityPacked(
      ['bytes32', 'uint256', 'uint256'],
      [nodeId, messageCount, nonce]
    );
    return ethers.keccak256(packed);
  }

  /**
   * Check if relay proof is already claimed for a day
   */
  async isRelayProofClaimed(nodeId: string, dayId: bigint): Promise<boolean> {
    return await this.relayManager.relayProofClaimed(nodeId, dayId);
  }

  /**
   * Get relay proof nonce for a node
   */
  async getRelayProofNonce(nodeId: string): Promise<bigint> {
    return await this.relayManager.getRelayProofNonce(nodeId);
  }

  // ===============================
  // FEE POOL REWARDS (MCT Token)
  // ===============================

  /**
   * Get current fee pool balance
   */
  async getFeePoolBalance(): Promise<bigint> {
    return await this.mctToken.feePool();
  }

  /**
   * Get transfer fee in basis points (10 = 0.1%)
   */
  async getTransferFeeBps(): Promise<bigint> {
    return await this.mctToken.TRANSFER_FEE_BPS();
  }

  /**
   * Claim fee pool reward
   */
  async claimFeeReward(): Promise<string> {
    this.logger.info('Claiming fee pool reward...');
    const tx = await this.mctToken.claimFeeReward(this.wallet.address);
    await tx.wait();
    this.logger.info(`Fee reward claimed: ${tx.hash}`);
    return tx.hash;
  }

  // ===============================
  // MINTING REWARDS (MCT Token)
  // ===============================

  /**
   * Get tokenomics info
   */
  async getTokenomicsInfo(): Promise<TokenomicsInfo> {
    const [
      feePool,
      transferFeeBps,
      baseRewardPer1000,
      messagesPerReward,
      dailyMintCap,
      mintedToday,
      remainingDailyMint,
      totalRewardsMinted,
      halvingCount,
      halvingThreshold,
      currentRewardRate,
      activeRelayCount,
    ] = await Promise.all([
      this.mctToken.feePool(),
      this.mctToken.TRANSFER_FEE_BPS(),
      this.mctToken.BASE_REWARD_PER_1000_MSG(),
      this.mctToken.MESSAGES_PER_REWARD(),
      this.mctToken.DAILY_MINT_CAP(),
      this.mctToken.mintedToday(),
      this.mctToken.remainingDailyMint(),
      this.mctToken.totalRewardsMinted(),
      this.mctToken.getHalvingCount(),
      this.mctToken.HALVING_THRESHOLD(),
      this.mctToken.calculateRewardPer1000Messages(),
      this.mctToken.activeRelayCount(),
    ]);

    return {
      feePool,
      transferFeeBps,
      baseRewardPer1000,
      messagesPerReward,
      dailyMintCap,
      mintedToday,
      remainingDailyMint,
      totalRewardsMinted,
      halvingCount,
      halvingThreshold,
      currentRewardRate,
      activeRelayCount,
    };
  }

  /**
   * Get base reward per 1000 messages
   */
  async getBaseRewardPer1000Messages(): Promise<bigint> {
    return await this.mctToken.BASE_REWARD_PER_1000_MSG();
  }

  /**
   * Get current reward rate (considering halving)
   */
  async getCurrentRewardRate(): Promise<bigint> {
    return await this.mctToken.calculateRewardPer1000Messages();
  }

  /**
   * Get daily mint cap (100 MCT)
   */
  async getDailyMintCap(): Promise<bigint> {
    return await this.mctToken.DAILY_MINT_CAP();
  }

  /**
   * Get remaining daily mint capacity
   */
  async getRemainingDailyMint(): Promise<bigint> {
    return await this.mctToken.remainingDailyMint();
  }

  /**
   * Check if amount can be minted today
   */
  async canMint(amount: bigint): Promise<boolean> {
    return await this.mctToken.canMint(amount);
  }

  /**
   * Get halving count
   */
  async getHalvingCount(): Promise<bigint> {
    return await this.mctToken.getHalvingCount();
  }

  /**
   * Get halving threshold (100,000 MCT)
   */
  async getHalvingThreshold(): Promise<bigint> {
    return await this.mctToken.HALVING_THRESHOLD();
  }

  /**
   * Get total rewards minted
   */
  async getTotalRewardsMinted(): Promise<bigint> {
    return await this.mctToken.totalRewardsMinted();
  }

  // ===============================
  // CLAIM ALL REWARDS
  // ===============================

  /**
   * Claim all available rewards (daily pool + fee pool)
   */
  async claimAllRewards(nodeId: string): Promise<{ dailyTx: string | null; feeTx: string | null }> {
    const claimable = await this.getClaimableRewards(nodeId);
    let dailyTx: string | null = null;
    let feeTx: string | null = null;

    // Claim daily pool reward if available
    if (claimable.dailyPoolReward > 0n) {
      const yesterday = this.getYesterdayDayId();
      try {
        dailyTx = await this.claimDailyPoolReward(nodeId, yesterday);
      } catch (e) {
        this.logger.warn(`Could not claim daily pool: ${e}`);
      }
    }

    // Claim fee pool reward if available
    if (claimable.feePoolReward > 0n) {
      try {
        feeTx = await this.claimFeeReward();
      } catch (e) {
        this.logger.warn(`Could not claim fee pool: ${e}`);
      }
    }

    return { dailyTx, feeTx };
  }

  // ===============================
  // REWARD SUMMARY
  // ===============================

  /**
   * Print comprehensive reward status
   */
  async printRewardStatus(nodeId: string): Promise<void> {
    this.logger.info('='.repeat(60));
    this.logger.info('💰 REWARD STATUS');
    this.logger.info('='.repeat(60));

    // Claimable rewards
    const claimable = await this.getClaimableRewards(nodeId);
    this.logger.info('📥 Claimable Rewards:');
    this.logger.info(`   Daily Pool:  ${ethers.formatEther(claimable.dailyPoolReward)} MCT`);
    this.logger.info(`   Fee Pool:    ${ethers.formatEther(claimable.feePoolReward)} MCT`);
    this.logger.info(`   Minting:     ${ethers.formatEther(claimable.mintingReward)} MCT`);
    this.logger.info(`   TOTAL:       ${ethers.formatEther(claimable.total)} MCT`);

    // Today's pool info
    const todayPool = await this.getTodayPoolInfo();
    this.logger.info('\n📊 Today\'s Pool:');
    this.logger.info(`   Day ID:           ${todayPool.dayId}`);
    this.logger.info(`   Total Relays:     ${todayPool.totalRelays}`);
    this.logger.info(`   Weighted Relays:  ${todayPool.totalWeightedRelays}`);
    this.logger.info(`   Pool Amount:      ${ethers.formatEther(todayPool.poolAmount)} MCT`);
    this.logger.info(`   Distributed:      ${todayPool.distributed ? 'Yes' : 'No'}`);

    // Node multiplier
    const multiplier = await this.getNodeRewardMultiplier(nodeId);
    this.logger.info(`\n🎯 Your Multiplier: ${Number(multiplier) / 100}x`);

    // Tokenomics
    const tokenomics = await this.getTokenomicsInfo();
    this.logger.info('\n📈 Tokenomics:');
    this.logger.info(`   Fee Pool Balance:     ${ethers.formatEther(tokenomics.feePool)} MCT`);
    this.logger.info(`   Transfer Fee:         ${Number(tokenomics.transferFeeBps) / 100}%`);
    this.logger.info(`   Current Reward Rate:  ${ethers.formatEther(tokenomics.currentRewardRate)} MCT per 1000 msgs`);
    this.logger.info(`   Daily Mint Cap:       ${ethers.formatEther(tokenomics.dailyMintCap)} MCT`);
    this.logger.info(`   Remaining Today:      ${ethers.formatEther(tokenomics.remainingDailyMint)} MCT`);
    this.logger.info(`   Halvings Occurred:    ${tokenomics.halvingCount}`);
    this.logger.info(`   Active Relay Count:   ${tokenomics.activeRelayCount}`);

    // Uptime requirements
    const uptimes = await this.getAllTierUptimes();
    this.logger.info('\n⏰ Uptime Requirements:');
    this.logger.info(`   🥉 Bronze:   ${Number(uptimes.bronze) / 3600}h/day`);
    this.logger.info(`   🥈 Silver:   ${Number(uptimes.silver) / 3600}h/day`);
    this.logger.info(`   🥇 Gold:     ${Number(uptimes.gold) / 3600}h/day`);
    this.logger.info(`   💎 Platinum: ${Number(uptimes.platinum) / 3600}h/day`);

    this.logger.info('='.repeat(60));
  }

  /**
   * Listen for reward events
   */
  setupEventListeners(nodeId: string): void {
    // Daily Pool Distributed
    this.relayManager.on('DailyPoolDistributed', (dayId, totalAmount, nodeCount) => {
      this.logger.info(`🎉 Daily Pool Distributed! Day ${dayId}: ${ethers.formatEther(totalAmount)} MCT to ${nodeCount} nodes`);
    });

    // Node Reward Claimed
    this.relayManager.on('NodeDailyRewardClaimed', (claimedNodeId, dayId, reward) => {
      if (claimedNodeId === nodeId) {
        this.logger.info(`💰 You claimed ${ethers.formatEther(reward)} MCT for day ${dayId}`);
      }
    });

    // Relay Proof Submitted
    this.relayManager.on('RelayProofSubmitted', (submittedNodeId, messageCount, dayId) => {
      if (submittedNodeId === nodeId) {
        this.logger.info(`📝 Relay proof submitted: ${messageCount} messages for day ${dayId}`);
      }
    });

    // Missed Reward Redistribution
    this.relayManager.on('MissedRewardRedistributed', (dayId, totalMissed, eligibleNodes) => {
      this.logger.info(`🔄 Missed rewards redistributed for day ${dayId}: ${ethers.formatEther(totalMissed)} MCT to ${eligibleNodes} nodes`);
    });

    // MCT Token Events
    this.mctToken.on('RelayRewardMinted', (relay, amount, messageCount) => {
      if (relay.toLowerCase() === this.wallet.address.toLowerCase()) {
        this.logger.info(`⛏️ Minting reward: ${ethers.formatEther(amount)} MCT for ${messageCount} messages`);
      }
    });

    this.mctToken.on('FeeRewardClaimed', (relay, amount) => {
      if (relay.toLowerCase() === this.wallet.address.toLowerCase()) {
        this.logger.info(`💰 Fee reward claimed: ${ethers.formatEther(amount)} MCT`);
      }
    });

    this.logger.info('Event listeners set up for reward notifications');
  }

  /**
   * Remove event listeners
   */
  removeEventListeners(): void {
    this.relayManager.removeAllListeners();
    this.mctToken.removeAllListeners();
    this.logger.info('Event listeners removed');
  }
}
