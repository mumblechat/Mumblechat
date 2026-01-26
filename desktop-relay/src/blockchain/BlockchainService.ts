/**
 * MumbleChat Desktop Relay Node - Blockchain Service
 * 
 * Web3 integration for MumbleChatRegistry, RelayManager, and MCT Token contracts
 * V4 Node Identity + Tier Staking System
 */

import { ethers } from 'ethers';
import { getLogger } from '../utils/logger';
import { RelayTier, getTierName } from '../config';

// Contract ABIs - V4 Split Architecture (RelayManager V3 - Reward Cap)
const REGISTRY_ABI = [
  'function identities(address) view returns (bytes32 publicKeyX, bytes32 publicKeyY, uint256 registeredAt, uint256 lastUpdated, bool isActive, string displayName)',
  'function register(bytes32 publicKeyX, string displayName)',
  'function isIdentityActive(address user) view returns (bool)',
];

// V4 RelayManager ABI - Node Identity + Tier System (V3 Reward Cap)
const RELAY_MANAGER_ABI = [
  // V3 Reward Cap Constants
  'function BASE_REWARD_PER_1000_MSG() view returns (uint256)',
  'function MESSAGES_PER_REWARD() view returns (uint256)',
  'function version() view returns (string)',
  
  // Node Identity Registration
  'function registerNodeIdentity(bytes32 nodeId, bytes32 machineIdHash, bytes32 serialHash, string endpoint, uint256 storageMB, uint8 tier) payable',
  'function deactivateNodeIdentity(bytes32 nodeId)',
  'function heartbeatByNodeId(bytes32 nodeId)',
  'function updateEndpointByNodeId(bytes32 nodeId, string endpoint)',
  'function updateStorageByNodeId(bytes32 nodeId, uint256 storageMB)',
  'function changeTier(bytes32 nodeId, uint8 newTier)',
  
  // View Functions
  'function getNodeByNodeId(bytes32 nodeId) view returns (address walletAddress, bytes32 machineIdHash, uint256 instanceNumber, string endpoint, uint256 stakedAmount, uint256 messagesRelayed, uint256 rewardsEarned, bool isActive, uint256 dailyUptimeSeconds, uint256 storageMB, uint8 tier, bool isOnline, uint256 registeredAt)',
  'function getNodeIdentity(bytes32 nodeId) view returns (bytes32 id, bytes32 machineIdHash, bytes32 serialHash, address walletAddress, uint256 instanceNumber, uint256 registeredAt, bool isActive)',
  'function getWalletNodeIds(address wallet) view returns (bytes32[])',
  'function getNodeIdsByMachine(bytes32 machineIdHash) view returns (bytes32[])',
  'function getTotalNodeIds() view returns (uint256)',
  'function isNodeOnlineByNodeId(bytes32 nodeId) view returns (bool)',
  'function getActiveNodeIds() view returns (bytes32[] nodeIds, address[] wallets)',
  'function getStakeForTier(uint8 tier) view returns (uint256)',
  'function getUptimeForTier(uint8 tier) view returns (uint256)',
  'function getTierInfo() view returns (uint256[4] stakes, uint256[4] uptimes, uint256[4] storages, uint256[4] feePercents, uint256[4] multipliers)',
  
  // Rewards
  'function claimFeeRewardByNodeId(bytes32 nodeId)',
  'function claimMintingRewardsByNodeId(bytes32 nodeId)',
  
  // Events
  'event NodeIdentityRegistered(bytes32 indexed nodeId, address indexed wallet, bytes32 machineIdHash, uint256 instanceNumber)',
  'event NodeIdentityDeactivated(bytes32 indexed nodeId, address indexed wallet)',
  'event NodeHeartbeatByNodeId(bytes32 indexed nodeId, address indexed wallet, uint256 uptimeSeconds)',
  'event TierChanged(bytes32 indexed nodeId, uint8 oldTier, uint8 newTier)',
];

const MCT_ABI = [
  'function balanceOf(address) view returns (uint256)',
  'function approve(address spender, uint256 amount) returns (bool)',
  'function allowance(address owner, address spender) view returns (uint256)',
  'function symbol() view returns (string)',
  'function decimals() view returns (uint8)',
  'function feePool() view returns (uint256)',
];

export interface NodeInfo {
  nodeId: string;
  walletAddress: string;
  machineIdHash: string;
  instanceNumber: bigint;
  endpoint: string;
  stakedAmount: bigint;
  messagesRelayed: bigint;
  rewardsEarned: bigint;
  isActive: boolean;
  dailyUptimeSeconds: bigint;
  storageMB: bigint;
  tier: RelayTier;
  isOnline: boolean;
  registeredAt: bigint;
}

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
  private relayManager: ethers.Contract;
  private mctToken: ethers.Contract;
  private logger = getLogger();
  
  // Current node ID (set after registration)
  private currentNodeId: string | null = null;

  constructor(
    rpcUrl: string,
    registryAddress: string,
    relayManagerAddress: string,
    mctTokenAddress: string,
    privateKey: string
  ) {
    this.provider = new ethers.JsonRpcProvider(rpcUrl);
    this.wallet = new ethers.Wallet(privateKey, this.provider);
    this.registry = new ethers.Contract(registryAddress, REGISTRY_ABI, this.wallet);
    this.relayManager = new ethers.Contract(relayManagerAddress, RELAY_MANAGER_ABI, this.wallet);
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
   * Check if relay node is registered (V4: check if wallet has any node IDs)
   */
  async isRelayRegistered(): Promise<boolean> {
    const nodeIds = await this.relayManager.getWalletNodeIds(this.wallet.address);
    return nodeIds.length > 0;
  }

  /**
   * Get current node ID
   */
  getCurrentNodeId(): string | null {
    return this.currentNodeId;
  }

  /**
   * Set current node ID
   */
  setCurrentNodeId(nodeId: string): void {
    this.currentNodeId = nodeId;
    this.logger.info(`Current node ID set: ${nodeId}`);
  }

  /**
   * Get all node IDs for this wallet
   */
  async getWalletNodeIds(): Promise<string[]> {
    return await this.relayManager.getWalletNodeIds(this.wallet.address);
  }

  /**
   * Get node info by Node ID (V4)
   */
  async getNodeInfoByNodeId(nodeId: string): Promise<NodeInfo | null> {
    try {
      const node = await this.relayManager.getNodeByNodeId(nodeId);
      
      if (node.walletAddress === ethers.ZeroAddress) {
        return null;
      }

      return {
        nodeId: nodeId,
        walletAddress: node.walletAddress,
        machineIdHash: node.machineIdHash,
        instanceNumber: node.instanceNumber,
        endpoint: node.endpoint,
        stakedAmount: node.stakedAmount,
        messagesRelayed: node.messagesRelayed,
        rewardsEarned: node.rewardsEarned,
        isActive: node.isActive,
        dailyUptimeSeconds: node.dailyUptimeSeconds,
        storageMB: node.storageMB,
        tier: node.tier as RelayTier,
        isOnline: node.isOnline,
        registeredAt: node.registeredAt,
      };
    } catch (error) {
      this.logger.error(`Failed to get node info: ${error}`);
      return null;
    }
  }

  /**
   * Get relay node info (V4: get first node or current node)
   */
  async getRelayNodeInfo(): Promise<RelayNodeInfo | null> {
    const nodeIds = await this.getWalletNodeIds();
    if (nodeIds.length === 0) return null;
    
    const nodeId = this.currentNodeId || nodeIds[0];
    const nodeInfo = await this.getNodeInfoByNodeId(nodeId);
    
    if (!nodeInfo) return null;

    return {
      endpoint: nodeInfo.endpoint,
      stakedAmount: nodeInfo.stakedAmount,
      messagesRelayed: nodeInfo.messagesRelayed,
      rewardsEarned: nodeInfo.rewardsEarned,
      isActive: nodeInfo.isActive,
      tier: nodeInfo.tier,
      totalUptimeSeconds: 0n, // V4 tracks daily uptime only
      lastHeartbeat: 0n, // Would need to track separately
      storageMB: nodeInfo.storageMB,
      dailyUptimeSeconds: nodeInfo.dailyUptimeSeconds,
    };
  }

  /**
   * Get stake required for a tier (V4)
   */
  async getStakeForTier(tier: RelayTier): Promise<bigint> {
    return await this.relayManager.getStakeForTier(tier);
  }

  /**
   * Get minimum stake required (Bronze tier)
   */
  async getMinStake(): Promise<bigint> {
    return await this.getStakeForTier(RelayTier.BRONZE);
  }

  /**
   * Approve MCT tokens for staking
   */
  async approveMCT(amount: bigint): Promise<string> {
    this.logger.info(`Approving ${ethers.formatEther(amount)} MCT for staking...`);
    const tx = await this.mctToken.approve(await this.relayManager.getAddress(), amount);
    await tx.wait();
    this.logger.info(`MCT approved: ${tx.hash}`);
    return tx.hash;
  }

  /**
   * Generate a Node ID from machine info
   */
  generateNodeId(machineId: string, serial: string, walletAddress: string): string {
    const combined = `${machineId}-${serial}-${walletAddress}-${Date.now()}`;
    return ethers.keccak256(ethers.toUtf8Bytes(combined));
  }

  /**
   * Register as relay node (V4 - Node Identity System)
   */
  async registerAsRelay(
    endpoint: string, 
    storageMB: number, 
    tier: RelayTier = RelayTier.BRONZE,
    machineId?: string,
    serial?: string
  ): Promise<{ txHash: string; nodeId: string }> {
    // Check if identity is registered first
    if (!(await this.isIdentityRegistered())) {
      throw new Error('Identity must be registered before registering as relay');
    }

    // Generate node ID and hashes
    const machineIdHash = ethers.keccak256(ethers.toUtf8Bytes(machineId || 'default-machine'));
    const serialHash = ethers.keccak256(ethers.toUtf8Bytes(serial || 'default-serial'));
    const nodeId = this.generateNodeId(machineId || 'default', serial || 'default', this.wallet.address);

    // Check MCT balance and allowance for selected tier
    const stakeRequired = await this.getStakeForTier(tier);
    const balance = await this.getMCTBalance();
    
    if (balance < stakeRequired) {
      throw new Error(`Insufficient MCT balance. Required: ${ethers.formatEther(stakeRequired)} MCT for ${getTierName(tier)} tier, Have: ${ethers.formatEther(balance)} MCT`);
    }

    const allowance = await this.mctToken.allowance(this.wallet.address, await this.relayManager.getAddress());
    if (allowance < stakeRequired) {
      await this.approveMCT(stakeRequired);
    }

    this.logger.info(`Registering node identity: ${endpoint}, ${storageMB}MB storage, ${getTierName(tier)} tier`);
    this.logger.info(`Node ID: ${nodeId}`);
    
    const tx = await this.relayManager.registerNodeIdentity(
      nodeId,
      machineIdHash,
      serialHash,
      endpoint,
      storageMB,
      tier
    );
    await tx.wait();
    
    this.currentNodeId = nodeId;
    this.logger.info(`Node registered: ${tx.hash}`);
    return { txHash: tx.hash, nodeId };
  }

  /**
   * Send heartbeat to update uptime (V4)
   */
  async sendHeartbeat(_storageMB?: number): Promise<string> {
    if (!this.currentNodeId) {
      throw new Error('No node ID set. Register as relay first or set node ID.');
    }
    
    this.logger.debug('Sending heartbeat...');
    const tx = await this.relayManager.heartbeatByNodeId(this.currentNodeId);
    await tx.wait();
    this.logger.debug(`Heartbeat sent: ${tx.hash}`);
    return tx.hash;
  }

  /**
   * Update storage capacity (V4)
   */
  async updateStorage(storageMB: number): Promise<string> {
    if (!this.currentNodeId) {
      throw new Error('No node ID set');
    }
    
    this.logger.info(`Updating storage to: ${storageMB}MB`);
    const tx = await this.relayManager.updateStorageByNodeId(this.currentNodeId, storageMB);
    await tx.wait();
    this.logger.info(`Storage updated: ${tx.hash}`);
    return tx.hash;
  }

  /**
   * Update relay endpoint (V4)
   */
  async updateEndpoint(newEndpoint: string): Promise<string> {
    if (!this.currentNodeId) {
      throw new Error('No node ID set');
    }
    
    this.logger.info(`Updating endpoint to: ${newEndpoint}`);
    const tx = await this.relayManager.updateEndpointByNodeId(this.currentNodeId, newEndpoint);
    await tx.wait();
    this.logger.info(`Endpoint updated: ${tx.hash}`);
    return tx.hash;
  }

  /**
   * Claim fee pool rewards (V4)
   */
  async claimRewards(): Promise<string> {
    if (!this.currentNodeId) {
      throw new Error('No node ID set');
    }
    
    this.logger.info('Claiming fee pool rewards...');
    const tx = await this.relayManager.claimFeeRewardByNodeId(this.currentNodeId);
    await tx.wait();
    this.logger.info(`Rewards claimed: ${tx.hash}`);
    return tx.hash;
  }

  /**
   * Claim minting rewards (V4)
   */
  async claimMintingRewards(): Promise<string> {
    if (!this.currentNodeId) {
      throw new Error('No node ID set');
    }
    
    this.logger.info('Claiming minting rewards...');
    const tx = await this.relayManager.claimMintingRewardsByNodeId(this.currentNodeId);
    await tx.wait();
    this.logger.info(`Minting rewards claimed: ${tx.hash}`);
    return tx.hash;
  }

  /**
   * Change tier (V4)
   */
  async changeTier(newTier: RelayTier): Promise<string> {
    if (!this.currentNodeId) {
      throw new Error('No node ID set');
    }
    
    this.logger.info(`Changing tier to: ${getTierName(newTier)}`);
    const tx = await this.relayManager.changeTier(this.currentNodeId, newTier);
    await tx.wait();
    this.logger.info(`Tier changed: ${tx.hash}`);
    return tx.hash;
  }

  /**
   * Deactivate relay node (V4)
   */
  async deactivateRelay(): Promise<string> {
    if (!this.currentNodeId) {
      throw new Error('No node ID set');
    }
    
    this.logger.info('Deactivating relay node...');
    const tx = await this.relayManager.deactivateNodeIdentity(this.currentNodeId);
    await tx.wait();
    this.logger.info(`Relay deactivated: ${tx.hash}`);
    return tx.hash;
  }

  /**
   * Get active relay nodes (V4)
   */
  async getActiveRelayNodes(): Promise<{ nodeIds: string[]; wallets: string[] }> {
    const result = await this.relayManager.getActiveNodeIds();
    return { nodeIds: result.nodeIds, wallets: result.wallets };
  }

  /**
   * Get total number of registered nodes
   */
  async getTotalNodeIds(): Promise<bigint> {
    return await this.relayManager.getTotalNodeIds();
  }

  /**
   * Check if a node is online
   */
  async isNodeOnline(nodeId: string): Promise<boolean> {
    return await this.relayManager.isNodeOnlineByNodeId(nodeId);
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
   * Print status summary (V4)
   */
  async printStatus(): Promise<void> {
    const address = this.getWalletAddress();
    const ramaBalance = await this.getRAMABalance();
    const mctBalance = await this.getMCTBalance();
    const isRelay = await this.isRelayRegistered();

    this.logger.info('='.repeat(50));
    this.logger.info('Blockchain Status (V4 Node Identity System)');
    this.logger.info('='.repeat(50));
    this.logger.info(`Wallet: ${address}`);
    this.logger.info(`RAMA Balance: ${ethers.formatEther(ramaBalance)} RAMA`);
    this.logger.info(`MCT Balance: ${ethers.formatEther(mctBalance)} MCT`);
    this.logger.info(`Relay Registered: ${isRelay}`);

    if (isRelay) {
      const nodeIds = await this.getWalletNodeIds();
      this.logger.info(`Total Nodes: ${nodeIds.length}`);
      
      for (const nodeId of nodeIds) {
        const info = await this.getNodeInfoByNodeId(nodeId);
        if (info) {
          this.logger.info(`--- Node: ${nodeId.slice(0, 18)}... ---`);
          this.logger.info(`  Tier: ${getTierName(info.tier)}`);
          this.logger.info(`  Staked: ${ethers.formatEther(info.stakedAmount)} MCT`);
          this.logger.info(`  Messages Relayed: ${info.messagesRelayed}`);
          this.logger.info(`  Rewards Earned: ${ethers.formatEther(info.rewardsEarned)} MCT`);
          this.logger.info(`  Online: ${info.isOnline ? 'Yes' : 'No'}`);
          this.logger.info(`  Daily Uptime: ${Number(info.dailyUptimeSeconds) / 3600}h`);
          this.logger.info(`  Endpoint: ${info.endpoint}`);
        }
      }
      
      if (this.currentNodeId) {
        this.logger.info(`Current Node ID: ${this.currentNodeId}`);
      }
    }
    this.logger.info('='.repeat(50));
  }
}
