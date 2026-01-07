/**
 * MumbleChat Desktop Relay - Hub Connection Service
 * 
 * For MANAGED mode: connects to MumbleChat Hub to get a public endpoint
 * without needing to know about IPs, ports, or port forwarding.
 * 
 * How it works:
 * 1. Node connects to hub (outbound connection - works behind any NAT/firewall)
 * 2. Hub assigns a unique endpoint: hub.mumblechat.io/node/{tunnelId}
 * 3. Users connect to hub, hub tunnels traffic to this node
 * 4. Hub takes 10% fee from rewards (configurable)
 */

import { EventEmitter } from 'events';
import WebSocket from 'ws';
import { getLogger } from '../utils/logger';

export interface HubConfig {
    url: string;
    walletAddress: string;
    nodeId?: string;
    reconnectIntervalMs: number;
}

export interface TunnelInfo {
    tunnelId: string;
    endpoint: string;
    httpEndpoint: string;
    hubFeePercent: number;
}

export class HubConnectionService extends EventEmitter {
    private config: HubConfig;
    private ws: WebSocket | null = null;
    private logger = getLogger();
    private tunnelInfo: TunnelInfo | null = null;
    private isConnected: boolean = false;
    private reconnectTimer: NodeJS.Timeout | null = null;
    private heartbeatTimer: NodeJS.Timeout | null = null;

    constructor(config: HubConfig) {
        super();
        this.config = config;
    }

    /**
     * Connect to the hub
     */
    async connect(): Promise<TunnelInfo> {
        return new Promise((resolve, reject) => {
            this.logger.info(`Connecting to MumbleChat Hub: ${this.config.url}`);
            
            this.ws = new WebSocket(this.config.url);
            
            const timeout = setTimeout(() => {
                reject(new Error('Hub connection timeout'));
            }, 30000);

            this.ws.on('open', () => {
                this.logger.info('Connected to hub, authenticating...');
                
                // Send authentication
                this.ws!.send(JSON.stringify({
                    type: 'NODE_AUTH',
                    walletAddress: this.config.walletAddress,
                    nodeId: this.config.nodeId,
                    signature: '' // TODO: Add signature for security
                }));
            });

            this.ws.on('message', (data: Buffer) => {
                try {
                    const message = JSON.parse(data.toString());
                    
                    switch (message.type) {
                        case 'TUNNEL_ESTABLISHED':
                            clearTimeout(timeout);
                            this.isConnected = true;
                            this.tunnelInfo = {
                                tunnelId: message.tunnelId,
                                endpoint: message.endpoint,
                                httpEndpoint: message.httpEndpoint,
                                hubFeePercent: message.hubFeePercent
                            };
                            
                            this.logger.info('');
                            this.logger.info('═'.repeat(60));
                            this.logger.info('   🎉 TUNNEL ESTABLISHED!');
                            this.logger.info('═'.repeat(60));
                            this.logger.info(`   Your public endpoint: ${this.tunnelInfo.endpoint}`);
                            this.logger.info(`   Hub fee: ${this.tunnelInfo.hubFeePercent}%`);
                            this.logger.info('');
                            this.logger.info('   ✅ No port forwarding needed!');
                            this.logger.info('   ✅ Works behind any NAT/firewall');
                            this.logger.info('   ✅ Users can connect to you now');
                            this.logger.info('═'.repeat(60));
                            this.logger.info('');
                            
                            // Start heartbeat
                            this.startHeartbeat();
                            
                            this.emit('connected', this.tunnelInfo);
                            resolve(this.tunnelInfo);
                            break;
                            
                        case 'HEARTBEAT_ACK':
                            // Heartbeat acknowledged
                            break;
                            
                        case 'USER_CONNECTED':
                            this.logger.info(`User connected: ${message.sessionId}`);
                            this.emit('userConnected', message.sessionId);
                            break;
                            
                        case 'USER_DISCONNECTED':
                            this.logger.info(`User disconnected: ${message.sessionId}`);
                            this.emit('userDisconnected', message.sessionId);
                            break;
                            
                        case 'MESSAGE_FROM_USER':
                            // User sent a message through the tunnel
                            this.emit('message', {
                                sessionId: message.sessionId,
                                payload: message.payload
                            });
                            break;
                            
                        case 'HUB_SHUTDOWN':
                            this.logger.warn('Hub is shutting down');
                            this.emit('hubShutdown');
                            break;
                            
                        case 'ERROR':
                            this.logger.error(`Hub error: ${message.message}`);
                            this.emit('error', new Error(message.message));
                            break;
                    }
                } catch (error) {
                    this.logger.error('Error parsing hub message:', error);
                }
            });

            this.ws.on('close', () => {
                this.isConnected = false;
                this.stopHeartbeat();
                this.logger.warn('Disconnected from hub');
                this.emit('disconnected');
                
                // Auto-reconnect
                this.scheduleReconnect();
            });

            this.ws.on('error', (error) => {
                clearTimeout(timeout);
                this.logger.error('Hub connection error:', error);
                
                if (!this.isConnected) {
                    reject(error);
                }
            });
        });
    }

    /**
     * Send message to a user through the tunnel
     */
    sendToUser(userId: string, payload: any): void {
        if (!this.ws || !this.isConnected) {
            this.logger.error('Not connected to hub');
            return;
        }

        this.ws.send(JSON.stringify({
            type: 'MESSAGE_TO_USER',
            userId,
            payload
        }));
    }

    /**
     * Broadcast message to all connected users
     */
    broadcast(payload: any): void {
        if (!this.ws || !this.isConnected) {
            this.logger.error('Not connected to hub');
            return;
        }

        this.ws.send(JSON.stringify({
            type: 'BROADCAST',
            payload
        }));
    }

    /**
     * Get the assigned tunnel endpoint
     */
    getEndpoint(): string | null {
        return this.tunnelInfo?.endpoint || null;
    }

    /**
     * Get tunnel info
     */
    getTunnelInfo(): TunnelInfo | null {
        return this.tunnelInfo;
    }

    /**
     * Check if connected
     */
    connected(): boolean {
        return this.isConnected;
    }

    /**
     * Disconnect from hub
     */
    disconnect(): void {
        this.stopHeartbeat();
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }
        if (this.ws) {
            this.ws.close();
            this.ws = null;
        }
        this.isConnected = false;
    }

    // ============ Private Methods ============

    private startHeartbeat(): void {
        this.heartbeatTimer = setInterval(() => {
            if (this.ws && this.isConnected) {
                this.ws.send(JSON.stringify({ type: 'HEARTBEAT' }));
            }
        }, 30000); // 30 seconds
    }

    private stopHeartbeat(): void {
        if (this.heartbeatTimer) {
            clearInterval(this.heartbeatTimer);
            this.heartbeatTimer = null;
        }
    }

    private scheduleReconnect(): void {
        if (this.reconnectTimer) return;
        
        this.logger.info(`Reconnecting to hub in ${this.config.reconnectIntervalMs / 1000}s...`);
        
        this.reconnectTimer = setTimeout(async () => {
            this.reconnectTimer = null;
            try {
                await this.connect();
            } catch (error) {
                this.logger.error('Reconnection failed:', error);
                this.scheduleReconnect();
            }
        }, this.config.reconnectIntervalMs);
    }
}

export default HubConnectionService;
