/**
 * MumbleChat Desktop Relay Node - Blockchain Service
 * 
 * Web3 integration for MumbleChatRegistry and MCT Token contracts
 * Based on MumbleChat Protocol documentation (07_SMART_CONTRACTS.md)
 */

import { ethers } from 'ethers';
import { getLogger } from '../utils/logger';
import { RelayTier, getTierName } from '../config';

// Contract ABIs (minimal interface for relay operations)
const REGISTRY_ABI = [
  'function identities(address) view returns (bytes32 publicKeyX, bytes32 publicKeyY, uint256 registeredAt, uint256 lastUpdated, bool isActive, string displayName)',
  'function relayNodes(address) view returns (string endpoint, uint256 stakedAmount, uint256 registeredAt, uint256 messagesRelayed, uint256 rewardsEarned, bool isActive, uint256 totalUptimeSeconds, uint256 lastHeartbeat, uint256 currentSessionStart, uint256 storageMB, uint256 dailyUptimeSeconds, uint256 lastDayReset, uint8 tier)',
  'function getActiveRelayNodes() view returns (address[])',
  'function minRelayStake() view returns (uint256)',
  'function register(bytes32 publicKeyX, string displayName)',
  'function registerAsRelay(string endpoint, uint256 storageMB)',
  'function deactivateRelay()',
  'function heartbeat(uint256 storageMB)',
  'function updateEndpoint(string endpoint)',
  'function claimRewards()',
  'function claimDailyPoolReward()',
  'function getRelayNodeInfo(address node) view returns (string endpoint, uint256 stakedAmount, uint256 messagesRelayed, uint256 rewardsEarned, bool isActive, uint8 tier)',
  'event HeartbeatReceived(address indexed node, uint256 uptimeSeconds, uint8 tier)',
  'event TierChanged(address indexed node, uint8 oldTier, uint8 newTier)',
];

const MCT_ABI = [
  'function balanceOf(address) view returns (uint256)',
  'function approve(address spender, uint256 amount) returns (bool)',
  'function allowance(address owner, address spender) view returns (uint256)',
  'function symbol() view returns (string)',
  'function decimals() view returns (uint8)',
];

export interface RelayNodeInfo {
  endpoint: string;
  stakedAmount: bigint;
  messagesRelayed: bigint;
  rewardsEarned: bigint;
  isActive: boolean;
  tier: RelayTier;
  totalUptimeSeconds: bigint;
  lastHeartbeat: bigint;
  storageMB: bigint;
  dailyUptimeSeconds: bigint;
}

export interface IdentityInfo {
  publicKeyX: string;
  publicKeyY: string;
  registeredAt: bigint;
  lastUpdated: bigint;
  isActive: boolean;
  displayName: string;
}

export class BlockchainService {
  private provider: ethers.JsonRpcProvider;
  private wallet: ethers.Wallet;
  private registry: ethers.Contract;
  private mctToken: ethers.Contract;
  private logger = getLogger();

  constructor(
    rpcUrl: string,
    registryAddress: string,
    mctTokenAddress: string,
    privateKey: string
  ) {
    this.provider = new ethers.JsonRpcProvider(rpcUrl);
    this.wallet = new ethers.Wallet(privateKey, this.provider);
    this.registry = new ethers.Contract(registryAddress, REGISTRY_ABI, this.wallet);
    this.mctToken = new ethers.Contract(mctTokenAddress, MCT_ABI, this.wallet);

    this.logger.info(`BlockchainService initialized`);
    this.logger.info(`Wallet address: ${this.wallet.address}`);
  }

  /**
   * Get wallet address
   */
  getWalletAddress(): string {
    return this.wallet.address;
  }

  /**
   * Get MCT balance
   */
  async getMCTBalance(): Promise<bigint> {
    return await this.mctToken.balanceOf(this.wallet.address);
  }

  /**
   * Get RAMA balance (native token)
   */
  async getRAMABalance(): Promise<bigint> {
    return await this.provider.getBalance(this.wallet.address);
  }

  /**
   * Check if identity is registered
   */
  async isIdentityRegistered(): Promise<boolean> {
    const identity = await this.registry.identities(this.wallet.address);
    return identity.isActive;
  }

  /**
   * Get identity info
   */
  async getIdentityInfo(address?: string): Promise<IdentityInfo | null> {
    const addr = address || this.wallet.address;
    const identity = await this.registry.identities(addr);
    
    if (!identity.isActive) {
      return null;
    }

    return {
      publicKeyX: identity.publicKeyX,
      publicKeyY: identity.publicKeyY,
      registeredAt: identity.registeredAt,
      lastUpdated: identity.lastUpdated,
      isActive: identity.isActive,
      displayName: identity.displayName,
    };
  }

  /**
   * Register identity
   */
  async registerIdentity(publicKeyX: string, displayName: string): Promise<string> {
    this.logger.info(`Registering identity: ${displayName}`);
    const tx = await this.registry.register(publicKeyX, displayName);
    await tx.wait();
    this.logger.info(`Identity registered: ${tx.hash}`);
    return tx.hash;
  }

  /**
   * Check if relay node is registered
   */
  async isRelayRegistered(): Promise<boolean> {
    const relay = await this.registry.relayNodes(this.wallet.address);
    return relay.isActive;
  }

  /**
   * Get relay node info
   */
  async getRelayNodeInfo(): Promise<RelayNodeInfo | null> {
    const relay = await this.registry.relayNodes(this.wallet.address);
    
    if (!relay.isActive) {
      return null;
    }

    return {
      endpoint: relay.endpoint,
      stakedAmount: relay.stakedAmount,
      messagesRelayed: relay.messagesRelayed,
      rewardsEarned: relay.rewardsEarned,
      isActive: relay.isActive,
      tier: relay.tier as RelayTier,
      totalUptimeSeconds: relay.totalUptimeSeconds,
      lastHeartbeat: relay.lastHeartbeat,
      storageMB: relay.storageMB,
      dailyUptimeSeconds: relay.dailyUptimeSeconds,
    };
  }

  /**
   * Get minimum stake required
   */
  async getMinStake(): Promise<bigint> {
    return await this.registry.minRelayStake();
  }

  /**
   * Approve MCT tokens for staking
   */
  async approveMCT(amount: bigint): Promise<string> {
    this.logger.info(`Approving ${ethers.formatEther(amount)} MCT for staking...`);
    const tx = await this.mctToken.approve(await this.registry.getAddress(), amount);
    await tx.wait();
    this.logger.info(`MCT approved: ${tx.hash}`);
    return tx.hash;
  }

  /**
   * Register as relay node
   */
  async registerAsRelay(endpoint: string, storageMB: number): Promise<string> {
    // Check if identity is registered first
    if (!(await this.isIdentityRegistered())) {
      throw new Error('Identity must be registered before registering as relay');
    }

    // Check MCT balance and allowance
    const minStake = await this.getMinStake();
    const balance = await this.getMCTBalance();
    
    if (balance < minStake) {
      throw new Error(`Insufficient MCT balance. Required: ${ethers.formatEther(minStake)} MCT, Have: ${ethers.formatEther(balance)} MCT`);
    }

    const allowance = await this.mctToken.allowance(this.wallet.address, await this.registry.getAddress());
    if (allowance < minStake) {
      await this.approveMCT(minStake);
    }

    this.logger.info(`Registering as relay node: ${endpoint}, ${storageMB}MB storage`);
    const tx = await this.registry.registerAsRelay(endpoint, storageMB);
    await tx.wait();
    this.logger.info(`Relay registered: ${tx.hash}`);
    return tx.hash;
  }

  /**
   * Send heartbeat to update uptime
   */
  async sendHeartbeat(storageMB: number): Promise<string> {
    this.logger.debug(`Sending heartbeat: ${storageMB}MB storage`);
    const tx = await this.registry.heartbeat(storageMB);
    await tx.wait();
    this.logger.debug(`Heartbeat sent: ${tx.hash}`);
    return tx.hash;
  }

  /**
   * Update relay endpoint
   */
  async updateEndpoint(newEndpoint: string): Promise<string> {
    this.logger.info(`Updating endpoint to: ${newEndpoint}`);
    const tx = await this.registry.updateEndpoint(newEndpoint);
    await tx.wait();
    this.logger.info(`Endpoint updated: ${tx.hash}`);
    return tx.hash;
  }

  /**
   * Claim pending rewards
   */
  async claimRewards(): Promise<string> {
    this.logger.info('Claiming rewards...');
    const tx = await this.registry.claimRewards();
    await tx.wait();
    this.logger.info(`Rewards claimed: ${tx.hash}`);
    return tx.hash;
  }

  /**
   * Claim daily pool reward
   */
  async claimDailyPoolReward(): Promise<string> {
    this.logger.info('Claiming daily pool reward...');
    const tx = await this.registry.claimDailyPoolReward();
    await tx.wait();
    this.logger.info(`Daily pool reward claimed: ${tx.hash}`);
    return tx.hash;
  }

  /**
   * Deactivate relay node (unstake)
   */
  async deactivateRelay(): Promise<string> {
    this.logger.info('Deactivating relay node...');
    const tx = await this.registry.deactivateRelay();
    await tx.wait();
    this.logger.info(`Relay deactivated: ${tx.hash}`);
    return tx.hash;
  }

  /**
   * Get active relay nodes
   */
  async getActiveRelayNodes(): Promise<string[]> {
    return await this.registry.getActiveRelayNodes();
  }

  /**
   * Get public key for an address
   */
  async getPublicKey(address: string): Promise<string | null> {
    const identity = await this.getIdentityInfo(address);
    return identity?.publicKeyX || null;
  }

  /**
   * Wait for transaction confirmation
   */
  async waitForTransaction(txHash: string, confirmations = 1): Promise<ethers.TransactionReceipt | null> {
    return await this.provider.waitForTransaction(txHash, confirmations);
  }

  /**
   * Get current gas price
   */
  async getGasPrice(): Promise<bigint> {
    const feeData = await this.provider.getFeeData();
    return feeData.gasPrice || 0n;
  }

  /**
   * Print status summary
   */
  async printStatus(): Promise<void> {
    const address = this.getWalletAddress();
    const ramaBalance = await this.getRAMABalance();
    const mctBalance = await this.getMCTBalance();
    const isRelay = await this.isRelayRegistered();

    this.logger.info('='.repeat(50));
    this.logger.info('Blockchain Status');
    this.logger.info('='.repeat(50));
    this.logger.info(`Wallet: ${address}`);
    this.logger.info(`RAMA Balance: ${ethers.formatEther(ramaBalance)} RAMA`);
    this.logger.info(`MCT Balance: ${ethers.formatEther(mctBalance)} MCT`);
    this.logger.info(`Relay Registered: ${isRelay}`);

    if (isRelay) {
      const info = await this.getRelayNodeInfo();
      if (info) {
        this.logger.info(`Tier: ${getTierName(info.tier)}`);
        this.logger.info(`Messages Relayed: ${info.messagesRelayed}`);
        this.logger.info(`Rewards Earned: ${ethers.formatEther(info.rewardsEarned)} MCT`);
        this.logger.info(`Endpoint: ${info.endpoint}`);
      }
    }
    this.logger.info('='.repeat(50));
  }
}
