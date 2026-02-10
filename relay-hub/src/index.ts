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

// ============ Registry Contract ABI ============
// Note: The actual contract has a non-standard return format, so we use raw calls

const REGISTRY_ABI = [
    'function isRegistered(address wallet) external view returns (bool)',
    'function totalRelayNodes() external view returns (uint256)',
    'function getActiveRelayNodes() external view returns (address[] memory)',
    'function minRelayStake() external view returns (uint256)',
    'function getRelayNode(address node) external view returns (string endpoint, uint256 stakedAmount, uint256 messagesRelayed, uint256 rewardsEarned, bool isActive, uint256 dailyUptimeSeconds, uint256 storageMB, uint8 tier, uint256 rewardMultiplier, bool isOnline)',
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
                        dailyUptimeSeconds: Number(info.dailyUptimeSeconds)
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
    
    if (!provider) {
        console.log(`[Hub] No blockchain connection, allowing node (dev mode)`);
        return { ...defaultInfo, isStaked: true };  // Allow in dev mode
    }
    
    try {
        // Use raw call because contract ABI has non-standard return format
        const paddedAddr = walletAddress.toLowerCase().replace('0x', '').padStart(64, '0');
        const calldata = GET_RELAY_NODE_SELECTOR + paddedAddr;
        
        const result = await provider.call({
            to: config.registryAddress,
            data: calldata
        });
        
        // Parse the hex result manually
        // Result format (each word is 64 hex chars / 32 bytes):
        // Word 0: offset pointer (320)
        // Word 1: stakedAmount (in wei)
        // Word 2: messagesRelayed
        // Word 3: rewardsEarned (in wei)
        // Word 4: isActive (1 or 0)
        // Word 5: 0
        // Word 6: offset for string
        // Word 7: tier
        // ...rest is string data
        
        if (result === '0x' || result.length < 66) {
            console.log(`[Hub] No relay node found for ${addr.slice(0,8)}...`);
            return defaultInfo;
        }
        
        const data = result.slice(2); // Remove 0x
        const words: string[] = [];
        for (let i = 0; i < data.length; i += 64) {
            words.push(data.slice(i, i + 64));
        }
        
        const stakedAmountWei = BigInt('0x' + (words[1] || '0'));
        const stakedAmount = Number(stakedAmountWei) / 1e18;
        const messagesRelayed = parseInt(words[3] || '0', 16);
        const rewardsEarnedWei = BigInt('0x' + (words[4] || '0'));
        const rewardsEarned = Number(rewardsEarnedWei) / 1e18;
        const isActive = parseInt(words[5] || '0', 16) === 1;
        const tier = parseInt(words[13] || '0', 16);
        
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
                isOnline: bcNode.isOnline  // Based on blockchain heartbeat (5 min timeout)
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
    console.log('═'.repeat(70));
});

// ============ Graceful Shutdown ============

process.on('SIGINT', () => {
    console.log('\n[Hub] Shutting down...');
    
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
