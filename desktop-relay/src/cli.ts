#!/usr/bin/env node
/**
 * MumbleChat Desktop Relay Node - CLI
 * 
 * Command-line interface for managing the relay node
 */

import { Command } from 'commander';
import chalk from 'chalk';
import ora from 'ora';
// eslint-disable-next-line @typescript-eslint/no-var-requires
const qrcode = require('qrcode-terminal');
import { ethers } from 'ethers';
import fs from 'fs';
import path from 'path';
import readline from 'readline';
import { RelayServer, loadConfig, saveConfig } from './RelayServer';
import { BlockchainService } from './blockchain/BlockchainService';
import { DashboardServer } from './dashboard/DashboardServer';
import { defaultConfig, RelayConfig, getTierName, RelayTier } from './config';

const program = new Command();

// Default config path
const CONFIG_PATH = process.env.MUMBLECHAT_CONFIG || './config.json';
const KEYSTORE_PATH = process.env.MUMBLECHAT_KEYSTORE || './keystore';

/**
 * Get private key from environment or keystore
 */
async function getPrivateKey(options: any): Promise<string | null> {
  // Check command line option
  if (options.privateKey) {
    return options.privateKey;
  }

  // Check environment variable
  const config = loadConfig(CONFIG_PATH);
  const envVar = config.wallet.privateKeyEnvVar;
  if (process.env[envVar]) {
    return process.env[envVar]!;
  }

  // Check keystore file
  const keystorePath = path.join(KEYSTORE_PATH, 'relay-key.json');
  if (fs.existsSync(keystorePath)) {
    const rl = readline.createInterface({
      input: process.stdin,
      output: process.stdout,
    });

    return new Promise((resolve) => {
      rl.question('Enter keystore password: ', async (password) => {
        rl.close();
        try {
          const keystore = fs.readFileSync(keystorePath, 'utf-8');
          const wallet = await ethers.Wallet.fromEncryptedJson(keystore, password);
          resolve(wallet.privateKey);
        } catch (err) {
          console.error(chalk.red('Failed to decrypt keystore'));
          resolve(null);
        }
      });
    });
  }

  return null;
}

/**
 * Interactive setup wizard
 */
async function setupWizard(): Promise<void> {
  console.log(chalk.cyan('\n='.repeat(60)));
  console.log(chalk.cyan.bold('   MumbleChat Desktop Relay Node - Setup Wizard'));
  console.log(chalk.cyan('='.repeat(60)));
  console.log();

  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
  });

  const question = (prompt: string): Promise<string> => {
    return new Promise((resolve) => {
      rl.question(chalk.yellow(prompt), resolve);
    });
  };

  try {
    // Load or create config
    let config = loadConfig(CONFIG_PATH);

    // Storage configuration
    console.log(chalk.white('\n📦 Storage Configuration'));
    const storageGB = await question(`Max storage in GB [${config.relay.maxStorageGB}]: `);
    if (storageGB) {
      config.relay.maxStorageGB = parseInt(storageGB) || config.relay.maxStorageGB;
    }

    // Network configuration
    console.log(chalk.white('\n🌐 Network Configuration'));
    const port = await question(`P2P port [${config.relay.port}]: `);
    if (port) {
      config.relay.port = parseInt(port) || config.relay.port;
    }

    const host = await question(`Bind host [${config.relay.host}]: `);
    if (host) {
      config.relay.host = host;
    }

    // API configuration
    console.log(chalk.white('\n🔌 API Configuration'));
    const enableApi = await question(`Enable monitoring API? (y/n) [y]: `);
    config.api.enabled = enableApi.toLowerCase() !== 'n';

    if (config.api.enabled) {
      const apiPort = await question(`API port [${config.api.port}]: `);
      if (apiPort) {
        config.api.port = parseInt(apiPort) || config.api.port;
      }
    }

    // Wallet setup
    console.log(chalk.white('\n💰 Wallet Configuration'));
    console.log(chalk.gray('Your wallet will be used to register as a relay node and receive MCT rewards.'));
    
    const walletChoice = await question('(1) Import private key, (2) Create new wallet, (3) Use existing keystore [1]: ');

    if (walletChoice === '2') {
      // Create new wallet
      const wallet = ethers.Wallet.createRandom();
      console.log(chalk.green('\n✅ New wallet created!'));
      console.log(chalk.white(`   Address: ${wallet.address}`));
      console.log(chalk.yellow('\n⚠️  SAVE YOUR MNEMONIC PHRASE (24 words):'));
      console.log(chalk.white(`   ${wallet.mnemonic?.phrase}`));
      
      const password = await question('\nEnter password to encrypt keystore: ');
      const keystoreJson = await wallet.encrypt(password);
      
      if (!fs.existsSync(KEYSTORE_PATH)) {
        fs.mkdirSync(KEYSTORE_PATH, { recursive: true });
      }
      fs.writeFileSync(path.join(KEYSTORE_PATH, 'relay-key.json'), keystoreJson);
      console.log(chalk.green(`Keystore saved to ${KEYSTORE_PATH}/relay-key.json`));

    } else if (walletChoice === '1' || !walletChoice) {
      // Import private key
      const privateKey = await question('Enter private key (0x...): ');
      if (privateKey && privateKey.startsWith('0x')) {
        const wallet = new ethers.Wallet(privateKey);
        console.log(chalk.green(`\n✅ Wallet imported: ${wallet.address}`));
        
        const password = await question('Enter password to encrypt keystore: ');
        const keystoreJson = await wallet.encrypt(password);
        
        if (!fs.existsSync(KEYSTORE_PATH)) {
          fs.mkdirSync(KEYSTORE_PATH, { recursive: true });
        }
        fs.writeFileSync(path.join(KEYSTORE_PATH, 'relay-key.json'), keystoreJson);
        console.log(chalk.green(`Keystore saved to ${KEYSTORE_PATH}/relay-key.json`));
      }
    }

    // Save configuration
    saveConfig(config, CONFIG_PATH);
    console.log(chalk.green(`\n✅ Configuration saved to ${CONFIG_PATH}`));

    // Print tier information
    console.log(chalk.white('\n📊 Relay Node Tier System:'));
    console.log(chalk.gray('─'.repeat(50)));
    console.log(chalk.white('  🥉 Bronze   : 1GB storage, 4+ hours/day  → 1.0x rewards'));
    console.log(chalk.white('  🥈 Silver   : 2GB storage, 8+ hours/day  → 1.5x rewards'));
    console.log(chalk.white('  🥇 Gold     : 4GB storage, 12+ hours/day → 2.0x rewards'));
    console.log(chalk.white('  💎 Platinum : 8GB storage, 16+ hours/day → 3.0x rewards'));
    console.log(chalk.gray('─'.repeat(50)));

    console.log(chalk.cyan('\n🚀 Setup complete! Run `mumblechat-relay start` to begin.\n'));

  } finally {
    rl.close();
  }
}

// Main program
program
  .name('mumblechat-relay')
  .description('MumbleChat Desktop Relay Node - Earn MCT by relaying messages')
  .version('1.0.0');

// Setup command
program
  .command('setup')
  .description('Interactive setup wizard')
  .action(async () => {
    await setupWizard();
  });

// Start command
program
  .command('start')
  .description('Start the relay node')
  .option('-c, --config <path>', 'Configuration file path', CONFIG_PATH)
  .option('-k, --private-key <key>', 'Private key (or set RELAY_PRIVATE_KEY env)')
  .option('-p, --port <port>', 'P2P port')
  .option('-s, --storage <gb>', 'Max storage in GB')
  .action(async (options) => {
    const spinner = ora('Starting MumbleChat Relay Node...').start();

    try {
      // Load config
      const config = loadConfig(options.config || CONFIG_PATH);
      
      // Apply CLI overrides
      if (options.port) config.relay.port = parseInt(options.port);
      if (options.storage) config.relay.maxStorageGB = parseInt(options.storage);

      // Get private key
      spinner.text = 'Loading wallet...';
      const privateKey = await getPrivateKey(options);
      if (!privateKey) {
        spinner.fail('No private key found. Run `mumblechat-relay setup` first.');
        process.exit(1);
      }

      // Create and start server
      spinner.text = 'Initializing relay server...';
      const server = new RelayServer(config);

      // Handle shutdown
      process.on('SIGINT', async () => {
        spinner.info('Shutting down...');
        await server.stop();
        process.exit(0);
      });

      process.on('SIGTERM', async () => {
        await server.stop();
        process.exit(0);
      });

      await server.start(privateKey);
      spinner.succeed('Relay node is running!');

      // Start dashboard server
      const dashboard = new DashboardServer(server, {
        port: config.api.port || 8445,
        host: '0.0.0.0'
      });
      await dashboard.start();
      
      console.log(chalk.cyan(`\n📊 Dashboard: http://localhost:${dashboard.getPort()}`));
      console.log(chalk.cyan(`📱 Mobile access: http://<your-ip>:${dashboard.getPort()}`));
      console.log();

      // Print status periodically
      setInterval(() => {
        server.printStatus();
      }, 60000);

    } catch (error: any) {
      spinner.fail(`Failed to start: ${error.message}`);
      process.exit(1);
    }
  });

// Status command
program
  .command('status')
  .description('Check relay node status and rewards')
  .option('-c, --config <path>', 'Configuration file path', CONFIG_PATH)
  .option('-k, --private-key <key>', 'Private key')
  .action(async (options) => {
    try {
      const config = loadConfig(options.config || CONFIG_PATH);
      const privateKey = await getPrivateKey(options);
      
      if (!privateKey) {
        console.error(chalk.red('No private key found. Run setup first.'));
        process.exit(1);
      }

      const blockchain = new BlockchainService(
        config.blockchain.rpcUrl,
        config.blockchain.registryAddress,
        config.blockchain.relayManagerAddress,
        config.blockchain.mctTokenAddress,
        privateKey
      );

      await blockchain.printStatus();

    } catch (error: any) {
      console.error(chalk.red(`Error: ${error.message}`));
      process.exit(1);
    }
  });

// Register command
program
  .command('register')
  .description('Register as a relay node on-chain')
  .option('-c, --config <path>', 'Configuration file path', CONFIG_PATH)
  .option('-k, --private-key <key>', 'Private key')
  .option('-e, --endpoint <url>', 'Public endpoint (e.g., tcp://1.2.3.4:19370)')
  .option('-s, --storage <mb>', 'Storage capacity in MB', '1024')
  .action(async (options) => {
    const spinner = ora('Registering relay node...').start();

    try {
      const config = loadConfig(options.config || CONFIG_PATH);
      const privateKey = await getPrivateKey(options);
      
      if (!privateKey) {
        spinner.fail('No private key found. Run setup first.');
        process.exit(1);
      }

      const blockchain = new BlockchainService(
        config.blockchain.rpcUrl,
        config.blockchain.registryAddress,
        config.blockchain.relayManagerAddress,
        config.blockchain.mctTokenAddress,
        privateKey
      );

      // Check if already registered
      if (await blockchain.isRelayRegistered()) {
        spinner.info('Already registered as relay node');
        await blockchain.printStatus();
        return;
      }

      // Register identity first if needed
      if (!(await blockchain.isIdentityRegistered())) {
        spinner.text = 'Registering identity...';
        const publicKey = ethers.keccak256(ethers.toUtf8Bytes(blockchain.getWalletAddress()));
        await blockchain.registerIdentity(publicKey, 'Desktop Relay');
      }

      // Get endpoint
      const endpoint = options.endpoint || `tcp://0.0.0.0:${config.relay.port}`;
      const storageMB = parseInt(options.storage);

      spinner.text = 'Registering as relay node...';
      const txHash = await blockchain.registerAsRelay(endpoint, storageMB);
      
      spinner.succeed(`Relay node registered! TX: ${txHash}`);
      await blockchain.printStatus();

    } catch (error: any) {
      spinner.fail(`Registration failed: ${error.message}`);
      process.exit(1);
    }
  });

// Claim rewards command
program
  .command('claim')
  .description('Claim pending MCT rewards')
  .option('-c, --config <path>', 'Configuration file path', CONFIG_PATH)
  .option('-k, --private-key <key>', 'Private key')
  .action(async (options) => {
    const spinner = ora('Claiming rewards...').start();

    try {
      const config = loadConfig(options.config || CONFIG_PATH);
      const privateKey = await getPrivateKey(options);
      
      if (!privateKey) {
        spinner.fail('No private key found. Run setup first.');
        process.exit(1);
      }

      const blockchain = new BlockchainService(
        config.blockchain.rpcUrl,
        config.blockchain.registryAddress,
        config.blockchain.relayManagerAddress,
        config.blockchain.mctTokenAddress,
        privateKey
      );

      // Claim both reward types
      spinner.text = 'Claiming message relay rewards...';
      await blockchain.claimRewards();

      spinner.succeed('Successfully claimed all rewards!');

      spinner.succeed('Rewards claimed!');
      await blockchain.printStatus();

    } catch (error: any) {
      spinner.fail(`Claim failed: ${error.message}`);
      process.exit(1);
    }
  });

// QR code command
program
  .command('qr')
  .description('Display relay node connection QR code')
  .option('-c, --config <path>', 'Configuration file path', CONFIG_PATH)
  .option('-k, --private-key <key>', 'Private key')
  .action(async (options) => {
    try {
      const config = loadConfig(options.config || CONFIG_PATH);
      const privateKey = await getPrivateKey(options);
      
      if (!privateKey) {
        console.error(chalk.red('No private key found. Run setup first.'));
        process.exit(1);
      }

      const wallet = new ethers.Wallet(privateKey);
      const endpoint = `tcp://YOUR_PUBLIC_IP:${config.relay.port}`;
      const connectionUrl = `mumblechat://relay?wallet=${wallet.address}&endpoint=${encodeURIComponent(endpoint)}`;

      console.log(chalk.cyan('\n📱 Scan this QR code with MumbleChat app to connect:\n'));
      qrcode.generate(connectionUrl, { small: true });
      console.log(chalk.white(`\nConnection URL: ${connectionUrl}\n`));
      console.log(chalk.yellow('⚠️  Replace YOUR_PUBLIC_IP with your actual public IP address\n'));

    } catch (error: any) {
      console.error(chalk.red(`Error: ${error.message}`));
      process.exit(1);
    }
  });

// Config command
program
  .command('config')
  .description('Show current configuration')
  .option('-c, --config <path>', 'Configuration file path', CONFIG_PATH)
  .action((options) => {
    const config = loadConfig(options.config || CONFIG_PATH);
    console.log(chalk.cyan('\nCurrent Configuration:\n'));
    console.log(JSON.stringify(config, null, 2));
  });

// Parse and run
program.parse(process.argv);

// Show help if no command
if (!process.argv.slice(2).length) {
  console.log(chalk.cyan(`
╔═══════════════════════════════════════════════════════════════╗
║         MumbleChat Desktop Relay Node                         ║
║                                                               ║
║   Earn MCT tokens by relaying encrypted messages              ║
║   for the MumbleChat P2P messaging network                    ║
║                                                               ║
║   Quick Start:                                                ║
║   1. mumblechat-relay setup     # Configure your node         ║
║   2. mumblechat-relay register  # Register on blockchain      ║
║   3. mumblechat-relay start     # Start relaying messages     ║
║                                                               ║
╚═══════════════════════════════════════════════════════════════╝
  `));
  program.help();
}
