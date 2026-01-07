/**
 * MumbleChat Desktop Relay Node - Multi-Instance Manager
 * 
 * Handles multiple relay node instances on the same machine with different wallets.
 * Each instance has its own folder, config, and Node ID.
 */

import fs from 'fs';
import path from 'path';
import os from 'os';
import crypto from 'crypto';
import readline from 'readline';

// Base directory for all relay instances
const RELAY_BASE_DIR = path.join(os.homedir(), '.mumblechat-relay');

export interface InstanceInfo {
    instanceNumber: number;
    nodeId: string;
    machineId: string;
    walletAddress: string | null;
    instancePath: string;
    isActive: boolean;
    createdAt: number;
    lastUsed: number;
}

export interface MachineIdentity {
    machineId: string;       // Hash of MAC address
    hostname: string;
    platform: string;
    arch: string;
}

/**
 * Get machine identity (MAC address hash for privacy)
 */
export function getMachineIdentity(): MachineIdentity {
    const networkInterfaces = os.networkInterfaces();
    let macAddress = '';
    
    // Find first non-internal MAC address
    for (const [name, interfaces] of Object.entries(networkInterfaces)) {
        if (!interfaces) continue;
        for (const iface of interfaces) {
            if (!iface.internal && iface.mac && iface.mac !== '00:00:00:00:00:00') {
                macAddress = iface.mac;
                break;
            }
        }
        if (macAddress) break;
    }
    
    // Fallback to hostname + platform if no MAC found
    if (!macAddress) {
        macAddress = `${os.hostname()}-${os.platform()}-${os.arch()}`;
    }
    
    // Hash for privacy (don't store raw MAC)
    const machineId = crypto
        .createHash('sha256')
        .update(macAddress + os.hostname())
        .digest('hex')
        .slice(0, 32);
    
    return {
        machineId,
        hostname: os.hostname(),
        platform: os.platform(),
        arch: os.arch()
    };
}

/**
 * Generate unique Node ID for a new instance
 */
export function generateNodeId(instanceNumber: number): string {
    const machine = getMachineIdentity();
    const timestamp = Date.now().toString();
    const random = crypto.randomBytes(8).toString('hex');
    
    return crypto
        .createHash('sha256')
        .update(`${machine.machineId}-${instanceNumber}-${timestamp}-${random}`)
        .digest('hex')
        .slice(0, 32);
}

/**
 * Get all existing relay instances on this machine
 */
export function getExistingInstances(): InstanceInfo[] {
    const instances: InstanceInfo[] = [];
    
    if (!fs.existsSync(RELAY_BASE_DIR)) {
        return instances;
    }
    
    // Check main instance (no suffix)
    const mainInstancePath = RELAY_BASE_DIR;
    const mainConfigPath = path.join(mainInstancePath, 'config.json');
    if (fs.existsSync(mainConfigPath)) {
        try {
            const config = JSON.parse(fs.readFileSync(mainConfigPath, 'utf-8'));
            instances.push({
                instanceNumber: 1,
                nodeId: config.nodeId || '',
                machineId: config.machineId || '',
                walletAddress: config.walletAddress || null,
                instancePath: mainInstancePath,
                isActive: isInstanceRunning(mainInstancePath),
                createdAt: config.createdAt || 0,
                lastUsed: config.lastUsed || 0
            });
        } catch (e) {
            // Invalid config, skip
        }
    }
    
    // Check numbered instances (instance-2, instance-3, etc.)
    const entries = fs.readdirSync(RELAY_BASE_DIR, { withFileTypes: true });
    for (const entry of entries) {
        if (entry.isDirectory() && entry.name.startsWith('instance-')) {
            const instanceNum = parseInt(entry.name.replace('instance-', ''));
            if (isNaN(instanceNum)) continue;
            
            const instancePath = path.join(RELAY_BASE_DIR, entry.name);
            const configPath = path.join(instancePath, 'config.json');
            
            if (fs.existsSync(configPath)) {
                try {
                    const config = JSON.parse(fs.readFileSync(configPath, 'utf-8'));
                    instances.push({
                        instanceNumber: instanceNum,
                        nodeId: config.nodeId || '',
                        machineId: config.machineId || '',
                        walletAddress: config.walletAddress || null,
                        instancePath,
                        isActive: isInstanceRunning(instancePath),
                        createdAt: config.createdAt || 0,
                        lastUsed: config.lastUsed || 0
                    });
                } catch (e) {
                    // Invalid config, skip
                }
            }
        }
    }
    
    return instances.sort((a, b) => a.instanceNumber - b.instanceNumber);
}

/**
 * Check if an instance is currently running (by PID file)
 */
export function isInstanceRunning(instancePath: string): boolean {
    const pidFile = path.join(instancePath, 'relay.pid');
    if (!fs.existsSync(pidFile)) return false;
    
    try {
        const pid = parseInt(fs.readFileSync(pidFile, 'utf-8').trim());
        // Check if process exists
        process.kill(pid, 0);
        return true;
    } catch (e) {
        // Process not running, clean up PID file
        try { fs.unlinkSync(pidFile); } catch {}
        return false;
    }
}

/**
 * Create PID file for current instance
 */
export function createPidFile(instancePath: string): void {
    const pidFile = path.join(instancePath, 'relay.pid');
    fs.writeFileSync(pidFile, process.pid.toString());
    
    // Clean up on exit
    process.on('exit', () => {
        try { fs.unlinkSync(pidFile); } catch {}
    });
    process.on('SIGINT', () => {
        try { fs.unlinkSync(pidFile); } catch {}
        process.exit();
    });
    process.on('SIGTERM', () => {
        try { fs.unlinkSync(pidFile); } catch {}
        process.exit();
    });
}

/**
 * Create a new instance directory
 */
export function createNewInstance(): InstanceInfo {
    const machine = getMachineIdentity();
    const existingInstances = getExistingInstances();
    
    // Determine next instance number
    let nextNumber = 1;
    if (existingInstances.length > 0) {
        nextNumber = Math.max(...existingInstances.map(i => i.instanceNumber)) + 1;
    }
    
    // Determine path
    let instancePath: string;
    if (nextNumber === 1) {
        instancePath = RELAY_BASE_DIR;
    } else {
        instancePath = path.join(RELAY_BASE_DIR, `instance-${nextNumber}`);
    }
    
    // Create directory
    fs.mkdirSync(instancePath, { recursive: true });
    fs.mkdirSync(path.join(instancePath, 'data'), { recursive: true });
    fs.mkdirSync(path.join(instancePath, 'logs'), { recursive: true });
    fs.mkdirSync(path.join(instancePath, 'keystore'), { recursive: true });
    
    // Generate Node ID
    const nodeId = generateNodeId(nextNumber);
    
    // Create instance config
    const instanceConfig = {
        instanceNumber: nextNumber,
        nodeId,
        machineId: machine.machineId,
        walletAddress: null,
        createdAt: Date.now(),
        lastUsed: Date.now()
    };
    
    fs.writeFileSync(
        path.join(instancePath, 'config.json'),
        JSON.stringify(instanceConfig, null, 2)
    );
    
    return {
        ...instanceConfig,
        instancePath,
        isActive: false
    };
}

/**
 * Interactive prompt for instance selection
 */
export async function selectInstance(): Promise<InstanceInfo | null> {
    const instances = getExistingInstances();
    
    if (instances.length === 0) {
        console.log('\n📂 No existing relay instances found.');
        console.log('   Creating first instance...\n');
        return createNewInstance();
    }
    
    console.log('\n' + '═'.repeat(60));
    console.log('  🖥️  MumbleChat Relay Node - Instance Manager');
    console.log('═'.repeat(60));
    console.log('\n📋 Existing instances on this machine:\n');
    
    for (const instance of instances) {
        const status = instance.isActive ? '🟢 RUNNING' : '⚪ STOPPED';
        const wallet = instance.walletAddress 
            ? `${instance.walletAddress.slice(0, 6)}...${instance.walletAddress.slice(-4)}`
            : 'Not configured';
        
        console.log(`   [${instance.instanceNumber}] ${status}`);
        console.log(`       Node ID: ${instance.nodeId.slice(0, 16)}...`);
        console.log(`       Wallet:  ${wallet}`);
        console.log(`       Path:    ${instance.instancePath}`);
        console.log('');
    }
    
    console.log(`   [N] Create NEW instance with different wallet`);
    console.log('');
    
    const rl = readline.createInterface({
        input: process.stdin,
        output: process.stdout
    });
    
    return new Promise((resolve) => {
        rl.question('Select instance number (or N for new): ', (answer) => {
            rl.close();
            
            const trimmed = answer.trim().toUpperCase();
            
            if (trimmed === 'N') {
                console.log('\n📂 Creating new instance...\n');
                resolve(createNewInstance());
                return;
            }
            
            const num = parseInt(trimmed);
            const found = instances.find(i => i.instanceNumber === num);
            
            if (found) {
                if (found.isActive) {
                    console.log('\n⚠️  This instance is already running!');
                    console.log('   Use a different instance or stop the running one first.\n');
                    resolve(null);
                } else {
                    resolve(found);
                }
            } else {
                console.log('\n❌ Invalid selection\n');
                resolve(null);
            }
        });
    });
}

/**
 * Get instance for current execution (auto-select or prompt)
 */
export async function getOrCreateInstance(
    autoCreate: boolean = false
): Promise<InstanceInfo> {
    const instances = getExistingInstances();
    
    // No instances - create first one
    if (instances.length === 0) {
        return createNewInstance();
    }
    
    // Check for running instances
    const runningInstances = instances.filter(i => i.isActive);
    if (runningInstances.length > 0 && !autoCreate) {
        console.log('\n⚠️  WARNING: You already have running relay instances:');
        for (const inst of runningInstances) {
            console.log(`   • Instance ${inst.instanceNumber}: ${inst.walletAddress || 'No wallet'}`);
        }
        console.log('');
    }
    
    // If auto-create requested, create new instance
    if (autoCreate) {
        return createNewInstance();
    }
    
    // If only one instance and not running, use it
    if (instances.length === 1 && !instances[0].isActive) {
        console.log(`\n📂 Using existing instance: ${instances[0].instancePath}\n`);
        return instances[0];
    }
    
    // Multiple instances - prompt user
    const selected = await selectInstance();
    if (!selected) {
        throw new Error('No instance selected');
    }
    
    return selected;
}

/**
 * Update instance config
 */
export function updateInstanceConfig(
    instancePath: string, 
    updates: Partial<InstanceInfo>
): void {
    const configPath = path.join(instancePath, 'config.json');
    let config: any = {};
    
    if (fs.existsSync(configPath)) {
        config = JSON.parse(fs.readFileSync(configPath, 'utf-8'));
    }
    
    config = { ...config, ...updates, lastUsed: Date.now() };
    
    fs.writeFileSync(configPath, JSON.stringify(config, null, 2));
}

/**
 * Get the base directory for relay instances
 */
export function getRelayBaseDir(): string {
    return RELAY_BASE_DIR;
}

/**
 * Display instance summary
 */
export function displayInstanceInfo(instance: InstanceInfo): void {
    console.log('\n' + '─'.repeat(50));
    console.log('  📡 Relay Node Instance');
    console.log('─'.repeat(50));
    console.log(`  Instance:   #${instance.instanceNumber}`);
    console.log(`  Node ID:    ${instance.nodeId}`);
    console.log(`  Machine ID: ${instance.machineId.slice(0, 16)}...`);
    console.log(`  Path:       ${instance.instancePath}`);
    console.log(`  Wallet:     ${instance.walletAddress || 'Not configured'}`);
    console.log(`  Status:     ${instance.isActive ? '🟢 Running' : '⚪ Stopped'}`);
    console.log('─'.repeat(50) + '\n');
}
