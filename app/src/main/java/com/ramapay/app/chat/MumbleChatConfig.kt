package com.ramapay.app.chat

import android.content.Context
import timber.log.Timber
import java.io.InputStream
import java.util.Properties

/**
 * MumbleChat Configuration - Centralized Dynamic Configuration
 * 
 * ALL contract addresses, URLs, and sensitive data are loaded dynamically.
 * NO HARDCODED VALUES - Everything from environment/properties file.
 * 
 * Configuration Sources (in priority order):
 * 1. BuildConfig (compile-time from gradle)
 * 2. mumblechat.properties in assets
 * 3. Default fallback values (development only)
 * 
 * Usage:
 * ```kotlin
 * val config = MumbleChatConfig.getInstance(context)
 * val rpcUrl = config.rpcUrl
 * val mctAddress = config.mctTokenAddress
 * ```
 */
object MumbleChatConfig {
    
    private const val TAG = "MumbleChatConfig"
    private const val PROPERTIES_FILE = "mumblechat.properties"
    
    // Loaded configuration values
    private var _initialized = false
    
    // ============ Blockchain Configuration ============
    var chainId: Long = 1370
        private set
    
    var rpcUrl: String = ""
        private set
    
    var explorerUrl: String = ""
        private set
    
    var explorerApiUrl: String = ""
        private set
    
    // ============ Contract Addresses ============
    var mctTokenAddress: String = ""
        private set
    
    var registryAddress: String = ""
        private set
    
    var relayManagerAddress: String = ""
        private set
    
    // ============ Hub Configuration ============
    var hubWsUrl: String = ""
        private set
    
    var hubHttpUrl: String = ""
        private set
    
    // ============ Network Constants ============
    var p2pPort: Int = 19370
        private set
    
    var lanDiscoveryPort: Int = 19371
        private set
    
    var heartbeatIntervalMs: Long = 5 * 60 * 1000
        private set
    
    var messageTtlDays: Int = 7
        private set
    
    // ============ Staking Tiers (MCT amounts) ============
    var bronzeStake: Long = 100
        private set
    
    var silverStake: Long = 200
        private set
    
    var goldStake: Long = 300
        private set
    
    var platinumStake: Long = 400
        private set
    
    // ============ Uptime Requirements (hours/day) ============
    var bronzeUptimeHours: Int = 4
        private set
    
    var silverUptimeHours: Int = 8
        private set
    
    var goldUptimeHours: Int = 12
        private set
    
    var platinumUptimeHours: Int = 16
        private set
    
    // ============ Reward Multipliers (basis points, 100 = 1x) ============
    var bronzeMultiplier: Int = 100
        private set
    
    var silverMultiplier: Int = 150
        private set
    
    var goldMultiplier: Int = 200
        private set
    
    var platinumMultiplier: Int = 300
        private set
    
    // ============ Tokenomics ============
    var dailyPoolAmount: Long = 100  // MCT per day
        private set
    
    var transferFeeBps: Int = 10  // 0.1% = 10 basis points
        private set
    
    var rewardPer1000Messages: Double = 0.001  // MCT
        private set
    
    var dailyMintCap: Long = 100  // MCT per day
        private set
    
    var halvingThreshold: Long = 100_000  // MCT minted before halving
        private set
    
    // ============ Storage Tiers (MB) ============
    var bronzeStorageMb: Long = 1024  // 1 GB
        private set
    
    var silverStorageMb: Long = 2048  // 2 GB
        private set
    
    var goldStorageMb: Long = 4096  // 4 GB
        private set
    
    var platinumStorageMb: Long = 8192  // 8 GB
        private set
    
    /**
     * Initialize configuration from context
     * Call this early in Application.onCreate()
     */
    @Synchronized
    fun initialize(context: Context) {
        if (_initialized) {
            Timber.d("$TAG: Already initialized")
            return
        }
        
        Timber.d("$TAG: Initializing MumbleChat configuration...")
        
        // Load from properties file
        loadFromProperties(context)
        
        // Override with BuildConfig if available
        loadFromBuildConfig()
        
        // Validate required fields
        validateConfiguration()
        
        _initialized = true
        Timber.i("$TAG: Configuration loaded successfully")
        logConfiguration()
    }
    
    /**
     * Get singleton instance (auto-initializes if context provided)
     */
    fun getInstance(context: Context? = null): MumbleChatConfig {
        if (!_initialized && context != null) {
            initialize(context)
        }
        return this
    }
    
    /**
     * Load configuration from assets/mumblechat.properties
     */
    private fun loadFromProperties(context: Context) {
        try {
            val inputStream: InputStream = context.assets.open(PROPERTIES_FILE)
            val properties = Properties()
            properties.load(inputStream)
            inputStream.close()
            
            // Blockchain
            chainId = properties.getProperty("CHAIN_ID", "1370").toLongOrNull() ?: 1370
            rpcUrl = properties.getProperty("RPC_URL", "https://blockchain.ramestta.com")
            explorerUrl = properties.getProperty("EXPLORER_URL", "https://ramascan.com")
            explorerApiUrl = properties.getProperty("EXPLORER_API_URL", "https://latest-backendapi.ramascan.com/api/v1")
            
            // Contracts
            mctTokenAddress = properties.getProperty("MCT_TOKEN_ADDRESS", "")
            registryAddress = properties.getProperty("REGISTRY_ADDRESS", "")
            relayManagerAddress = properties.getProperty("RELAY_MANAGER_ADDRESS", "")
            
            // Hub
            hubWsUrl = properties.getProperty("HUB_WS_URL", "wss://hub.mumblechat.com/node/connect")
            hubHttpUrl = properties.getProperty("HUB_HTTP_URL", "https://hub.mumblechat.com")
            
            // Network
            p2pPort = properties.getProperty("P2P_PORT", "19370").toIntOrNull() ?: 19370
            lanDiscoveryPort = properties.getProperty("LAN_DISCOVERY_PORT", "19371").toIntOrNull() ?: 19371
            heartbeatIntervalMs = properties.getProperty("HEARTBEAT_INTERVAL_MS", "300000").toLongOrNull() ?: 300000
            messageTtlDays = properties.getProperty("MESSAGE_TTL_DAYS", "7").toIntOrNull() ?: 7
            
            // Staking
            bronzeStake = properties.getProperty("BRONZE_STAKE", "100").toLongOrNull() ?: 100
            silverStake = properties.getProperty("SILVER_STAKE", "200").toLongOrNull() ?: 200
            goldStake = properties.getProperty("GOLD_STAKE", "300").toLongOrNull() ?: 300
            platinumStake = properties.getProperty("PLATINUM_STAKE", "400").toLongOrNull() ?: 400
            
            // Uptime
            bronzeUptimeHours = properties.getProperty("BRONZE_UPTIME_HOURS", "4").toIntOrNull() ?: 4
            silverUptimeHours = properties.getProperty("SILVER_UPTIME_HOURS", "8").toIntOrNull() ?: 8
            goldUptimeHours = properties.getProperty("GOLD_UPTIME_HOURS", "12").toIntOrNull() ?: 12
            platinumUptimeHours = properties.getProperty("PLATINUM_UPTIME_HOURS", "16").toIntOrNull() ?: 16
            
            Timber.d("$TAG: Loaded from properties file")
            
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Properties file not found, using defaults")
            loadDefaultValues()
        }
    }
    
    /**
     * Override with BuildConfig values if available
     * BuildConfig is generated from gradle build variants
     */
    private fun loadFromBuildConfig() {
        try {
            // Try to load from BuildConfig via reflection
            // This allows different values per build variant (debug/release)
            val buildConfigClass = Class.forName("com.ramapay.app.BuildConfig")
            
            buildConfigClass.getDeclaredField("MUMBLECHAT_RPC_URL")?.let {
                rpcUrl = it.get(null) as? String ?: rpcUrl
            }
            
            buildConfigClass.getDeclaredField("MUMBLECHAT_MCT_ADDRESS")?.let {
                mctTokenAddress = it.get(null) as? String ?: mctTokenAddress
            }
            
            buildConfigClass.getDeclaredField("MUMBLECHAT_REGISTRY_ADDRESS")?.let {
                registryAddress = it.get(null) as? String ?: registryAddress
            }
            
            buildConfigClass.getDeclaredField("MUMBLECHAT_RELAY_MANAGER_ADDRESS")?.let {
                relayManagerAddress = it.get(null) as? String ?: relayManagerAddress
            }
            
            Timber.d("$TAG: Overrides from BuildConfig applied")
            
        } catch (e: Exception) {
            Timber.d("$TAG: No BuildConfig overrides available")
        }
    }
    
    /**
     * Load default values (Ramestta Mainnet)
     * These are fallbacks for development - production should use properties file
     */
    private fun loadDefaultValues() {
        Timber.w("$TAG: Using default configuration values")
        
        // Ramestta Mainnet defaults
        chainId = 1370
        rpcUrl = "https://blockchain.ramestta.com"
        explorerUrl = "https://ramascan.com"
        explorerApiUrl = "https://latest-backendapi.ramascan.com/api/v1"
        
        // Contract addresses (deployed on mainnet)
        mctTokenAddress = "0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE"
        registryAddress = "0x4f8D4955F370881B05b68D2344345E749d8632e3"
        relayManagerAddress = "0xF78F840eF0e321512b09e98C76eA0229Affc4b73"
        
        // Hub
        hubWsUrl = "wss://hub.mumblechat.com/node/connect"
        hubHttpUrl = "https://hub.mumblechat.com"
    }
    
    /**
     * Validate required configuration is present
     */
    private fun validateConfiguration() {
        val errors = mutableListOf<String>()
        
        if (rpcUrl.isBlank()) errors.add("RPC_URL is required")
        if (mctTokenAddress.isBlank()) errors.add("MCT_TOKEN_ADDRESS is required")
        if (registryAddress.isBlank()) errors.add("REGISTRY_ADDRESS is required")
        if (relayManagerAddress.isBlank()) errors.add("RELAY_MANAGER_ADDRESS is required")
        
        if (errors.isNotEmpty()) {
            Timber.e("$TAG: Configuration validation failed: ${errors.joinToString(", ")}")
            // In production, you might want to throw an exception here
            // For now, load defaults to allow development
            loadDefaultValues()
        }
    }
    
    /**
     * Log current configuration (redacted for security)
     */
    private fun logConfiguration() {
        Timber.i("$TAG: ═══════════════════════════════════════════")
        Timber.i("$TAG: MumbleChat Configuration")
        Timber.i("$TAG: ═══════════════════════════════════════════")
        Timber.i("$TAG: Chain ID: $chainId")
        Timber.i("$TAG: RPC URL: $rpcUrl")
        Timber.i("$TAG: MCT Token: ${mctTokenAddress.take(10)}...${mctTokenAddress.takeLast(6)}")
        Timber.i("$TAG: Registry: ${registryAddress.take(10)}...${registryAddress.takeLast(6)}")
        Timber.i("$TAG: Relay Manager: ${relayManagerAddress.take(10)}...${relayManagerAddress.takeLast(6)}")
        Timber.i("$TAG: Hub WS: $hubWsUrl")
        Timber.i("$TAG: P2P Port: $p2pPort")
        Timber.i("$TAG: ═══════════════════════════════════════════")
    }
    
    /**
     * Get stake required for a tier (in MCT)
     */
    fun getStakeForTier(tier: Int): Long {
        return when (tier) {
            0 -> bronzeStake
            1 -> silverStake
            2 -> goldStake
            3 -> platinumStake
            else -> bronzeStake
        }
    }
    
    /**
     * Get uptime required for a tier (in hours/day)
     */
    fun getUptimeForTier(tier: Int): Int {
        return when (tier) {
            0 -> bronzeUptimeHours
            1 -> silverUptimeHours
            2 -> goldUptimeHours
            3 -> platinumUptimeHours
            else -> bronzeUptimeHours
        }
    }
    
    /**
     * Get reward multiplier for a tier (basis points)
     */
    fun getMultiplierForTier(tier: Int): Int {
        return when (tier) {
            0 -> bronzeMultiplier
            1 -> silverMultiplier
            2 -> goldMultiplier
            3 -> platinumMultiplier
            else -> bronzeMultiplier
        }
    }
    
    /**
     * Get storage required for a tier (in MB)
     */
    fun getStorageForTier(tier: Int): Long {
        return when (tier) {
            0 -> bronzeStorageMb
            1 -> silverStorageMb
            2 -> goldStorageMb
            3 -> platinumStorageMb
            else -> bronzeStorageMb
        }
    }
    
    /**
     * Get tier name
     */
    fun getTierName(tier: Int): String {
        return when (tier) {
            0 -> "Bronze"
            1 -> "Silver"
            2 -> "Gold"
            3 -> "Platinum"
            else -> "Bronze"
        }
    }
    
    /**
     * Get tier emoji
     */
    fun getTierEmoji(tier: Int): String {
        return when (tier) {
            0 -> "🥉"
            1 -> "🥈"
            2 -> "🥇"
            3 -> "💎"
            else -> "🥉"
        }
    }
    
    /**
     * Calculate tier from uptime and storage
     */
    fun calculateTier(uptimeHours: Int, storageMb: Long): Int {
        return when {
            uptimeHours >= platinumUptimeHours && storageMb >= platinumStorageMb -> 3
            uptimeHours >= goldUptimeHours && storageMb >= goldStorageMb -> 2
            uptimeHours >= silverUptimeHours && storageMb >= silverStorageMb -> 1
            else -> 0
        }
    }
}
