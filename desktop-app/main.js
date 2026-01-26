const { app, BrowserWindow, Tray, Menu, ipcMain, shell, nativeImage } = require('electron');
const path = require('path');
const WebSocket = require('ws');
const Store = require('electron-store');
const { ethers } = require('ethers');
const os = require('os');
const crypto = require('crypto');

// Initialize store for persistent data
const store = new Store({
    defaults: {
        wallet: '',
        nodeId: '',
        autoStart: false,
        autoReconnect: true,
        theme: 'dark'
    }
});

// Global references
let mainWindow = null;
let tray = null;
let ws = null;
let isRelaying = false;
let provider = null;
let rewardsInterval = null;

// Stats tracking
let stats = {
    messagesRelayed: 0,
    usersConnected: 0,
    bytesTransferred: 0,
    uptimeSeconds: 0,
    sessionStart: null,
    peakUsers: 0
};

// Tier information
let tierInfo = {
    level: 0,
    name: 'Bronze',
    badge: '🥉',
    multiplier: 1.0,
    stakedAmount: 0,
    requiredUptime: 4
};

// Rewards tracking
let rewards = {
    dailyPool: 0,
    feePool: 0,
    minting: 0,
    total: 0,
    lastClaim: null
};

// Hub tunnel info (secure endpoint - hides user IP)
let hubTunnel = {
    tunnelId: null,
    endpoint: null,          // e.g., wss://hub.mumblechat.com/node/abc123
    hubFeePercent: 10,       // Hub takes 10% fee for tunnel service
    isActive: false
};

// Configuration
const CONFIG = {
    HUB_URL: 'wss://hub.mumblechat.com/node/connect',
    RPC_URL: 'https://blockchain.ramestta.com',
    CHAIN_ID: 1370,
    CONTRACTS: {
        MCT_TOKEN: '0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE',
        REGISTRY: '0x4f8D4955F370881B05b68D2344345E749d8632e3',
        RELAY_MANAGER: '0xF78F840eF0e321512b09e98C76eA0229Affc4b73'
    },
    TIERS: [
        { name: 'Bronze', stake: 100, uptime: 4, multiplier: 1.0, badge: '🥉' },
        { name: 'Silver', stake: 200, uptime: 8, multiplier: 1.5, badge: '🥈' },
        { name: 'Gold', stake: 300, uptime: 12, multiplier: 2.0, badge: '🥇' },
        { name: 'Platinum', stake: 400, uptime: 16, multiplier: 3.0, badge: '💎' }
    ]
};

// Contract ABIs (minimal)
const RELAY_MANAGER_ABI = [
    'function getNodeInfo(address) view returns (uint256 stakedAmount, uint256 tier, uint256 lastActive, uint256 totalRelays, bool isActive)',
    'function getClaimableRewards(address) view returns (uint256 dailyPool, uint256 feePool, uint256 minting)',
    'function claimRewards() external',
    'function getDailyPoolInfo() view returns (uint256 totalPool, uint256 activeNodes, uint256 currentBlock)',
    'function getNodeUptime(address) view returns (uint256 todayUptime, uint256 totalUptime)'
];

const MCT_TOKEN_ABI = [
    'function balanceOf(address) view returns (uint256)',
    'function decimals() view returns (uint8)'
];

// Get unique machine ID
function getMachineId() {
    let cached = store.get('machineId');
    if (cached) return cached;
    
    const id = crypto.randomBytes(16).toString('hex');
    store.set('machineId', id);
    return id;
}

// Initialize blockchain connection
async function initBlockchain() {
    try {
        provider = new ethers.JsonRpcProvider(CONFIG.RPC_URL);
        await provider.getNetwork();
        sendToRenderer('log', ['Connected to Ramestta blockchain', 'success']);
        return true;
    } catch (error) {
        sendToRenderer('log', ['Blockchain connection failed: ' + error.message, 'error']);
        return false;
    }
}

// Get contract instances
function getContracts() {
    if (!provider) return null;
    
    return {
        relayManager: new ethers.Contract(CONFIG.CONTRACTS.RELAY_MANAGER, RELAY_MANAGER_ABI, provider),
        mctToken: new ethers.Contract(CONFIG.CONTRACTS.MCT_TOKEN, MCT_TOKEN_ABI, provider)
    };
}

// Fetch tier info from blockchain (graceful - non-blocking)
async function fetchTierInfo() {
    const wallet = store.get('wallet');
    if (!wallet) return;
    
    // For now, use default tier - hub will provide actual tier info
    // The staking is verified by the hub when you connect
    const defaultTier = CONFIG.TIERS[0];
    tierInfo = {
        level: 0,
        name: defaultTier.name,
        badge: defaultTier.badge,
        multiplier: defaultTier.multiplier,
        stakedAmount: 100,
        requiredUptime: defaultTier.uptime,
        totalRelays: stats.messagesRelayed
    };
    
    sendToRenderer('tierUpdate', tierInfo);
    sendToRenderer('log', ['Tier info loaded (verified by hub on connect)', 'success']);
}

// Fetch claimable rewards - estimated locally, claim via website
async function fetchRewards() {
    const wallet = store.get('wallet');
    if (!wallet) return;
    
    // Estimate rewards based on local uptime and messages
    // Actual rewards are tracked by hub and claimed via website
    estimateLocalRewards();
}

/**
 * Calculate rewards based on contract logic (MumbleChatRelayManager.sol):
 * 
 * CONTRACT REWARD SYSTEM:
 * ════════════════════════════════════════════════════════════════════════════
 * 1. RELAY REWARD: (totalWeightedRelays × 0.001 MCT) / 100 = 0.00001 MCT per relay
 * 2. WEIGHTED RELAYS: relays × tier_multiplier (1.0x/1.5x/2.0x/3.0x)
 * 3. EFFECTIVE POOL: min(totalEarned, 100 MCT daily cap)
 * 4. YOUR SHARE: (yourWeightedRelays / totalWeightedRelays) × effectivePool
 * 
 * TIER FEE REWARD (separate from relay, based on uptime):
 * 5. TIER REWARD: (dailyPool × tierFeePercent) / 100
 *    - Bronze: 10% = 10 MCT max (shared among all Bronze nodes)
 *    - Silver: 20% = 20 MCT max
 *    - Gold: 30% = 30 MCT max
 *    - Platinum: 40% = 40 MCT max
 * 6. ACTUAL: tierReward × (yourUptime% / 100) ÷ nodesInTier
 * 7. MISSED: Goes to 100% uptime nodes as bonus
 * ════════════════════════════════════════════════════════════════════════════
 * 
 * ALL VALUES ARE DYNAMIC FROM HUB - nodes/relays change in real-time!
 */
function estimateLocalRewards() {
    const uptimeSeconds = stats.uptimeSeconds;
    const uptimeHours = uptimeSeconds / 3600;
    const messagesRelayed = stats.messagesRelayed;
    
    // Contract constants (from MumbleChatRelayManager.sol)
    const DAILY_POOL_AMOUNT = hubStats.dailyPool || 100;  // Dynamic from hub
    const MCT_PER_100_RELAYS = 0.001;        // 0.001 MCT per 100 weighted relays
    const TIER_FEE_PERCENTS = [10, 20, 30, 40];  // % of daily pool for tier
    const TIER_MULTIPLIERS = [1.0, 1.5, 2.0, 3.0];
    const REQUIRED_UPTIMES = [4, 8, 12, 16];     // hours required per day
    
    const tierLevel = tierInfo.level || 0;
    const multiplier = TIER_MULTIPLIERS[tierLevel];
    const feePercent = TIER_FEE_PERCENTS[tierLevel];
    const requiredUptime = REQUIRED_UPTIMES[tierLevel];
    
    // Calculate uptime percentage (capped at 100%)
    const uptimePercent = Math.min(100, (uptimeHours / requiredUptime) * 100);
    
    // ═══════════════════════════════════════════════════════════════════
    // DYNAMIC NETWORK STATS FROM HUB (updated every heartbeat)
    // ═══════════════════════════════════════════════════════════════════
    const activeNodes = Math.max(1, hubStats.activeNodes || 1);
    const totalNetworkRelays = Math.max(1, hubStats.totalWeightedRelays || 1);
    
    // ═══════════════════════════════════════════════════════════════════
    // PART 1: RELAY POOL REWARDS (based on YOUR relays vs TOTAL network)
    // Formula: (yourWeightedRelays / totalNetworkRelays) × effectivePool
    // ═══════════════════════════════════════════════════════════════════
    
    const yourWeightedRelays = messagesRelayed * multiplier;
    
    // Total earned from ALL network relays: (totalRelays × 0.001) / 100
    const totalEarnedFromRelays = (totalNetworkRelays * MCT_PER_100_RELAYS) / 100;
    
    // Effective pool capped at daily max
    const effectiveRelayPool = Math.min(totalEarnedFromRelays, DAILY_POOL_AMOUNT);
    
    // Your share of relay pool (proportional to your contribution)
    let relayPoolReward = 0;
    if (yourWeightedRelays > 0 && totalNetworkRelays > 0) {
        relayPoolReward = (yourWeightedRelays / totalNetworkRelays) * effectiveRelayPool;
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // PART 2: TIER FEE REWARDS (shared among ALL active nodes - DYNAMIC)
    // Formula: (tierPool / activeNodes) × (uptimePercent / 100)
    // activeNodes updates in real-time from hub!
    // ═══════════════════════════════════════════════════════════════════
    
    // Total tier pool (e.g., Bronze = 10% of daily pool)
    const tierPool = (DAILY_POOL_AMOUNT * feePercent) / 100;
    
    // Your potential share = tierPool / total active nodes (DYNAMIC!)
    const potentialPerNode = tierPool / activeNodes;
    
    // Actual reward based on your uptime percentage
    const actualTierReward = potentialPerNode * (uptimePercent / 100);
    
    // Missed reward (goes to bonus pool for 100% uptime nodes)
    const missedReward = potentialPerNode - actualTierReward;
    
    // ═══════════════════════════════════════════════════════════════════
    // PART 3: BONUS (from others' missed rewards - only 100% uptime)
    // ═══════════════════════════════════════════════════════════════════
    
    let mintingBonus = 0;
    if (uptimePercent >= 100 && hubStats.missedPool > 0 && hubStats.fullUptimeNodes > 0) {
        mintingBonus = hubStats.missedPool / hubStats.fullUptimeNodes;
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // TOTALS - ALL VALUES ARE DYNAMIC FROM HUB
    // ═══════════════════════════════════════════════════════════════════
    
    rewards = {
        dailyPool: relayPoolReward,           // Your relay share (dynamic)
        feePool: actualTierReward,            // Your tier share (dynamic)
        minting: mintingBonus,                // Bonus pool share
        total: 0,
        // Dynamic network info for UI
        uptimePercent: uptimePercent,
        weightedRelays: yourWeightedRelays,
        totalNetworkRelays: totalNetworkRelays,
        activeNodes: activeNodes              // Shows current node count
    };
    rewards.total = rewards.dailyPool + rewards.feePool + rewards.minting;
    
    sendToRenderer('rewardsUpdate', rewards);
}

// Hub statistics (received from hub via websocket - UPDATED EVERY 30s)
let hubStats = {
    activeNodes: 1,        // Will be updated dynamically from hub
    totalWeightedRelays: 0,
    missedPool: 0,
    fullUptimeNodes: 0,
    dailyPool: 100
};

// Create main window
function createWindow() {
    mainWindow = new BrowserWindow({
        width: 900,
        height: 650,
        minWidth: 800,
        minHeight: 600,
        resizable: true,
        frame: false,
        titleBarStyle: 'hidden',
        trafficLightPosition: { x: 12, y: 12 },
        webPreferences: {
            nodeIntegration: false,
            contextIsolation: true,
            preload: path.join(__dirname, 'preload.js')
        },
        icon: path.join(__dirname, 'assets', 'icon.png'),
        backgroundColor: '#0a0a0f',
        show: false
    });

    mainWindow.loadFile('index.html');
    
    mainWindow.once('ready-to-show', () => {
        mainWindow.show();
    });

    mainWindow.on('close', (event) => {
        if (!app.isQuitting) {
            event.preventDefault();
            mainWindow.hide();
        }
    });
    
    mainWindow.on('closed', () => {
        mainWindow = null;
    });
}

// Create system tray
function createTray() {
    // Create a data URL for the tray icon
    const trayIconDataUrl = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABgAAAAYCAYAAADgdz34AAAABHNCSVQICAgIfAhkiAAAAAlwSFlzAAAAsQAAALEBxi1JjQAAABl0RVh0U29mdHdhcmUAd3d3Lmlua3NjYXBlLm9yZ5vuPBoAAAJsSURBVEiJtZU9aBRBFMd/M7t3l0RjiIWFhYUgiBYWNjYKFhYWFhZaiKCFjYWFICIIAUEwCIKIYGNhYWFhYWEhioWFhYWFhYWFhYWFRUT8wI+Lyd3OzGuxu9m7S3JR/MOw7Mz+/2/ezJt5IiLshO70HdAB0APoWusFSGvtHQ6Ho8Nh9OmFR0TkSxHoAzYdGkaNRqN7HcfZl2Xa0dq9APQm/r0AnBKRqxsRWGsj4CJwEniw3lFEeoCBRCIBHAIqIvJxAwIABxgHbgMX1hNYa3uAK8BE8l4ZY34E7m9EYIw5BTwBrifjl4E9xtgz1tqeJOYGcAqob0RgrT0M3ASuJePXgJsicisRvU/CTmCqHlFVFUKIwf/CJ4CKiLy11o4BfcA0cFlE6sAF4HAS+2u9hFUVAQ4Bx4wxE9baQaAPGAemRGQEGAGGgD9JBPV2dF1X27adjRaqAodFpCEig0AfMC4iI9bas8AwsDfJ4oq1NlzXaroBo9FolBuNxgHgMHBERG4Bh4F+EdkD9CY5CK211v4BXtcjWGmtHQP6gVFr7RHgMHAkmToCHE32+4Gqtbberlx0XdeBwFpr+5Nx/cCEiFwBRoDBJIeJpO8B4EASQdcqBCH4fzJJLo4mjxFgPIm5ASKSA6pJBF3/Qb1aRJK1toJm0JU8BpK5g0AfMCEiXclcBRgEvgJVEblnjJlZl0BEIhH5Drxfy1wS/y5wJll7MMnhqqqqWteNdNAOHAaGgQfW2i/W2q9J2J6BZO5w8liRRlBKqe5EklEPrQlEpGqtLQKzSQRdwBhwO4lgT5LBNhGpbJfgLx3WG1NsJQPNAAAAAElFTkSuQmCC';
    
    const trayIcon = nativeImage.createFromDataURL(trayIconDataUrl);
    tray = new Tray(trayIcon.resize({ width: 16, height: 16 }));
    
    updateTrayMenu();
    
    tray.setToolTip('MumbleChat Relay');
    
    tray.on('click', () => {
        if (mainWindow) {
            mainWindow.isVisible() ? mainWindow.hide() : mainWindow.show();
        }
    });
}

function updateTrayMenu() {
    if (!tray) return;
    
    const contextMenu = Menu.buildFromTemplate([
        { 
            label: 'Open MumbleChat Relay', 
            click: () => mainWindow && mainWindow.show() 
        },
        { type: 'separator' },
        { 
            label: isRelaying ? '🟢 Connected & Relaying' : '🔴 Disconnected',
            enabled: false
        },
        { 
            label: `📊 Messages: ${stats.messagesRelayed.toLocaleString()}`,
            enabled: false
        },
        { 
            label: `💰 Claimable: ${rewards.total.toFixed(4)} MCT`,
            enabled: false
        },
        { type: 'separator' },
        { 
            label: isRelaying ? '⏹️ Stop Relaying' : '▶️ Start Relaying',
            click: () => {
                if (isRelaying) {
                    stopRelay();
                } else {
                    startRelay();
                }
            }
        },
        { type: 'separator' },
        { 
            label: 'Quit MumbleChat Relay', 
            click: () => {
                app.isQuitting = true;
                app.quit();
            }
        }
    ]);
    
    tray.setContextMenu(contextMenu);
}

// Send message to renderer
function sendToRenderer(channel, data) {
    if (mainWindow && mainWindow.webContents) {
        mainWindow.webContents.send(channel, data);
    }
}

// WebSocket connection to hub
function startRelay() {
    const wallet = store.get('wallet');
    if (!wallet) {
        sendToRenderer('log', ['Please configure wallet first', 'error']);
        return;
    }
    
    if (ws && ws.readyState === WebSocket.OPEN) {
        return;
    }
    
    sendToRenderer('connectionChange', 'connecting');
    sendToRenderer('log', ['Connecting to hub.mumblechat.com...', 'info']);
    
    ws = new WebSocket(CONFIG.HUB_URL);
    
    ws.on('open', () => {
        isRelaying = true;
        stats.sessionStart = Date.now();
        
        // Register as relay node
        ws.send(JSON.stringify({
            type: 'NODE_AUTH',
            data: {
                walletAddress: wallet,
                machineId: getMachineId(),
                nodeId: store.get('nodeId') || 'desktop-' + crypto.randomBytes(4).toString('hex'),
                version: '4.0.0',
                platform: process.platform,
                tier: tierInfo.level
            }
        }));
        
        sendToRenderer('connectionChange', true);
        sendToRenderer('log', ['Connected to hub successfully!', 'success']);
        updateTrayMenu();
        
        // Start heartbeat to keep connection alive (IMPORTANT for uptime tracking!)
        startHeartbeat();
        
        // Start rewards polling
        startRewardsPolling();
    });
    
    ws.on('message', (data) => {
        try {
            const msg = JSON.parse(data.toString());
            handleHubMessage(msg);
        } catch (e) {
            console.error('Message parse error:', e);
        }
    });
    
    ws.on('close', (code, reason) => {
        isRelaying = false;
        sendToRenderer('connectionChange', false);
        sendToRenderer('log', [`Disconnected (code: ${code})`, 'warning']);
        updateTrayMenu();
        stopRewardsPolling();
        
        // Auto-reconnect
        if (store.get('autoReconnect', true)) {
            setTimeout(() => {
                if (!isRelaying && store.get('wallet')) {
                    sendToRenderer('log', ['Attempting to reconnect...', 'info']);
                    startRelay();
                }
            }, 5000);
        }
    });
    
    ws.on('error', (err) => {
        sendToRenderer('log', ['Connection error: ' + err.message, 'error']);
    });
}

function handleHubMessage(msg) {
    switch (msg.type) {
        case 'NODE_REGISTERED':
            store.set('nodeId', msg.data?.nodeId || msg.nodeId);
            sendToRenderer('log', ['Node registered: ' + (msg.data?.nodeId || msg.nodeId), 'success']);
            break;
        
        // ═══════════════════════════════════════════════════════════════════
        // HUB TUNNEL - Secure endpoint that hides your IP
        // Hub provides a tunnel endpoint and takes 10% fee for the service
        // ═══════════════════════════════════════════════════════════════════
        case 'TUNNEL_ESTABLISHED':
            if (msg.data) {
                hubTunnel = {
                    tunnelId: msg.data.tunnelId,
                    endpoint: msg.data.endpoint,      // wss://hub.mumblechat.com/node/{tunnelId}
                    hubFeePercent: msg.data.hubFeePercent || 10,
                    isActive: true
                };
                store.set('hubTunnel', hubTunnel);
                sendToRenderer('tunnelUpdate', hubTunnel);
                sendToRenderer('log', ['🔒 Secure tunnel established! Your IP is hidden.', 'success']);
                sendToRenderer('log', [`Tunnel endpoint: ${hubTunnel.endpoint}`, 'info']);
                sendToRenderer('log', [`Hub fee: ${hubTunnel.hubFeePercent}% of rewards`, 'info']);
            }
            break;
            
        case 'RELAY_MESSAGE':
        case 'relay_message':
            stats.messagesRelayed++;
            stats.bytesTransferred += msg.data?.size || 100;
            sendToRenderer('statsUpdate', {
                messagesRelayed: stats.messagesRelayed,
                bytesTransferred: stats.bytesTransferred
            });
            // Recalculate rewards when messages change
            estimateLocalRewards();
            updateTrayMenu();
            break;
            
        case 'USER_COUNT':
        case 'user_count':
            stats.usersConnected = msg.data?.count || msg.count || 0;
            if (stats.usersConnected > stats.peakUsers) {
                stats.peakUsers = stats.usersConnected;
            }
            sendToRenderer('statsUpdate', {
                usersConnected: stats.usersConnected,
                peakUsers: stats.peakUsers
            });
            break;
            
        case 'PING':
        case 'ping':
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify({ type: 'PONG' }));
            }
            break;
            
        case 'HEARTBEAT_ACK':
            // Hub sends back real stats with heartbeat acknowledgment
            if (msg.data) {
                // Update local stats with REAL data from hub
                if (msg.data.messagesRelayed !== undefined) {
                    stats.messagesRelayed = msg.data.messagesRelayed;
                }
                if (msg.data.connectedUsers !== undefined) {
                    stats.usersConnected = msg.data.connectedUsers;
                    if (stats.usersConnected > stats.peakUsers) {
                        stats.peakUsers = stats.usersConnected;
                    }
                }
                if (msg.data.bytesTransferred !== undefined) {
                    stats.bytesTransferred = msg.data.bytesTransferred;
                }
                
                // Update hub stats for reward calculation
                hubStats.activeNodes = msg.data.totalNodes || hubStats.activeNodes;
                hubStats.totalWeightedRelays = msg.data.totalWeightedRelays || msg.data.totalNetworkMessages || 0;
                
                // Update tier info from hub
                if (msg.data.stakedAmount !== undefined) {
                    tierInfo.stakedAmount = msg.data.stakedAmount;
                }
                if (msg.data.tier !== undefined) {
                    tierInfo.level = msg.data.tier;
                    const tierConfig = CONFIG.TIERS[tierInfo.level] || CONFIG.TIERS[0];
                    tierInfo.name = tierConfig.name;
                    tierInfo.badge = tierConfig.badge;
                    tierInfo.multiplier = tierConfig.multiplier;
                }
                
                // Send updates to UI
                sendToRenderer('statsUpdate', {
                    messagesRelayed: stats.messagesRelayed,
                    usersConnected: stats.usersConnected,
                    peakUsers: stats.peakUsers,
                    bytesTransferred: stats.bytesTransferred
                });
                sendToRenderer('tierUpdate', tierInfo);
                
                // Recalculate rewards with real data
                estimateLocalRewards();
                
                updateTrayMenu();
            }
            break;
            
        case 'REWARDS_UPDATE':
        case 'earnings_update':
            if (msg.data?.rewards) {
                rewards = { ...rewards, ...msg.data.rewards };
                sendToRenderer('rewardsUpdate', rewards);
            }
            break;
            
        case 'TIER_UPDATE':
            if (msg.data?.tier !== undefined) {
                fetchTierInfo();
            }
            break;
            
        // Hub statistics for accurate reward calculation
        case 'POOL_STATS':
        case 'pool_stats':
            if (msg.data) {
                hubStats = {
                    activeNodes: msg.data.activeNodes || hubStats.activeNodes,
                    totalWeightedRelays: msg.data.totalWeightedRelays || 0,
                    missedPool: msg.data.missedPool || 0,
                    fullUptimeNodes: msg.data.fullUptimeNodes || 0,
                    dailyPool: msg.data.dailyPool || 100
                };
                // Recalculate with new hub stats
                estimateLocalRewards();
                sendToRenderer('log', [`Pool stats updated: ${hubStats.activeNodes} nodes, ${hubStats.dailyPool} MCT pool`, 'info']);
            }
            break;
            
        case 'NODE_COUNT':
        case 'node_count':
            hubStats.activeNodes = msg.data?.count || msg.count || hubStats.activeNodes;
            estimateLocalRewards();
            break;
    }
}

function stopRelay() {
    if (ws) {
        ws.close();
        ws = null;
    }
    isRelaying = false;
    sendToRenderer('connectionChange', false);
    sendToRenderer('log', ['Relay stopped', 'warning']);
    updateTrayMenu();
    stopRewardsPolling();
    stopHeartbeat();
}

// ═══════════════════════════════════════════════════════════════════
// HEARTBEAT - Keep connection alive and track uptime on hub
// Hub requires heartbeat every 60 seconds to consider node "online"
// ═══════════════════════════════════════════════════════════════════
let heartbeatInterval = null;

function startHeartbeat() {
    if (heartbeatInterval) clearInterval(heartbeatInterval);
    
    // Send heartbeat every 30 seconds (hub timeout is 60s)
    heartbeatInterval = setInterval(() => {
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({
                type: 'HEARTBEAT',
                data: {
                    uptimeSeconds: stats.uptimeSeconds,
                    messagesRelayed: stats.messagesRelayed,
                    usersConnected: stats.usersConnected
                }
            }));
        }
    }, 30000);
    
    // Send first heartbeat immediately
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
            type: 'HEARTBEAT',
            data: {
                uptimeSeconds: stats.uptimeSeconds,
                messagesRelayed: stats.messagesRelayed,
                usersConnected: stats.usersConnected
            }
        }));
    }
    
    sendToRenderer('log', ['Heartbeat started (30s interval)', 'info']);
}

function stopHeartbeat() {
    if (heartbeatInterval) {
        clearInterval(heartbeatInterval);
        heartbeatInterval = null;
    }
}

// Rewards polling
function startRewardsPolling() {
    if (rewardsInterval) clearInterval(rewardsInterval);
    
    // Fetch immediately
    fetchTierInfo();
    fetchRewards();
    
    // Then poll every 30 seconds
    rewardsInterval = setInterval(() => {
        fetchRewards();
    }, 30000);
}

function stopRewardsPolling() {
    if (rewardsInterval) {
        clearInterval(rewardsInterval);
        rewardsInterval = null;
    }
}

// Uptime tracking
let uptimeInterval = null;
function startUptimeTracking() {
    if (uptimeInterval) clearInterval(uptimeInterval);
    
    uptimeInterval = setInterval(() => {
        if (isRelaying) {
            stats.uptimeSeconds++;
            sendToRenderer('statsUpdate', {
                uptimeSeconds: stats.uptimeSeconds
            });
            
            // Update estimated rewards every minute
            if (stats.uptimeSeconds % 60 === 0) {
                estimateLocalRewards();
            }
        }
    }, 1000);
}

// IPC handlers
ipcMain.handle('getConfig', () => {
    return {
        wallet: store.get('wallet'),
        nodeId: store.get('nodeId'),
        machineId: getMachineId(),
        autoStart: store.get('autoStart', false),
        autoReconnect: store.get('autoReconnect', true),
        isRelaying,
        stats,
        tierInfo,
        rewards,
        hubTunnel: store.get('hubTunnel') || hubTunnel
    };
});

ipcMain.handle('setConfig', (event, config) => {
    if (config.wallet !== undefined) store.set('wallet', config.wallet);
    if (config.autoStart !== undefined) {
        store.set('autoStart', config.autoStart);
        app.setLoginItemSettings({
            openAtLogin: config.autoStart,
            openAsHidden: true
        });
    }
    if (config.autoReconnect !== undefined) store.set('autoReconnect', config.autoReconnect);
    return true;
});

ipcMain.handle('startRelay', () => {
    startRelay();
    return true;
});

ipcMain.handle('stopRelay', () => {
    stopRelay();
    return true;
});

ipcMain.handle('claimRewards', async () => {
    // For claiming, user needs to use web interface or wallet
    // This would require signing transaction
    sendToRenderer('log', ['Please claim rewards via mumblechat.com/staking', 'warning']);
    shell.openExternal('https://mumblechat.com/staking');
    return false;
});

ipcMain.handle('openExternal', (event, url) => {
    shell.openExternal(url);
});

ipcMain.handle('refreshTier', () => {
    fetchTierInfo();
    fetchRewards();
});

ipcMain.handle('getSystemInfo', () => {
    return {
        platform: process.platform,
        arch: process.arch,
        cpus: os.cpus().length,
        memory: Math.round(os.totalmem() / 1024 / 1024 / 1024),
        hostname: os.hostname(),
        appVersion: app.getVersion()
    };
});

// App lifecycle
app.whenReady().then(async () => {
    // Initialize blockchain connection
    await initBlockchain();
    
    createWindow();
    createTray();
    startUptimeTracking();
    
    // Fetch initial tier info if wallet is set
    if (store.get('wallet')) {
        fetchTierInfo();
        
        // Auto-start relay if configured
        if (store.get('autoStart', false)) {
            setTimeout(startRelay, 2000);
        }
    }
});

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
        app.quit();
    }
});

app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
        createWindow();
    } else if (mainWindow) {
        mainWindow.show();
    }
});

app.on('before-quit', () => {
    app.isQuitting = true;
    stopRelay();
    stopRewardsPolling();
    if (uptimeInterval) clearInterval(uptimeInterval);
});

// Prevent multiple instances
const gotTheLock = app.requestSingleInstanceLock();
if (!gotTheLock) {
    app.quit();
} else {
    app.on('second-instance', () => {
        if (mainWindow) {
            if (mainWindow.isMinimized()) mainWindow.restore();
            mainWindow.show();
            mainWindow.focus();
        }
    });
}
