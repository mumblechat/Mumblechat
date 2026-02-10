/**
 * MumbleChat Relay Hub - WITH CROSS-NODE MESSAGING
 * 
 * Managed endpoint service for node operators.
 * Now supports cross-node message relay!
 */

import express from 'express';
import { createServer } from 'http';
import { WebSocket, WebSocketServer } from 'ws';
import { v4 as uuidv4 } from 'uuid';
import cors from 'cors';
import dotenv from 'dotenv';
import { ethers } from 'ethers';

dotenv.config();

// ============ Configuration ============

const config = {
    port: parseInt(process.env.HUB_PORT || '8080'),
    domain: process.env.HUB_DOMAIN || 'hub.mumblechat.com',
    rpcUrl: process.env.RPC_URL || 'https://blockchain.ramestta.com',
    // Registry contract for user identity
    registryAddress: process.env.REGISTRY_ADDRESS || '0x4f8D4955F370881B05b68D2344345E749d8632e3',
    // Relay Manager contract for node staking
    relayManagerAddress: process.env.RELAY_MANAGER_ADDRESS || '0xF78F840eF0e321512b09e98C76eA0229Affc4b73',
    hubFeePercent: parseInt(process.env.HUB_FEE_PERCENT || '10'),
    minStakeRequired: parseInt(process.env.MIN_STAKE_REQUIRED || '100'),  // 100 MCT minimum
    // Set to false to allow unstaked nodes (for testing/development)
    requireStaking: process.env.REQUIRE_STAKING === 'true' || false,
};

// ============ Gasless Heartbeat System ============
// Hub sends blockchain heartbeats on behalf of connected nodes
// Node owners don't pay gas - hub relays using their keys stored server-side

const HEARTBEAT_ABI = ['function heartbeat() external'];
const BLOCKCHAIN_HEARTBEAT_INTERVAL = 5.5 * 60 * 60 * 1000; // 5.5 hours (contract timeout = 6 hours)

// Pre-configured node private keys (wallet address lowercase -> private key)
// Hub sends heartbeat TX using each node's own wallet so contract sees correct msg.sender
const nodePrivateKeys: Map<string, string> = new Map();

// Parse NODE_KEYS env: comma-separated "address:privatekey" pairs
// e.g. NODE_KEYS=0xabc:0x123,0xdef:0x456
function loadNodeKeys() {
    const keysEnv = process.env.NODE_KEYS || '';
    if (keysEnv) {
        const pairs = keysEnv.split(',');
        for (const pair of pairs) {
            const [addr, key] = pair.trim().split(':');
            if (addr && key) {
                nodePrivateKeys.set(addr.toLowerCase(), key);
                console.log(`[Heartbeat] Loaded key for node ${addr.slice(0, 10)}...`);
            }
        }
    }
    console.log(`[Heartbeat] ${nodePrivateKeys.size} node keys loaded for gasless heartbeat`);
}

// Track active heartbeat intervals per wallet
const heartbeatIntervals: Map<string, NodeJS.Timeout> = new Map();

async function sendHeartbeatForNode(walletAddress: string, privateKey: string) {
    try {
        if (!provider) {
            console.error('[Heartbeat] No blockchain provider');
            return;
        }
        const wallet = new ethers.Wallet(privateKey, provider);
        const contract = new ethers.Contract(config.registryAddress, HEARTBEAT_ABI, wallet);
        const tx = await contract.heartbeat({ gasLimit: 200000 });
        await tx.wait();
        console.log(`[Heartbeat] 💓 Sent for ${walletAddress.slice(0, 10)}... tx: ${tx.hash.slice(0, 16)}...`);
    } catch (error: any) {
        console.error(`[Heartbeat] ❌ Failed for ${walletAddress.slice(0, 10)}...: ${error.message?.slice(0, 80)}`);
    }
}

function startHeartbeatForNode(walletAddress: string) {
    const addr = walletAddress.toLowerCase();
    const privateKey = nodePrivateKeys.get(addr);
    if (!privateKey) return;
    
    // Don't start if already running
    if (heartbeatIntervals.has(addr)) return;
    
    console.log(`[Heartbeat] 🟢 Starting gasless heartbeat for ${addr.slice(0, 10)}...`);
    
    // Send initial heartbeat after 15 seconds
    setTimeout(() => {
        if (connectedNodes.has(nodeByWallet.get(addr) || '')) {
            sendHeartbeatForNode(addr, privateKey);
        }
    }, 15000);
    
    // Then every 4 minutes
    const interval = setInterval(() => {
        // Only send if node is still connected via WebSocket
        const tunnelId = nodeByWallet.get(addr);
        if (tunnelId && connectedNodes.has(tunnelId)) {
            sendHeartbeatForNode(addr, privateKey);
        } else {
            // Node disconnected, stop heartbeat
            stopHeartbeatForNode(addr);
        }
    }, BLOCKCHAIN_HEARTBEAT_INTERVAL);
    
    heartbeatIntervals.set(addr, interval);
}

function stopHeartbeatForNode(walletAddress: string) {
    const addr = walletAddress.toLowerCase();
    const interval = heartbeatIntervals.get(addr);
    if (interval) {
        clearInterval(interval);
        heartbeatIntervals.delete(addr);
        console.log(`[Heartbeat] 🔴 Stopped gasless heartbeat for ${addr.slice(0, 10)}...`);
    }
}

// ============ P2P Peer-to-Peer Ping System ============
// Nodes ping each other every 5 minutes (gasless, off-chain)
// Hub orchestrates by sharing peer list; nodes do direct WebSocket pings

const P2P_PING_INTERVAL = 5 * 60 * 1000; // 5 minutes

interface P2PPingResult {
    from: string;      // wallet address of pinger
    to: string;        // wallet address of target
    latencyMs: number;  // round-trip time in ms
    timestamp: number;
    success: boolean;
}

// Track P2P ping results for each node pair
const p2pPingResults: Map<string, P2PPingResult[]> = new Map(); // "from->to" => results

// Track node type (desktop/mobile) and last P2P ping time
interface NodeP2PInfo {
    walletAddress: string;
    nodeType: 'desktop' | 'mobile' | 'server';  // node type
    lastP2PPing: number;   // timestamp of last P2P ping received
    p2pPeers: Map<string, { latencyMs: number, lastPing: number, online: boolean }>;
}

const nodeP2PInfo: Map<string, NodeP2PInfo> = new Map(); // wallet -> info

// Orchestrate P2P pings - tell each node to ping all other nodes
function broadcastPeerList() {
    const peerList = Array.from(connectedNodes.values()).map(n => ({
        tunnelId: n.tunnelId,
        walletAddress: n.walletAddress,
        nodeType: nodeP2PInfo.get(n.walletAddress.toLowerCase())?.nodeType || 'server',
    }));
    
    for (const node of connectedNodes.values()) {
        if (node.socket.readyState === WebSocket.OPEN) {
            const peers = peerList.filter(p => p.tunnelId !== node.tunnelId);
            node.socket.send(JSON.stringify({
                type: 'PEER_LIST',
                peers,
                pingInterval: P2P_PING_INTERVAL
            }));
        }
    }
}

// Broadcast peer list every 5 minutes
setInterval(broadcastPeerList, P2P_PING_INTERVAL);

// Record P2P ping result from a node
function recordP2PPing(from: string, to: string, latencyMs: number, success: boolean) {
    const key = `${from.toLowerCase()}->${to.toLowerCase()}`;
    if (!p2pPingResults.has(key)) {
        p2pPingResults.set(key, []);
    }
    const results = p2pPingResults.get(key)!;
    results.push({ from, to, latencyMs, timestamp: Date.now(), success });
    // Keep only last 50 results
    if (results.length > 50) results.splice(0, results.length - 50);
    
    // Update node P2P info
    const info = nodeP2PInfo.get(from.toLowerCase());
    if (info) {
        info.lastP2PPing = Date.now();
        info.p2pPeers.set(to.toLowerCase(), { latencyMs, lastPing: Date.now(), online: success });
    }
}

// ============ Registry Contract ABI ============
// Note: The actual contract has a non-standard return format, so we use raw calls

const REGISTRY_ABI = [
    'function isRegistered(address wallet) external view returns (bool)',
    'function totalRelayNodes() external view returns (uint256)',
    'function getActiveRelayNodes() external view returns (address[] memory)',
    'function minRelayStake() external view returns (uint256)',
    'function getRelayNode(address node) external view returns (string endpoint, uint256 stakedAmount, uint256 messagesRelayed, uint256 rewardsEarned, bool isActive, uint256 dailyUptimeSeconds, uint256 storageMB, uint8 tier, uint256 rewardMultiplier, bool isOnline, uint256 registeredAt)',
    'function isNodeOnline(address node) external view returns (bool)'
];

// Function selector for getRelayNode(address) - computed as keccak256("getRelayNode(address)")[:4]
const GET_RELAY_NODE_SELECTOR = '0xa12d2773';

// Relay Manager ABI (for node staking)
const RELAY_MANAGER_ABI = [
    'function relayNodes(address) external view returns (bool isActive, uint8 tier, uint256 stakedAmount, uint256 messagesRelayed, uint256 rewardsEarned, uint256 dailyUptimeSeconds, uint256 storageMB, bool isOnline, uint256 registeredAt)',
    'function getActiveEndpoints() external view returns (tuple(string nodeId, string endpoint, address wallet)[] memory)',
    'function getTotalNodeIds() external view returns (uint256)'
];

// ============ Blockchain Provider ============

let provider: ethers.JsonRpcProvider | null = null;
let registryContract: ethers.Contract | null = null;
let relayManagerContract: ethers.Contract | null = null;

function initBlockchain() {
    try {
        provider = new ethers.JsonRpcProvider(config.rpcUrl);
        registryContract = new ethers.Contract(config.registryAddress, REGISTRY_ABI, provider);
        relayManagerContract = new ethers.Contract(config.relayManagerAddress, RELAY_MANAGER_ABI, provider);
        console.log(`[Hub] Connected to blockchain: ${config.rpcUrl}`);
        console.log(`[Hub] Registry contract: ${config.registryAddress}`);
        console.log(`[Hub] Relay Manager contract: ${config.relayManagerAddress}`);
        console.log(`[Hub] Staking required: ${config.requireStaking}`);
    } catch (error) {
        console.error('[Hub] Failed to connect to blockchain:', error);
    }
}

// ============ Staking Verification ============

interface StakingInfo {
    isStaked: boolean;
    stakedAmount: number;
    messagesRelayed: number;
    rewardsEarned: number;
    tier: number;
    isActive: boolean;
}

// Cache staking info to reduce RPC calls (cache for 5 minutes)
const stakingCache: Map<string, { info: StakingInfo, timestamp: number }> = new Map();
const STAKING_CACHE_TTL = 5 * 60 * 1000;  // 5 minutes

// ============ Dynamic Active Relay Node Discovery ============
interface BlockchainNode {
    walletAddress: string;
    endpoint: string;
    stakedAmount: number;
    messagesRelayed: number;
    rewardsEarned: number;
    tier: number;
    isActive: boolean;
    isOnline: boolean;
    storageMB: number;
    dailyUptimeSeconds: number;
    registeredAt: number;
}

let cachedBlockchainNodes: BlockchainNode[] = [];
let blockchainCacheTimestamp = 0;
const BLOCKCHAIN_REFRESH_INTERVAL = 60 * 1000; // Refresh every 60 seconds

async function refreshActiveRelayNodes(): Promise<BlockchainNode[]> {
    if (!registryContract) return cachedBlockchainNodes;
    
    // Return cache if fresh
    if (Date.now() - blockchainCacheTimestamp < BLOCKCHAIN_REFRESH_INTERVAL && cachedBlockchainNodes.length > 0) {
        return cachedBlockchainNodes;
    }
    
    try {
        // Get all active relay node addresses from blockchain (deduplicate)
        const rawAddresses: string[] = await registryContract.getActiveRelayNodes();
        const activeAddresses = [...new Set(rawAddresses.map((a: string) => a))];
        console.log(`[Hub] Blockchain: ${activeAddresses.length} unique active relay nodes (${rawAddresses.length} raw)`);
        
        const nodes: BlockchainNode[] = [];
        const seen = new Set<string>();
        
        for (const addr of activeAddresses) {
            const key = addr.toLowerCase();
            if (seen.has(key)) continue;
            seen.add(key);
            try {
                const info = await registryContract.getRelayNode(addr);
                if (info.isActive) {
                    nodes.push({
                        walletAddress: addr,
                        endpoint: info.endpoint || `hub.mumblechat.com/node/${addr.slice(2, 10).toLowerCase()}`,
                        stakedAmount: Number(BigInt(info.stakedAmount)) / 1e18,
                        messagesRelayed: Number(info.messagesRelayed),
                        rewardsEarned: Number(BigInt(info.rewardsEarned)) / 1e18,
                        tier: Number(info.tier),
                        isActive: info.isActive,
                        isOnline: info.isOnline,
                        storageMB: Number(info.storageMB),
                        dailyUptimeSeconds: Number(info.dailyUptimeSeconds),
                        registeredAt: Number(info.registeredAt || 0)
                    });
                }
            } catch (e) {
                // Skip nodes we can't query
            }
        }
        
        cachedBlockchainNodes = nodes;
        blockchainCacheTimestamp = Date.now();
        return nodes;
    } catch (error: any) {
        console.error('[Hub] Failed to refresh active relay nodes:', error.message);
        return cachedBlockchainNodes;
    }
}

// Background refresh every 60 seconds
setInterval(async () => {
    try {
        await refreshActiveRelayNodes();
    } catch (e) {
        // Silent fail for background refresh
    }
}, BLOCKCHAIN_REFRESH_INTERVAL);

async function verifyStaking(walletAddress: string): Promise<StakingInfo> {
    const addr = walletAddress.toLowerCase();
    
    // Check cache first
    const cached = stakingCache.get(addr);
    if (cached && Date.now() - cached.timestamp < STAKING_CACHE_TTL) {
        return cached.info;
    }
    
    const defaultInfo: StakingInfo = {
        isStaked: false,
        stakedAmount: 0,
        messagesRelayed: 0,
        rewardsEarned: 0,
        tier: 0,
        isActive: false
    };
    
    if (!registryContract) {
        console.log(`[Hub] No blockchain connection, allowing node (dev mode)`);
        return { ...defaultInfo, isStaked: true };  // Allow in dev mode
    }
    
    try {
        // Use typed contract call via getRelayNode ABI
        const result = await registryContract.getRelayNode(walletAddress);
        
        const stakedAmountWei = BigInt(result.stakedAmount);
        const stakedAmount = Number(stakedAmountWei) / 1e18;
        const messagesRelayed = Number(result.messagesRelayed);
        const rewardsEarnedWei = BigInt(result.rewardsEarned);
        const rewardsEarned = Number(rewardsEarnedWei) / 1e18;
        const isActive = result.isActive;
        const tier = Number(result.tier);
        
        const info: StakingInfo = {
            isStaked: isActive && stakedAmount >= config.minStakeRequired,
            stakedAmount,
            messagesRelayed,
            rewardsEarned,
            tier,
            isActive
        };
        
        // Cache the result
        stakingCache.set(addr, { info, timestamp: Date.now() });
        
        console.log(`[Hub] Staking check for ${addr.slice(0,8)}...: staked=${info.stakedAmount} MCT, active=${info.isActive}, tier=${info.tier}`);
        
        return info;
    } catch (error) {
        console.error(`[Hub] Failed to verify staking for ${addr}:`, error);
        return defaultInfo;
    }
}

// ============ Types ============

interface ConnectedNode {
    nodeId: string;
    walletAddress: string;
    tunnelId: string;
    socket: WebSocket;
    connectedAt: Date;
    lastHeartbeat: Date;
    connectedUsers: Map<string, WebSocket>;  // sessionId -> user socket
    stats: {
        messagesRelayed: number;
        bytesTransferred: number;
    };
    staking: StakingInfo;  // Staking info from blockchain
}

interface ConnectedUser {
    sessionId: string;
    walletAddress?: string;
    nodeId: string;  // Which node they're connected through (tunnelId)
    socket: WebSocket;
    connectedAt: Date;
}

// ============ State ============

const connectedNodes: Map<string, ConnectedNode> = new Map();  // tunnelId -> node
const nodeByWallet: Map<string, string> = new Map();  // node wallet -> tunnelId
const connectedUsers: Map<string, ConnectedUser> = new Map();  // sessionId -> user

// *** Global user registry for cross-node routing ***
const usersByAddress: Map<string, { sessionId: string, tunnelId: string }> = new Map();  // user wallet -> session info

// ============ Offline Message Queue ============

interface OfflineMessage {
    id: string;
    from: string;
    to: string;
    payload: any;
    timestamp: number;
    expiresAt: number;  // 7 days from creation
    storedOnNodes: string[];  // Store on multiple nodes for redundancy
    delivered: boolean;
}

const offlineMessages: Map<string, OfflineMessage[]> = new Map();  // recipient address -> messages
const MESSAGE_EXPIRY_DAYS = 7;
const REDUNDANT_NODE_COUNT = 3;  // Store on 2-3 nodes

// ============ Express App ============

const app = express();
app.use(cors());
app.use(express.json());

// Health check
app.get('/health', (req, res) => {
    res.json({
        status: 'ok',
        nodes: connectedNodes.size,
        users: connectedUsers.size,
        registeredUsers: usersByAddress.size,
        uptime: process.uptime()
    });
});

// Get hub stats - dynamically merges connected WebSocket nodes + all blockchain-registered active nodes
app.get('/api/stats', async (req, res) => {
    // Start with connected WebSocket nodes
    const nodeStats = Array.from(connectedNodes.values()).map(node => ({
        tunnelId: node.tunnelId,
        endpoint: `${config.domain}/node/${node.tunnelId}`,
        walletAddress: node.walletAddress,
        connectedUsers: node.connectedUsers.size,
        messagesRelayed: node.stats.messagesRelayed,
        connectedAt: node.connectedAt,
        lastHeartbeat: node.lastHeartbeat,
        stakedAmount: (node as any).staking?.stakedAmount || 0,
        rewardsEarned: (node as any).staking?.rewardsEarned || 0,
        tier: (node as any).staking?.tier || 0,
        isStaked: (node as any).staking?.isStaked || false,
        blockchainMessages: (node as any).staking?.messagesRelayed || 0,
        isOnline: true  // Connected via WebSocket = online
    }));

    // Fetch all active relay nodes from blockchain (cached, refreshes every 60s)
    const blockchainNodes = await refreshActiveRelayNodes();
    
    // Add blockchain nodes that are NOT already connected via WebSocket
    const connectedWallets = new Set(
        Array.from(connectedNodes.values()).map(n => n.walletAddress?.toLowerCase()).filter(Boolean)
    );
    
    for (const bcNode of blockchainNodes) {
        if (!connectedWallets.has(bcNode.walletAddress.toLowerCase())) {
            // Only include blockchain-only nodes if they have a recent heartbeat (isOnline)
            if (!bcNode.isOnline) continue;
            
            nodeStats.push({
                tunnelId: `bc-${bcNode.walletAddress.slice(2, 10).toLowerCase()}`,
                endpoint: bcNode.endpoint,
                walletAddress: bcNode.walletAddress,
                connectedUsers: 0,
                messagesRelayed: bcNode.messagesRelayed,
                connectedAt: new Date().toISOString(),
                lastHeartbeat: new Date().toISOString(),
                stakedAmount: bcNode.stakedAmount,
                rewardsEarned: bcNode.rewardsEarned,
                tier: bcNode.tier,
                isStaked: true,
                blockchainMessages: bcNode.messagesRelayed,
                isOnline: true
            } as any);
        }
    }

    res.json({
        totalNodes: nodeStats.length,
        totalUsers: connectedUsers.size,
        registeredUsers: usersByAddress.size,
        hubFeePercent: config.hubFeePercent,
        nodes: nodeStats
    });
});

// Get all registered relay nodes from blockchain (fully dynamic, no hardcoded addresses)
app.get('/api/blockchain/nodes', async (req, res) => {
    try {
        if (!registryContract) {
            return res.status(503).json({ error: 'Blockchain not connected' });
        }
        
        // Fetch all active nodes from blockchain
        const blockchainNodes = await refreshActiveRelayNodes();
        
        const nodes = blockchainNodes.map(node => ({
            walletAddress: node.walletAddress,
            endpoint: node.endpoint,
            stakedAmount: node.stakedAmount,
            messagesRelayed: node.messagesRelayed,
            rewardsEarned: node.rewardsEarned,
            isActive: node.isActive,
            isOnline: node.isOnline,
            tier: node.tier,
            storageMB: node.storageMB,
            dailyUptimeSeconds: node.dailyUptimeSeconds,
            isConnectedToHub: nodeByWallet.has(node.walletAddress.toLowerCase())
        }));
        
        res.json({
            totalRegistered: blockchainNodes.length,
            nodes
        });
    } catch (error: any) {
        console.error('[Hub] Error fetching blockchain nodes:', error.message);
        res.status(500).json({ error: 'Failed to fetch blockchain nodes' });
    }
});

// *** Gasless Heartbeat Status ***
app.get('/api/heartbeat/status', (req, res) => {
    const status = Array.from(heartbeatIntervals.keys()).map(addr => ({
        wallet: addr.slice(0, 10) + '...',
        active: true,
        isConnected: nodeByWallet.has(addr)
    }));
    
    const configuredKeys = Array.from(nodePrivateKeys.keys()).map(addr => ({
        wallet: addr.slice(0, 10) + '...',
        hasKey: true,
        heartbeatActive: heartbeatIntervals.has(addr),
        isConnected: nodeByWallet.has(addr)
    }));
    
    res.json({
        totalConfiguredKeys: nodePrivateKeys.size,
        activeHeartbeats: heartbeatIntervals.size,
        intervalMs: BLOCKCHAIN_HEARTBEAT_INTERVAL,
        configuredNodes: configuredKeys,
        activeNodes: status
    });
});

// *** P2P Ping Status ***
app.get('/api/p2p/status', (req, res) => {
    const nodes: any[] = [];
    
    for (const [wallet, info] of nodeP2PInfo) {
        const peers: any[] = [];
        for (const [peerWallet, peerInfo] of info.p2pPeers) {
            peers.push({
                wallet: peerWallet.slice(0, 10) + '...',
                walletAddress: peerWallet,
                latencyMs: peerInfo.latencyMs,
                lastPing: peerInfo.lastPing,
                online: peerInfo.online && (Date.now() - peerInfo.lastPing) < P2P_PING_INTERVAL * 2
            });
        }
        nodes.push({
            wallet: wallet.slice(0, 10) + '...',
            walletAddress: wallet,
            nodeType: info.nodeType,
            lastP2PPing: info.lastP2PPing,
            isP2PActive: info.lastP2PPing > 0 && (Date.now() - info.lastP2PPing) < P2P_PING_INTERVAL * 2,
            peers
        });
    }
    
    res.json({
        totalNodes: nodeP2PInfo.size,
        pingIntervalMs: P2P_PING_INTERVAL,
        nodes
    });
});

// *** Enhanced stats with P2P and node type info ***
app.get('/api/stats/v2', async (req, res) => {
    const page = parseInt(req.query.page as string) || 1;
    const pageSize = parseInt(req.query.pageSize as string) || 20;
    const filter = (req.query.filter as string) || 'all'; // all, online, offline
    
    // Build complete node list
    const allNodes: any[] = [];
    
    // Connected WebSocket nodes (definitely online)
    for (const node of connectedNodes.values()) {
        const p2pInfo = nodeP2PInfo.get(node.walletAddress.toLowerCase());
        allNodes.push({
            tunnelId: node.tunnelId,
            endpoint: `${config.domain}/node/${node.tunnelId}`,
            walletAddress: node.walletAddress,
            connectedUsers: node.connectedUsers.size,
            messagesRelayed: node.stats.messagesRelayed,
            connectedAt: node.connectedAt,
            lastHeartbeat: node.lastHeartbeat,
            stakedAmount: node.staking?.stakedAmount || 0,
            rewardsEarned: node.staking?.rewardsEarned || 0,
            tier: node.staking?.tier || 0,
            isStaked: node.staking?.isStaked || false,
            blockchainMessages: node.staking?.messagesRelayed || 0,
            isOnline: true,
            nodeType: p2pInfo?.nodeType || 'server',
            p2pActive: p2pInfo ? (Date.now() - p2pInfo.lastP2PPing) < P2P_PING_INTERVAL * 2 : false,
            p2pPeers: p2pInfo ? Array.from(p2pInfo.p2pPeers.entries()).map(([w, p]) => ({
                wallet: w,
                latencyMs: p.latencyMs,
                online: p.online
            })) : [],
            source: 'websocket'
        });
    }
    
    // Blockchain nodes not connected via WebSocket
    const blockchainNodes = await refreshActiveRelayNodes();
    const connectedWallets = new Set(
        Array.from(connectedNodes.values()).map(n => n.walletAddress?.toLowerCase()).filter(Boolean)
    );
    
    for (const bcNode of blockchainNodes) {
        if (!connectedWallets.has(bcNode.walletAddress.toLowerCase())) {
            const bcHeartbeat = bcNode.registeredAt > 0 
                ? new Date(bcNode.registeredAt * 1000).toISOString() 
                : null;
            allNodes.push({
                tunnelId: `bc-${bcNode.walletAddress.slice(2, 10).toLowerCase()}`,
                endpoint: bcNode.endpoint,
                walletAddress: bcNode.walletAddress,
                connectedUsers: 0,
                messagesRelayed: bcNode.messagesRelayed,
                connectedAt: bcHeartbeat,
                lastHeartbeat: bcHeartbeat,
                stakedAmount: bcNode.stakedAmount,
                rewardsEarned: bcNode.rewardsEarned,
                tier: bcNode.tier,
                isStaked: true,
                blockchainMessages: bcNode.messagesRelayed,
                isOnline: bcNode.isOnline,
                nodeType: bcNode.endpoint?.includes('mobile') ? 'mobile' : 'unknown',
                p2pActive: false,
                p2pPeers: [],
                source: 'blockchain'
            });
        }
    }
    
    // Apply filter
    let filteredNodes = allNodes;
    if (filter === 'online') {
        filteredNodes = allNodes.filter(n => n.isOnline);
    } else if (filter === 'offline') {
        filteredNodes = allNodes.filter(n => !n.isOnline);
    }
    
    // Pagination
    const totalNodes = filteredNodes.length;
    const totalPages = Math.ceil(totalNodes / pageSize);
    const startIdx = (page - 1) * pageSize;
    const pagedNodes = filteredNodes.slice(startIdx, startIdx + pageSize);
    
    const onlineCount = allNodes.filter(n => n.isOnline).length;
    const offlineCount = allNodes.filter(n => !n.isOnline).length;
    
    res.json({
        totalNodes: allNodes.length,
        onlineNodes: onlineCount,
        offlineNodes: offlineCount,
        totalUsers: connectedUsers.size,
        registeredUsers: usersByAddress.size,
        hubFeePercent: config.hubFeePercent,
        pagination: {
            page,
            pageSize,
            totalPages,
            totalItems: totalNodes
        },
        nodes: pagedNodes
    });
});

// Get available endpoints for users
app.get('/api/endpoints', (req, res) => {
    const endpoints = Array.from(connectedNodes.values())
        .filter(node => Date.now() - node.lastHeartbeat.getTime() < 60000)
        .map(node => ({
            endpoint: `wss://${config.domain}/node/${node.tunnelId}`,
            tunnelId: node.tunnelId,
            users: node.connectedUsers.size,
            load: node.connectedUsers.size / 100
        }))
        .sort((a, b) => a.load - b.load);

    res.json({ endpoints });
});

// *** Check if a user is online (for cross-node status) ***
app.get('/api/user/:address', (req, res) => {
    const address = req.params.address.toLowerCase();
    const userInfo = usersByAddress.get(address);
    
    if (userInfo) {
        const node = connectedNodes.get(userInfo.tunnelId);
        res.json({
            online: true,
            nodeId: userInfo.tunnelId,
            nodeEndpoint: node ? `${config.domain}/node/${node.tunnelId}` : null
        });
    } else {
        res.json({ online: false });
    }
});

// *** Get pending offline messages count for a user ***
app.get('/api/user/:address/pending', (req, res) => {
    const address = req.params.address.toLowerCase();
    const messages = offlineMessages.get(address) || [];
    const pending = messages.filter(m => !m.delivered && m.expiresAt > Date.now());
    res.json({ 
        address,
        pendingCount: pending.length,
        messages: pending.map(m => ({
            id: m.id,
            from: m.from,
            timestamp: m.timestamp,
            expiresAt: m.expiresAt
        }))
    });
});

// *** Get offline queue stats ***
app.get('/api/offline-stats', (req, res) => {
    let totalMessages = 0;
    let pendingMessages = 0;
    const now = Date.now();
    
    for (const messages of offlineMessages.values()) {
        totalMessages += messages.length;
        pendingMessages += messages.filter(m => !m.delivered && m.expiresAt > now).length;
    }
    
    res.json({
        totalRecipients: offlineMessages.size,
        totalMessages,
        pendingMessages
    });
});

// ============ HTTP Server ============

const server = createServer(app);

// ============ Offline Message Functions ============

function storeOfflineMessage(from: string, to: string, payload: any): string {
    const toAddr = to.toLowerCase();
    const fromAddr = from.toLowerCase();
    const msgId = `offline_${Date.now()}_${Math.random().toString(36).slice(2, 11)}`;
    
    // Get available nodes for redundant storage
    const availableNodes = Array.from(connectedNodes.values())
        .filter(n => Date.now() - n.lastHeartbeat.getTime() < 60000)
        .map(n => n.tunnelId);
    
    // Select up to REDUNDANT_NODE_COUNT nodes
    const storageNodes = availableNodes.slice(0, REDUNDANT_NODE_COUNT);
    
    const offlineMsg: OfflineMessage = {
        id: msgId,
        from: fromAddr,
        to: toAddr,
        payload,
        timestamp: Date.now(),
        expiresAt: Date.now() + (MESSAGE_EXPIRY_DAYS * 24 * 60 * 60 * 1000),  // 7 days
        storedOnNodes: storageNodes,
        delivered: false
    };
    
    // Add to hub's queue
    if (!offlineMessages.has(toAddr)) {
        offlineMessages.set(toAddr, []);
    }
    offlineMessages.get(toAddr)!.push(offlineMsg);
    
    // Distribute to relay nodes for redundant storage
    for (const tunnelId of storageNodes) {
        const node = connectedNodes.get(tunnelId);
        if (node && node.socket.readyState === WebSocket.OPEN) {
            node.socket.send(JSON.stringify({
                type: 'STORE_OFFLINE_MESSAGE',
                message: offlineMsg
            }));
        }
    }
    
    console.log(`[Hub] Stored offline message ${msgId.slice(0,12)}... for ${toAddr.slice(0,8)}... on ${storageNodes.length} nodes`);
    return msgId;
}

function deliverOfflineMessages(address: string, tunnelId: string, sessionId: string): number {
    const addr = address.toLowerCase();
    const messages = offlineMessages.get(addr) || [];
    const node = connectedNodes.get(tunnelId);
    
    if (!node) return 0;
    
    let deliveredCount = 0;
    const now = Date.now();
    
    for (const msg of messages) {
        if (msg.delivered || msg.expiresAt < now) continue;
        
        // Extract actual text content from nested payload
        let textContent = msg.payload;
        if (typeof textContent === 'object') {
            textContent = textContent.text || textContent.payload || textContent.content || textContent.encryptedBlob || textContent;
        }
        
        // Deliver to user via their node
        node.socket.send(JSON.stringify({
            type: 'DELIVER_OFFLINE_MESSAGE',
            sessionId,
            message: {
                type: 'message',
                from: msg.from,
                senderAddress: msg.from,
                to: msg.to,
                text: textContent,
                payload: textContent,
                encryptedBlob: textContent,
                messageId: msg.id,
                timestamp: msg.timestamp,
                isOfflineMessage: true
            }
        }));
        
        msg.delivered = true;
        deliveredCount++;
        
        // Notify storage nodes to mark as delivered
        for (const nodeId of msg.storedOnNodes) {
            const storageNode = connectedNodes.get(nodeId);
            if (storageNode && storageNode.socket.readyState === WebSocket.OPEN) {
                storageNode.socket.send(JSON.stringify({
                    type: 'MARK_MESSAGE_DELIVERED',
                    messageId: msg.id,
                    recipient: addr
                }));
            }
        }
    }
    
    if (deliveredCount > 0) {
        console.log(`[Hub] Delivered ${deliveredCount} offline messages to ${addr.slice(0,8)}...`);
    }
    
    return deliveredCount;
}

function cleanupExpiredMessages(): void {
    const now = Date.now();
    let cleanedTotal = 0;
    
    for (const [addr, messages] of offlineMessages) {
        const before = messages.length;
        const filtered = messages.filter(m => m.expiresAt > now && !m.delivered);
        
        if (filtered.length !== before) {
            cleanedTotal += (before - filtered.length);
            if (filtered.length === 0) {
                offlineMessages.delete(addr);
            } else {
                offlineMessages.set(addr, filtered);
            }
        }
    }
    
    if (cleanedTotal > 0) {
        console.log(`[Hub] Cleaned up ${cleanedTotal} expired/delivered offline messages`);
    }
    
    // Notify all nodes to cleanup
    for (const node of connectedNodes.values()) {
        if (node.socket.readyState === WebSocket.OPEN) {
            node.socket.send(JSON.stringify({
                type: 'CLEANUP_EXPIRED_MESSAGES',
                expiryTime: now
            }));
        }
    }
}

// Run cleanup every hour
setInterval(cleanupExpiredMessages, 60 * 60 * 1000);

// ============ WebSocket Server ============

const wss = new WebSocketServer({ server });

wss.on('connection', (ws: WebSocket, req) => {
    const url = req.url || '/';
    
    console.log(`[Hub] New connection: ${url}`);

    if (url === '/node/connect') {
        handleNodeConnection(ws);
    } else if (url.startsWith('/node/')) {
        const tunnelId = url.replace('/node/', '');
        handleUserConnection(ws, tunnelId);
    } else if (url === '/user/connect') {
        handleAutoUserConnection(ws);
    } else {
        ws.close(4000, 'Invalid endpoint');
    }
});

// ============ Node Connection Handler ============

function handleNodeConnection(ws: WebSocket) {
    let node: ConnectedNode | null = null;
    const tunnelId = uuidv4().slice(0, 8);

    ws.on('message', async (data: Buffer) => {
        try {
            const message = JSON.parse(data.toString());

            switch (message.type) {
                case 'NODE_AUTH':
                    // Support both formats: message.walletAddress and message.data.walletAddress
                    const walletAddress = message.walletAddress || message.data?.walletAddress;
                    const signature = message.signature || message.data?.signature;
                    const nodeId = message.nodeId || message.data?.nodeId;
                    
                    if (!walletAddress) {
                        console.log('[Hub] ❌ Node AUTH failed: No wallet address provided');
                        ws.send(JSON.stringify({
                            type: 'NODE_AUTH_FAILED',
                            error: 'MISSING_WALLET',
                            message: 'Wallet address is required'
                        }));
                        ws.close(4002, 'Missing wallet address');
                        return;
                    }
                    
                    // *** VERIFY STAKING BEFORE ACCEPTING NODE ***
                    const stakingInfo = await verifyStaking(walletAddress);
                    
                    if (!stakingInfo.isStaked) {
                        console.log(`[Hub] ❌ Node REJECTED: ${walletAddress.slice(0,8)}... - Not staked (${stakingInfo.stakedAmount} MCT, min: ${config.minStakeRequired} MCT)`);
                        ws.send(JSON.stringify({
                            type: 'NODE_AUTH_FAILED',
                            error: 'INSUFFICIENT_STAKE',
                            message: `You need to stake at least ${config.minStakeRequired} MCT to run a relay node`,
                            currentStake: stakingInfo.stakedAmount,
                            requiredStake: config.minStakeRequired,
                            isActive: stakingInfo.isActive
                        }));
                        ws.close(4003, 'Insufficient stake');
                        return;
                    }
                    
                    // *** DISCONNECT OLD CONNECTION FROM SAME WALLET ***
                    const existingTunnelId = nodeByWallet.get(walletAddress);
                    if (existingTunnelId) {
                        const existingNode = connectedNodes.get(existingTunnelId);
                        if (existingNode && existingNode.socket.readyState === WebSocket.OPEN) {
                            console.log(`[Hub] Replacing existing node ${existingTunnelId} for wallet ${walletAddress.slice(0,8)}...`);
                            existingNode.socket.close(4001, 'Replaced by new connection');
                        }
                        connectedNodes.delete(existingTunnelId);
                    }
                    
                    node = {
                        nodeId: nodeId || tunnelId,
                        walletAddress,
                        tunnelId,
                        socket: ws,
                        connectedAt: new Date(),
                        lastHeartbeat: new Date(),
                        connectedUsers: new Map(),
                        stats: {
                            messagesRelayed: 0,
                            bytesTransferred: 0
                        },
                        staking: stakingInfo
                    };
                    
                    connectedNodes.set(tunnelId, node);
                    nodeByWallet.set(walletAddress, tunnelId);
                    
                    // Send tunnel established message
                    ws.send(JSON.stringify({
                        type: 'TUNNEL_ESTABLISHED',
                        tunnelId,
                        endpoint: `wss://${config.domain}/node/${tunnelId}`,
                        httpEndpoint: `https://${config.domain}/node/${tunnelId}`,
                        hubFeePercent: config.hubFeePercent,
                        stakedAmount: stakingInfo.stakedAmount,
                        tier: stakingInfo.tier,
                        message: 'Your node is now accessible!'
                    }));
                    
                    // Also send NODE_REGISTERED for backwards compatibility with older clients
                    ws.send(JSON.stringify({
                        type: 'NODE_REGISTERED',
                        nodeId: nodeId || tunnelId,
                        data: {
                            nodeId: nodeId || tunnelId,
                            tunnelId
                        }
                    }));
                    
                    console.log(`[Hub] ✅ Node connected: ${tunnelId} (wallet: ${walletAddress.slice(0,8)}..., staked: ${stakingInfo.stakedAmount} MCT, tier: ${stakingInfo.tier})`);
                    
                    // Start gasless blockchain heartbeat if we have the node's key
                    startHeartbeatForNode(walletAddress);
                    
                    // Initialize P2P info for this node
                    nodeP2PInfo.set(walletAddress.toLowerCase(), {
                        walletAddress: walletAddress.toLowerCase(),
                        nodeType: message.nodeType || message.data?.nodeType || 'server',
                        lastP2PPing: 0,
                        p2pPeers: new Map()
                    });
                    
                    // Send peer list to all nodes (including new one)
                    setTimeout(broadcastPeerList, 2000);
                    break;

                case 'HEARTBEAT':
                    if (node) {
                        node.lastHeartbeat = new Date();
                        
                        // Calculate total WEIGHTED relays (tier multiplier applied)
                        const TIER_MULTIPLIERS = [1.0, 1.5, 2.0, 3.0];
                        let totalWeightedRelays = 0;
                        for (const [, n] of connectedNodes) {
                            const mult = TIER_MULTIPLIERS[n.staking?.tier || 0] || 1;
                            totalWeightedRelays += n.stats.messagesRelayed * mult;
                        }
                        
                        // Send back node stats so desktop app can display real data
                        ws.send(JSON.stringify({ 
                            type: 'HEARTBEAT_ACK',
                            data: {
                                // Your node's stats
                                messagesRelayed: node.stats.messagesRelayed,
                                connectedUsers: node.connectedUsers.size,
                                bytesTransferred: node.stats.bytesTransferred,
                                // Network stats (DYNAMIC!)
                                totalNodes: connectedNodes.size,
                                totalNetworkUsers: Array.from(connectedNodes.values()).reduce((sum, n) => sum + n.connectedUsers.size, 0),
                                totalNetworkMessages: Array.from(connectedNodes.values()).reduce((sum, n) => sum + n.stats.messagesRelayed, 0),
                                totalWeightedRelays: totalWeightedRelays,  // For reward calculation
                                // Your staking info
                                stakedAmount: node.staking?.stakedAmount || 0,
                                tier: node.staking?.tier || 0,
                                rewardsEarned: node.staking?.rewardsEarned || 0
                            }
                        }));
                    }
                    break;

                case 'MESSAGE_TO_USER':
                    // Node sending message to a user - CHECK CROSS-NODE
                    if (node) {
                        // P2P ping handling - relay ping to target node
                        if (message.subtype === 'P2P_PING') {
                            const targetWallet = message.targetWallet?.toLowerCase();
                            const targetTunnelId = nodeByWallet.get(targetWallet || '');
                            if (targetTunnelId) {
                                const targetNode = connectedNodes.get(targetTunnelId);
                                if (targetNode && targetNode.socket.readyState === WebSocket.OPEN) {
                                    targetNode.socket.send(JSON.stringify({
                                        type: 'P2P_PING',
                                        fromWallet: node.walletAddress,
                                        fromTunnelId: node.tunnelId,
                                        pingId: message.pingId,
                                        timestamp: Date.now()
                                    }));
                                }
                            }
                            break;
                        }
                        if (message.subtype === 'P2P_PONG') {
                            const targetWallet = message.targetWallet?.toLowerCase();
                            const targetTunnelId = nodeByWallet.get(targetWallet || '');
                            if (targetTunnelId) {
                                const targetNode = connectedNodes.get(targetTunnelId);
                                if (targetNode && targetNode.socket.readyState === WebSocket.OPEN) {
                                    targetNode.socket.send(JSON.stringify({
                                        type: 'P2P_PONG',
                                        fromWallet: node.walletAddress,
                                        pingId: message.pingId,
                                        latencyMs: message.latencyMs,
                                        timestamp: Date.now()
                                    }));
                                }
                            }
                            // Record the ping result
                            recordP2PPing(
                                node.walletAddress, 
                                message.targetWallet || '',
                                message.latencyMs || 0, 
                                true
                            );
                            break;
                        }
                        
                        const { userId, payload } = message;
                        let delivered = false;
                        const senderAddr = (payload.from || payload.senderAddress || '').toLowerCase();
                        
                        // First try local user (on same node)
                        const userSocket = node.connectedUsers.get(userId);
                        if (userSocket && userSocket.readyState === WebSocket.OPEN) {
                            userSocket.send(JSON.stringify(payload));
                            node.stats.messagesRelayed++;
                            delivered = true;
                        }
                        
                        // Cross-node delivery
                        if (!delivered && payload.to) {
                            const targetAddr = payload.to.toLowerCase();
                            const targetUser = usersByAddress.get(targetAddr);
                            
                            if (targetUser && targetUser.tunnelId !== node.tunnelId) {
                                // User is on DIFFERENT node - route through that node
                                const targetNode = connectedNodes.get(targetUser.tunnelId);
                                if (targetNode && targetNode.socket.readyState === WebSocket.OPEN) {
                                    // Forward to target node
                                    targetNode.socket.send(JSON.stringify({
                                        type: 'CROSS_NODE_MESSAGE',
                                        targetSessionId: targetUser.sessionId,
                                        payload: payload
                                    }));
                                    node.stats.messagesRelayed++;
                                    console.log(`[Hub] Cross-node relay: ${node.tunnelId} -> ${targetUser.tunnelId} for ${targetAddr.slice(0,8)}...`);
                                    delivered = true;
                                    
                                    // *** Send delivery confirmation back to sender's node ***
                                    ws.send(JSON.stringify({
                                        type: 'DELIVERY_RECEIPT',
                                        messageId: payload.messageId,
                                        to: targetAddr,
                                        status: 'delivered',
                                        timestamp: Date.now()
                                    }));
                                }
                            } else if (!targetUser) {
                                // *** USER IS OFFLINE - Store for later delivery ***
                                const msgId = storeOfflineMessage(
                                    payload.from || payload.senderAddress,
                                    targetAddr,
                                    payload
                                );
                                console.log(`[Hub] User ${targetAddr.slice(0,8)}... offline, queued message ${msgId.slice(0,12)}...`);
                                
                                // Send queued confirmation back to sender
                                ws.send(JSON.stringify({
                                    type: 'MESSAGE_QUEUED',
                                    messageId: payload.messageId,
                                    queuedId: msgId,
                                    status: 'queued_offline',
                                    recipient: targetAddr,
                                    expiresIn: `${MESSAGE_EXPIRY_DAYS} days`
                                }));
                            }
                        }
                        
                        // *** If delivered locally on same node, send confirmation ***
                        if (delivered && !payload.to) {
                            ws.send(JSON.stringify({
                                type: 'DELIVERY_RECEIPT',
                                messageId: payload.messageId,
                                to: payload.to,
                                status: 'delivered',
                                timestamp: Date.now()
                            }));
                        }
                    }
                    break;

                // *** NEW: Handle cross-node message from other node ***
                case 'CROSS_NODE_DELIVERY':
                    if (node && message.targetSessionId && message.payload) {
                        const userSocket = node.connectedUsers.get(message.targetSessionId);
                        if (userSocket && userSocket.readyState === WebSocket.OPEN) {
                            userSocket.send(JSON.stringify(message.payload));
                            node.stats.messagesRelayed++;
                        }
                    }
                    break;

                // *** User registered their address with the node ***
                case 'USER_AUTHENTICATED':
                    if (node && message.sessionId && message.address) {
                        const addr = message.address.toLowerCase();
                        usersByAddress.set(addr, {
                            sessionId: message.sessionId,
                            tunnelId: node.tunnelId
                        });
                        console.log(`[Hub] User ${addr.slice(0,8)}... registered on node ${node.tunnelId}`);
                        
                        // *** Deliver any pending offline messages ***
                        const delivered = deliverOfflineMessages(addr, node.tunnelId, message.sessionId);
                        if (delivered > 0) {
                            console.log(`[Hub] Delivered ${delivered} queued messages to ${addr.slice(0,8)}...`);
                        }
                    }
                    break;

                // *** P2P Ping result from a node ***
                case 'P2P_PING_RESULT':
                    if (node && message.results) {
                        for (const result of message.results) {
                            recordP2PPing(
                                node.walletAddress,
                                result.targetWallet,
                                result.latencyMs || 0,
                                result.success
                            );
                        }
                    }
                    break;
                
                // *** Node type update (desktop/mobile/server) ***
                case 'NODE_TYPE_UPDATE':
                    if (node && message.nodeType) {
                        const p2pInfo = nodeP2PInfo.get(node.walletAddress.toLowerCase());
                        if (p2pInfo) {
                            p2pInfo.nodeType = message.nodeType;
                        }
                        console.log(`[Hub] Node ${node.tunnelId} type: ${message.nodeType}`);
                    }
                    break;

                case 'BROADCAST':
                    if (node) {
                        for (const [userId, userSocket] of node.connectedUsers) {
                            if (userSocket.readyState === WebSocket.OPEN) {
                                userSocket.send(JSON.stringify(message.payload));
                            }
                        }
                        node.stats.messagesRelayed += node.connectedUsers.size;
                    }
                    break;
            }
        } catch (error) {
            console.error('[Hub] Error handling node message:', error);
        }
    });

    ws.on('close', () => {
        if (node) {
            // Remove all users registered through this node from global registry
            for (const [addr, info] of usersByAddress) {
                if (info.tunnelId === node.tunnelId) {
                    usersByAddress.delete(addr);
                }
            }
            
            // Notify all users
            for (const [userId, userSocket] of node.connectedUsers) {
                userSocket.send(JSON.stringify({
                    type: 'NODE_DISCONNECTED',
                    message: 'The relay node has disconnected. Please reconnect.'
                }));
                userSocket.close();
            }
            
            nodeByWallet.delete(node.walletAddress);
            connectedNodes.delete(tunnelId);
            
            // Stop gasless heartbeat for this node
            stopHeartbeatForNode(node.walletAddress);
            
            // Remove P2P info
            nodeP2PInfo.delete(node.walletAddress.toLowerCase());
            
            // Broadcast updated peer list
            setTimeout(broadcastPeerList, 1000);
            
            console.log(`[Hub] Node disconnected: ${tunnelId}`);
        }
    });

    ws.on('error', (error) => {
        console.error(`[Hub] Node error: ${error.message}`);
    });
}

// ============ User Connection Handler ============

function handleUserConnection(ws: WebSocket, tunnelId: string) {
    const node = connectedNodes.get(tunnelId);
    
    if (!node) {
        ws.send(JSON.stringify({
            type: 'ERROR',
            message: 'Node not found or offline'
        }));
        ws.close(4004, 'Node not found');
        return;
    }

    const sessionId = uuidv4();
    const user: ConnectedUser = {
        sessionId,
        nodeId: tunnelId,
        socket: ws,
        connectedAt: new Date()
    };

    connectedUsers.set(sessionId, user);
    node.connectedUsers.set(sessionId, ws);

    // Notify node
    node.socket.send(JSON.stringify({
        type: 'USER_CONNECTED',
        sessionId,
        timestamp: Date.now()
    }));

    ws.send(JSON.stringify({
        type: 'CONNECTED',
        sessionId,
        nodeId: tunnelId,
        message: 'Connected to relay node'
    }));

    console.log(`[Hub] User connected to node ${tunnelId}: ${sessionId.slice(0,8)}...`);

    ws.on('message', (data: Buffer) => {
        try {
            const payload = JSON.parse(data.toString());
            
            // *** NEW: Handle authentication to register user address ***
            if (payload.type === 'authenticate' && payload.address || payload.walletAddress) {
                const addr = payload.address || payload.walletAddress.toLowerCase();
                user.walletAddress = addr;
                
                // Register in global user registry
                usersByAddress.set(addr, {
                    sessionId,
                    tunnelId
                });
                console.log(`[Hub] User ${addr.slice(0,8)}... authenticated on node ${tunnelId}`);
            }
            
            // *** Direct hub routing for relay messages ***
            if (payload.type === 'relay' && payload.to) {
                const targetAddr = payload.to.toLowerCase();
                const targetInfo = usersByAddress.get(targetAddr);
                
                if (targetInfo) {
                    const targetNode = connectedNodes.get(targetInfo.tunnelId);
                    if (targetNode) {
                        if (targetInfo.tunnelId === tunnelId) {
                            // Same node - forward to node
                            node.socket.send(JSON.stringify({
                                type: 'MESSAGE_FROM_USER',
                                sessionId,
                                payload
                            }));
                        } else {
                            // Different node - CROSS-NODE RELAY
                            targetNode.socket.send(JSON.stringify({
                                type: 'CROSS_NODE_MESSAGE',
                                targetSessionId: targetInfo.sessionId,
                                sourceSessionId: sessionId,
                                payload: {
                                    type: 'message',
                                    from: payload.from,
                                    senderAddress: payload.from,
                                    to: targetAddr,
                                    payload: payload.encryptedBlob || payload.payload,
                                    encryptedBlob: payload.encryptedBlob || payload.payload,
                                    messageId: payload.messageId,
                                    timestamp: payload.timestamp || Date.now()
                                }
                            }));
                            node.stats.messagesRelayed++;
                            console.log(`[Hub] Cross-node: ${tunnelId} -> ${targetInfo.tunnelId} for ${targetAddr.slice(0,8)}...`);
                            
                            // Send delivery confirmation
                            ws.send(JSON.stringify({
                                type: 'delivery_receipt',
                                messageId: payload.messageId,
                                status: 'routed'
                            }));
                        }
                        return;  // Don't forward to node again
                    }
                } else {
                    // *** USER IS OFFLINE - Queue message for later delivery ***
                    const msgId = storeOfflineMessage(
                        payload.from,
                        targetAddr,
                        payload
                    );
                    console.log(`[Hub] User ${targetAddr.slice(0,8)}... offline, queued message ${msgId.slice(0,12)}...`);
                    
                    // Send queued confirmation
                    ws.send(JSON.stringify({
                        type: 'message_queued',
                        messageId: payload.messageId,
                        queuedId: msgId,
                        status: 'queued_offline',
                        recipient: targetAddr,
                        expiresIn: `${MESSAGE_EXPIRY_DAYS} days`
                    }));
                    return;
                }
            }
            
            // Forward all other messages to the node
            if (node.socket.readyState === WebSocket.OPEN) {
                node.socket.send(JSON.stringify({
                    type: 'MESSAGE_FROM_USER',
                    sessionId,
                    payload
                }));
                node.stats.messagesRelayed++;
                node.stats.bytesTransferred += data.length;
            }
        } catch (e) {
            // Forward raw data if not JSON
            if (node.socket.readyState === WebSocket.OPEN) {
                node.socket.send(JSON.stringify({
                    type: 'MESSAGE_FROM_USER',
                    sessionId,
                    payload: data.toString()
                }));
            }
        }
    });

    ws.on('close', () => {
        // Remove from global registry
        if (user.walletAddress) {
            usersByAddress.delete(user.walletAddress);
        }
        
        connectedUsers.delete(sessionId);
        node.connectedUsers.delete(sessionId);
        
        if (node.socket.readyState === WebSocket.OPEN) {
            node.socket.send(JSON.stringify({
                type: 'USER_DISCONNECTED',
                sessionId
            }));
        }
        
        console.log(`[Hub] User disconnected: ${sessionId.slice(0,8)}...`);
    });
}

// ============ Auto User Connection ============

function handleAutoUserConnection(ws: WebSocket) {
    let bestNode: ConnectedNode | null = null;
    let minLoad = Infinity;

    for (const node of connectedNodes.values()) {
        if (Date.now() - node.lastHeartbeat.getTime() < 60000) {
            const load = node.connectedUsers.size;
            if (load < minLoad) {
                minLoad = load;
                bestNode = node;
            }
        }
    }

    if (!bestNode) {
        ws.send(JSON.stringify({
            type: 'ERROR',
            message: 'No relay nodes available'
        }));
        ws.close(4004, 'No nodes available');
        return;
    }

    handleUserConnection(ws, bestNode.tunnelId);
}

// ============ Start Server ============

// Initialize blockchain connection first
initBlockchain();

// Load node private keys for gasless heartbeat
loadNodeKeys();

server.listen(config.port, () => {
    console.log('═'.repeat(70));
    console.log('   MumbleChat Relay Hub - WITH STAKING VERIFICATION');
    console.log('═'.repeat(70));
    console.log(`   Server running on port ${config.port}`);
    console.log(`   Domain: ${config.domain}`);
    console.log(`   Hub fee: ${config.hubFeePercent}%`);
    console.log(`   Min stake required: ${config.minStakeRequired} MCT`);
    console.log('');
    console.log('   Features:');
    console.log('   ✓ Multi-node support');
    console.log('   ✓ Cross-node message relay');
    console.log('   ✓ Global user registry');
    console.log('   ✓ Load balancing');
    console.log('   ✓ MCT STAKING VERIFICATION');
    console.log('   ✓ GASLESS HEARTBEAT RELAYER (every 5.5hr)');
    console.log('   ✓ P2P PEER-TO-PEER PING (every 5min)');
    console.log('═'.repeat(70));
});

// ============ Graceful Shutdown ============

process.on('SIGINT', () => {
    console.log('\n[Hub] Shutting down...');
    
    // Stop all heartbeat intervals
    for (const [addr, interval] of heartbeatIntervals) {
        clearInterval(interval);
    }
    heartbeatIntervals.clear();
    
    for (const node of connectedNodes.values()) {
        node.socket.send(JSON.stringify({
            type: 'HUB_SHUTDOWN',
            message: 'Hub is shutting down'
        }));
        node.socket.close();
    }
    
    server.close(() => {
        console.log('[Hub] Server closed');
        process.exit(0);
    });
});
