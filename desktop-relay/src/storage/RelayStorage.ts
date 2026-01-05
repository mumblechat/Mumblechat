/**
 * MumbleChat Desktop Relay Node - Storage
 * 
 * Simple JSON-based storage for relay messages
 */

import fs from 'fs';
import path from 'path';
import { getLogger } from '../utils/logger';

const logger = getLogger();

export interface StoredMessage {
  id: string;
  from: string;
  to: string;
  encryptedContent: string;
  timestamp: number;
  expiresAt: number;
  delivered: boolean;
  deliveredAt?: number;
}

export interface RelayStats {
  totalMessages: number;
  deliveredMessages: number;
  expiredMessages: number;
  activeUsers: number;
  startTime: number;
  lastHeartbeat?: number;
}

interface StorageData {
  messages: StoredMessage[];
  stats: RelayStats;
  publicKeys: Record<string, string>;
}

export class RelayStorage {
  private dataPath: string;
  private data: StorageData;
  private saveTimeout: NodeJS.Timeout | null = null;

  constructor(dataDir: string) {
    this.dataPath = path.join(dataDir, 'relay-data.json');
    this.data = this.loadData();
    
    logger.info(`Storage initialized at ${this.dataPath}`);
  }

  private loadData(): StorageData {
    try {
      if (fs.existsSync(this.dataPath)) {
        const content = fs.readFileSync(this.dataPath, 'utf-8');
        return JSON.parse(content);
      }
    } catch (error) {
      logger.error('Failed to load storage data:', error);
    }
    
    // Default data
    return {
      messages: [],
      stats: {
        totalMessages: 0,
        deliveredMessages: 0,
        expiredMessages: 0,
        activeUsers: 0,
        startTime: Date.now(),
      },
      publicKeys: {}
    };
  }

  private scheduleSave(): void {
    if (this.saveTimeout) {
      clearTimeout(this.saveTimeout);
    }
    
    this.saveTimeout = setTimeout(() => {
      this.saveData();
    }, 1000); // Debounce saves
  }

  private saveData(): void {
    try {
      const dir = path.dirname(this.dataPath);
      if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
      }
      
      fs.writeFileSync(this.dataPath, JSON.stringify(this.data, null, 2));
    } catch (error) {
      logger.error('Failed to save storage data:', error);
    }
  }

  /**
   * Store a message for later delivery
   */
  storeMessage(message: Omit<StoredMessage, 'delivered' | 'deliveredAt'>): void {
    const storedMessage: StoredMessage = {
      ...message,
      delivered: false,
    };
    
    this.data.messages.push(storedMessage);
    this.data.stats.totalMessages++;
    this.scheduleSave();
    
    logger.debug(`Stored message ${message.id} for ${message.to}`);
  }

  /**
   * Get pending messages for a recipient
   */
  getPendingMessages(recipient: string): StoredMessage[] {
    const now = Date.now();
    return this.data.messages.filter(
      msg => msg.to.toLowerCase() === recipient.toLowerCase() && 
             !msg.delivered && 
             msg.expiresAt > now
    );
  }

  /**
   * Mark a message as delivered
   */
  markDelivered(messageId: string): void {
    const message = this.data.messages.find(m => m.id === messageId);
    if (message) {
      message.delivered = true;
      message.deliveredAt = Date.now();
      this.data.stats.deliveredMessages++;
      this.scheduleSave();
      
      logger.debug(`Marked message ${messageId} as delivered`);
    }
  }

  /**
   * Clean up expired messages
   */
  cleanupExpired(): number {
    const now = Date.now();
    const before = this.data.messages.length;
    
    this.data.messages = this.data.messages.filter(msg => {
      if (msg.expiresAt < now) {
        this.data.stats.expiredMessages++;
        return false;
      }
      return true;
    });
    
    const cleaned = before - this.data.messages.length;
    if (cleaned > 0) {
      this.scheduleSave();
      logger.info(`Cleaned up ${cleaned} expired messages`);
    }
    
    return cleaned;
  }

  /**
   * Store a public key for a user
   */
  storePublicKey(address: string, publicKey: string): void {
    this.data.publicKeys[address.toLowerCase()] = publicKey;
    this.scheduleSave();
  }

  /**
   * Get a user's public key
   */
  getPublicKey(address: string): string | null {
    return this.data.publicKeys[address.toLowerCase()] || null;
  }

  /**
   * Get relay statistics
   */
  getStats(): RelayStats {
    return { ...this.data.stats };
  }

  /**
   * Update relay statistics
   */
  updateStats(updates: Partial<RelayStats>): void {
    this.data.stats = { ...this.data.stats, ...updates };
    this.scheduleSave();
  }

  /**
   * Get message count by recipient
   */
  getMessageCountByRecipient(recipient: string): number {
    return this.data.messages.filter(
      m => m.to.toLowerCase() === recipient.toLowerCase()
    ).length;
  }

  /**
   * Get all unique users
   */
  getUniqueUsers(): string[] {
    const users = new Set<string>();
    this.data.messages.forEach(msg => {
      users.add(msg.from.toLowerCase());
      users.add(msg.to.toLowerCase());
    });
    return Array.from(users);
  }

  /**
   * Close storage (save pending changes)
   */
  close(): void {
    if (this.saveTimeout) {
      clearTimeout(this.saveTimeout);
    }
    this.saveData();
    logger.info('Storage closed');
  }
}
