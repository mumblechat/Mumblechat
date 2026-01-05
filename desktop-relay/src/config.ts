/**
 * MumbleChat Desktop Relay Node - Configuration
 * 
 * Based on MumbleChat Protocol documentation (04_RELAY_AND_REWARDS.md)
 */

export interface RelayConfig {
  relay: {
    port: number;
    host: string;
    maxConnections: number;
    maxStorageGB: number;
    messageTTLDays: number;
    heartbeatIntervalMs: number;
  };
  blockchain: {
    rpcUrl: string;
    chainId: number;
    registryAddress: string;
    mctTokenAddress: string;
  };
  wallet: {
    privateKeyEnvVar: string;
    keyStorePath: string;
  };
  storage: {
    dbPath: string;
    backupPath: string;
  };
  logging: {
    level: string;
    file: string;
    maxSize: string;
    maxFiles: number;
  };
  api: {
    enabled: boolean;
    port: number;
    apiKey: string;
  };
}

/**
 * Storage Tier Thresholds (GB Scale) - matches smart contract
 */
export const StorageTiers = {
  BRONZE_GB: 1,      // 1 GB
  SILVER_GB: 2,      // 2 GB
  GOLD_GB: 4,        // 4 GB
  PLATINUM_GB: 8,    // 8 GB+
} as const;

/**
 * Uptime Requirements (hours per day) - matches smart contract
 */
export const UptimeTiers = {
  BRONZE_HOURS: 4,    // 4+ hours/day
  SILVER_HOURS: 8,    // 8+ hours/day
  GOLD_HOURS: 12,     // 12+ hours/day
  PLATINUM_HOURS: 16, // 16+ hours/day
} as const;

/**
 * Daily Pool Share Percentages - matches smart contract
 */
export const PoolShare = {
  BRONZE_PERCENT: 10,   // 10%
  SILVER_PERCENT: 20,   // 20%
  GOLD_PERCENT: 30,     // 30%
  PLATINUM_PERCENT: 40, // 40%
} as const;

/**
 * Fee Pool Bonus Multipliers (100 = 1x) - matches smart contract
 */
export const FeeBonus = {
  BRONZE_MULTIPLIER: 100,   // 1.0x
  SILVER_MULTIPLIER: 150,   // 1.5x
  GOLD_MULTIPLIER: 200,     // 2.0x
  PLATINUM_MULTIPLIER: 300, // 3.0x
} as const;

/**
 * Network Constants
 */
export const NetworkConfig = {
  P2P_PORT: 19370,
  LAN_DISCOVERY_PORT: 19371,
  DHT_K_BUCKET_SIZE: 20,
  DHT_ALPHA: 3,
  DHT_KEY_SIZE: 160,
  MAX_PEERS: 200,
  PING_INTERVAL_MS: 30_000,
  PEER_TIMEOUT_MS: 120_000,
  MESSAGE_TTL_HOPS: 10,
  HEARTBEAT_INTERVAL_MS: 5 * 60 * 1000, // 5 minutes
  CLEANUP_INTERVAL_MS: 60 * 60 * 1000,  // 1 hour
} as const;

/**
 * Protocol Message Types
 */
export enum MessageType {
  HANDSHAKE = 0x01,
  HANDSHAKE_ACK = 0x02,
  PING = 0x03,
  PONG = 0x04,
  MESSAGE = 0x10,
  MESSAGE_ACK = 0x11,
  RELAY_REQUEST = 0x20,
  RELAY_RESPONSE = 0x21,
  PEER_EXCHANGE = 0x30,
  DHT_FIND_NODE = 0x40,
  DHT_FIND_NODE_RESPONSE = 0x41,
  DHT_STORE = 0x42,
  DHT_STORE_RESPONSE = 0x43,
  SYNC_REQUEST = 0x50,
  SYNC_RESPONSE = 0x51,
}

/**
 * Relay Tiers
 */
export enum RelayTier {
  BRONZE = 0,
  SILVER = 1,
  GOLD = 2,
  PLATINUM = 3,
}

/**
 * Get tier from storage and uptime
 */
export function calculateTier(storageGB: number, uptimeHours: number): RelayTier {
  if (storageGB >= StorageTiers.PLATINUM_GB && uptimeHours >= UptimeTiers.PLATINUM_HOURS) {
    return RelayTier.PLATINUM;
  }
  if (storageGB >= StorageTiers.GOLD_GB && uptimeHours >= UptimeTiers.GOLD_HOURS) {
    return RelayTier.GOLD;
  }
  if (storageGB >= StorageTiers.SILVER_GB && uptimeHours >= UptimeTiers.SILVER_HOURS) {
    return RelayTier.SILVER;
  }
  return RelayTier.BRONZE;
}

/**
 * Get tier name
 */
export function getTierName(tier: RelayTier): string {
  switch (tier) {
    case RelayTier.PLATINUM: return '💎 Platinum';
    case RelayTier.GOLD: return '🥇 Gold';
    case RelayTier.SILVER: return '🥈 Silver';
    case RelayTier.BRONZE: return '🥉 Bronze';
  }
}

/**
 * Get fee multiplier for tier (in basis points)
 */
export function getTierMultiplier(tier: RelayTier): number {
  switch (tier) {
    case RelayTier.PLATINUM: return FeeBonus.PLATINUM_MULTIPLIER;
    case RelayTier.GOLD: return FeeBonus.GOLD_MULTIPLIER;
    case RelayTier.SILVER: return FeeBonus.SILVER_MULTIPLIER;
    case RelayTier.BRONZE: return FeeBonus.BRONZE_MULTIPLIER;
  }
}

/**
 * Default configuration
 */
export const defaultConfig: RelayConfig = {
  relay: {
    port: 19370,
    host: '0.0.0.0',
    maxConnections: 200,
    maxStorageGB: 8,
    messageTTLDays: 7,
    heartbeatIntervalMs: 300000,
  },
  blockchain: {
    rpcUrl: 'https://blockchain.ramestta.com',
    chainId: 1370,
    registryAddress: '0x4f8D4955F370881B05b68D2344345E749d8632e3',
    mctTokenAddress: '0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE',
  },
  wallet: {
    privateKeyEnvVar: 'RELAY_PRIVATE_KEY',
    keyStorePath: './keystore',
  },
  storage: {
    dbPath: './data/messages.db',
    backupPath: './data/backup',
  },
  logging: {
    level: 'info',
    file: './logs/relay.log',
    maxSize: '100m',
    maxFiles: 5,
  },
  api: {
    enabled: true,
    port: 19380,
    apiKey: '',
  },
};
