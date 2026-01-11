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
    connectedUsers: Map<string, WebSocket>;  // sessionId -> user socket
    stats: {
        messagesRelayed: number;
        bytesTransferred: number;
    };
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

// *** NEW: Global user registry for cross-node routing ***
const usersByAddress: Map<string, { sessionId: string, tunnelId: string }> = new Map();  // user wallet -> session info

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
        registeredUsers: usersByAddress.size,
        hubFeePercent: config.hubFeePercent,
        nodes: nodeStats
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

// *** NEW: Check if a user is online (for cross-node status) ***
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

// ============ HTTP Server ============

const server = createServer(app);

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
                    const { walletAddress, signature, nodeId } = message;
                    
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
                    
                    ws.send(JSON.stringify({
                        type: 'TUNNEL_ESTABLISHED',
                        tunnelId,
                        endpoint: `wss://${config.domain}/node/${tunnelId}`,
                        httpEndpoint: `https://${config.domain}/node/${tunnelId}`,
                        hubFeePercent: config.hubFeePercent,
                        message: 'Your node is now accessible!'
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
                    // Node sending message to a user - CHECK CROSS-NODE
                    if (node) {
                        const { userId, payload } = message;
                        let delivered = false;
                        
                        // First try local user (on same node)
                        const userSocket = node.connectedUsers.get(userId);
                        if (userSocket && userSocket.readyState === WebSocket.OPEN) {
                            userSocket.send(JSON.stringify(payload));
                            node.stats.messagesRelayed++;
                            delivered = true;
                        }
                        
                        // *** NEW: Cross-node delivery ***
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
                                }
                            }
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

                // *** NEW: User registered their address with the node ***
                case 'USER_AUTHENTICATED':
                    if (node && message.sessionId && message.address) {
                        const addr = message.address.toLowerCase();
                        usersByAddress.set(addr, {
                            sessionId: message.sessionId,
                            tunnelId: node.tunnelId
                        });
                        console.log(`[Hub] User ${addr.slice(0,8)}... registered on node ${node.tunnelId}`);
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
            if (payload.type === 'authenticate' && payload.address) {
                const addr = payload.address.toLowerCase();
                user.walletAddress = addr;
                
                // Register in global user registry
                usersByAddress.set(addr, {
                    sessionId,
                    tunnelId
                });
                console.log(`[Hub] User ${addr.slice(0,8)}... authenticated on node ${tunnelId}`);
            }
            
            // *** NEW: Direct hub routing for relay messages ***
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

server.listen(config.port, () => {
    console.log('═'.repeat(70));
    console.log('   MumbleChat Relay Hub - WITH CROSS-NODE MESSAGING');
    console.log('═'.repeat(70));
    console.log(`   Server running on port ${config.port}`);
    console.log(`   Domain: ${config.domain}`);
    console.log(`   Hub fee: ${config.hubFeePercent}%`);
    console.log('');
    console.log('   Features:');
    console.log('   ✓ Multi-node support');
    console.log('   ✓ Cross-node message relay');
    console.log('   ✓ Global user registry');
    console.log('   ✓ Load balancing');
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
