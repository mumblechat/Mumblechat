package com.ramapay.app.chat.relay

/**
 * Configuration for Relay Node operations.
 * 
 * Based on MumbleChat Protocol documentation (04_RELAY_AND_REWARDS.md).
 */
object RelayConfig {
    
    // ============ Storage Tiers (GB Scale) ============
    
    /** Storage tier thresholds in bytes */
    object StorageTiers {
        const val BRONZE_MB = 1024L           // 1 GB
        const val SILVER_MB = 2048L           // 2 GB  
        const val GOLD_MB = 4096L             // 4 GB
        const val PLATINUM_MB = 8192L         // 8 GB+
    }
    
    /** Uptime requirements in hours per day */
    object UptimeTiers {
        const val BRONZE_HOURS = 4             // 4+ hours/day
        const val SILVER_HOURS = 8             // 8+ hours/day
        const val GOLD_HOURS = 12              // 12+ hours/day
        const val PLATINUM_HOURS = 16          // 16+ hours/day
    }
    
    /** Daily pool share percentages */
    object PoolShare {
        const val BRONZE_PERCENT = 10          // 10%
        const val SILVER_PERCENT = 20          // 20%
        const val GOLD_PERCENT = 30            // 30%
        const val PLATINUM_PERCENT = 40        // 40%
    }
    
    /** Fee pool bonus multipliers (100 = 1x) */
    object FeeBonus {
        const val BRONZE_MULTIPLIER = 100      // 1.0x
        const val SILVER_MULTIPLIER = 150      // 1.5x
        const val GOLD_MULTIPLIER = 200        // 2.0x
        const val PLATINUM_MULTIPLIER = 300    // 3.0x
    }
    
    // ============ Timing Constants ============
    
    /** Heartbeat interval in milliseconds (5 minutes) */
    const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L
    
    /** Message TTL in days */
    const val DEFAULT_MESSAGE_TTL_DAYS = 7
    const val MIN_MESSAGE_TTL_DAYS = 1
    const val MAX_MESSAGE_TTL_DAYS = 30
    
    /** Cleanup interval in milliseconds (1 hour) */
    const val CLEANUP_INTERVAL_MS = 60 * 60 * 1000L
    
    /** Peer discovery interval in milliseconds (30 seconds) */
    const val PEER_DISCOVERY_INTERVAL_MS = 30 * 1000L
    
    /** Connection timeout in milliseconds */
    const val CONNECTION_TIMEOUT_MS = 10 * 1000L
    
    // ============ Network Constants ============
    
    /** P2P listening port */
    const val P2P_PORT = 19370
    
    /** LAN discovery UDP port */
    const val LAN_DISCOVERY_PORT = 19371
    
    /** Maximum concurrent peer connections */
    const val MAX_PEER_CONNECTIONS = 50
    
    /** Maximum pending messages per recipient */
    const val MAX_PENDING_MESSAGES_PER_RECIPIENT = 1000
    
    // ============ Notification ============
    
    /** Notification channel ID for relay service */
    const val NOTIFICATION_CHANNEL_ID = "mumblechat_relay_service"
    
    /** Foreground service notification ID */
    const val FOREGROUND_NOTIFICATION_ID = 19370
    
    // ============ Tier Enum ============
    
    enum class RelayTier(
        val storageMB: Long,
        val uptimeHours: Int,
        val poolPercent: Int,
        val feeMultiplier: Int,
        val displayName: String
    ) {
        BRONZE(
            storageMB = StorageTiers.BRONZE_MB,
            uptimeHours = UptimeTiers.BRONZE_HOURS,
            poolPercent = PoolShare.BRONZE_PERCENT,
            feeMultiplier = FeeBonus.BRONZE_MULTIPLIER,
            displayName = "Bronze"
        ),
        SILVER(
            storageMB = StorageTiers.SILVER_MB,
            uptimeHours = UptimeTiers.SILVER_HOURS,
            poolPercent = PoolShare.SILVER_PERCENT,
            feeMultiplier = FeeBonus.SILVER_MULTIPLIER,
            displayName = "Silver"
        ),
        GOLD(
            storageMB = StorageTiers.GOLD_MB,
            uptimeHours = UptimeTiers.GOLD_HOURS,
            poolPercent = PoolShare.GOLD_PERCENT,
            feeMultiplier = FeeBonus.GOLD_MULTIPLIER,
            displayName = "Gold"
        ),
        PLATINUM(
            storageMB = StorageTiers.PLATINUM_MB,
            uptimeHours = UptimeTiers.PLATINUM_HOURS,
            poolPercent = PoolShare.PLATINUM_PERCENT,
            feeMultiplier = FeeBonus.PLATINUM_MULTIPLIER,
            displayName = "Platinum"
        );
        
        companion object {
            /**
             * Determine tier based on current uptime and storage.
             */
            fun calculateTier(dailyUptimeHours: Int, storageMB: Long): RelayTier {
                return when {
                    dailyUptimeHours >= PLATINUM.uptimeHours && storageMB >= PLATINUM.storageMB -> PLATINUM
                    dailyUptimeHours >= GOLD.uptimeHours && storageMB >= GOLD.storageMB -> GOLD
                    dailyUptimeHours >= SILVER.uptimeHours && storageMB >= SILVER.storageMB -> SILVER
                    else -> BRONZE
                }
            }
            
            /**
             * Get tier from ordinal value (0-3).
             */
            fun fromOrdinal(ordinal: Int): RelayTier {
                return values().getOrNull(ordinal) ?: BRONZE
            }
        }
    }
}
