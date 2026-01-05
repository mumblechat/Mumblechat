/**
 * MumbleChat Relay Node - Dashboard Server
 * 
 * Serves the web-based dashboard UI and API for monitoring/managing the relay node
 */

import express, { Express, Request, Response } from 'express';
import { createServer, Server } from 'http';
import path from 'path';
import { RelayServer } from '../RelayServer';
import { getLogger } from '../utils/logger';

export interface DashboardConfig {
  port: number;
  host: string;
}

export class DashboardServer {
  private app: Express;
  private server: Server | null = null;
  private relayServer: RelayServer;
  private config: DashboardConfig;
  private logger = getLogger();
  
  // Stats tracking
  private messagesRelayed: number = 0;
  private dailyRewards: number = 0;
  private transactions: any[] = [];
  
  constructor(relayServer: RelayServer, config: DashboardConfig) {
    this.relayServer = relayServer;
    this.config = config;
    this.app = express();
    this.setupMiddleware();
    this.setupRoutes();
    this.setupRelayEvents();
  }
  
  private setupMiddleware(): void {
    // JSON body parser
    this.app.use(express.json());
    
    // CORS for local development
    this.app.use((req, res, next) => {
      res.header('Access-Control-Allow-Origin', '*');
      res.header('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
      res.header('Access-Control-Allow-Headers', 'Origin, X-Requested-With, Content-Type, Accept');
      if (req.method === 'OPTIONS') {
        return res.sendStatus(200);
      }
      next();
    });
    
    // Serve static files from public directory
    const publicPath = path.join(__dirname, '../../public');
    this.app.use(express.static(publicPath));
  }
  
  private setupRoutes(): void {
    // Dashboard page
    this.app.get('/', (req: Request, res: Response) => {
      const publicPath = path.join(__dirname, '../../public/index.html');
      res.sendFile(publicPath);
    });
    
    // API: Get relay status
    this.app.get('/api/status', async (req: Request, res: Response) => {
      try {
        const stats = this.relayServer.getStats();
        res.json({
          success: true,
          data: {
            uptime: stats.uptime,
            uptimeFormatted: this.formatUptime(stats.uptime),
            peersConnected: stats.peersConnected,
            messagesRelayed: stats.messagesRelayed,
            messagesDelivered: stats.messagesDelivered,
            tier: this.getTierName(stats.tier),
            storageUsedMB: stats.storageUsedMB,
            storageMaxMB: stats.storageMaxMB,
            rewardsEarned: stats.rewardsEarned,
            isOnline: this.relayServer.isActive()
          }
        });
      } catch (error: any) {
        res.status(500).json({ success: false, error: error.message });
      }
    });
    
    // API: Get wallet info
    this.app.get('/api/wallet', async (req: Request, res: Response) => {
      try {
        // Access blockchain service through relay server events
        res.json({
          success: true,
          data: {
            address: 'Loading...',
            ramaBalance: '0',
            mctBalance: '0'
          }
        });
      } catch (error: any) {
        res.status(500).json({ success: false, error: error.message });
      }
    });
    
    // API: Get rewards info
    this.app.get('/api/rewards', async (req: Request, res: Response) => {
      try {
        res.json({
          success: true,
          data: {
            totalEarned: 0,
            dailyRewards: this.dailyRewards,
            pendingRewards: 0,
            lastClaim: null
          }
        });
      } catch (error: any) {
        res.status(500).json({ success: false, error: error.message });
      }
    });
    
    // API: Get registration status
    this.app.get('/api/registration', async (req: Request, res: Response) => {
      try {
        res.json({
          success: true,
          data: {
            isRegistered: false,
            stakedAmount: '0',
            endpoint: null,
            tier: 'bronze',
            nextPayout: null
          }
        });
      } catch (error: any) {
        res.status(500).json({ success: false, error: error.message });
      }
    });
    
    // API: Get transaction pool
    this.app.get('/api/transactions', (req: Request, res: Response) => {
      res.json({
        success: true,
        data: this.transactions.slice(0, 50)
      });
    });
    
    // API: Get activity log
    this.app.get('/api/activity', (req: Request, res: Response) => {
      res.json({
        success: true,
        data: []
      });
    });
    
    // Health check
    this.app.get('/health', (req: Request, res: Response) => {
      res.json({ 
        status: 'ok', 
        timestamp: new Date().toISOString(),
        service: 'mumblechat-relay-dashboard'
      });
    });
  }
  
  private setupRelayEvents(): void {
    // Track message relay events
    this.relayServer.on('messageRelayed', (messageId: string) => {
      this.messagesRelayed++;
    });
    
    // Track rewards (would come from blockchain events)
    this.relayServer.on('rewardEarned', (amount: number) => {
      this.dailyRewards += amount;
    });
  }
  
  private formatUptime(seconds: number): string {
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    return `${hours}h ${minutes}m`;
  }
  
  private getTierName(tier: number): string {
    switch (tier) {
      case 0: return 'bronze';
      case 1: return 'silver';
      case 2: return 'gold';
      case 3: return 'diamond';
      default: return 'bronze';
    }
  }
  
  async start(): Promise<void> {
    return new Promise((resolve, reject) => {
      try {
        this.server = createServer(this.app);
        
        this.server.listen(this.config.port, this.config.host, () => {
          this.logger.info(`Dashboard server started at http://${this.config.host}:${this.config.port}`);
          resolve();
        });
        
        this.server.on('error', (error: any) => {
          if (error.code === 'EADDRINUSE') {
            this.logger.warn(`Dashboard port ${this.config.port} in use, trying ${this.config.port + 1}`);
            this.config.port += 1;
            this.server?.listen(this.config.port, this.config.host);
          } else {
            reject(error);
          }
        });
      } catch (error) {
        reject(error);
      }
    });
  }
  
  async stop(): Promise<void> {
    return new Promise((resolve) => {
      if (this.server) {
        this.server.close(() => {
          this.logger.info('Dashboard server stopped');
          resolve();
        });
      } else {
        resolve();
      }
    });
  }
  
  getPort(): number {
    return this.config.port;
  }
}
