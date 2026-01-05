/**
 * MumbleChat Desktop Relay Node - Relay Server
 * 
 * Main relay node server that ties together all components
 */

import { EventEmitter } from 'events';
import { ethers } from 'ethers';
import { getLogger, initLogger } from './utils/logger';
import { RelayConfig, defaultConfig, NetworkConfig, getTierName, calculateTier, RelayTier } from './config';
import { P2PServer, IncomingMessage, RelayRequest } from './network/P2PServer';
import { RelayStorage } from './storage/RelayStorage';
import { BlockchainService } from './blockchain/BlockchainService';
import { computeKeyHash } from './utils/crypto';
import fs from 'fs';
import path from 'path';

export interface RelayStats {
  uptime: number;
  peersConnected: number;
  messagesRelayed: number;
  messagesDelivered: number;
  storageUsedMB: number;
  storageMaxMB: number;
  tier: RelayTier;
  rewardsEarned: string;
}

export class RelayServer extends EventEmitter {
  private config: RelayConfig;
  private p2pServer: P2PServer | null = null;
  private storage: RelayStorage | null = null;
  private blockchain: BlockchainService | null = null;
  private logger = getLogger();
  
  private startTime: number = 0;
  private heartbeatInterval: NodeJS.Timeout | null = null;
  private cleanupInterval: NodeJS.Timeout | null = null;
  private deliveryInterval: NodeJS.Timeout | null = null;
  private isRunning: boolean = false;
  private privateKey: string = '';

  constructor(config: Partial<RelayConfig> = {}) {
    super();
    this.config = { ...defaultConfig, ...config };
  }

  /**
   * Initialize and start the relay server
   */
  async start(privateKey: string): Promise<void> {
    this.privateKey = privateKey;
    
    // Initialize logger
    initLogger(this.config.logging);
    this.logger = getLogger();

    this.logger.info('='.repeat(60));
    this.logger.info('   MumbleChat Desktop Relay Node');
    this.logger.info('='.repeat(60));
    this.logger.info('');

    try {
      // Initialize blockchain service
      this.logger.info('Connecting to Ramestta blockchain...');
      this.blockchain = new BlockchainService(
        this.config.blockchain.rpcUrl,
        this.config.blockchain.registryAddress,
        this.config.blockchain.mctTokenAddress,
        privateKey
      );
      await this.blockchain.printStatus();

      // Initialize storage
      this.logger.info('Initializing message storage...');
      const storageDir = path.dirname(this.config.storage.dbPath);
      this.storage = new RelayStorage(storageDir);

      // Initialize P2P server
      this.logger.info('Starting P2P server...');
      this.p2pServer = new P2PServer(
        this.config.relay.host,
        this.config.relay.port,
        this.blockchain.getWalletAddress(),
        this.config.relay.maxConnections
      );

      // Set up event handlers
      this.setupEventHandlers();

      // Start P2P server
      await this.p2pServer.start();

      // Start background jobs
      this.startHeartbeat();
      this.startCleanupJob();
      this.startDeliveryJob();

      this.startTime = Date.now();
      this.isRunning = true;

      this.logger.info('');
      this.logger.info('✅ Relay node is now running!');
      this.logger.info('');
      this.logger.info(`   TCP Port: ${this.config.relay.port}`);
      this.logger.info(`   WebSocket Port: ${this.config.relay.port + 1}`);
      this.logger.info(`   Max Storage: ${this.config.relay.maxStorageGB} GB`);
      this.logger.info('');
      this.logger.info('Press Ctrl+C to stop');
      this.logger.info('');

      // Emit started event
      this.emit('started');

    } catch (error) {
      this.logger.error('Failed to start relay server:', error);
      throw error;
    }
  }

  /**
   * Set up event handlers
   */
  private setupEventHandlers(): void {
    if (!this.p2pServer || !this.storage) return;

    // Handle incoming relay requests
    this.p2pServer.on('relayRequest', (request: RelayRequest) => {
      this.handleRelayRequest(request);
    });

    // Handle incoming messages (for direct routing)
    this.p2pServer.on('message', (message: IncomingMessage) => {
      this.handleMessage(message);
    });

    // Handle sync requests
    this.p2pServer.on('syncRequest', ({ peer, lastSyncTime }) => {
      this.handleSyncRequest(peer, lastSyncTime);
    });

    // Handle peer connections
    this.p2pServer.on('peerConnected', (peer) => {
      this.logger.info(`Peer connected: ${peer.walletAddress}`);
      // Check if we have pending messages for this peer
      this.deliverPendingMessages(peer.walletAddress);
    });
  }

  /**
   * Handle relay request (store message for offline recipient)
   */
  private async handleRelayRequest(request: RelayRequest): Promise<void> {
    if (!this.storage) return;

    const ttlMs = (request.ttlDays || this.config.relay.messageTTLDays) * 24 * 60 * 60 * 1000;
    
    this.storage.storeMessage({
      id: request.messageId,
      from: request.senderKeyHash,
      to: request.recipientKeyHash,
      encryptedContent: request.encryptedBlob.toString('base64'),
      timestamp: Date.now(),
      expiresAt: Date.now() + ttlMs,
    });

    this.logger.debug(`Stored message ${request.messageId} for ${request.recipientKeyHash}`);
    this.emit('messageRelayed', request.messageId);
  }

  /**
   * Handle incoming message (route to recipient if online)
   */
  private async handleMessage(message: IncomingMessage): Promise<void> {
    if (!this.p2pServer) return;

    // Try to deliver directly
    const delivered = this.p2pServer.sendToAddress(
      message.recipientAddress,
      0x10, // MESSAGE type
      Buffer.from(JSON.stringify({
        messageId: message.messageId,
        senderAddress: message.senderAddress,
        encryptedBlob: message.encryptedBlob.toString('base64'),
        timestamp: message.timestamp,
        signature: message.signature,
      }))
    );

    if (!delivered && this.storage) {
      // Store for later delivery
      const recipientKeyHash = computeKeyHash(message.recipientAddress);
      const senderKeyHash = computeKeyHash(message.senderAddress);
      const ttlMs = this.config.relay.messageTTLDays * 24 * 60 * 60 * 1000;

      this.storage.storeMessage({
        id: message.messageId,
        from: senderKeyHash,
        to: recipientKeyHash,
        encryptedContent: message.encryptedBlob.toString('base64'),
        timestamp: message.timestamp,
        expiresAt: Date.now() + ttlMs,
      });
    }
  }

  /**
   * Handle sync request (send pending messages)
   */
  private async handleSyncRequest(peer: any, lastSyncTime: number): Promise<void> {
    if (!this.storage || !this.p2pServer) return;

    const recipientKeyHash = computeKeyHash(peer.walletAddress);
    const messages = this.storage.getPendingMessages(recipientKeyHash);

    if (messages.length > 0) {
      this.logger.info(`Syncing ${messages.length} messages to ${peer.walletAddress}`);

      for (const msg of messages) {
        this.p2pServer.sendMessage(peer, 0x51, Buffer.from(JSON.stringify({
          messageId: msg.id,
          senderKeyHash: msg.from,
          encryptedBlob: msg.encryptedContent,
          timestamp: msg.timestamp,
        })));

        this.storage.markDelivered(msg.id);
      }
    }
  }

  /**
   * Deliver pending messages to a newly connected peer
   */
  private async deliverPendingMessages(walletAddress: string): Promise<void> {
    if (!this.storage || !this.p2pServer) return;

    const recipientKeyHash = computeKeyHash(walletAddress);
    const messages = this.storage.getPendingMessages(recipientKeyHash);

    if (messages.length > 0) {
      this.logger.info(`Delivering ${messages.length} pending messages to ${walletAddress}`);

      for (const msg of messages) {
        const delivered = this.p2pServer.sendToAddress(
          walletAddress,
          0x10,
          Buffer.from(JSON.stringify({
            messageId: msg.id,
            senderKeyHash: msg.from,
            encryptedBlob: msg.encryptedContent,
            timestamp: msg.timestamp,
          }))
        );

        if (delivered) {
          this.storage.markDelivered(msg.id);
        }
      }
    }
  }

  /**
   * Start heartbeat to blockchain
   */
  private startHeartbeat(): void {
    this.heartbeatInterval = setInterval(async () => {
      try {
        if (this.blockchain && this.storage) {
          const stats = this.storage.getStats();
          await this.blockchain.sendHeartbeat(stats.totalMessages);
        }
      } catch (error) {
        this.logger.error('Heartbeat failed:', error);
      }
    }, this.config.relay.heartbeatIntervalMs);
  }

  /**
   * Start cleanup job
   */
  private startCleanupJob(): void {
    this.cleanupInterval = setInterval(() => {
      if (this.storage) {
        const expired = this.storage.cleanupExpired();
        if (expired > 0) {
          this.logger.info(`Cleanup: ${expired} expired messages removed`);
        }
      }
    }, NetworkConfig.CLEANUP_INTERVAL_MS);
  }

  /**
   * Start delivery job (periodic check for online peers)
   */
  private startDeliveryJob(): void {
    this.deliveryInterval = setInterval(() => {
      if (this.p2pServer) {
        const onlinePeers = this.p2pServer.getOnlinePeers();
        for (const peer of onlinePeers) {
          this.deliverPendingMessages(peer);
        }
      }
    }, 30000); // Check every 30 seconds
  }

  /**
   * Get relay statistics
   */
  getStats(): RelayStats {
    const uptimeSeconds = Math.floor((Date.now() - this.startTime) / 1000);
    const uptimeHours = uptimeSeconds / 3600;
    const storageGB = this.config.relay.maxStorageGB;
    const tier = calculateTier(storageGB, uptimeHours);
    const storageStats = this.storage?.getStats();

    return {
      uptime: uptimeSeconds,
      peersConnected: this.p2pServer?.getPeerCount() || 0,
      messagesRelayed: storageStats?.totalMessages || 0,
      messagesDelivered: storageStats?.deliveredMessages || 0,
      storageUsedMB: 0, // Simplified
      storageMaxMB: this.config.relay.maxStorageGB * 1024,
      tier,
      rewardsEarned: '0',
    };
  }

  /**
   * Print status to console
   */
  async printStatus(): Promise<void> {
    const stats = this.getStats();
    const hours = Math.floor(stats.uptime / 3600);
    const minutes = Math.floor((stats.uptime % 3600) / 60);
    const seconds = stats.uptime % 60;

    this.logger.info('='.repeat(50));
    this.logger.info('Relay Node Status');
    this.logger.info('='.repeat(50));
    this.logger.info(`Uptime: ${hours}h ${minutes}m ${seconds}s`);
    this.logger.info(`Tier: ${getTierName(stats.tier)}`);
    this.logger.info(`Peers Connected: ${stats.peersConnected}`);
    this.logger.info(`Messages Relayed: ${stats.messagesRelayed}`);
    this.logger.info(`Messages Delivered: ${stats.messagesDelivered}`);
    this.logger.info(`Storage: ${stats.storageUsedMB}MB / ${stats.storageMaxMB}MB`);
    
    if (this.blockchain) {
      const info = await this.blockchain.getRelayNodeInfo();
      if (info) {
        this.logger.info(`On-chain Rewards: ${ethers.formatEther(info.rewardsEarned)} MCT`);
      }
    }
    this.logger.info('='.repeat(50));
  }

  /**
   * Stop the relay server
   */
  async stop(): Promise<void> {
    this.logger.info('Stopping relay server...');

    // Clear intervals
    if (this.heartbeatInterval) clearInterval(this.heartbeatInterval);
    if (this.cleanupInterval) clearInterval(this.cleanupInterval);
    if (this.deliveryInterval) clearInterval(this.deliveryInterval);

    // Stop P2P server
    if (this.p2pServer) {
      await this.p2pServer.stop();
    }

    // Close storage
    if (this.storage) {
      this.storage.close();
    }

    this.isRunning = false;
    this.logger.info('Relay server stopped');
    this.emit('stopped');
  }

  /**
   * Check if server is running
   */
  isActive(): boolean {
    return this.isRunning;
  }
}

/**
 * Load configuration from file
 */
export function loadConfig(configPath: string): RelayConfig {
  if (!fs.existsSync(configPath)) {
    return defaultConfig;
  }

  const content = fs.readFileSync(configPath, 'utf-8');
  const fileConfig = JSON.parse(content);
  return { ...defaultConfig, ...fileConfig };
}

/**
 * Save configuration to file
 */
export function saveConfig(config: RelayConfig, configPath: string): void {
  const dir = path.dirname(configPath);
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }
  fs.writeFileSync(configPath, JSON.stringify(config, null, 2));
}
