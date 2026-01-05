/**
 * MumbleChat Desktop Relay Node - Crypto Utilities
 * 
 * Cryptographic utilities for message handling and key management
 */

import crypto from 'crypto';
import { ethers } from 'ethers';

/**
 * Derive NodeID from wallet address (SHA256)
 */
export function deriveNodeId(walletAddress: string): Buffer {
  const normalized = walletAddress.toLowerCase();
  return crypto.createHash('sha256').update(normalized).digest();
}

/**
 * Compute XOR distance between two node IDs (Kademlia)
 */
export function xorDistance(a: Buffer, b: Buffer): Buffer {
  const result = Buffer.alloc(32);
  for (let i = 0; i < 32; i++) {
    result[i] = a[i] ^ b[i];
  }
  return result;
}

/**
 * Compare XOR distances
 */
export function compareDistance(a: Buffer, b: Buffer): number {
  for (let i = 0; i < 32; i++) {
    if (a[i] < b[i]) return -1;
    if (a[i] > b[i]) return 1;
  }
  return 0;
}

/**
 * Find the k-bucket index for a given node ID (relative to our ID)
 */
export function getBucketIndex(ourId: Buffer, theirId: Buffer): number {
  for (let i = 0; i < 256; i++) {
    const byteIndex = Math.floor(i / 8);
    const bitIndex = 7 - (i % 8);
    const ourBit = (ourId[byteIndex] >> bitIndex) & 1;
    const theirBit = (theirId[byteIndex] >> bitIndex) & 1;
    if (ourBit !== theirBit) {
      return 255 - i;
    }
  }
  return 0;
}

/**
 * Hash message ID
 */
export function hashMessageId(messageId: string): Buffer {
  return crypto.createHash('sha256').update(messageId).digest();
}

/**
 * Generate random message ID
 */
export function generateMessageId(): string {
  return crypto.randomBytes(16).toString('hex');
}

/**
 * Compute key hash for routing (first 8 bytes of keccak256)
 */
export function computeKeyHash(address: string): string {
  const hash = ethers.keccak256(ethers.toUtf8Bytes(address.toLowerCase()));
  return hash.slice(2, 18); // First 8 bytes in hex
}

/**
 * Sign message with private key
 */
export function signMessage(message: Buffer, privateKey: string): string {
  const wallet = new ethers.Wallet(privateKey);
  const hash = ethers.keccak256(message);
  return wallet.signMessageSync(ethers.getBytes(hash));
}

/**
 * Verify message signature
 */
export function verifySignature(message: Buffer, signature: string, expectedAddress: string): boolean {
  try {
    const hash = ethers.keccak256(message);
    const recoveredAddress = ethers.verifyMessage(ethers.getBytes(hash), signature);
    return recoveredAddress.toLowerCase() === expectedAddress.toLowerCase();
  } catch {
    return false;
  }
}

/**
 * Encrypt data with AES-256-GCM (for local storage)
 */
export function encryptLocal(data: Buffer, key: Buffer): { ciphertext: Buffer; nonce: Buffer; tag: Buffer } {
  const nonce = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, nonce);
  
  const ciphertext = Buffer.concat([cipher.update(data), cipher.final()]);
  const tag = cipher.getAuthTag();
  
  return { ciphertext, nonce, tag };
}

/**
 * Decrypt data with AES-256-GCM (for local storage)
 */
export function decryptLocal(ciphertext: Buffer, nonce: Buffer, tag: Buffer, key: Buffer): Buffer {
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, nonce);
  decipher.setAuthTag(tag);
  
  return Buffer.concat([decipher.update(ciphertext), decipher.final()]);
}

/**
 * Derive storage encryption key from private key
 */
export function deriveStorageKey(privateKey: string): Buffer {
  return crypto.createHash('sha256')
    .update(privateKey + ':storage')
    .digest();
}

/**
 * Generate ephemeral keypair for X25519 key exchange
 */
export function generateX25519Keypair(): { publicKey: Buffer; privateKey: Buffer } {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('x25519');
  return {
    publicKey: publicKey.export({ type: 'spki', format: 'der' }).slice(-32),
    privateKey: privateKey.export({ type: 'pkcs8', format: 'der' }).slice(-32),
  };
}
