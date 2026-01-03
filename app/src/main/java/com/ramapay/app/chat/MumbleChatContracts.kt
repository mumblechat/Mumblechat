package com.ramapay.app.chat

/**
 * MumbleChat Contract Addresses
 * AUTO-GENERATED - DO NOT EDIT
 * Deployed: 2026-01-02T16:09:08.803Z
 * Updated: MCT Token V3 Tokenomics + Governance
 */
object MumbleChatContracts {
    const val CHAIN_ID = 1370L
    const val RPC_URL = "https://blockchain.ramestta.com"
    const val EXPLORER_URL = "https://ramascan.com"
    const val API_URL = "https://latest-backendapi.ramascan.com/api/v1"
    
    // MCT Token (UUPS Proxy)
    const val MCT_TOKEN_PROXY = "0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE"
    const val MCT_TOKEN_IMPL = "0xC76ea6934D24615E9A348C5eF5Aed54E638A5AAD"
    
    // MumbleChat Registry (UUPS Proxy)
    const val REGISTRY_PROXY = "0x4f8D4955F370881B05b68D2344345E749d8632e3"
    const val REGISTRY_IMPL = "0xC69C387a67324A08d1410aEB770dB0AC18c9ad15"
    
    // MCT Token V3 Tokenomics
    const val MAX_SUPPLY = 1_000_000L              // 1,000,000 MCT (can be changed via governance)
    const val ABSOLUTE_MAX_SUPPLY = 10_000_000L   // 10M absolute limit (cannot exceed)
    const val INITIAL_SUPPLY = 1_000L              // 1,000 MCT initial
    const val HALVING_THRESHOLD = 100_000L         // Halve rewards every 100k MCT minted
    const val DAILY_MINT_CAP = 100L                // Max 100 MCT per day
    const val MESSAGES_PER_REWARD = 1000L          // 1 reward per 1000 messages
    const val BASE_REWARD_PER_1000_MSG = 0.001     // 0.001 MCT per 1000 messages
    const val MIN_RELAY_STAKE = 100L               // 100 MCT to become relay node
    
    // Transfer Fee (Post-Minting Era)
    const val TRANSFER_FEE_BPS = 10                // 0.1% transfer fee for relay rewards
    
    // Governance
    const val GOVERNANCE_THRESHOLD = 90            // 90% vote required to change max supply
    const val VOTING_PERIOD_DAYS = 7               // 7 day voting period
    
    // Relay Node Requirements
    const val RECOMMENDED_UPTIME_HOURS = 4         // 4+ hours/day recommended
    const val CACHE_SIZE_MB = 50                   // ~50 MB temporary cache
    const val BATTERY_USAGE_PERCENT = "2-5%"       // Approx battery usage per hour
}
