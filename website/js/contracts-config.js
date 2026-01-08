/**
 * MumbleChat Configuration
 * Loads contract addresses from .env or falls back to defaults
 */

// Load environment variables (for build tools like Vite)
// For browser, we'll load from window.ENV if available
const ENV = typeof window !== 'undefined' && window.ENV ? window.ENV : {
    RPC_URL: 'https://blockchain.ramestta.com',
    MCT_TOKEN_ADDRESS: '0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE',
    REGISTRY_ADDRESS: '0x4f8D4955F370881B05b68D2344345E749d8632e3',
    NODE_MANAGER_ADDRESS: '0x4f8D4955F370881B05b68D2344345E749d8632e3',
    HUB_API_URL: 'https://hub.mumblechat.com/api/stats',
    HUB_WS_URL: 'wss://hub.mumblechat.com',
    CHAIN_ID: 1729
};

// Export configuration
export const CONTRACTS = {
    RPC_URL: ENV.RPC_URL,
    MCT_TOKEN: ENV.MCT_TOKEN_ADDRESS,
    REGISTRY: ENV.REGISTRY_ADDRESS,
    NODE_MANAGER: ENV.NODE_MANAGER_ADDRESS,
    HUB_API_URL: ENV.HUB_API_URL,
    HUB_WS_URL: ENV.HUB_WS_URL,
    CHAIN_ID: ENV.CHAIN_ID
};

// Function selectors for contract calls
export const SELECTORS = {
    // MCT Token
    feePool: '0xae2e933b',
    
    // Registry
    totalRelayNodes: '0xc4de1ef3',
    activeRelayNodes: '0x73eec9d2',
    relayNodes: '0xad23e18f',
    
    // Node Manager
    getTodayPoolInfo: '0xc8aff12f',
    getNodeInfo: '0x' + '1234', // Update with actual selector
    heartbeat: '0x' + '5678' // Update with actual selector
};

console.log('📋 MumbleChat Config Loaded:', CONTRACTS);
