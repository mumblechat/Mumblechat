/**
 * MumbleChat Desktop Relay Node - Main Entry Point
 * 
 * Programmatic entry point for embedding or importing
 */

export { RelayServer, loadConfig, saveConfig } from './RelayServer';
export { BlockchainService } from './blockchain/BlockchainService';
export { RelayStorage } from './storage/RelayStorage';
export { P2PServer } from './network/P2PServer';
export * from './config';
export * from './utils/crypto';
export { initLogger, getLogger } from './utils/logger';

// Default export for programmatic usage
import { RelayServer, loadConfig } from './RelayServer';

/**
 * Quick start function for programmatic usage
 * 
 * @example
 * ```typescript
 * import startRelay from 'mumblechat-relay';
 * 
 * startRelay({
 *   privateKey: process.env.RELAY_PRIVATE_KEY,
 *   port: 19370,
 *   maxStorageGB: 8,
 * });
 * ```
 */
export default async function startRelay(options: {
  privateKey: string;
  configPath?: string;
  port?: number;
  maxStorageGB?: number;
}): Promise<RelayServer> {
  const config = loadConfig(options.configPath || './config.json');
  
  if (options.port) {
    config.relay.port = options.port;
  }
  if (options.maxStorageGB) {
    config.relay.maxStorageGB = options.maxStorageGB;
  }

  const server = new RelayServer(config);
  await server.start(options.privateKey);
  
  return server;
}
