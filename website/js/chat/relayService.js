/**
 * MumbleChat Relay Service
 * Fetches online relay nodes from Hub API and blockchain
 */

import { CONTRACTS, RAMESTTA_CONFIG, HUB_CONFIG, RELAY_DEFAULTS } from './config.js';

const RPC_URL = RAMESTTA_CONFIG.rpcUrls[0];
const REGISTRY = CONTRACTS.registry;

// Tier names
const TIER_NAMES = ['Bronze', 'Silver', 'Gold', 'Platinum'];

// Cache for relay nodes
let nodesCache = null;
let cacheTime = 0;
const CACHE_TTL = 30000; // 30 seconds

/**
 * Fetch relay nodes from Hub API
 */
async function fetchNodesFromHub() {
    try {
        const response = await fetch(`${HUB_CONFIG.apiUrl}/api/stats`, {
            method: 'GET',
            headers: { 'Accept': 'application/json' }
        });
        
        if (!response.ok) {
            throw new Error('Hub API not available');
        }
        
        const data = await response.json();
        
        if (data.nodes && data.nodes.length > 0) {
            return data.nodes.map(node => ({
                address: node.walletAddress || '0x' + node.tunnelId,
                endpoint: `wss://${node.endpoint}`,
                wsUrl: `wss://${node.endpoint}`,
                tunnelId: node.tunnelId,
                isOnline: true,
                nodeType: 'Hub Relay',
                tier: 0,
                tierName: 'Hub',
                staked: 0,
                storageMB: 4096,
                connectedUsers: node.connectedUsers || 0,
                messagesRelayed: node.messagesRelayed || 0,
                lastHeartbeat: new Date(node.lastHeartbeat).getTime() / 1000,
                source: 'hub'
            }));
        }
        return [];
    } catch (error) {
        console.warn('Could not fetch nodes from hub:', error.message);
        return [];
    }
}

/**
 * Fetch relay nodes from blockchain (legacy support)
 */
async function fetchNodesFromBlockchain() {
    try {
        // Function selectors for Relay Manager contract
        const RELAY_MANAGER = CONTRACTS.relayManager;
        
        // Get active endpoints
        const response = await fetch(RPC_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                jsonrpc: '2.0',
                id: 1,
                method: 'eth_call',
                params: [{
                    to: RELAY_MANAGER,
                    data: '0xc05c1049' // getActiveEndpoints()
                }, 'latest']
            })
        });
        
        const result = await response.json();
        
        if (result.result && result.result.length > 66) {
            // Parse endpoints array
            const data = result.result.slice(2);
            // ... parsing logic
            return [];
        }
        return [];
    } catch (error) {
        console.warn('Could not fetch nodes from blockchain:', error.message);
        return [];
    }
}

/**
 * Fetch all online relay nodes
 */
export async function fetchOnlineRelayNodes() {
    // Check cache
    if (nodesCache && (Date.now() - cacheTime) < CACHE_TTL) {
        return nodesCache;
    }
    
    const nodes = { desktop: [], mobile: [], hub: [], all: [] };
    
    try {
        // Fetch from Hub API (primary)
        const hubNodes = await fetchNodesFromHub();
        
        if (hubNodes.length > 0) {
            nodes.hub = hubNodes;
            nodes.all = hubNodes;
            
            // Categorize by type
            hubNodes.forEach(node => {
                if (node.nodeType === 'Mobile') {
                    nodes.mobile.push(node);
                } else {
                    nodes.desktop.push(node);
                }
            });
            
            console.log('Found ' + hubNodes.length + ' active relay nodes from hub');
        }
        
        // Fallback to blockchain nodes if no hub nodes
        if (nodes.all.length === 0) {
            const blockchainNodes = await fetchNodesFromBlockchain();
            nodes.all = blockchainNodes;
        }
        
        // Cache results
        nodesCache = nodes;
        cacheTime = Date.now();
        
        return nodes;
    } catch (error) {
        console.error('Error fetching relay nodes:', error);
        return nodes;
    }
}

/**
 * Get the best available relay node
 */
export async function getBestRelayNode() {
    const nodes = await fetchOnlineRelayNodes();
    
    // Prefer hub nodes (least connected users for load balancing)
    if (nodes.hub && nodes.hub.length > 0) {
        const sorted = [...nodes.hub].sort((a, b) => 
            (a.connectedUsers || 0) - (b.connectedUsers || 0)
        );
        return sorted[0];
    }
    
    // Fallback to desktop nodes
    if (nodes.desktop.length > 0 && nodes.desktop[0].wsUrl) {
        return nodes.desktop[0];
    }
    
    // Fallback to mobile nodes
    if (nodes.mobile.length > 0 && nodes.mobile[0].wsUrl) {
        return nodes.mobile[0];
    }
    
    // No online nodes - return default
    return {
        wsUrl: RELAY_DEFAULTS.default,
        endpoint: RELAY_DEFAULTS.default,
        isOnline: true,
        nodeType: 'Default',
        tierName: 'Default'
    };
}

/**
 * Test connection to a relay node
 */
export async function testRelayConnection(wsUrl, timeout = 5000) {
    return new Promise((resolve) => {
        try {
            const ws = new WebSocket(wsUrl);
            const timer = setTimeout(() => {
                ws.close();
                resolve(false);
            }, timeout);
            
            ws.onopen = () => {
                clearTimeout(timer);
                ws.close();
                resolve(true);
            };
            
            ws.onerror = () => {
                clearTimeout(timer);
                resolve(false);
            };
        } catch {
            resolve(false);
        }
    });
}

/**
 * Get relay node statistics from hub
 */
export async function getRelayStats() {
    try {
        const response = await fetch(`${HUB_CONFIG.apiUrl}/api/stats`);
        return await response.json();
    } catch (error) {
        console.error('Error fetching relay stats:', error);
        return { totalNodes: 0, totalUsers: 0, nodes: [] };
    }
}
