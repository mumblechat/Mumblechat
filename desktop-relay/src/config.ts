/**
 * MumbleChat Desktop Relay Node - Configuration
 * 
 * Based on MumbleChat Protocol documentation (04_RELAY_AND_REWARDS.md)
 * 
 * Supports TWO modes:
 * 1. SELF_HOSTED: Node operator provides their own IP/domain (advanced users)
 * 2. MANAGED: Node connects to MumbleChat Hub, we provide endpoint (easy mode)
 */

export type NodeMode = 'SELF_HOSTED' | 'MANAGED';

export interface RelayConfig {
  mode: NodeMode;                    // SELF_HOSTED or MANAGED
  
  // Hub settings (for MANAGED mode)
  hub: {
    url: string;                     // wss://hub.mumblechat.io
    reconnectIntervalMs: number;     // Auto-reconnect interval
    feePercent: number;              // Hub takes this % of rewards
  };
  
  // Self-hosted settings (for SELF_HOSTED mode)
  selfHosted: {
    endpoint: string;                // Your public endpoint (ip:port or domain:port)
    port: number;                    // Local listening port
    host: string;                    // Local bind address
  };
  
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
    relayManagerAddress: string;
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
 * V3 Reward Cap Constants - matches smart contract
 * Prevents nodes from earning more than their message entitlement
 */
export const RewardCap = {
  BASE_REWARD_PER_1000_MSG: 0.001,  // 0.001 MCT per 1000 messages
  MESSAGES_PER_REWARD: 1000,         // Messages required per reward unit
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
 * Load configuration from environment variables
 * ALL sensitive data MUST come from .env file - no hardcoded values allowed
 */
function getEnvOrThrow(key: string): string {
  const value = process.env[key];
  if (!value) {
    throw new Error(`Missing required environment variable: ${key}`);
  }
  return value;
}

function getEnvOrDefault(key: string, defaultValue: string): string {
  return process.env[key] || defaultValue;
}

function getEnvNumberOrDefault(key: string, defaultValue: number): number {
  const value = process.env[key];
  return value ? parseInt(value, 10) : defaultValue;
}

/**
 * Build configuration from environment variables
 * Required env vars:
 *   - RPC_URL: Blockchain RPC endpoint
 *   - CHAIN_ID: Network chain ID
 *   - REGISTRY_ADDRESS: MumbleChat Registry contract address
 *   - RELAY_MANAGER_ADDRESS: MumbleChat Relay Manager contract address
 *   - MCT_TOKEN_ADDRESS: MCT Token contract address
 *   - HUB_URL: WebSocket URL for hub connection (managed mode)
 * 
 * Optional env vars:
 *   - NODE_MODE: 'MANAGED' or 'SELF_HOSTED' (default: MANAGED)
 *   - RELAY_PORT: Local relay port (default: 19370)
 *   - RELAY_HOST: Local bind address (default: 0.0.0.0)
 *   - SELF_HOSTED_ENDPOINT: Public endpoint for self-hosted mode
 *   - MAX_CONNECTIONS: Max peer connections (default: 200)
 *   - MAX_STORAGE_GB: Max storage in GB (default: 8)
 *   - MESSAGE_TTL_DAYS: Message retention days (default: 7)
 *   - HEARTBEAT_INTERVAL_MS: Heartbeat interval (default: 300000)
 *   - API_PORT: Local API port (default: 19380)
 *   - API_KEY: API authentication key
 *   - LOG_LEVEL: Logging level (default: info)
 */
export function loadConfig(): RelayConfig {
  return {
    // Mode from env or default to MANAGED
    mode: (getEnvOrDefault('NODE_MODE', 'MANAGED') as NodeMode),
    
    // Hub settings - URL from environment
    hub: {
      url: getEnvOrThrow('HUB_URL'),
      reconnectIntervalMs: getEnvNumberOrDefault('HUB_RECONNECT_MS', 5000),
      feePercent: getEnvNumberOrDefault('HUB_FEE_PERCENT', 10),
    },
    
    // Self-hosted settings from environment
    selfHosted: {
      endpoint: getEnvOrDefault('SELF_HOSTED_ENDPOINT', ''),
      port: getEnvNumberOrDefault('SELF_HOSTED_PORT', 19370),
      host: getEnvOrDefault('SELF_HOSTED_HOST', '0.0.0.0'),
    },
    
    relay: {
      port: getEnvNumberOrDefault('RELAY_PORT', 19370),
      host: getEnvOrDefault('RELAY_HOST', '0.0.0.0'),
      maxConnections: getEnvNumberOrDefault('MAX_CONNECTIONS', 200),
      maxStorageGB: getEnvNumberOrDefault('MAX_STORAGE_GB', 8),
      messageTTLDays: getEnvNumberOrDefault('MESSAGE_TTL_DAYS', 7),
      heartbeatIntervalMs: getEnvNumberOrDefault('HEARTBEAT_INTERVAL_MS', 300000),
    },
    
    // Blockchain config - ALL addresses from environment (required)
    blockchain: {
      rpcUrl: getEnvOrThrow('RPC_URL'),
      chainId: getEnvNumberOrDefault('CHAIN_ID', 1370),
      registryAddress: getEnvOrThrow('REGISTRY_ADDRESS'),
      relayManagerAddress: getEnvOrThrow('RELAY_MANAGER_ADDRESS'),
      mctTokenAddress: getEnvOrThrow('MCT_TOKEN_ADDRESS'),
    },
    
    wallet: {
      privateKeyEnvVar: 'RELAY_PRIVATE_KEY',
      keyStorePath: getEnvOrDefault('KEYSTORE_PATH', './keystore'),
    },
    
    storage: {
      dbPath: getEnvOrDefault('DB_PATH', './data/messages.db'),
      backupPath: getEnvOrDefault('BACKUP_PATH', './data/backup'),
    },
    
    logging: {
      level: getEnvOrDefault('LOG_LEVEL', 'info'),
      file: getEnvOrDefault('LOG_FILE', './logs/relay.log'),
      maxSize: getEnvOrDefault('LOG_MAX_SIZE', '100m'),
      maxFiles: getEnvNumberOrDefault('LOG_MAX_FILES', 5),
    },
    
    api: {
      enabled: getEnvOrDefault('API_ENABLED', 'true') === 'true',
      port: getEnvNumberOrDefault('API_PORT', 19380),
      apiKey: getEnvOrDefault('API_KEY', ''),
    },
  };
}

/**
 * Default configuration - for reference only
 * In production, use loadConfig() which reads from environment
 * @deprecated Use loadConfig() instead
 */
export const defaultConfig: RelayConfig = {
  mode: 'MANAGED',
  hub: {
    url: process.env.HUB_URL || '',
    reconnectIntervalMs: 5000,
    feePercent: 10,
  },
  selfHosted: {
    endpoint: '',
    port: 19370,
    host: '0.0.0.0',
  },
  relay: {
    port: 19370,
    host: '0.0.0.0',
    maxConnections: 200,
    maxStorageGB: 8,
    messageTTLDays: 7,
    heartbeatIntervalMs: 300000,
  },
  blockchain: {
    rpcUrl: process.env.RPC_URL || '',
    chainId: parseInt(process.env.CHAIN_ID || '1370', 10),
    registryAddress: process.env.REGISTRY_ADDRESS || '',
    relayManagerAddress: process.env.RELAY_MANAGER_ADDRESS || '',
    mctTokenAddress: process.env.MCT_TOKEN_ADDRESS || '',
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
