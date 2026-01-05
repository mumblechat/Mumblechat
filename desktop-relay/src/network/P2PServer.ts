/**
 * MumbleChat Desktop Relay Node - P2P Server
 * 
 * TCP/WebSocket server for peer-to-peer messaging
 * Based on MumbleChat Protocol documentation (03_MESSAGING_PROTOCOL.md)
 */

import net from 'net';
import { WebSocket, WebSocketServer } from 'ws';
import { EventEmitter } from 'events';
import { getLogger } from '../utils/logger';
import { deriveNodeId, computeKeyHash, verifySignature, generateMessageId } from '../utils/crypto';
import { MessageType, NetworkConfig } from '../config';

export interface PeerInfo {
  id: string;
  nodeId: Buffer;
  walletAddress: string;
  socket: net.Socket | WebSocket;
  lastSeen: number;
  isAuthenticated: boolean;
  endpoint?: string;
}

export interface IncomingMessage {
  messageId: string;
  senderAddress: string;
  recipientAddress: string;
  encryptedBlob: Buffer;
  timestamp: number;
  signature: string;
}

export interface RelayRequest {
  messageId: string;
  recipientKeyHash: string;
  senderKeyHash: string;
  encryptedBlob: Buffer;
  ttlDays: number;
  timestamp: number;
  signature: string;
}

export class P2PServer extends EventEmitter {
  private tcpServer: net.Server | null = null;
  private wsServer: WebSocketServer | null = null;
  private peers: Map<string, PeerInfo> = new Map();
  private messageCache: Map<string, number> = new Map();
  private nodeId: Buffer;
  private walletAddress: string;
  private logger = getLogger();

  private host: string;
  private port: number;
  private maxConnections: number;

  constructor(host: string, port: number, walletAddress: string, maxConnections: number = 200) {
    super();
    this.host = host;
    this.port = port;
    this.walletAddress = walletAddress;
    this.maxConnections = maxConnections;
    this.nodeId = deriveNodeId(walletAddress);
  }

  /**
   * Start TCP and WebSocket servers
   */
  async start(): Promise<void> {
    await this.startTCPServer();
    await this.startWebSocketServer();
    this.startMaintenanceLoop();
    this.logger.info(`P2P Server started on ${this.host}:${this.port}`);
  }

  /**
   * Start TCP server
   */
  private startTCPServer(): Promise<void> {
    return new Promise((resolve, reject) => {
      this.tcpServer = net.createServer((socket) => {
        this.handleTCPConnection(socket);
      });

      this.tcpServer.on('error', (err) => {
        this.logger.error('TCP Server error:', err);
        reject(err);
      });

      this.tcpServer.listen(this.port, this.host, () => {
        this.logger.info(`TCP Server listening on ${this.host}:${this.port}`);
        resolve();
      });
    });
  }

  /**
   * Start WebSocket server (for browser/cross-platform compatibility)
   */
  private startWebSocketServer(): Promise<void> {
    return new Promise((resolve) => {
      this.wsServer = new WebSocketServer({ 
        port: this.port + 1,
        host: this.host,
      });

      this.wsServer.on('connection', (ws, req) => {
        this.handleWebSocketConnection(ws, req);
      });

      this.wsServer.on('error', (err) => {
        this.logger.error('WebSocket Server error:', err);
      });

      this.logger.info(`WebSocket Server listening on ${this.host}:${this.port + 1}`);
      resolve();
    });
  }

  /**
   * Handle incoming TCP connection
   */
  private handleTCPConnection(socket: net.Socket): void {
    const remoteAddress = `${socket.remoteAddress}:${socket.remotePort}`;
    this.logger.debug(`New TCP connection from ${remoteAddress}`);

    if (this.peers.size >= this.maxConnections) {
      this.logger.warn(`Max connections reached, rejecting ${remoteAddress}`);
      socket.end();
      return;
    }

    const peerId = generateMessageId();
    const peer: PeerInfo = {
      id: peerId,
      nodeId: Buffer.alloc(32),
      walletAddress: '',
      socket,
      lastSeen: Date.now(),
      isAuthenticated: false,
      endpoint: remoteAddress,
    };

    this.peers.set(peerId, peer);

    let buffer = Buffer.alloc(0);

    socket.on('data', (data) => {
      buffer = Buffer.concat([buffer, data]);
      this.processBuffer(peer, buffer, (consumed) => {
        buffer = buffer.slice(consumed);
      });
    });

    socket.on('close', () => {
      this.peers.delete(peerId);
      this.logger.debug(`TCP connection closed: ${remoteAddress}`);
    });

    socket.on('error', (err) => {
      this.logger.error(`TCP socket error (${remoteAddress}):`, err);
      this.peers.delete(peerId);
    });
  }

  /**
   * Handle incoming WebSocket connection
   */
  private handleWebSocketConnection(ws: WebSocket, req: any): void {
    const remoteAddress = req.socket.remoteAddress;
    this.logger.debug(`New WebSocket connection from ${remoteAddress}`);

    if (this.peers.size >= this.maxConnections) {
      this.logger.warn(`Max connections reached, rejecting ${remoteAddress}`);
      ws.close();
      return;
    }

    const peerId = generateMessageId();
    const peer: PeerInfo = {
      id: peerId,
      nodeId: Buffer.alloc(32),
      walletAddress: '',
      socket: ws,
      lastSeen: Date.now(),
      isAuthenticated: false,
      endpoint: remoteAddress,
    };

    this.peers.set(peerId, peer);

    ws.on('message', (data) => {
      const buffer = Buffer.isBuffer(data) ? data : Buffer.from(data as ArrayBuffer);
      this.processBuffer(peer, buffer, () => {});
    });

    ws.on('close', () => {
      this.peers.delete(peerId);
      this.logger.debug(`WebSocket connection closed: ${remoteAddress}`);
    });

    ws.on('error', (err) => {
      this.logger.error(`WebSocket error (${remoteAddress}):`, err);
      this.peers.delete(peerId);
    });
  }

  /**
   * Process incoming data buffer
   */
  private processBuffer(peer: PeerInfo, buffer: Buffer, consumed: (bytes: number) => void): void {
    if (buffer.length < 5) return; // Minimum: type (1) + length (4)

    const type = buffer.readUInt8(0);
    const length = buffer.readUInt32BE(1);

    if (buffer.length < 5 + length) return; // Wait for more data

    const payload = buffer.slice(5, 5 + length);
    consumed(5 + length);

    peer.lastSeen = Date.now();
    this.handleMessage(peer, type, payload);
  }

  /**
   * Handle protocol message
   */
  private handleMessage(peer: PeerInfo, type: number, payload: Buffer): void {
    switch (type) {
      case MessageType.HANDSHAKE:
        this.handleHandshake(peer, payload);
        break;
      case MessageType.PING:
        this.handlePing(peer, payload);
        break;
      case MessageType.MESSAGE:
        this.handleChatMessage(peer, payload);
        break;
      case MessageType.RELAY_REQUEST:
        this.handleRelayRequest(peer, payload);
        break;
      case MessageType.PEER_EXCHANGE:
        this.handlePeerExchange(peer, payload);
        break;
      case MessageType.DHT_FIND_NODE:
        this.handleDHTFindNode(peer, payload);
        break;
      case MessageType.SYNC_REQUEST:
        this.handleSyncRequest(peer, payload);
        break;
      default:
        this.logger.warn(`Unknown message type: ${type}`);
    }
  }

  /**
   * Handle handshake message
   */
  private handleHandshake(peer: PeerInfo, payload: Buffer): void {
    try {
      const data = JSON.parse(payload.toString());
      
      // Verify signature
      const message = Buffer.from(`handshake:${data.walletAddress}:${data.timestamp}`);
      if (!verifySignature(message, data.signature, data.walletAddress)) {
        this.logger.warn(`Invalid handshake signature from ${peer.endpoint}`);
        this.disconnectPeer(peer.id);
        return;
      }

      // Update peer info
      peer.walletAddress = data.walletAddress;
      peer.nodeId = deriveNodeId(data.walletAddress);
      peer.isAuthenticated = true;

      // Send handshake ack
      const ack = {
        walletAddress: this.walletAddress,
        nodeId: this.nodeId.toString('hex'),
        timestamp: Date.now(),
      };
      this.sendMessage(peer, MessageType.HANDSHAKE_ACK, Buffer.from(JSON.stringify(ack)));

      this.logger.info(`Peer authenticated: ${data.walletAddress}`);
      this.emit('peerConnected', peer);
    } catch (err) {
      this.logger.error('Failed to process handshake:', err);
    }
  }

  /**
   * Handle ping message
   */
  private handlePing(peer: PeerInfo, payload: Buffer): void {
    // Echo back as pong
    this.sendMessage(peer, MessageType.PONG, payload);
  }

  /**
   * Handle chat message (for routing/relay)
   */
  private handleChatMessage(peer: PeerInfo, payload: Buffer): void {
    if (!peer.isAuthenticated) {
      this.logger.warn(`Unauthenticated peer tried to send message: ${peer.endpoint}`);
      return;
    }

    try {
      const data = JSON.parse(payload.toString());
      
      // Check for duplicate (message cache)
      if (this.messageCache.has(data.messageId)) {
        return;
      }
      this.messageCache.set(data.messageId, Date.now());

      const message: IncomingMessage = {
        messageId: data.messageId,
        senderAddress: peer.walletAddress,
        recipientAddress: data.recipientAddress,
        encryptedBlob: Buffer.from(data.encryptedBlob, 'base64'),
        timestamp: data.timestamp,
        signature: data.signature,
      };

      this.emit('message', message);
    } catch (err) {
      this.logger.error('Failed to process chat message:', err);
    }
  }

  /**
   * Handle relay request (store-and-forward)
   */
  private handleRelayRequest(peer: PeerInfo, payload: Buffer): void {
    if (!peer.isAuthenticated) {
      this.logger.warn(`Unauthenticated peer tried to relay: ${peer.endpoint}`);
      return;
    }

    try {
      const data = JSON.parse(payload.toString());

      const request: RelayRequest = {
        messageId: data.messageId,
        recipientKeyHash: data.recipientKeyHash,
        senderKeyHash: computeKeyHash(peer.walletAddress),
        encryptedBlob: Buffer.from(data.encryptedBlob, 'base64'),
        ttlDays: data.ttlDays || 7,
        timestamp: data.timestamp,
        signature: data.signature,
      };

      this.emit('relayRequest', request);

      // Send relay response
      const response = {
        messageId: data.messageId,
        accepted: true,
        timestamp: Date.now(),
      };
      this.sendMessage(peer, MessageType.RELAY_RESPONSE, Buffer.from(JSON.stringify(response)));
    } catch (err) {
      this.logger.error('Failed to process relay request:', err);
    }
  }

  /**
   * Handle peer exchange (DHT)
   */
  private handlePeerExchange(peer: PeerInfo, payload: Buffer): void {
    try {
      const data = JSON.parse(payload.toString());
      this.emit('peerExchange', { peer, peers: data.peers });
    } catch (err) {
      this.logger.error('Failed to process peer exchange:', err);
    }
  }

  /**
   * Handle DHT find node request
   */
  private handleDHTFindNode(peer: PeerInfo, payload: Buffer): void {
    try {
      const data = JSON.parse(payload.toString());
      this.emit('dhtFindNode', { peer, targetNodeId: data.targetNodeId });
    } catch (err) {
      this.logger.error('Failed to process DHT find node:', err);
    }
  }

  /**
   * Handle sync request (fetch pending messages)
   */
  private handleSyncRequest(peer: PeerInfo, payload: Buffer): void {
    if (!peer.isAuthenticated) {
      return;
    }

    try {
      const data = JSON.parse(payload.toString());
      this.emit('syncRequest', { peer, lastSyncTime: data.lastSyncTime });
    } catch (err) {
      this.logger.error('Failed to process sync request:', err);
    }
  }

  /**
   * Send message to peer
   */
  sendMessage(peer: PeerInfo, type: MessageType, payload: Buffer): void {
    const header = Buffer.alloc(5);
    header.writeUInt8(type, 0);
    header.writeUInt32BE(payload.length, 1);
    const packet = Buffer.concat([header, payload]);

    if (peer.socket instanceof net.Socket) {
      peer.socket.write(packet);
    } else if (peer.socket instanceof WebSocket) {
      peer.socket.send(packet);
    }
  }

  /**
   * Send message to specific wallet address
   */
  sendToAddress(walletAddress: string, type: MessageType, payload: Buffer): boolean {
    const peer = this.findPeerByAddress(walletAddress);
    if (peer) {
      this.sendMessage(peer, type, payload);
      return true;
    }
    return false;
  }

  /**
   * Broadcast message to all authenticated peers
   */
  broadcast(type: MessageType, payload: Buffer, exclude?: string[]): void {
    for (const [peerId, peer] of this.peers) {
      if (peer.isAuthenticated && !exclude?.includes(peerId)) {
        this.sendMessage(peer, type, payload);
      }
    }
  }

  /**
   * Find peer by wallet address
   */
  findPeerByAddress(walletAddress: string): PeerInfo | null {
    for (const peer of this.peers.values()) {
      if (peer.walletAddress.toLowerCase() === walletAddress.toLowerCase()) {
        return peer;
      }
    }
    return null;
  }

  /**
   * Check if peer is connected
   */
  isPeerOnline(walletAddress: string): boolean {
    return this.findPeerByAddress(walletAddress) !== null;
  }

  /**
   * Get all online peers
   */
  getOnlinePeers(): string[] {
    return Array.from(this.peers.values())
      .filter(p => p.isAuthenticated)
      .map(p => p.walletAddress);
  }

  /**
   * Disconnect peer
   */
  disconnectPeer(peerId: string): void {
    const peer = this.peers.get(peerId);
    if (peer) {
      if (peer.socket instanceof net.Socket) {
        peer.socket.end();
      } else if (peer.socket instanceof WebSocket) {
        peer.socket.close();
      }
      this.peers.delete(peerId);
    }
  }

  /**
   * Start maintenance loop (cleanup, pings)
   */
  private startMaintenanceLoop(): void {
    setInterval(() => {
      const now = Date.now();

      // Ping peers and remove stale ones
      for (const [peerId, peer] of this.peers) {
        if (now - peer.lastSeen > NetworkConfig.PEER_TIMEOUT_MS) {
          this.logger.debug(`Removing stale peer: ${peer.walletAddress || peer.endpoint}`);
          this.disconnectPeer(peerId);
        } else if (now - peer.lastSeen > NetworkConfig.PING_INTERVAL_MS) {
          this.sendMessage(peer, MessageType.PING, Buffer.from(Date.now().toString()));
        }
      }

      // Clean old message cache entries
      for (const [msgId, timestamp] of this.messageCache) {
        if (now - timestamp > 60 * 60 * 1000) { // 1 hour
          this.messageCache.delete(msgId);
        }
      }
    }, NetworkConfig.PING_INTERVAL_MS);
  }

  /**
   * Get peer count
   */
  getPeerCount(): number {
    return this.peers.size;
  }

  /**
   * Get authenticated peer count
   */
  getAuthenticatedPeerCount(): number {
    return Array.from(this.peers.values()).filter(p => p.isAuthenticated).length;
  }

  /**
   * Stop servers
   */
  async stop(): Promise<void> {
    // Close all peer connections
    for (const peer of this.peers.values()) {
      if (peer.socket instanceof net.Socket) {
        peer.socket.end();
      } else if (peer.socket instanceof WebSocket) {
        peer.socket.close();
      }
    }
    this.peers.clear();

    // Close servers
    if (this.tcpServer) {
      this.tcpServer.close();
    }
    if (this.wsServer) {
      this.wsServer.close();
    }

    this.logger.info('P2P Server stopped');
  }
}
