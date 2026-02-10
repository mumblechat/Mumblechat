package com.ramapay.app.chat.blockchain

import android.content.Context
import com.ramapay.app.chat.MumbleChatContracts
import com.ramapay.app.chat.core.WalletBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.http.HttpService
import timber.log.Timber
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * MumbleChat Blockchain Service
 * ═══════════════════════════════════════════════════════════════════════════
 * 
 * Interacts with MumbleChatRegistry and MCTToken contracts on Ramestta.
 * 
 * Features:
 * - Read registered identities (public keys)
 * - Get active relay nodes for P2P bootstrap
 * - Register identity on-chain
 * - Check relay node rewards
 */
@Singleton
class MumbleChatBlockchainService @Inject constructor(
    private val context: Context,
    private val walletBridge: WalletBridge
) {
    private val web3j: Web3j by lazy {
        Web3j.build(HttpService(MumbleChatContracts.RPC_URL))
    }
    
    companion object {
        private const val TAG = "MumbleChatBlockchain"
        private const val CHAIN_ID = MumbleChatContracts.CHAIN_ID
        private const val MUMBLECHAT_REGISTRY_ADDRESS = MumbleChatContracts.REGISTRY_PROXY
    }
    
    // ============ Registry Contract Functions ============
    
    /**
     * Get active relay nodes from the Registry contract.
     * These are phones that have staked MCT to become relay nodes.
     * 
     * @return List of relay node data (address, endpoint, stake, etc.)
     */
    suspend fun getActiveRelayNodes(): List<RelayNodeInfo> = withContext(Dispatchers.IO) {
        try {
            val function = Function(
                "getActiveRelayNodes",
                emptyList(),
                listOf(object : TypeReference<DynamicArray<Address>>() {})
            )
            
            val encodedFunction = FunctionEncoder.encode(function)
            val response = web3j.ethCall(
                Transaction.createEthCallTransaction(
                    null,
                    MumbleChatContracts.REGISTRY_PROXY,
                    encodedFunction
                ),
                DefaultBlockParameterName.LATEST
            ).send()
            
            if (response.hasError()) {
                Timber.e("$TAG: getActiveRelayNodes error: ${response.error.message}")
                return@withContext emptyList()
            }
            
            val output = FunctionReturnDecoder.decode(
                response.value,
                function.outputParameters
            )
            
            if (output.isEmpty()) {
                return@withContext emptyList()
            }
            
            @Suppress("UNCHECKED_CAST")
            val addresses = (output[0] as DynamicArray<Address>).value
            
            // Fetch details for each relay node
            addresses.mapNotNull { address ->
                getRelayNodeDetails(address.value)
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to get relay nodes")
            emptyList()
        }
    }
    
    /**
     * Get details of a specific relay node.
     */
    suspend fun getRelayNodeDetails(address: String): RelayNodeInfo? = withContext(Dispatchers.IO) {
        try {
            val function = Function(
                "getRelayNode",
                listOf(Address(address)),
                listOf(
                    object : TypeReference<Utf8String>() {},  // endpoint
                    object : TypeReference<Uint256>() {},      // stakedAmount
                    object : TypeReference<Uint256>() {},      // messagesRelayed
                    object : TypeReference<Uint256>() {},      // rewardsEarned
                    object : TypeReference<Bool>() {}          // isActive
                )
            )
            
            val encodedFunction = FunctionEncoder.encode(function)
            val response = web3j.ethCall(
                Transaction.createEthCallTransaction(
                    null,
                    MumbleChatContracts.REGISTRY_PROXY,
                    encodedFunction
                ),
                DefaultBlockParameterName.LATEST
            ).send()
            
            if (response.hasError()) {
                Timber.e("$TAG: getRelayNode error: ${response.error.message}")
                return@withContext null
            }
            
            val output = FunctionReturnDecoder.decode(
                response.value,
                function.outputParameters
            )
            
            if (output.size < 5) {
                return@withContext null
            }
            
            val isActive = (output[4] as Bool).value
            if (!isActive) {
                return@withContext null
            }
            
            RelayNodeInfo(
                walletAddress = address,
                endpoint = (output[0] as Utf8String).value,
                stakedAmount = (output[1] as Uint256).value,
                messagesRelayed = (output[2] as Uint256).value.toLong(),
                rewardsEarned = (output[3] as Uint256).value,
                isActive = true
            )
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to get relay node details for $address")
            null
        }
    }
    
    /**
     * Get relay node status with user-friendly values (V2 with tier info).
     * Returns null if user is not a relay node.
     */
    suspend fun getRelayNode(address: String): RelayNodeStatus? = withContext(Dispatchers.IO) {
        try {
            // V4.1: getRelayNode now returns 11 fields including registeredAt
            val function = Function(
                "getRelayNode",
                listOf(Address(address)),
                listOf(
                    object : TypeReference<Utf8String>() {},  // endpoint
                    object : TypeReference<Uint256>() {},      // stakedAmount
                    object : TypeReference<Uint256>() {},      // messagesRelayed
                    object : TypeReference<Uint256>() {},      // rewardsEarned
                    object : TypeReference<Bool>() {},         // isActive
                    object : TypeReference<Uint256>() {},      // dailyUptimeSeconds
                    object : TypeReference<Uint256>() {},      // storageMB
                    object : TypeReference<Uint256>() {},      // tier (enum as uint8)
                    object : TypeReference<Uint256>() {},      // rewardMultiplier
                    object : TypeReference<Bool>() {},         // isOnline
                    object : TypeReference<Uint256>() {}       // registeredAt (V4.1)
                )
            )
            
            val encodedFunction = FunctionEncoder.encode(function)
            val response = web3j.ethCall(
                Transaction.createEthCallTransaction(
                    null,
                    MumbleChatContracts.REGISTRY_PROXY,
                    encodedFunction
                ),
                DefaultBlockParameterName.LATEST
            ).send()
            
            if (response.hasError()) {
                Timber.e("$TAG: getRelayNode error: ${response.error.message}")
                return@withContext null
            }
            
            val output = FunctionReturnDecoder.decode(
                response.value,
                function.outputParameters
            )
            
            if (output.size < 11) {
                Timber.w("$TAG: getRelayNode returned ${output.size} fields, expected 11")
                return@withContext null
            }
            
            val isActive = (output[4] as Bool).value
            val stakedAmount = (output[1] as Uint256).value
            val rewardsEarned = (output[3] as Uint256).value
            val dailyUptimeSeconds = (output[5] as Uint256).value.toLong()
            val storageMB = (output[6] as Uint256).value.toInt()
            val tier = (output[7] as Uint256).value.toInt()
            val rewardMultiplier = (output[8] as Uint256).value.toDouble() / 100.0  // Basis points to decimal
            val isOnline = (output[9] as Bool).value
            val registeredAt = (output[10] as Uint256).value.toLong()  // V4.1: registeredAt timestamp
            
            RelayNodeStatus(
                endpoint = (output[0] as Utf8String).value,
                stakedAmount = stakedAmount.toDouble() / 1e18,
                registeredAt = registeredAt,  // V4.1: Now properly parsed from contract
                messagesRelayed = (output[2] as Uint256).value.toLong(),
                rewardsEarned = rewardsEarned.toDouble() / 1e18,
                isActive = isActive,
                dailyUptimeSeconds = dailyUptimeSeconds,
                storageMB = storageMB,
                tier = tier,
                rewardMultiplier = rewardMultiplier,
                isOnline = isOnline
            )
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to get relay node for $address")
            null
        }
    }
    
    // ============ V3.1 Daily Pool Functions ============
    
    /**
     * Data class for today's pool info
     */
    data class TodayPoolInfo(
        val dayId: Long,
        val totalRelays: Long,
        val totalWeightedRelays: Long,
        val poolAmount: Double,
        val numContributors: Int
    )
    
    /**
     * Data class for node's daily stats
     */
    data class MyTodayStats(
        val relayCount: Long,
        val weightedRelayCount: Long,
        val estimatedReward: Double
    )
    
    /**
     * Get today's pool info from the contract.
     */
    suspend fun getTodayPoolInfo(): TodayPoolInfo? = withContext(Dispatchers.IO) {
        try {
            val function = Function(
                "getTodayPoolInfo",
                emptyList(),
                listOf(
                    object : TypeReference<Uint256>() {},  // dayId
                    object : TypeReference<Uint256>() {},  // totalRelays
                    object : TypeReference<Uint256>() {},  // totalWeightedRelays
                    object : TypeReference<Uint256>() {},  // poolAmount
                    object : TypeReference<Uint256>() {}   // numContributors
                )
            )
            
            val encodedFunction = FunctionEncoder.encode(function)
            val response = web3j.ethCall(
                Transaction.createEthCallTransaction(
                    null,
                    MumbleChatContracts.REGISTRY_PROXY,
                    encodedFunction
                ),
                DefaultBlockParameterName.LATEST
            ).send()
            
            if (response.hasError()) {
                Timber.e("$TAG: getTodayPoolInfo error: ${response.error.message}")
                return@withContext null
            }
            
            val output = FunctionReturnDecoder.decode(
                response.value,
                function.outputParameters
            )
            
            if (output.size < 5) {
                return@withContext null
            }
            
            TodayPoolInfo(
                dayId = (output[0] as Uint256).value.toLong(),
                totalRelays = (output[1] as Uint256).value.toLong(),
                totalWeightedRelays = (output[2] as Uint256).value.toLong(),
                poolAmount = (output[3] as Uint256).value.toDouble() / 1e18,
                numContributors = (output[4] as Uint256).value.toInt()
            )
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to get today pool info")
            null
        }
    }
    
    /**
     * Get claimable reward for a node for a specific day.
     */
    suspend fun getClaimableReward(address: String, dayId: Long): Double = withContext(Dispatchers.IO) {
        try {
            val function = Function(
                "getClaimableReward",
                listOf(Address(address), Uint256(dayId)),
                listOf(object : TypeReference<Uint256>() {})
            )
            
            val encodedFunction = FunctionEncoder.encode(function)
            val response = web3j.ethCall(
                Transaction.createEthCallTransaction(
                    null,
                    MumbleChatContracts.REGISTRY_PROXY,
                    encodedFunction
                ),
                DefaultBlockParameterName.LATEST
            ).send()
            
            if (response.hasError()) {
                Timber.e("$TAG: getClaimableReward error: ${response.error.message}")
                return@withContext 0.0
            }
            
            val output = FunctionReturnDecoder.decode(
                response.value,
                function.outputParameters
            )
            
            if (output.isEmpty()) {
                return@withContext 0.0
            }
            
            (output[0] as Uint256).value.toDouble() / 1e18
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to get claimable reward")
            0.0
        }
    }
    
    /**
     * Get my stats for today.
     */
    suspend fun getMyTodayStats(address: String): MyTodayStats? = withContext(Dispatchers.IO) {
        try {
            // This function uses msg.sender, so we need to call via the address
            // For view-only, we'll reconstruct from nodeDailyStats mapping
            val today = System.currentTimeMillis() / 1000 / 86400
            
            val function = Function(
                "nodeDailyStats",
                listOf(Address(address), Uint256(today)),
                listOf(
                    object : TypeReference<Uint256>() {},  // relayCount
                    object : TypeReference<Uint256>() {},  // weightedRelayCount
                    object : TypeReference<Bool>() {}      // claimed
                )
            )
            
            val encodedFunction = FunctionEncoder.encode(function)
            val response = web3j.ethCall(
                Transaction.createEthCallTransaction(
                    null,
                    MumbleChatContracts.REGISTRY_PROXY,
                    encodedFunction
                ),
                DefaultBlockParameterName.LATEST
            ).send()
            
            if (response.hasError()) {
                Timber.e("$TAG: getMyTodayStats error: ${response.error.message}")
                return@withContext null
            }
            
            val output = FunctionReturnDecoder.decode(
                response.value,
                function.outputParameters
            )
            
            if (output.size < 3) {
                return@withContext null
            }
            
            val relayCount = (output[0] as Uint256).value.toLong()
            val weightedRelayCount = (output[1] as Uint256).value.toLong()
            
            // Calculate estimated reward
            val poolInfo = getTodayPoolInfo()
            val estimatedReward = if (poolInfo != null && poolInfo.totalWeightedRelays > 0) {
                val totalActualEarned = poolInfo.totalWeightedRelays * 0.001 / 100 // 0.001 MCT per relay, weighted by basis points
                val effectivePool = minOf(poolInfo.poolAmount, totalActualEarned)
                (weightedRelayCount.toDouble() * effectivePool) / poolInfo.totalWeightedRelays
            } else {
                0.0
            }
            
            MyTodayStats(
                relayCount = relayCount,
                weightedRelayCount = weightedRelayCount,
                estimatedReward = estimatedReward
            )
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to get my today stats")
            null
        }
    }
    
    /**
     * Get the fee pool balance from MCT token.
     */
    suspend fun getFeePoolBalance(): Double = withContext(Dispatchers.IO) {
        try {
            val function = Function(
                "feePool",  // Correct function name from Solidity contract
                emptyList(),
                listOf(object : TypeReference<Uint256>() {})
            )
            
            val encodedFunction = FunctionEncoder.encode(function)
            val response = web3j.ethCall(
                Transaction.createEthCallTransaction(
                    null,
                    MumbleChatContracts.MCT_TOKEN_PROXY,
                    encodedFunction
                ),
                DefaultBlockParameterName.LATEST
            ).send()
            
            if (response.hasError()) {
                Timber.e("$TAG: getFeePoolBalance error: ${response.error.message}")
                return@withContext 0.0
            }
            
            val output = FunctionReturnDecoder.decode(
                response.value,
                function.outputParameters
            )
            
            if (output.isEmpty()) {
                return@withContext 0.0
            }
            
            (output[0] as Uint256).value.toDouble() / 1e18
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to get fee pool balance")
            0.0
        }
    }
    
    /**
     * Check if an address is registered on MumbleChat.
     */
    suspend fun isRegistered(address: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val function = Function(
                "isRegistered",
                listOf(Address(address)),
                listOf(object : TypeReference<Bool>() {})
            )
            
            val encodedFunction = FunctionEncoder.encode(function)
            val response = web3j.ethCall(
                Transaction.createEthCallTransaction(
                    null,
                    MumbleChatContracts.REGISTRY_PROXY,
                    encodedFunction
                ),
                DefaultBlockParameterName.LATEST
            ).send()
            
            if (response.hasError()) {
                return@withContext false
            }
            
            val output = FunctionReturnDecoder.decode(
                response.value,
                function.outputParameters
            )
            
            if (output.isEmpty()) {
                return@withContext false
            }
            
            (output[0] as Bool).value
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to check registration for $address")
            false
        }
    }
    
    /**
     * Get public key for an address.
     */
    suspend fun getPublicKey(address: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val function = Function(
                "getPublicKey",
                listOf(Address(address)),
                listOf(object : TypeReference<Bytes32>() {})
            )
            
            val encodedFunction = FunctionEncoder.encode(function)
            val response = web3j.ethCall(
                Transaction.createEthCallTransaction(
                    null,
                    MumbleChatContracts.REGISTRY_PROXY,
                    encodedFunction
                ),
                DefaultBlockParameterName.LATEST
            ).send()
            
            if (response.hasError()) {
                return@withContext null
            }
            
            val output = FunctionReturnDecoder.decode(
                response.value,
                function.outputParameters
            )
            
            if (output.isEmpty()) {
                return@withContext null
            }
            
            (output[0] as Bytes32).value
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to get public key for $address")
            null
        }
    }
    
    /**
     * Get full identity information for an address.
     */
    data class IdentityInfo(
        val publicKey: String,
        val displayName: String,
        val registeredAt: Long,
        val lastUpdated: Long,
        val isActive: Boolean
    )
    
    suspend fun getIdentity(address: String): IdentityInfo? = withContext(Dispatchers.IO) {
        try {
            val function = Function(
                "getIdentity",
                listOf(Address(address)),
                listOf(
                    object : TypeReference<Bytes32>() {},   // publicKeyX
                    object : TypeReference<Uint256>() {},   // registeredAt
                    object : TypeReference<Uint256>() {},   // lastUpdated
                    object : TypeReference<Bool>() {},      // isActive
                    object : TypeReference<Utf8String>() {} // displayName
                )
            )
            
            val encodedFunction = FunctionEncoder.encode(function)
            val response = web3j.ethCall(
                Transaction.createEthCallTransaction(
                    null,
                    MumbleChatContracts.REGISTRY_PROXY,
                    encodedFunction
                ),
                DefaultBlockParameterName.LATEST
            ).send()
            
            if (response.hasError()) {
                Timber.e("$TAG: getIdentity error: ${response.error.message}")
                return@withContext null
            }
            
            val output = FunctionReturnDecoder.decode(
                response.value,
                function.outputParameters
            )
            
            if (output.size < 5) {
                return@withContext null
            }
            
            IdentityInfo(
                publicKey = (output[0] as Bytes32).value.toString(),
                registeredAt = (output[1] as Uint256).value.toLong(),
                lastUpdated = (output[2] as Uint256).value.toLong(),
                isActive = (output[3] as Bool).value,
                displayName = (output[4] as Utf8String).value
            )
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to get identity for $address")
            null
        }
    }
    
    /**
     * Get total number of registered users.
     */
    suspend fun getTotalUsers(): Long = withContext(Dispatchers.IO) {
        try {
            val function = Function(
                "totalUsers",
                emptyList(),
                listOf(object : TypeReference<Uint256>() {})
            )
            
            val encodedFunction = FunctionEncoder.encode(function)
            val response = web3j.ethCall(
                Transaction.createEthCallTransaction(
                    null,
                    MumbleChatContracts.REGISTRY_PROXY,
                    encodedFunction
                ),
                DefaultBlockParameterName.LATEST
            ).send()
            
            if (response.hasError()) {
                return@withContext 0L
            }
            
            val output = FunctionReturnDecoder.decode(
                response.value,
                function.outputParameters
            )
            
            if (output.isEmpty()) {
                return@withContext 0L
            }
            
            (output[0] as Uint256).value.toLong()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to get total users")
            0L
        }
    }
    
    /**
     * Get total number of active relay nodes.
     */
    suspend fun getTotalRelayNodes(): Long = withContext(Dispatchers.IO) {
        try {
            val function = Function(
                "totalRelayNodes",
                emptyList(),
                listOf(object : TypeReference<Uint256>() {})
            )
            
            val encodedFunction = FunctionEncoder.encode(function)
            val response = web3j.ethCall(
                Transaction.createEthCallTransaction(
                    null,
                    MumbleChatContracts.REGISTRY_PROXY,
                    encodedFunction
                ),
                DefaultBlockParameterName.LATEST
            ).send()
            
            if (response.hasError()) {
                return@withContext 0L
            }
            
            val output = FunctionReturnDecoder.decode(
                response.value,
                function.outputParameters
            )
            
            if (output.isEmpty()) {
                return@withContext 0L
            }
            
            (output[0] as Uint256).value.toLong()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to get total relay nodes")
            0L
        }
    }
    
    // ============ MCT Token Functions ============
    
    /**
     * Get MCT token balance for an address.
     */
    suspend fun getMCTBalance(address: String): BigInteger = withContext(Dispatchers.IO) {
        try {
            val function = Function(
                "balanceOf",
                listOf(Address(address)),
                listOf(object : TypeReference<Uint256>() {})
            )
            
            val encodedFunction = FunctionEncoder.encode(function)
            val response = web3j.ethCall(
                Transaction.createEthCallTransaction(
                    null,
                    MumbleChatContracts.MCT_TOKEN_PROXY,
                    encodedFunction
                ),
                DefaultBlockParameterName.LATEST
            ).send()
            
            if (response.hasError()) {
                return@withContext BigInteger.ZERO
            }
            
            val output = FunctionReturnDecoder.decode(
                response.value,
                function.outputParameters
            )
            
            if (output.isEmpty()) {
                return@withContext BigInteger.ZERO
            }
            
            (output[0] as Uint256).value
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to get MCT balance for $address")
            BigInteger.ZERO
        }
    }
    
    /**
     * Get current relay reward amount (0.01% of total supply).
     */
    suspend fun calculateRelayReward(): BigInteger = withContext(Dispatchers.IO) {
        try {
            val function = Function(
                "calculateRelayReward",
                emptyList(),
                listOf(object : TypeReference<Uint256>() {})
            )
            
            val encodedFunction = FunctionEncoder.encode(function)
            val response = web3j.ethCall(
                Transaction.createEthCallTransaction(
                    null,
                    MumbleChatContracts.MCT_TOKEN_PROXY,
                    encodedFunction
                ),
                DefaultBlockParameterName.LATEST
            ).send()
            
            if (response.hasError()) {
                return@withContext BigInteger.ZERO
            }
            
            val output = FunctionReturnDecoder.decode(
                response.value,
                function.outputParameters
            )
            
            if (output.isEmpty()) {
                return@withContext BigInteger.ZERO
            }
            
            (output[0] as Uint256).value
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to calculate relay reward")
            BigInteger.ZERO
        }
    }
    
    // ============ User Blocking Functions ============
    
    /**
     * Block a user on the blockchain.
     * This adds the address to the caller's blocked list.
     * 
     * NOTE: This is a write operation that requires a signed transaction.
     * For now, we store blocking locally and sync to chain when possible.
     * 
     * @param addressToBlock The wallet address to block
     * @return Transaction hash or null if failed
     */
    suspend fun blockUser(addressToBlock: String): String? = withContext(Dispatchers.IO) {
        try {
            // For now, just log and return a placeholder
            // Full implementation requires signed transaction support
            Timber.d("$TAG: Blocking user $addressToBlock (local only for now)")
            "local-block-${System.currentTimeMillis()}"
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to block user $addressToBlock")
            null
        }
    }
    
    /**
     * Unblock a user on the blockchain.
     * This removes the address from the caller's blocked list.
     * 
     * NOTE: This is a write operation that requires a signed transaction.
     * For now, we store blocking locally and sync to chain when possible.
     * 
     * @param addressToUnblock The wallet address to unblock
     * @return Transaction hash or null if failed
     */
    suspend fun unblockUser(addressToUnblock: String): String? = withContext(Dispatchers.IO) {
        try {
            // For now, just log and return a placeholder
            // Full implementation requires signed transaction support
            Timber.d("$TAG: Unblocking user $addressToUnblock (local only for now)")
            "local-unblock-${System.currentTimeMillis()}"
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to unblock user $addressToUnblock")
            null
        }
    }
    
    /**
     * Check if a user is blocked on the blockchain.
     * 
     * @param blocker The wallet address of the potential blocker
     * @param blocked The wallet address to check
     * @return True if blocked, false otherwise
     */
    suspend fun isBlocked(blocker: String, blocked: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val function = Function(
                "isBlocked",
                listOf(Address(blocker), Address(blocked)),
                listOf(object : TypeReference<Bool>() {})
            )
            
            val encodedFunction = FunctionEncoder.encode(function)
            val response = web3j.ethCall(
                Transaction.createEthCallTransaction(
                    null,
                    MumbleChatContracts.REGISTRY_PROXY,
                    encodedFunction
                ),
                DefaultBlockParameterName.LATEST
            ).send()
            
            if (response.hasError()) {
                return@withContext false
            }
            
            val output = FunctionReturnDecoder.decode(
                response.value,
                function.outputParameters
            )
            
            if (output.isEmpty()) {
                return@withContext false
            }
            
            (output[0] as Bool).value
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to check block status")
            false
        }
    }
    
    /**
     * Check if sender can send message to recipient.
     * Verifies both are registered and recipient hasn't blocked sender.
     * 
     * @param sender The wallet address of the sender
     * @param recipient The wallet address of the recipient
     * @return True if message can be sent, false otherwise
     */
    suspend fun canSendMessage(sender: String, recipient: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val function = Function(
                "canSendMessage",
                listOf(Address(sender), Address(recipient)),
                listOf(object : TypeReference<Bool>() {})
            )
            
            val encodedFunction = FunctionEncoder.encode(function)
            val response = web3j.ethCall(
                Transaction.createEthCallTransaction(
                    null,
                    MumbleChatContracts.REGISTRY_PROXY,
                    encodedFunction
                ),
                DefaultBlockParameterName.LATEST
            ).send()
            
            if (response.hasError()) {
                // If contract doesn't support this function, fallback to isBlocked check
                val isBlockedByRecipient = isBlocked(recipient, sender)
                return@withContext !isBlockedByRecipient
            }
            
            val output = FunctionReturnDecoder.decode(
                response.value,
                function.outputParameters
            )
            
            if (output.isEmpty()) {
                // Fallback: check if blocked
                val isBlockedByRecipient = isBlocked(recipient, sender)
                return@withContext !isBlockedByRecipient
            }
            
            (output[0] as Bool).value
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to check canSendMessage, falling back to isBlocked")
            // Fallback to isBlocked check
            try {
                !isBlocked(recipient, sender)
            } catch (e2: Exception) {
                Timber.e(e2, "$TAG: Fallback isBlocked also failed")
                true // Allow sending if we can't verify
            }
        }
    }

    /**
     * Get list of blocked users for an address.
     * 
     * @param address The wallet address to get blocked list for
     * @return List of blocked wallet addresses
     */
    suspend fun getBlockedUsers(address: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val function = Function(
                "getBlockedUsers",
                listOf(Address(address)),
                listOf(object : TypeReference<DynamicArray<Address>>() {})
            )
            
            val encodedFunction = FunctionEncoder.encode(function)
            val response = web3j.ethCall(
                Transaction.createEthCallTransaction(
                    null,
                    MumbleChatContracts.REGISTRY_PROXY,
                    encodedFunction
                ),
                DefaultBlockParameterName.LATEST
            ).send()
            
            if (response.hasError()) {
                return@withContext emptyList()
            }
            
            val output = FunctionReturnDecoder.decode(
                response.value,
                function.outputParameters
            )
            
            if (output.isEmpty()) {
                return@withContext emptyList()
            }
            
            @Suppress("UNCHECKED_CAST")
            (output[0] as DynamicArray<Address>).value.map { it.value }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to get blocked users")
            emptyList()
        }
    }
    
    // ============ Transaction Confirmation Helpers ============
    
    /**
     * Wait for a transaction to be confirmed with the specified number of block confirmations.
     * 
     * @param txHash The transaction hash to wait for
     * @param requiredConfirmations Number of block confirmations required (default 2)
     * @param maxWaitTimeMs Maximum time to wait in milliseconds (default 60 seconds)
     * @param pollIntervalMs Interval between checks in milliseconds (default 2 seconds)
     * @return TransactionConfirmation with status, or null if timeout
     */
    suspend fun waitForConfirmations(
        txHash: String,
        requiredConfirmations: Int = 2,
        maxWaitTimeMs: Long = 60000,
        pollIntervalMs: Long = 2000
    ): TransactionConfirmation? = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        Timber.d("$TAG: Waiting for $requiredConfirmations confirmations on tx: $txHash")
        
        while (System.currentTimeMillis() - startTime < maxWaitTimeMs) {
            try {
                // Get transaction receipt
                val receipt = web3j.ethGetTransactionReceipt(txHash).send()
                
                if (receipt.transactionReceipt.isPresent) {
                    val txReceipt = receipt.transactionReceipt.get()
                    val txBlockNumber = txReceipt.blockNumber.toLong()
                    
                    // Get current block number
                    val currentBlock = web3j.ethBlockNumber().send().blockNumber.toLong()
                    val confirmations = (currentBlock - txBlockNumber + 1).toInt()
                    
                    Timber.d("$TAG: Tx $txHash at block $txBlockNumber, current block $currentBlock, confirmations: $confirmations")
                    
                    if (confirmations >= requiredConfirmations) {
                        val status = txReceipt.status == "0x1"
                        val gasUsed = txReceipt.gasUsed?.toLong() ?: 0L
                        
                        Timber.d("$TAG: Tx $txHash confirmed! Status: $status, Gas used: $gasUsed")
                        
                        return@withContext TransactionConfirmation(
                            txHash = txHash,
                            blockNumber = txBlockNumber,
                            confirmations = confirmations,
                            status = status,
                            gasUsed = gasUsed
                        )
                    }
                }
                
                // Wait before next check
                kotlinx.coroutines.delay(pollIntervalMs)
                
            } catch (e: Exception) {
                Timber.w(e, "$TAG: Error checking tx confirmation, retrying...")
                kotlinx.coroutines.delay(pollIntervalMs)
            }
        }
        
        Timber.w("$TAG: Timeout waiting for tx $txHash confirmations")
        null
    }
    
    /**
     * Check if a transaction has been mined (included in a block).
     */
    suspend fun isTransactionMined(txHash: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val receipt = web3j.ethGetTransactionReceipt(txHash).send()
            receipt.transactionReceipt.isPresent
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error checking if tx is mined")
            false
        }
    }
    
    /**
     * Get current block number.
     */
    suspend fun getCurrentBlockNumber(): Long = withContext(Dispatchers.IO) {
        try {
            web3j.ethBlockNumber().send().blockNumber.toLong()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error getting current block")
            0L
        }
    }
    
    /**
     * Get current gas price from the network.
     * Returns a reasonable default if the call fails.
     */
    suspend fun getGasPrice(): BigInteger = withContext(Dispatchers.IO) {
        try {
            val gasPrice = web3j.ethGasPrice().send().gasPrice
            Timber.d("$TAG: Current gas price: $gasPrice")
            // Add 20% buffer to ensure transaction goes through
            gasPrice.multiply(BigInteger.valueOf(120)).divide(BigInteger.valueOf(100))
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error getting gas price, using default")
            // Default gas price: 10 Gwei (should work for most cases)
            BigInteger.valueOf(10_000_000_000L)
        }
    }
    
    // ============ Heartbeat ============
    
    /**
     * Send a heartbeat to the blockchain to prove the node is online.
     * This updates the lastSeen timestamp and storage amount.
     * 
     * Note: In production, this would be routed through the wallet's 
     * transaction signing infrastructure. For now, we return a mock hash
     * and log the attempt for testing.
     * 
     * @param storageMB Current storage usage in MB
     * @return Transaction hash if successful, null otherwise
     */
    suspend fun sendHeartbeat(storageMB: Long): String? = withContext(Dispatchers.IO) {
        try {
            val walletAddress = walletBridge.getCurrentWalletAddress()
                ?: throw Exception("No wallet connected")
            
            Timber.d("$TAG: Sending heartbeat with storage: ${storageMB}MB")
            
            // Call heartbeat function on contract
            // Function: heartbeat(uint256 storageMB)
            val function = Function(
                "heartbeat",
                listOf(Uint256(storageMB)),
                emptyList()
            )
            
            val encodedFunction = FunctionEncoder.encode(function)
            
            // Create call transaction (read-only to check if function exists)
            val transaction = Transaction.createEthCallTransaction(
                walletAddress,
                MUMBLECHAT_REGISTRY_ADDRESS,
                encodedFunction
            )
            
            // For now, simulate the heartbeat (actual signing would require wallet integration)
            // In production, this would go through wallet's transaction signing flow
            Timber.d("$TAG: Heartbeat prepared for $walletAddress to $MUMBLECHAT_REGISTRY_ADDRESS")
            Timber.d("$TAG: Function data: $encodedFunction")
            
            // Return a mock transaction hash for UI testing
            // In production, this would be the actual tx hash from signed transaction
            val mockTxHash = "0x" + java.util.UUID.randomUUID().toString().replace("-", "")
            Timber.d("$TAG: Heartbeat simulated! Mock TX: $mockTxHash")
            
            mockTxHash
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to send heartbeat")
            null
        }
    }
}

/**
 * Data class for relay node information.
 */
data class RelayNodeInfo(
    val walletAddress: String,
    val endpoint: String,
    val stakedAmount: BigInteger,
    val messagesRelayed: Long,
    val rewardsEarned: BigInteger,
    val isActive: Boolean
)

/**
 * Data class for transaction confirmation status.
 */
data class TransactionConfirmation(
    val txHash: String,
    val blockNumber: Long,
    val confirmations: Int,
    val status: Boolean,    // true = success, false = failed/reverted
    val gasUsed: Long
)

/**
 * Data class for relay node status V2 (with tier info).
 */
data class RelayNodeStatus(
    val endpoint: String,
    val stakedAmount: Double,
    val registeredAt: Long,
    val messagesRelayed: Long,
    val rewardsEarned: Double,
    val isActive: Boolean,
    // V2 fields
    val dailyUptimeSeconds: Long = 0,
    val storageMB: Int = 50,
    val tier: Int = 0,  // 0=Bronze, 1=Silver, 2=Gold, 3=Platinum
    val rewardMultiplier: Double = 1.0,
    val isOnline: Boolean = false
) {
    val tierName: String
        get() = when (tier) {
            0 -> "Bronze"
            1 -> "Silver"
            2 -> "Gold"
            3 -> "Platinum"
            else -> "Bronze"
        }

    val dailyUptimeHours: Double
        get() = dailyUptimeSeconds / 3600.0
}

