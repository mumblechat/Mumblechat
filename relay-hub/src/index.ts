/**
 * MumbleChat Relay Hub
 * 
 * Managed endpoint service for node operators who don't want to deal with
 * IPs, domains, port forwarding, or any technical configuration.
 * 
 * How it works:
 * 1. Node operator downloads desktop app
 * 2. App connects to this hub (outbound connection - works behind any NAT)
 * 3. Hub assigns a unique endpoint: hub.mumblechat.io/node/{nodeId}
 * 4. Users connect to hub, hub tunnels traffic to the node
 * 5. Hub takes 10% fee from node rewards
 * 
 * Architecture:
 * 
 *   Node Owner                    Hub                         Users
 *   ──────────                   ────                        ─────
 *   [Desktop App] ─────────────► [This Server] ◄─────────── [Mobile/Web App]
 *   (outbound tunnel)            (public endpoint)           (users connect)
 *   
 *   No port forwarding needed!   We handle everything!
 */

import express from 'express';
import { createServer } from 'http';
import { WebSocket, WebSocketServer } from 'ws';
import { ethers } from 'ethers';
import { v4 as uuidv4 } from 'uuid';
import cors from 'cors';
import dotenv from 'dotenv';

dotenv.config();

// ============ Configuration ============

const config = {
    port: parseInt(process.env.HUB_PORT || '8080'),
    domain: process.env.HUB_DOMAIN || 'hub.mumblechat.com',
    rpcUrl: process.env.RPC_URL || 'https://blockchain.ramestta.com',
    relayManagerAddress: process.env.RELAY_MANAGER_ADDRESS || '0xF78F840eF0e321512b09e98C76eA0229Affc4b73',
    hubFeePercent: parseInt(process.env.HUB_FEE_PERCENT || '10'),
};

// ============ Types ============

interface ConnectedNode {
    nodeId: string;
    walletAddress: string;
    tunnelId: string;
    socket: WebSocket;
    connectedAt: Date;
    lastHeartbeat: Date;
    connectedUsers: Map<string, WebSocket>;  // userId -> user socket
    stats: {
        messagesRelayed: number;
        bytesTransferred: number;
    };
}

interface ConnectedUser {
    sessionId: string;
    walletAddress?: string;
    nodeId: string;  // Which node they're connected through
    socket: WebSocket;
    connectedAt: Date;
}

// ============ State ============

const connectedNodes: Map<string, ConnectedNode> = new Map();  // tunnelId -> node
const nodeByWallet: Map<string, string> = new Map();  // wallet -> tunnelId
const connectedUsers: Map<string, ConnectedUser> = new Map();  // sessionId -> user

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
        uptime: process.uptime()
    });
});

// Get hub stats
app.get('/api/stats', (req, res) => {
    const nodeStats = Array.from(connectedNodes.values()).map(node => ({
        tunnelId: node.tunnelId,
        endpoint: `${config.domain}/node/${node.tunnelId}`,
        connectedUsers: node.connectedUsers.size,
        messagesRelayed: node.stats.messagesRelayed,
        connectedAt: node.connectedAt,
        lastHeartbeat: node.lastHeartbeat
    }));

    res.json({
        totalNodes: connectedNodes.size,
        totalUsers: connectedUsers.size,
        hubFeePercent: config.hubFeePercent,
        nodes: nodeStats
    });
});

// Get available endpoints for users
app.get('/api/endpoints', (req, res) => {
    const endpoints = Array.from(connectedNodes.values())
        .filter(node => Date.now() - node.lastHeartbeat.getTime() < 60000)  // Active in last minute
        .map(node => ({
            endpoint: `wss://${config.domain}/node/${node.tunnelId}`,
            tunnelId: node.tunnelId,
            users: node.connectedUsers.size,
            load: node.connectedUsers.size / 100  // Assume max 100 users per node for load balancing
        }))
        .sort((a, b) => a.load - b.load);  // Sort by load (least loaded first)

    res.json({ endpoints });
});

// ============ HTTP Server ============

const server = createServer(app);

// ============ WebSocket Server ============

const wss = new WebSocketServer({ server });

wss.on('connection', (ws: WebSocket, req) => {
    const url = req.url || '/';
    
    console.log(`[Hub] New connection: ${url}`);

    // Route based on URL
    if (url === '/node/connect') {
        // This is a NODE connecting to establish tunnel
        handleNodeConnection(ws);
    } else if (url.startsWith('/node/')) {
        // This is a USER connecting to a specific node
        const tunnelId = url.replace('/node/', '');
        handleUserConnection(ws, tunnelId);
    } else if (url === '/user/connect') {
        // User wants to connect to best available node
        handleAutoUserConnection(ws);
    } else {
        ws.close(4000, 'Invalid endpoint');
    }
});

// ============ Node Connection Handler ============

function handleNodeConnection(ws: WebSocket) {
    let node: ConnectedNode | null = null;
    const tunnelId = uuidv4().slice(0, 8);  // Short unique ID

    ws.on('message', async (data: Buffer) => {
        try {
            const message = JSON.parse(data.toString());

            switch (message.type) {
                case 'NODE_AUTH':
                    // Node authenticating with wallet signature
                    const { walletAddress, signature, nodeId } = message;
                    
                    // TODO: Verify signature
                    // For now, just accept
                    
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
                        }
                    };
                    
                    connectedNodes.set(tunnelId, node);
                    nodeByWallet.set(walletAddress, tunnelId);
                    
                    // Send endpoint to node
                    ws.send(JSON.stringify({
                        type: 'TUNNEL_ESTABLISHED',
                        tunnelId,
                        endpoint: `wss://${config.domain}/node/${tunnelId}`,
                        httpEndpoint: `https://${config.domain}/node/${tunnelId}`,
                        hubFeePercent: config.hubFeePercent,
                        message: 'Your node is now accessible! Share this endpoint or register it on blockchain.'
                    }));
                    
                    console.log(`[Hub] Node connected: ${tunnelId} (wallet: ${walletAddress})`);
                    break;

                case 'HEARTBEAT':
                    if (node) {
                        node.lastHeartbeat = new Date();
                        ws.send(JSON.stringify({ type: 'HEARTBEAT_ACK' }));
                    }
                    break;

                case 'MESSAGE_TO_USER':
                    // Node sending message to a user
                    if (node) {
                        const userSocket = node.connectedUsers.get(message.userId);
                        if (userSocket && userSocket.readyState === WebSocket.OPEN) {
                            userSocket.send(JSON.stringify(message.payload));
                            node.stats.messagesRelayed++;
                            node.stats.bytesTransferred += data.length;
                        }
                    }
                    break;

                case 'BROADCAST':
                    // Node broadcasting to all connected users
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
            // Notify all users connected through this node
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

    // Notify node of new user
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

    console.log(`[Hub] User connected to node ${tunnelId}: ${sessionId}`);

    ws.on('message', (data: Buffer) => {
        // Forward all user messages to the node
        if (node.socket.readyState === WebSocket.OPEN) {
            node.socket.send(JSON.stringify({
                type: 'MESSAGE_FROM_USER',
                sessionId,
                payload: JSON.parse(data.toString())
            }));
            node.stats.messagesRelayed++;
            node.stats.bytesTransferred += data.length;
        }
    });

    ws.on('close', () => {
        connectedUsers.delete(sessionId);
        node.connectedUsers.delete(sessionId);
        
        // Notify node
        if (node.socket.readyState === WebSocket.OPEN) {
            node.socket.send(JSON.stringify({
                type: 'USER_DISCONNECTED',
                sessionId
            }));
        }
        
        console.log(`[Hub] User disconnected: ${sessionId}`);
    });
}

// ============ Auto User Connection (Load Balanced) ============

function handleAutoUserConnection(ws: WebSocket) {
    // Find least loaded active node
    let bestNode: ConnectedNode | null = null;
    let minLoad = Infinity;

    for (const node of connectedNodes.values()) {
        // Check if node is active (heartbeat in last 60 seconds)
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

    // Connect user to the best node
    handleUserConnection(ws, bestNode.tunnelId);
}

// ============ Start Server ============

server.listen(config.port, () => {
    console.log('═'.repeat(70));
    console.log('   MumbleChat Relay Hub - Managed Endpoint Service');
    console.log('═'.repeat(70));
    console.log(`   Server running on port ${config.port}`);
    console.log(`   Domain: ${config.domain}`);
    console.log(`   Hub fee: ${config.hubFeePercent}%`);
    console.log('');
    console.log('   Endpoints:');
    console.log(`   • Node tunnel: ws://localhost:${config.port}/node/connect`);
    console.log(`   • User connect: ws://localhost:${config.port}/user/connect`);
    console.log(`   • User to node: ws://localhost:${config.port}/node/{tunnelId}`);
    console.log(`   • API stats: http://localhost:${config.port}/api/stats`);
    console.log(`   • API endpoints: http://localhost:${config.port}/api/endpoints`);
    console.log('═'.repeat(70));
});

// ============ Graceful Shutdown ============

process.on('SIGINT', () => {
    console.log('\n[Hub] Shutting down...');
    
    // Notify all nodes
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
