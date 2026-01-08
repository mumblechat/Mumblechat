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
    relayManager: '0xF78F840eF0e321512b09e98C76eA0229Affc4b73',  // V4 Node Management
    mctToken: '0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE' // MCT Token contract address
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
    endpoints: [
        'wss://hub.mumblechat.com/node/4df2770c',
        'wss://hub.mumblechat.com/node/41cd6731',
        'wss://hub.mumblechat.com/node/b661fbcb'
    ]
};

export const RELAY_DEFAULTS = {
    primary: 'wss://hub.mumblechat.com/node/4df2770c',
    fallback: [
        'wss://hub.mumblechat.com/node/41cd6731',
        'wss://hub.mumblechat.com/node/b661fbcb'
    ],
    desktop: 'ws://localhost:19371',
    mobile: 'ws://localhost:8444',
    default: 'wss://hub.mumblechat.com/node/4df2770c'
};

// Get best relay endpoint with load balancing
export async function getBestRelayEndpoint() {
    try {
        const response = await fetch(`${HUB_CONFIG.apiUrl}/api/stats`);
        const data = await response.json();
        
        if (data.nodes && data.nodes.length > 0) {
            // Find node with least connections for load balancing
            const activeNodes = data.nodes.filter(n => n.status === 'connected');
            if (activeNodes.length > 0) {
                const bestNode = activeNodes.reduce((best, node) => 
                    (node.connectedUsers || 0) < (best.connectedUsers || 0) ? node : best
                );
                return `wss://hub.mumblechat.com/node/${bestNode.nodeId}`;
            }
        }
    } catch (e) {
        console.warn('Could not fetch relay endpoints from hub, using default');
    }
    
    return RELAY_DEFAULTS.default;
}

export const MESSAGE_TYPES = {
    // Handshake & Connection (0x00-0x0F)
    HANDSHAKE: 0x01,
    HANDSHAKE_ACK: 0x02,
    PING: 0x03,
    PONG: 0x04,
    DISCONNECT: 0x05,
    
    // DHT Operations (0x10-0x1F)
    FIND_NODE: 0x10,
    FIND_NODE_RESP: 0x11,
    ANNOUNCE: 0x12,
    ANNOUNCE_ACK: 0x13,
    
    // Direct Messaging (0x20-0x2F)
    DIRECT_MSG: 0x20,
    GROUP_MSG: 0x21,
    MSG_ACK: 0x22,
    MSG_NACK: 0x23,
    MSG_READ: 0x24,
    TYPING: 0x25,
    
    // Relay Operations (0x30-0x3F)
    RELAY_REQUEST: 0x30,
    RELAY_ACCEPT: 0x31,
    RELAY_REJECT: 0x32,
    RELAY_DATA: 0x33,
    RELAY_CLOSE: 0x34,
    
    // Store & Forward (0x40-0x4F)
    STORE_MSG: 0x40,
    RETRIEVE_MSG: 0x41,
    RETRIEVE_RESP: 0x42,
    DELETE_MSG: 0x43,
    
    // File Transfer (0x50-0x5F)
    FILE_OFFER: 0x50,
    FILE_ACCEPT: 0x51,
    FILE_REJECT: 0x52,
    FILE_CHUNK: 0x53,
    FILE_COMPLETE: 0x54
};

export const RELAY_TIERS = {
    BRONZE: { name: 'Bronze', minStake: 100, rewardMultiplier: 1.0, storage: '1GB', uptimeHours: 4 },
    SILVER: { name: 'Silver', minStake: 100, rewardMultiplier: 1.5, storage: '2GB', uptimeHours: 8 },
    GOLD: { name: 'Gold', minStake: 100, rewardMultiplier: 2.0, storage: '4GB', uptimeHours: 12 },
    PLATINUM: { name: 'Platinum', minStake: 100, rewardMultiplier: 3.0, storage: '8GB', uptimeHours: 16 }
};

export const STORAGE_KEYS = {
    USER: 'mumblechat_user',
    CONTACTS: 'mumblechat_contacts',
    MESSAGES: 'mumblechat_messages',
    SETTINGS: 'mumblechat_settings',
    RELAY_URL: 'mumblechat_relay_url',
    GROUPS: 'mumblechat_groups',
    BLOCKED: 'mumblechat_blocked'
};
