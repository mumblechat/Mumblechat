/**
 * MumbleChat Relay Service
 * Fetches online relay nodes from blockchain and manages connections
 */

import { CONTRACTS, RAMESTTA_CONFIG } from './config.js';

const RPC_URL = RAMESTTA_CONFIG.rpcUrls[0];
const REGISTRY = CONTRACTS.registry;

// Function selectors
const SELECTORS = {
    totalRelayNodes: '0xc4de1ef3',
    activeRelayNodes: '0x73eec9d2',
    relayNodes: '0xad23e18f'
};

// Development mode: use localhost for relay connections  
const IS_DEV = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1' || window.location.hostname === '127.0.0.1:9999';

// Tier names
const TIER_NAMES = ['Bronze', 'Silver', 'Gold', 'Platinum'];

/**
 * Make eth_call to RPC
 */
async function ethCall(to, data) {
    try {
        const response = await fetch(RPC_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                jsonrpc: '2.0',
                id: 1,
                method: 'eth_call',
                params: [{ to, data }, 'latest']
            })
        });
        const result = await response.json();
        return result.result;
    } catch (error) {
        console.error('RPC call failed:', error);
        return null;
    }
}

/**
 * Batch RPC call for multiple requests
 */
async function batchEthCall(calls) {
    try {
        const response = await fetch(RPC_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(calls.map((call, i) => ({
                jsonrpc: '2.0',
                id: i + 1,
                method: 'eth_call',
                params: [{ to: call.to, data: call.data }, 'latest']
            })))
        });
        const results = await response.json();
        return results.sort((a, b) => a.id - b.id).map(r => r.result);
    } catch (error) {
        console.error('Batch RPC call failed:', error);
        return calls.map(() => null);
    }
}

/**
 * Parse relay node struct data from hex
 */
function parseRelayNodeData(hexData, address) {
    try {
        const data = hexData.slice(2);
        const chunks = [];
        for (let i = 0; i < data.length; i += 64) {
            chunks.push(data.slice(i, i + 64));
        }
        
        // Parse endpoint string (dynamic - first chunk is offset)
        const endpointOffset = Number(BigInt('0x' + (chunks[0] || '0'))) * 2;
        let endpoint = '';
        if (endpointOffset > 0 && data.length > endpointOffset) {
            const endpointData = data.slice(endpointOffset);
            const strLength = Number(BigInt('0x' + endpointData.slice(0, 64)));
            const strHex = endpointData.slice(64, 64 + strLength * 2);
            endpoint = decodeHexString(strHex);
        }
        
        const stakedAmountRaw = BigInt('0x' + (chunks[1] || '0'));
        const stakedAmount = Number(stakedAmountRaw / BigInt(10**18));
        const lastHeartbeat = Number(BigInt('0x' + (chunks[7] || '0')));
        const storageMB = Number(BigInt('0x' + (chunks[9] || '0')));
        const tier = Number(BigInt('0x' + (chunks[12] || '0')));
        
        // Check if online (heartbeat within 1 hour)
        const now = Math.floor(Date.now() / 1000);
        const timeSinceHeartbeat = now - lastHeartbeat;
        const isOnline = timeSinceHeartbeat < 3600 && timeSinceHeartbeat >= 0;
        
        // Determine node type based on storage (phones typically have less storage)
        const nodeType = storageMB <= 2048 ? 'Mobile' : 'Desktop';
        
        return {
            address,
            endpoint,
            staked: stakedAmount,
            nodeType,
            storageMB,
            tier,
            tierName: TIER_NAMES[Math.min(Math.max(tier, 0), 3)],
            isOnline,
            lastHeartbeat,
            timeSinceHeartbeat
        };
    } catch (e) {
        console.error('Error parsing node data:', e);
        return null;
    }
}

/**
 * Decode hex string to UTF-8
 */
function decodeHexString(hex) {
    let str = '';
    for (let i = 0; i < hex.length; i += 2) {
        const code = parseInt(hex.substr(i, 2), 16);
        if (code === 0) break;
        str += String.fromCharCode(code);
    }
    return str;
}

/**
 * Convert node endpoint to WebSocket URL
 * Handles various endpoint formats
 */
function endpointToWebSocketUrl(endpoint, nodeAddress) {
    if (!endpoint) return null;
    
    // If already a WebSocket URL, return as-is
    if (endpoint.startsWith('ws://') || endpoint.startsWith('wss://')) {
        // In development, redirect relay nodes to localhost
        if (IS_DEV && !endpoint.includes('localhost')) {
            console.log(`🔄 Development mode: redirecting ${endpoint} to localhost:19371`);
            return 'ws://localhost:19371';
        }
        return endpoint;
    }
    
    // If it's an HTTP URL, convert to WS
    if (endpoint.startsWith('http://')) {
        return endpoint.replace('http://', 'ws://');
    }
    if (endpoint.startsWith('https://')) {
        return endpoint.replace('https://', 'wss://');
    }
    
    // If it's a libp2p multiaddr, try to extract host/port
    // Format: /dns4/relay.mumblechat.io/tcp/19371/...
    // or /ip4/1.2.3.4/tcp/19371/...
    const dnsMatch = endpoint.match(/\/dns4\/([^\/]+)\/tcp\/(\d+)/);
    if (dnsMatch) {
        return `ws://${dnsMatch[1]}:${dnsMatch[2]}`;
    }
    
    const ipMatch = endpoint.match(/\/ip4\/([^\/]+)\/tcp\/(\d+)/);
    if (ipMatch) {
        return `ws://${ipMatch[1]}:${ipMatch[2]}`;
    }
    
    // Fallback: can't parse
    return null;
}

/**
 * Fetch all online relay nodes from blockchain
 */
export async function fetchOnlineRelayNodes() {
    const nodes = { desktop: [], mobile: [], all: [] };
    
    try {
            // In development mode, use local relay servers
            if (IS_DEV) {
                console.log('🔧 Development mode: using localhost relay servers');
                const desktopNode = {
                    address: '0xAC59CEA3E124CE70A7d88b8Ba4f3e3325Acb9DC7',
                    endpoint: 'ws://localhost:19371',
                    wsUrl: 'ws://localhost:19371',
                    isOnline: true,
                    nodeType: 'Desktop',
                    tier: 0,
                    stakedAmount: 0,
                    storageMB: 4096
                };
                nodes.desktop.push(desktopNode);
                nodes.all.push(desktopNode);
                return nodes;
            }
        
        // Get total nodes count
        const totalNodesHex = await ethCall(REGISTRY, SELECTORS.totalRelayNodes);
        const totalNodes = totalNodesHex ? Number(BigInt(totalNodesHex)) : 0;
        
        if (totalNodes === 0) {
            return nodes;
        }
        
        // Batch fetch all node addresses
        const addressCalls = [];
        for (let i = 0; i < totalNodes; i++) {
            const indexHex = i.toString(16).padStart(64, '0');
            addressCalls.push({ to: REGISTRY, data: SELECTORS.activeRelayNodes + indexHex });
        }
        
        const addressResults = await batchEthCall(addressCalls);
        const nodeAddresses = addressResults
            .filter(r => r && r.length >= 66)
            .map(r => '0x' + r.slice(-40));
        
        // Batch fetch all node details
        const detailCalls = nodeAddresses.map(addr => {
            const addrPadded = addr.slice(2).toLowerCase().padStart(64, '0');
            return { to: REGISTRY, data: SELECTORS.relayNodes + addrPadded };
        });
        
        const detailResults = await batchEthCall(detailCalls);
        
        // Parse all results
        for (let i = 0; i < nodeAddresses.length; i++) {
            const nodeDataHex = detailResults[i];
            if (nodeDataHex && nodeDataHex.length > 2) {
                const nodeData = parseRelayNodeData(nodeDataHex, nodeAddresses[i]);
                if (nodeData && nodeData.isOnline) {
                    // Convert endpoint to WebSocket URL
                    nodeData.wsUrl = endpointToWebSocketUrl(nodeData.endpoint, nodeData.address);
                    
                    nodes.all.push(nodeData);
                    if (nodeData.nodeType === 'Desktop') {
                        nodes.desktop.push(nodeData);
                    } else {
                        nodes.mobile.push(nodeData);
                    }
                }
            }
        }
        
        // Sort by staked amount (higher = more reliable)
        nodes.desktop.sort((a, b) => b.staked - a.staked);
        nodes.mobile.sort((a, b) => b.staked - a.staked);
        nodes.all.sort((a, b) => b.staked - a.staked);
        
        return nodes;
    } catch (error) {
        console.error('Error fetching relay nodes:', error);
        return nodes;
    }
}

/**
 * Get the best available relay node
 * Prefers Desktop nodes with higher stake
 */
export async function getBestRelayNode() {
    const nodes = await fetchOnlineRelayNodes();
    
    // Prefer desktop nodes
    if (nodes.desktop.length > 0 && nodes.desktop[0].wsUrl) {
        return nodes.desktop[0];
    }
    
    // Fallback to mobile nodes
    if (nodes.mobile.length > 0 && nodes.mobile[0].wsUrl) {
        return nodes.mobile[0];
    }
    
    // No online nodes
    return null;
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
