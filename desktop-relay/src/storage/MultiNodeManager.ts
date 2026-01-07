/**
 * MumbleChat Desktop Relay - Multi-Node Manager
 * 
 * Manages multiple relay nodes running on the same machine
 * with different wallet addresses and isolated storage.
 * 
 * Features:
 * - Run multiple nodes with different wallets
 * - Isolated storage per node
 * - Shared machine resources tracking
 * - Port allocation management
 */

import { StorageManager } from './StorageManager';
import { getLogger } from '../utils/logger';
import fs from 'fs';
import path from 'path';

export interface NodeInstance {
    nodeId: string;
    walletAddress: string;
    privateKey: string;  // Encrypted or reference to keystore
    tier: 'BRONZE' | 'SILVER' | 'GOLD' | 'PLATINUM';
    storageMB: number;
    port: number;
    wsPort: number;
    apiPort: number;
    dataPath: string;
    status: 'stopped' | 'starting' | 'running' | 'error';
    pid?: number;
    endpoint?: string;
}

export interface MultiNodeConfig {
    baseDataPath: string;
    basePort: number;        // First node uses this, subsequent +10
    maxNodesPerMachine: number;
}

const DEFAULT_CONFIG: MultiNodeConfig = {
    baseDataPath: './data',
    basePort: 19370,
    maxNodesPerMachine: 10
};

export class MultiNodeManager {
    private logger = getLogger();
    private config: MultiNodeConfig;
    private storageManager: StorageManager;
    private nodes: Map<string, NodeInstance> = new Map();
    private configPath: string;

    constructor(config: Partial<MultiNodeConfig> = {}) {
        this.config = { ...DEFAULT_CONFIG, ...config };
        this.storageManager = new StorageManager(this.config.baseDataPath);
        this.configPath = path.join(this.config.baseDataPath, 'nodes-config.json');
        
        this.loadConfig();
    }

    /**
     * Load existing node configurations
     */
    private loadConfig(): void {
        try {
            if (fs.existsSync(this.configPath)) {
                const data = JSON.parse(fs.readFileSync(this.configPath, 'utf8'));
                for (const node of data.nodes || []) {
                    this.nodes.set(node.nodeId, { ...node, status: 'stopped' });
                }
                this.logger.info(`Loaded ${this.nodes.size} node configuration(s)`);
            }
        } catch (error) {
            this.logger.error('Failed to load node config:', error);
        }
    }

    /**
     * Save node configurations
     */
    private saveConfig(): void {
        try {
            const data = {
                machineId: this.storageManager.getMachineIdHash(),
                lastUpdated: new Date().toISOString(),
                nodes: Array.from(this.nodes.values()).map(n => ({
                    nodeId: n.nodeId,
                    walletAddress: n.walletAddress,
                    tier: n.tier,
                    storageMB: n.storageMB,
                    port: n.port,
                    wsPort: n.wsPort,
                    apiPort: n.apiPort,
                    dataPath: n.dataPath
                    // Don't save privateKey or status
                }))
            };
            
            fs.writeFileSync(this.configPath, JSON.stringify(data, null, 2));
        } catch (error) {
            this.logger.error('Failed to save node config:', error);
        }
    }

    /**
     * Get next available ports for a new node
     */
    private getNextPorts(): { port: number; wsPort: number; apiPort: number } {
        const usedPorts = new Set<number>();
        
        for (const node of this.nodes.values()) {
            usedPorts.add(node.port);
            usedPorts.add(node.wsPort);
            usedPorts.add(node.apiPort);
        }
        
        let basePort = this.config.basePort;
        
        while (usedPorts.has(basePort) || usedPorts.has(basePort + 1) || usedPorts.has(basePort + 10)) {
            basePort += 100; // Each node gets a range of 100 ports
        }
        
        return {
            port: basePort,
            wsPort: basePort + 1,
            apiPort: basePort + 10
        };
    }

    /**
     * Add a new node configuration
     */
    addNode(
        nodeId: string,
        walletAddress: string,
        privateKey: string,
        tier: NodeInstance['tier'],
        storageMB: number
    ): NodeInstance {
        // Check limits
        if (this.nodes.size >= this.config.maxNodesPerMachine) {
            throw new Error(`Maximum ${this.config.maxNodesPerMachine} nodes per machine exceeded`);
        }
        
        // Check storage availability
        const storageCheck = this.storageManager.canAllocateStorage(storageMB);
        if (!storageCheck.canAllocate) {
            throw new Error(storageCheck.reason);
        }
        
        // Check if node already exists
        if (this.nodes.has(nodeId)) {
            throw new Error(`Node ${nodeId} already configured`);
        }
        
        // Get ports
        const ports = this.getNextPorts();
        
        // Initialize storage
        const dataPath = this.storageManager.initNodeStorage(nodeId, storageMB);
        
        // Create node instance
        const node: NodeInstance = {
            nodeId,
            walletAddress,
            privateKey,
            tier,
            storageMB,
            port: ports.port,
            wsPort: ports.wsPort,
            apiPort: ports.apiPort,
            dataPath,
            status: 'stopped'
        };
        
        this.nodes.set(nodeId, node);
        this.saveConfig();
        
        this.logger.info(`Added node ${nodeId}`);
        this.logger.info(`  Wallet: ${walletAddress}`);
        this.logger.info(`  Tier: ${tier}`);
        this.logger.info(`  Storage: ${storageMB}MB`);
        this.logger.info(`  Ports: ${ports.port} (P2P), ${ports.wsPort} (WS), ${ports.apiPort} (API)`);
        
        return node;
    }

    /**
     * Remove a node configuration
     */
    removeNode(nodeId: string, cleanupStorage: boolean = false): void {
        const node = this.nodes.get(nodeId);
        
        if (!node) {
            throw new Error(`Node ${nodeId} not found`);
        }
        
        if (node.status === 'running') {
            throw new Error(`Node ${nodeId} is still running. Stop it first.`);
        }
        
        if (cleanupStorage) {
            this.storageManager.cleanupNodeStorage(nodeId);
        }
        
        this.nodes.delete(nodeId);
        this.saveConfig();
        
        this.logger.info(`Removed node ${nodeId}`);
    }

    /**
     * Get all configured nodes
     */
    getNodes(): NodeInstance[] {
        return Array.from(this.nodes.values());
    }

    /**
     * Get a specific node
     */
    getNode(nodeId: string): NodeInstance | undefined {
        return this.nodes.get(nodeId);
    }

    /**
     * Get machine storage info
     */
    getMachineInfo() {
        return this.storageManager.getMachineStorageInfo();
    }

    /**
     * Get machine ID hash for blockchain registration
     */
    getMachineIdHash(): string {
        return this.storageManager.getMachineIdHash();
    }

    /**
     * Get recommended tier for new node
     */
    getRecommendedTier() {
        return this.storageManager.getRecommendedTier();
    }

    /**
     * Print summary of all nodes
     */
    printSummary(): void {
        const machineInfo = this.storageManager.getMachineStorageInfo();
        
        console.log('\n' + '═'.repeat(70));
        console.log('   MULTI-NODE MANAGER - STATUS');
        console.log('═'.repeat(70));
        console.log(`   Machine ID: ${machineInfo.machineId}`);
        console.log(`   Machine ID Hash: ${this.getMachineIdHash().slice(0, 18)}...`);
        console.log('');
        console.log(`   Disk Total: ${machineInfo.disk.totalGB.toFixed(1)} GB`);
        console.log(`   Disk Free:  ${machineInfo.disk.freeGB.toFixed(1)} GB`);
        console.log(`   Available for nodes: ${(machineInfo.availableForNewNodesMB / 1024).toFixed(2)} GB`);
        console.log('');
        console.log(`   Configured Nodes: ${this.nodes.size}/${this.config.maxNodesPerMachine}`);
        console.log('');
        
        if (this.nodes.size > 0) {
            console.log('   ┌──────────────────────────────────────────────────────────────┐');
            console.log('   │ NODE ID      │ WALLET         │ TIER     │ STORAGE │ STATUS │');
            console.log('   ├──────────────────────────────────────────────────────────────┤');
            
            for (const node of this.nodes.values()) {
                const nodeIdShort = node.nodeId.slice(0, 8);
                const walletShort = node.walletAddress.slice(0, 10) + '...';
                const storage = `${(node.storageMB / 1024).toFixed(1)}GB`;
                const status = node.status === 'running' ? '🟢' : '⚪';
                
                console.log(`   │ ${nodeIdShort.padEnd(12)} │ ${walletShort.padEnd(14)} │ ${node.tier.padEnd(8)} │ ${storage.padEnd(7)} │ ${status}      │`);
            }
            
            console.log('   └──────────────────────────────────────────────────────────────┘');
        } else {
            console.log('   No nodes configured yet.');
            console.log('');
            console.log('   To add a node:');
            console.log('   1. Register on blockchain with stake');
            console.log('   2. Add node to this manager');
            console.log('   3. Start the node');
        }
        
        const rec = this.storageManager.getRecommendedTier();
        console.log('');
        console.log(`   Recommended tier for new node: ${rec.tier} (${rec.storageMB}MB)`);
        console.log('═'.repeat(70) + '\n');
    }
}

export default MultiNodeManager;
