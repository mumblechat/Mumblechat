package com.ramapay.app.chat.core

import com.ramapay.app.chat.MumbleChatContracts

/**
 * Configuration constants for MumbleChat Protocol.
 */
object ChatConfig {
    // Blockchain (from MumbleChatContracts - auto-generated from deployment)
    const val RAMESTTA_MAINNET_ID = MumbleChatContracts.CHAIN_ID
    const val RAMESTTA_RPC = MumbleChatContracts.RPC_URL
    const val RAMESTTA_EXPLORER = MumbleChatContracts.EXPLORER_URL
    
    // Contract addresses (deployed on Ramestta mainnet)
    const val MUMBLECHAT_REGISTRY_ADDRESS = MumbleChatContracts.REGISTRY_PROXY
    const val MCT_TOKEN_ADDRESS = MumbleChatContracts.MCT_TOKEN_PROXY
    
    // Key derivation
    const val DERIVATION_MESSAGE = "MUMBLECHAT_KEY_DERIVATION_V1"
    const val BACKUP_KEY_MESSAGE = "MUMBLECHAT_BACKUP_KEY_V1"
    const val HKDF_SALT = "mumblechat"
    
    // P2P Network
    const val DHT_PROTOCOL = "/mumblechat/kad/1.0.0"
    const val CHAT_PROTOCOL = "/mumblechat/chat/1.0.0"
    const val RELAY_PROTOCOL = "/mumblechat/relay/1.0.0"
    
    // Bootstrap nodes - fallback if no relay nodes on blockchain
    // Format: "IP:PORT" or "/ip4/IP/tcp/PORT"
    val BOOTSTRAP_NODES = listOf<String>(
        // No hardcoded bootstrap nodes needed!
        // LAN discovery will find peers on local network
        // Blockchain relay nodes will be fetched dynamically
    )
    
    // Message settings
    const val MESSAGE_TTL_DAYS = 7
    const val MAX_MESSAGE_SIZE_BYTES = 1024 * 1024 // 1 MB
    const val MESSAGE_PREVIEW_LENGTH = 100
    
    // Relay settings
    const val DEFAULT_RELAY_STORAGE_MB = 100
    const val MIN_RELAY_STAKE_MCT = MumbleChatContracts.MIN_RELAY_STAKE // 100 MCT
    
    // MCT Token
    const val MCT_DECIMALS = 18
    const val MCT_SYMBOL = "MCT"
    const val MCT_NAME = "MumbleChat Token"
    
    // Backup
    const val BACKUP_FILE_NAME = "mumblechat_backup.mcb"
    const val BACKUP_VERSION = 1
}
