/**
 * MumbleChat Desktop Relay - Storage Manager
 * 
 * Handles real disk space management for relay nodes.
 * 
 * Features:
 * - Real disk space detection
 * - Per-node quota enforcement
 * - Multi-node isolation on same machine
 * - Actual usage tracking
 */

import fs from 'fs';
import path from 'path';
import os from 'os';
import { execSync } from 'child_process';
import { getLogger } from '../utils/logger';

export interface DiskInfo {
    totalBytes: number;
    freeBytes: number;
    usedBytes: number;
    totalGB: number;
    freeGB: number;
    usedGB: number;
}

export interface NodeStorageInfo {
    nodeId: string;
    dataPath: string;
    allocatedMB: number;
    usedMB: number;
    freeMB: number;
    messageCount: number;
}

export interface MachineStorageInfo {
    machineId: string;
    disk: DiskInfo;
    nodes: NodeStorageInfo[];
    totalAllocatedMB: number;
    totalUsedMB: number;
    availableForNewNodesMB: number;
}

export class StorageManager {
    private logger = getLogger();
    private baseDataPath: string;
    private machineId: string;

    constructor(baseDataPath: string = './data') {
        this.baseDataPath = path.resolve(baseDataPath);
        this.machineId = this.generateMachineId();
        
        // Ensure base data directory exists
        if (!fs.existsSync(this.baseDataPath)) {
            fs.mkdirSync(this.baseDataPath, { recursive: true });
        }
    }

    /**
     * Generate unique machine ID from hardware info
     */
    private generateMachineId(): string {
        try {
            const interfaces = os.networkInterfaces();
            let mac = '';
            
            // Get first non-internal MAC address
            for (const name of Object.keys(interfaces)) {
                for (const iface of interfaces[name] || []) {
                    if (!iface.internal && iface.mac && iface.mac !== '00:00:00:00:00:00') {
                        mac = iface.mac;
                        break;
                    }
                }
                if (mac) break;
            }
            
            // Combine with hostname for uniqueness
            const hostname = os.hostname();
            const combined = `${mac}-${hostname}`;
            
            // Create hash
            const crypto = require('crypto');
            return crypto.createHash('sha256').update(combined).digest('hex').slice(0, 16);
        } catch (error) {
            this.logger.error('Failed to generate machine ID:', error);
            return 'unknown-' + Date.now();
        }
    }

    /**
     * Get machine ID hash for blockchain registration
     */
    getMachineIdHash(): string {
        const crypto = require('crypto');
        return '0x' + crypto.createHash('sha256').update(this.machineId).digest('hex');
    }

    /**
     * Get real disk space info for the data partition
     */
    getDiskInfo(): DiskInfo {
        try {
            if (os.platform() === 'win32') {
                // Windows
                const drive = path.parse(this.baseDataPath).root || 'C:\\';
                const output = execSync(`wmic logicaldisk where "DeviceID='${drive.replace('\\', '')}'" get Size,FreeSpace /format:csv`, { encoding: 'utf8' });
                const lines = output.trim().split('\n');
                const [, , freeSpace, size] = lines[1].split(',');
                
                const totalBytes = parseInt(size);
                const freeBytes = parseInt(freeSpace);
                
                return {
                    totalBytes,
                    freeBytes,
                    usedBytes: totalBytes - freeBytes,
                    totalGB: totalBytes / (1024 ** 3),
                    freeGB: freeBytes / (1024 ** 3),
                    usedGB: (totalBytes - freeBytes) / (1024 ** 3)
                };
            } else {
                // Linux/Mac
                const output = execSync(`df -B1 "${this.baseDataPath}" | tail -1`, { encoding: 'utf8' });
                const parts = output.trim().split(/\s+/);
                
                const totalBytes = parseInt(parts[1]);
                const usedBytes = parseInt(parts[2]);
                const freeBytes = parseInt(parts[3]);
                
                return {
                    totalBytes,
                    freeBytes,
                    usedBytes,
                    totalGB: totalBytes / (1024 ** 3),
                    freeGB: freeBytes / (1024 ** 3),
                    usedGB: usedBytes / (1024 ** 3)
                };
            }
        } catch (error) {
            this.logger.error('Failed to get disk info:', error);
            return {
                totalBytes: 0,
                freeBytes: 0,
                usedBytes: 0,
                totalGB: 0,
                freeGB: 0,
                usedGB: 0
            };
        }
    }

    /**
     * Get actual directory size in bytes
     */
    getDirectorySize(dirPath: string): number {
        let totalSize = 0;
        
        try {
            if (!fs.existsSync(dirPath)) return 0;
            
            const files = fs.readdirSync(dirPath);
            
            for (const file of files) {
                const filePath = path.join(dirPath, file);
                const stats = fs.statSync(filePath);
                
                if (stats.isDirectory()) {
                    totalSize += this.getDirectorySize(filePath);
                } else {
                    totalSize += stats.size;
                }
            }
        } catch (error) {
            this.logger.error(`Failed to get size of ${dirPath}:`, error);
        }
        
        return totalSize;
    }

    /**
     * Get data path for a specific node
     */
    getNodeDataPath(nodeId: string): string {
        // Use first 8 chars of nodeId as folder name
        const folderName = nodeId.startsWith('0x') ? nodeId.slice(2, 10) : nodeId.slice(0, 8);
        return path.join(this.baseDataPath, `node-${folderName}`);
    }

    /**
     * Initialize storage for a node
     */
    initNodeStorage(nodeId: string, allocatedMB: number): string {
        const nodePath = this.getNodeDataPath(nodeId);
        
        // Create directories
        const dirs = ['messages', 'cache', 'logs'];
        for (const dir of dirs) {
            const fullPath = path.join(nodePath, dir);
            if (!fs.existsSync(fullPath)) {
                fs.mkdirSync(fullPath, { recursive: true });
            }
        }
        
        // Write allocation info
        const infoPath = path.join(nodePath, 'storage-info.json');
        fs.writeFileSync(infoPath, JSON.stringify({
            nodeId,
            allocatedMB,
            createdAt: new Date().toISOString(),
            machineId: this.machineId
        }, null, 2));
        
        this.logger.info(`Initialized storage for node ${nodeId} at ${nodePath}`);
        this.logger.info(`Allocated: ${allocatedMB} MB`);
        
        return nodePath;
    }

    /**
     * Get storage info for a specific node
     */
    getNodeStorageInfo(nodeId: string): NodeStorageInfo | null {
        const nodePath = this.getNodeDataPath(nodeId);
        
        if (!fs.existsSync(nodePath)) {
            return null;
        }
        
        try {
            const infoPath = path.join(nodePath, 'storage-info.json');
            const info = JSON.parse(fs.readFileSync(infoPath, 'utf8'));
            
            const usedBytes = this.getDirectorySize(nodePath);
            const usedMB = usedBytes / (1024 * 1024);
            
            // Count messages
            const messagesPath = path.join(nodePath, 'messages');
            const messageCount = fs.existsSync(messagesPath) 
                ? fs.readdirSync(messagesPath).length 
                : 0;
            
            return {
                nodeId,
                dataPath: nodePath,
                allocatedMB: info.allocatedMB,
                usedMB: Math.round(usedMB * 100) / 100,
                freeMB: Math.round((info.allocatedMB - usedMB) * 100) / 100,
                messageCount
            };
        } catch (error) {
            this.logger.error(`Failed to get storage info for ${nodeId}:`, error);
            return null;
        }
    }

    /**
     * Get all nodes running on this machine
     */
    getAllNodeStorageInfo(): NodeStorageInfo[] {
        const nodes: NodeStorageInfo[] = [];
        
        if (!fs.existsSync(this.baseDataPath)) {
            return nodes;
        }
        
        const dirs = fs.readdirSync(this.baseDataPath);
        
        for (const dir of dirs) {
            if (dir.startsWith('node-')) {
                const nodeId = dir.replace('node-', '');
                const info = this.getNodeStorageInfo(nodeId);
                if (info) {
                    nodes.push(info);
                }
            }
        }
        
        return nodes;
    }

    /**
     * Get complete machine storage info
     */
    getMachineStorageInfo(): MachineStorageInfo {
        const disk = this.getDiskInfo();
        const nodes = this.getAllNodeStorageInfo();
        
        const totalAllocatedMB = nodes.reduce((sum, n) => sum + n.allocatedMB, 0);
        const totalUsedMB = nodes.reduce((sum, n) => sum + n.usedMB, 0);
        
        // Available for new nodes = free disk space - safety buffer (10%)
        const safetyBufferMB = disk.totalGB * 1024 * 0.1; // 10% of total
        const availableForNewNodesMB = Math.max(0, (disk.freeGB * 1024) - safetyBufferMB - totalAllocatedMB);
        
        return {
            machineId: this.machineId,
            disk,
            nodes,
            totalAllocatedMB: Math.round(totalAllocatedMB),
            totalUsedMB: Math.round(totalUsedMB * 100) / 100,
            availableForNewNodesMB: Math.round(availableForNewNodesMB)
        };
    }

    /**
     * Check if machine can support a new node with given storage
     */
    canAllocateStorage(requestedMB: number): { canAllocate: boolean; reason?: string; available: number } {
        const info = this.getMachineStorageInfo();
        
        if (requestedMB > info.availableForNewNodesMB) {
            return {
                canAllocate: false,
                reason: `Requested ${requestedMB}MB but only ${info.availableForNewNodesMB}MB available`,
                available: info.availableForNewNodesMB
            };
        }
        
        return {
            canAllocate: true,
            available: info.availableForNewNodesMB
        };
    }

    /**
     * Get recommended storage tier based on available space
     */
    getRecommendedTier(): { tier: string; storageMB: number; reason: string } {
        const info = this.getMachineStorageInfo();
        const availableGB = info.availableForNewNodesMB / 1024;
        
        if (availableGB >= 8) {
            return { tier: 'PLATINUM', storageMB: 8192, reason: `${availableGB.toFixed(1)}GB available` };
        } else if (availableGB >= 4) {
            return { tier: 'GOLD', storageMB: 4096, reason: `${availableGB.toFixed(1)}GB available` };
        } else if (availableGB >= 2) {
            return { tier: 'SILVER', storageMB: 2048, reason: `${availableGB.toFixed(1)}GB available` };
        } else if (availableGB >= 1) {
            return { tier: 'BRONZE', storageMB: 1024, reason: `${availableGB.toFixed(1)}GB available` };
        } else {
            return { tier: 'NONE', storageMB: 0, reason: `Only ${(availableGB * 1024).toFixed(0)}MB available - need at least 1GB` };
        }
    }

    /**
     * Clean up storage for a node
     */
    cleanupNodeStorage(nodeId: string): void {
        const nodePath = this.getNodeDataPath(nodeId);
        
        if (fs.existsSync(nodePath)) {
            fs.rmSync(nodePath, { recursive: true, force: true });
            this.logger.info(`Cleaned up storage for node ${nodeId}`);
        }
    }

    /**
     * Check if storage quota is exceeded
     */
    isQuotaExceeded(nodeId: string): boolean {
        const info = this.getNodeStorageInfo(nodeId);
        if (!info) return false;
        
        return info.usedMB > info.allocatedMB;
    }

    /**
     * Print storage summary
     */
    printStorageSummary(): void {
        const info = this.getMachineStorageInfo();
        
        console.log('\n' + '═'.repeat(60));
        console.log('   MACHINE STORAGE INFO');
        console.log('═'.repeat(60));
        console.log(`   Machine ID: ${info.machineId}`);
        console.log(`   Total Disk: ${info.disk.totalGB.toFixed(1)} GB`);
        console.log(`   Free Disk:  ${info.disk.freeGB.toFixed(1)} GB`);
        console.log(`   Used Disk:  ${info.disk.usedGB.toFixed(1)} GB`);
        console.log('');
        console.log(`   Nodes on this machine: ${info.nodes.length}`);
        console.log(`   Total Allocated: ${(info.totalAllocatedMB / 1024).toFixed(2)} GB`);
        console.log(`   Total Used:      ${(info.totalUsedMB / 1024).toFixed(2)} GB`);
        console.log(`   Available for new nodes: ${(info.availableForNewNodesMB / 1024).toFixed(2)} GB`);
        
        if (info.nodes.length > 0) {
            console.log('\n   NODES:');
            for (const node of info.nodes) {
                const usage = (node.usedMB / node.allocatedMB * 100).toFixed(1);
                console.log(`   • ${node.nodeId}: ${node.usedMB.toFixed(1)}/${node.allocatedMB}MB (${usage}%) - ${node.messageCount} msgs`);
            }
        }
        
        const rec = this.getRecommendedTier();
        console.log('\n   RECOMMENDED TIER FOR NEW NODE:');
        console.log(`   ${rec.tier} (${rec.storageMB}MB) - ${rec.reason}`);
        console.log('═'.repeat(60) + '\n');
    }

    /**
     * Lock storage at system level using the install script
     * This creates actual disk reservation
     */
    async lockSystemStorage(nodeId: string, storageMB: number): Promise<boolean> {
        try {
            const platform = os.platform();
            let command: string;
            
            if (platform === 'linux') {
                command = `/var/lib/mumblechat/install-linux.sh --lock ${nodeId} ${storageMB}`;
            } else if (platform === 'darwin') {
                command = `/usr/local/var/mumblechat/install-macos.sh --lock ${nodeId} ${storageMB}`;
            } else if (platform === 'win32') {
                command = `"%ProgramData%\\MumbleChat\\install-windows.bat" --lock ${nodeId} ${storageMB}`;
            } else {
                // Fallback: manual directory creation
                return this.fallbackLockStorage(nodeId, storageMB);
            }
            
            execSync(command, { encoding: 'utf8' });
            this.logger.info(`System storage locked: ${storageMB}MB for ${nodeId}`);
            return true;
        } catch (error) {
            this.logger.error('Failed to lock system storage:', error);
            return this.fallbackLockStorage(nodeId, storageMB);
        }
    }

    /**
     * Fallback storage locking without system script
     */
    private fallbackLockStorage(nodeId: string, storageMB: number): boolean {
        try {
            const nodePath = this.getNodeDataPath(nodeId);
            const storagePath = path.join(nodePath, 'storage');
            const reserveFile = path.join(storagePath, '.reserved_space');
            
            // Create directories
            fs.mkdirSync(storagePath, { recursive: true });
            
            // Create sparse file for reservation (doesn't actually use disk on most filesystems)
            const fd = fs.openSync(reserveFile, 'w');
            fs.ftruncateSync(fd, storageMB * 1024 * 1024);
            fs.closeSync(fd);
            
            // Save node info
            fs.writeFileSync(
                path.join(nodePath, 'node.json'),
                JSON.stringify({
                    node_id: nodeId,
                    storage_mb: storageMB,
                    locked_at: new Date().toISOString(),
                    storage_path: storagePath
                }, null, 2)
            );
            
            this.logger.info(`Fallback storage locked: ${storageMB}MB for ${nodeId}`);
            return true;
        } catch (error) {
            this.logger.error('Failed fallback storage lock:', error);
            return false;
        }
    }

    /**
     * Unlock storage at system level
     */
    async unlockSystemStorage(nodeId: string): Promise<boolean> {
        try {
            const platform = os.platform();
            let command: string;
            
            if (platform === 'linux') {
                command = `/var/lib/mumblechat/install-linux.sh --unlock ${nodeId}`;
            } else if (platform === 'darwin') {
                command = `/usr/local/var/mumblechat/install-macos.sh --unlock ${nodeId}`;
            } else if (platform === 'win32') {
                command = `"%ProgramData%\\MumbleChat\\install-windows.bat" --unlock ${nodeId}`;
            } else {
                // Fallback
                this.cleanupNodeStorage(nodeId);
                return true;
            }
            
            execSync(command, { encoding: 'utf8' });
            this.logger.info(`System storage unlocked for ${nodeId}`);
            return true;
        } catch (error) {
            this.logger.error('Failed to unlock system storage:', error);
            this.cleanupNodeStorage(nodeId);
            return true;
        }
    }

    /**
     * Get system resource limits from install script
     */
    getSystemLimits(): { maxNodes: number; availableMB: number; deployedNodes: number } {
        try {
            const platform = os.platform();
            let resourceFile: string;
            
            if (platform === 'linux') {
                resourceFile = '/etc/mumblechat/resources.json';
            } else if (platform === 'darwin') {
                resourceFile = '/usr/local/etc/mumblechat/resources.json';
            } else if (platform === 'win32') {
                resourceFile = path.join(process.env.ProgramData || 'C:\\ProgramData', 'MumbleChat', 'config', 'resources.json');
            } else {
                return { maxNodes: 10, availableMB: 50000, deployedNodes: 0 };
            }
            
            if (fs.existsSync(resourceFile)) {
                const data = JSON.parse(fs.readFileSync(resourceFile, 'utf8'));
                return {
                    maxNodes: data.max_nodes || 10,
                    availableMB: data.disk_available_mb || 50000,
                    deployedNodes: data.deployed_nodes || 0
                };
            }
        } catch (error) {
            this.logger.error('Failed to read system limits:', error);
        }
        
        return { maxNodes: 10, availableMB: 50000, deployedNodes: 0 };
    }
}

export default StorageManager;
