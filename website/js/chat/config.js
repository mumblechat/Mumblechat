/**
 * MumbleChat Configuration
 * Network settings, contract addresses, and constants
 */

export const RAMESTTA_CONFIG = {
    chainId: '0x55A',
    chainIdDecimal: 1370,
    chainName: 'Ramestta Mainnet',
    nativeCurrency: { name: 'RAMA', symbol: 'RAMA', decimals: 18 },
    rpcUrls: ['https://blockchain.ramestta.com'],
    blockExplorerUrls: ['https://ramascan.com']
};

export const CONTRACTS = {
    registry: '0x4f8D4955F370881B05b68D2344345E749d8632e3',
    relayManager: '0xF78F840eF0e321512b09e98C76eA0229Affc4b73',
    mctToken: '0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE'
};

export const REGISTRY_ABI = [
    {
        "name": "register",
        "type": "function",
        "inputs": [
            { "name": "publicKeyX", "type": "bytes32" },
            { "name": "displayName", "type": "string" }
        ],
        "outputs": [],
        "stateMutability": "nonpayable"
    },
    {
        "name": "updateDisplayName",
        "type": "function",
        "inputs": [{ "name": "newDisplayName", "type": "string" }],
        "outputs": [],
        "stateMutability": "nonpayable"
    },
    {
        "name": "identities",
        "type": "function",
        "inputs": [{ "name": "", "type": "address" }],
        "outputs": [
            { "name": "publicKeyX", "type": "bytes32" },
            { "name": "publicKeyY", "type": "bytes32" },
            { "name": "registeredAt", "type": "uint256" },
            { "name": "lastUpdated", "type": "uint256" },
            { "name": "isActive", "type": "bool" },
            { "name": "displayName", "type": "string" },
            { "name": "keyVersion", "type": "uint8" }
        ],
        "stateMutability": "view"
    },
    {
        "name": "updateIdentity",
        "type": "function",
        "inputs": [
            { "name": "newPubKeyX", "type": "bytes32" },
            { "name": "newPubKeyY", "type": "bytes32" },
            { "name": "keyVersion", "type": "uint8" }
        ],
        "outputs": [],
        "stateMutability": "nonpayable"
    }
];

// Hub Configuration for V4 Relay Network
export const HUB_CONFIG = {
    apiUrl: 'https://hub.mumblechat.com',
    wsUrl: 'wss://hub.mumblechat.com',
    endpoints: []  // Dynamically fetched
};

export const RELAY_DEFAULTS = {
    primary: 'wss://hub.mumblechat.com/user/connect',
    fallback: [
        'wss://hub.mumblechat.com/user/connect'
    ],
    desktop: 'ws://localhost:19371',
    mobile: 'ws://localhost:8444',
    default: 'wss://hub.mumblechat.com/user/connect'
};

// Get best relay endpoint with load balancing - FAST with timeout
export async function getBestRelayEndpoint() {
    try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 3000);
        
        // Use /api/endpoints which only returns REAL connected nodes (not blockchain-only)
        const response = await fetch(`${HUB_CONFIG.apiUrl}/api/endpoints`, {
            signal: controller.signal
        });
        clearTimeout(timeoutId);
        
        const data = await response.json();
        
        if (data.endpoints && data.endpoints.length > 0) {
            // Load balance - find node with least connections
            const bestNode = data.endpoints.reduce((best, node) => 
                (node.users || 0) < (best.users || 0) ? node : best
            );
            console.log('[Config] Selected endpoint:', bestNode.tunnelId, 'users:', bestNode.users);
            return bestNode.endpoint;
        }
    } catch (e) {
        console.warn('Could not fetch relay endpoints, using fallback');
    }
    
    // Fallback: use auto-connect endpoint (hub picks best node)
    return `${HUB_CONFIG.wsUrl}/user/connect`;
}

export const MESSAGE_TYPES = {
    HANDSHAKE: 0x01,
    HANDSHAKE_ACK: 0x02,
    PING: 0x03,
    PONG: 0x04,
    DISCONNECT: 0x05,
    FIND_NODE: 0x10,
    FIND_NODE_RESP: 0x11,
    ANNOUNCE: 0x12,
    ANNOUNCE_ACK: 0x13,
    DIRECT_MSG: 0x20,
    GROUP_MSG: 0x21,
    MSG_ACK: 0x22,
    MSG_NACK: 0x23,
    MSG_READ: 0x24,
    TYPING: 0x25,
    RELAY_REQUEST: 0x30,
    RELAY_ACCEPT: 0x31,
    RELAY_REJECT: 0x32,
    RELAYED_MSG: 0x33,
    STORE_MSG: 0x40,
    RETRIEVE_MSG: 0x41,
    DELETE_MSG: 0x42
};

export const PROTOCOL_VERSION = 4;
export const MAX_MESSAGE_SIZE = 1024 * 64;
export const DEFAULT_TTL = 86400;
export const MESSAGE_EXPIRY = 7 * 24 * 60 * 60 * 1000;

// Storage keys for localStorage
export const STORAGE_KEYS = {
    USER: 'mumblechat_user',
    CONTACTS: 'mumblechat_contacts',
    MESSAGES: 'mumblechat_messages',
    GROUPS: 'mumblechat_groups',
    BLOCKED: 'mumblechat_blocked',
    SETTINGS: 'mumblechat_settings',
    RELAY_URL: 'mumblechat_relay_url',
    CRYPTO_KEYS: 'mumblechat_crypto_keys',
    PUBLIC_KEYS: 'mumblechat_public_keys'
};
