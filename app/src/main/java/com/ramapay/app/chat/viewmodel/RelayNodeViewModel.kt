package com.ramapay.app.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ramapay.app.chat.MumbleChatContracts
import com.ramapay.app.chat.blockchain.RelayNodeStatus
import com.ramapay.app.chat.core.WalletBridge
import com.ramapay.app.chat.registry.RegistrationManager
import com.ramapay.app.entity.TransactionReturn
import com.ramapay.app.interact.CreateTransactionInteract
import com.ramapay.app.service.KeyService
import com.ramapay.app.service.TokensService
import com.ramapay.app.service.TransactionSendHandlerInterface
import com.ramapay.app.web3.entity.Address
import com.ramapay.app.web3.entity.Web3Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Uint256
import timber.log.Timber
import java.math.BigDecimal
import java.math.BigInteger
import javax.inject.Inject

/**
 * ViewModel for managing relay node status and operations.
 * Supports V2 features: Tier system, fee rewards, protection protocol
 */
@HiltViewModel
class RelayNodeViewModel @Inject constructor(
    private val walletBridge: WalletBridge,
    private val registrationManager: RegistrationManager,
    private val tokensService: TokensService,
    private val keyService: KeyService,
    private val createTransactionInteract: CreateTransactionInteract
) : ViewModel(), TransactionSendHandlerInterface {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _relayStatus = MutableStateFlow<RelayNodeStatus?>(null)
    val relayStatus: StateFlow<RelayNodeStatus?> = _relayStatus

    private val _mctBalance = MutableStateFlow(0.0)
    val mctBalance: StateFlow<Double> = _mctBalance

    private val _isRegistered = MutableStateFlow(false)
    val isRegistered: StateFlow<Boolean> = _isRegistered

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _success = MutableStateFlow<String?>(null)
    val success: StateFlow<String?> = _success

    // V2: Tier and security info
    private val _tierInfo = MutableStateFlow<TierInfo?>(null)
    val tierInfo: StateFlow<TierInfo?> = _tierInfo

    private val _securityInfo = MutableStateFlow<SecurityInfo?>(null)
    val securityInfo: StateFlow<SecurityInfo?> = _securityInfo

    private val _feePoolShare = MutableStateFlow(0.0)
    val feePoolShare: StateFlow<Double> = _feePoolShare

    // V3.1: Daily pool stats
    private val _dailyPoolStats = MutableStateFlow<DailyPoolStats?>(null)
    val dailyPoolStats: StateFlow<DailyPoolStats?> = _dailyPoolStats

    private val _claimableReward = MutableStateFlow(0.0)
    val claimableReward: StateFlow<Double> = _claimableReward

    private var pendingOperation: String? = null

    // Data classes for UI
    data class TierInfo(
        val tier: String,           // Bronze, Silver, Gold, Platinum
        val multiplier: Double,     // 1.0, 1.5, 2.0, 3.0
        val dailyUptimeHours: Double,
        val storageMB: Int,
        val nextTier: String?,
        val uptimeNeeded: Double,   // Hours needed for next tier
        val storageNeeded: Int      // MB needed for next tier
    )

    data class SecurityInfo(
        val reputation: Int,        // 0-100
        val violations: Int,
        val isBlacklisted: Boolean,
        val canOperate: Boolean
    )
    
    // V3.1: Daily pool stats
    data class DailyPoolStats(
        val dayId: Long,                // Today's day ID
        val myRelaysToday: Long,        // My relay count today
        val networkRelaysToday: Long,   // Total network relays today
        val numContributors: Int,       // Number of nodes contributing
        val poolAmount: Double,         // Pool amount (100 MCT)
        val estimatedReward: Double,    // My estimated share
        val yesterdayClaimable: Double  // Claimable from yesterday
    )

    fun loadRelayStatus() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                val wallet = walletBridge.getCurrentWallet()
                if (wallet == null) {
                    _error.value = "No wallet available"
                    _isLoading.value = false
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    // Check if user is registered
                    val identity = registrationManager.blockchainService.getIdentity(wallet.address)
                    _isRegistered.value = identity?.isActive == true

                    // Get MCT balance
                    val mctBalance = getMctBalance(wallet.address)
                    _mctBalance.value = mctBalance

                    // Get relay node status (V2 with tier info)
                    val relayInfo = getRelayNodeInfo(wallet.address)
                    _relayStatus.value = relayInfo

                    // Parse tier info from relay status
                    if (relayInfo?.isActive == true) {
                        _tierInfo.value = parseTierInfo(relayInfo)
                        _securityInfo.value = getSecurityInfo(wallet.address)
                        loadFeePoolShare(wallet.address)
                    }
                }

            } catch (e: Exception) {
                Timber.e(e, "Failed to load relay status")
                _error.value = "Failed to load relay status: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun parseTierInfo(relay: RelayNodeStatus): TierInfo {
        val uptimeHours = relay.dailyUptimeSeconds / 3600.0
        val storageMB = relay.storageMB

        // Determine current tier - GB scale thresholds
        // Bronze: 1GB (1024MB), 4h | Silver: 2GB (2048MB), 8h | Gold: 4GB (4096MB), 12h | Platinum: 8GB (8192MB), 16h
        val (tier, multiplier) = when {
            uptimeHours >= 16 && storageMB >= 8192 -> "Platinum" to 3.0
            uptimeHours >= 12 && storageMB >= 4096 -> "Gold" to 2.0
            uptimeHours >= 8 && storageMB >= 2048 -> "Silver" to 1.5
            else -> "Bronze" to 1.0
        }

        // Calculate next tier requirements (with GB thresholds)
        val (nextTier, uptimeNeeded, storageNeeded) = when (tier) {
            "Bronze" -> Triple("Silver", maxOf(0.0, 8.0 - uptimeHours), maxOf(0, 2048 - storageMB))
            "Silver" -> Triple("Gold", maxOf(0.0, 12.0 - uptimeHours), maxOf(0, 4096 - storageMB))
            "Gold" -> Triple("Platinum", maxOf(0.0, 16.0 - uptimeHours), maxOf(0, 8192 - storageMB))
            else -> Triple(null, 0.0, 0)
        }

        return TierInfo(
            tier = tier,
            multiplier = multiplier,
            dailyUptimeHours = uptimeHours,
            storageMB = storageMB,
            nextTier = nextTier,
            uptimeNeeded = uptimeNeeded,
            storageNeeded = storageNeeded
        )
    }

    private suspend fun getSecurityInfo(walletAddress: String): SecurityInfo {
        // In production, this would call the contract's getNodeSecurityInfo
        // For now, return default values
        return SecurityInfo(
            reputation = 50,
            violations = 0,
            isBlacklisted = false,
            canOperate = true
        )
    }

    private suspend fun getMctBalance(walletAddress: String): Double {
        return try {
            val token = tokensService.getToken(
                MumbleChatContracts.CHAIN_ID,
                MumbleChatContracts.MCT_TOKEN_PROXY
            )
            if (token != null) {
                token.balance.divide(BigDecimal.TEN.pow(18)).toDouble()
            } else {
                0.0
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to get MCT balance")
            0.0
        }
    }

    private suspend fun getRelayNodeInfo(walletAddress: String): RelayNodeStatus? {
        return try {
            registrationManager.blockchainService.getRelayNode(walletAddress)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get relay node info")
            null
        }
    }

    private suspend fun loadFeePoolShare(walletAddress: String) {
        // In production, call the MCT token contract to get claimable fee rewards
        // The fee pool share depends on: totalFeePool * (nodeUptime / totalUptime) * tierMultiplier
        // For now, estimate based on tier and uptime
        val status = _relayStatus.value ?: return
        val tierInfo = _tierInfo.value ?: return
        
        // Simplified calculation: base share scaled by tier
        // Real implementation would call claimableFeeReward() on MCT contract
        val baseShare = (status.messagesRelayed / 1000.0) * 0.0001 // Estimated
        _feePoolShare.value = baseShare * tierInfo.multiplier
    }

    fun activateAsRelay() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            pendingOperation = "activate"

            try {
                val wallet = walletBridge.getCurrentWallet()
                if (wallet == null) {
                    _error.value = "No wallet available"
                    _isLoading.value = false
                    return@launch
                }

                // Check if registered
                if (_isRegistered.value != true) {
                    _error.value = "You must register on MumbleChat first"
                    _isLoading.value = false
                    return@launch
                }

                // Check MCT balance
                if (_mctBalance.value < 100.0) {
                    _error.value = "Insufficient MCT balance. You need 100 MCT to stake."
                    _isLoading.value = false
                    return@launch
                }

                // First, approve MCT spending
                // Then register as relay
                // For simplicity, we'll do the registerAsRelay transaction
                // In production, you'd first do an approve() transaction

                // Generate P2P endpoint (device-specific)
                val endpoint = generateP2PEndpoint(wallet.address)

                // Encode registerAsRelay function call
                val function = Function(
                    "registerAsRelay",
                    listOf(Utf8String(endpoint)),
                    emptyList()
                )
                val txData = FunctionEncoder.encode(function)

                // Create transaction
                val tx = Web3Transaction(
                    Address(wallet.address),
                    Address(MumbleChatContracts.REGISTRY_PROXY),
                    BigInteger.ZERO,
                    BigInteger.ZERO,
                    BigInteger.valueOf(300000), // Higher gas for stake transfer
                    -1,
                    txData
                )

                // Request signature and send
                createTransactionInteract.requestSignature(tx, wallet, MumbleChatContracts.CHAIN_ID, this@RelayNodeViewModel)

            } catch (e: Exception) {
                Timber.e(e, "Failed to activate as relay")
                _error.value = "Failed to activate: ${e.message}"
                _isLoading.value = false
            }
        }
    }

    fun deactivateRelay() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            pendingOperation = "deactivate"

            try {
                val wallet = walletBridge.getCurrentWallet()
                if (wallet == null) {
                    _error.value = "No wallet available"
                    _isLoading.value = false
                    return@launch
                }

                // Encode deactivateRelay function call
                val function = Function(
                    "deactivateRelay",
                    emptyList(),
                    emptyList()
                )
                val txData = FunctionEncoder.encode(function)

                // Create transaction
                val tx = Web3Transaction(
                    Address(wallet.address),
                    Address(MumbleChatContracts.REGISTRY_PROXY),
                    BigInteger.ZERO,
                    BigInteger.ZERO,
                    BigInteger.valueOf(200000),
                    -1,
                    txData
                )

                // Request signature and send
                createTransactionInteract.requestSignature(tx, wallet, MumbleChatContracts.CHAIN_ID, this@RelayNodeViewModel)

            } catch (e: Exception) {
                Timber.e(e, "Failed to deactivate relay")
                _error.value = "Failed to deactivate: ${e.message}"
                _isLoading.value = false
            }
        }
    }

    private fun generateP2PEndpoint(walletAddress: String): String {
        // Generate a P2P multiaddr endpoint for this device
        // In production, this would use the actual P2P library
        return "/dns4/relay.mumblechat.io/tcp/4001/p2p/${walletAddress.lowercase()}"
    }

    // V2: Send heartbeat to update uptime
    fun sendHeartbeat() {
        viewModelScope.launch {
            _isLoading.value = true
            pendingOperation = "heartbeat"

            try {
                val wallet = walletBridge.getCurrentWallet() ?: return@launch

                val function = Function(
                    "heartbeat",
                    emptyList(),
                    emptyList()
                )
                val txData = FunctionEncoder.encode(function)

                val tx = Web3Transaction(
                    Address(wallet.address),
                    Address(MumbleChatContracts.REGISTRY_PROXY),
                    BigInteger.ZERO,
                    BigInteger.ZERO,
                    BigInteger.valueOf(100000),
                    -1,
                    txData
                )

                createTransactionInteract.requestSignature(tx, wallet, MumbleChatContracts.CHAIN_ID, this@RelayNodeViewModel)
            } catch (e: Exception) {
                Timber.e(e, "Failed to send heartbeat")
                _error.value = "Heartbeat failed: ${e.message}"
                _isLoading.value = false
            }
        }
    }

    // V2: Claim fee pool rewards with tier multiplier
    fun claimFeeReward() {
        viewModelScope.launch {
            _isLoading.value = true
            pendingOperation = "claim_fee"

            try {
                val wallet = walletBridge.getCurrentWallet() ?: return@launch

                val function = Function(
                    "claimFeeReward",
                    emptyList(),
                    emptyList()
                )
                val txData = FunctionEncoder.encode(function)

                val tx = Web3Transaction(
                    Address(wallet.address),
                    Address(MumbleChatContracts.REGISTRY_PROXY),
                    BigInteger.ZERO,
                    BigInteger.ZERO,
                    BigInteger.valueOf(150000),
                    -1,
                    txData
                )

                createTransactionInteract.requestSignature(tx, wallet, MumbleChatContracts.CHAIN_ID, this@RelayNodeViewModel)
            } catch (e: Exception) {
                Timber.e(e, "Failed to claim fee reward")
                _error.value = "Claim failed: ${e.message}"
                _isLoading.value = false
            }
        }
    }
    
    // V3.1: Claim daily pool reward for yesterday
    fun claimDailyPoolReward() {
        viewModelScope.launch {
            _isLoading.value = true
            pendingOperation = "claim_daily"

            try {
                val wallet = walletBridge.getCurrentWallet() ?: return@launch
                
                // Calculate yesterday's day ID (Unix timestamp / 86400)
                val yesterdayDayId = (System.currentTimeMillis() / 1000 / 86400) - 1

                val function = Function(
                    "claimDailyPoolReward",
                    listOf(Uint256(yesterdayDayId)),
                    emptyList()
                )
                val txData = FunctionEncoder.encode(function)

                val tx = Web3Transaction(
                    Address(wallet.address),
                    Address(MumbleChatContracts.REGISTRY_PROXY),
                    BigInteger.ZERO,
                    BigInteger.ZERO,
                    BigInteger.valueOf(200000),
                    -1,
                    txData
                )

                createTransactionInteract.requestSignature(tx, wallet, MumbleChatContracts.CHAIN_ID, this@RelayNodeViewModel)
            } catch (e: Exception) {
                Timber.e(e, "Failed to claim daily pool reward")
                _error.value = "Claim failed: ${e.message}"
                _isLoading.value = false
            }
        }
    }
    
    // V3.1: Load daily pool stats
    fun loadDailyPoolStats() {
        viewModelScope.launch {
            try {
                val wallet = walletBridge.getCurrentWallet() ?: return@launch
                
                // In production, call the contract's getTodayPoolInfo and getMyTodayStats
                // For now, estimate based on local data
                val todayDayId = System.currentTimeMillis() / 1000 / 86400
                
                // TODO: Call registrationManager.blockchainService.getTodayPoolInfo()
                // For now, provide placeholder data
                _dailyPoolStats.value = DailyPoolStats(
                    dayId = todayDayId,
                    myRelaysToday = _relayStatus.value?.messagesRelayed ?: 0,
                    networkRelaysToday = 0, // Will be fetched from contract
                    numContributors = 0,
                    poolAmount = 100.0,
                    estimatedReward = 0.0,
                    yesterdayClaimable = _claimableReward.value
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to load daily pool stats")
            }
        }
    }

    // V2: Update storage capacity
    fun updateStorage(storageMB: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            pendingOperation = "update_storage"

            try {
                val wallet = walletBridge.getCurrentWallet() ?: return@launch

                val function = Function(
                    "updateStorage",
                    listOf(Uint256(storageMB.toLong())),
                    emptyList()
                )
                val txData = FunctionEncoder.encode(function)

                val tx = Web3Transaction(
                    Address(wallet.address),
                    Address(MumbleChatContracts.REGISTRY_PROXY),
                    BigInteger.ZERO,
                    BigInteger.ZERO,
                    BigInteger.valueOf(100000),
                    -1,
                    txData
                )

                createTransactionInteract.requestSignature(tx, wallet, MumbleChatContracts.CHAIN_ID, this@RelayNodeViewModel)
            } catch (e: Exception) {
                Timber.e(e, "Failed to update storage")
                _error.value = "Update storage failed: ${e.message}"
                _isLoading.value = false
            }
        }
    }

    // V2: Activate with storage parameter
    fun activateAsRelayWithStorage(storageMB: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            pendingOperation = "activate"

            try {
                val wallet = walletBridge.getCurrentWallet()
                if (wallet == null) {
                    _error.value = "No wallet available"
                    _isLoading.value = false
                    return@launch
                }

                if (_isRegistered.value != true) {
                    _error.value = "You must register on MumbleChat first"
                    _isLoading.value = false
                    return@launch
                }

                if (_mctBalance.value < 100.0) {
                    _error.value = "Insufficient MCT balance. You need 100 MCT to stake."
                    _isLoading.value = false
                    return@launch
                }

                val endpoint = generateP2PEndpoint(wallet.address)

                // V2: registerAsRelay with storage parameter
                val function = Function(
                    "registerAsRelay",
                    listOf(Utf8String(endpoint), Uint256(storageMB.toLong())),
                    emptyList()
                )
                val txData = FunctionEncoder.encode(function)

                val tx = Web3Transaction(
                    Address(wallet.address),
                    Address(MumbleChatContracts.REGISTRY_PROXY),
                    BigInteger.ZERO,
                    BigInteger.ZERO,
                    BigInteger.valueOf(300000),
                    -1,
                    txData
                )

                createTransactionInteract.requestSignature(tx, wallet, MumbleChatContracts.CHAIN_ID, this@RelayNodeViewModel)
            } catch (e: Exception) {
                Timber.e(e, "Failed to activate as relay")
                _error.value = "Failed to activate: ${e.message}"
                _isLoading.value = false
            }
        }
    }
    
    // V3: Submit relay proof for decentralized relay rewards
    fun submitRelayProof(
        messageHash: ByteArray,
        senderAddress: String,
        recipientAddress: String,
        timestamp: Long,
        senderSignature: ByteArray
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            pendingOperation = "submit_proof"

            try {
                val wallet = walletBridge.getCurrentWallet() ?: return@launch

                // Encode submitRelayProof function call
                val function = Function(
                    "submitRelayProof",
                    listOf(
                        org.web3j.abi.datatypes.generated.Bytes32(messageHash),
                        org.web3j.abi.datatypes.Address(senderAddress),
                        org.web3j.abi.datatypes.Address(recipientAddress),
                        Uint256(timestamp),
                        org.web3j.abi.datatypes.DynamicBytes(senderSignature)
                    ),
                    emptyList()
                )
                val txData = FunctionEncoder.encode(function)

                val tx = Web3Transaction(
                    Address(wallet.address),
                    Address(MumbleChatContracts.REGISTRY_PROXY),
                    BigInteger.ZERO,
                    BigInteger.ZERO,
                    BigInteger.valueOf(200000),
                    -1,
                    txData
                )

                createTransactionInteract.requestSignature(tx, wallet, MumbleChatContracts.CHAIN_ID, this@RelayNodeViewModel)
            } catch (e: Exception) {
                Timber.e(e, "Failed to submit relay proof")
                _error.value = "Submit proof failed: ${e.message}"
                _isLoading.value = false
            }
        }
    }
    
    // V3: Submit batch relay proofs (more efficient)
    fun submitBatchRelayProofs(proofs: List<RelayProof>) {
        viewModelScope.launch {
            _isLoading.value = true
            pendingOperation = "submit_batch_proof"

            try {
                val wallet = walletBridge.getCurrentWallet() ?: return@launch
                
                if (proofs.isEmpty()) {
                    _error.value = "No proofs to submit"
                    _isLoading.value = false
                    return@launch
                }
                
                if (proofs.size > 50) {
                    _error.value = "Maximum 50 proofs per batch"
                    _isLoading.value = false
                    return@launch
                }

                // Encode submitBatchRelayProofs function call
                val messageHashes = proofs.map { org.web3j.abi.datatypes.generated.Bytes32(it.messageHash) }
                val senders = proofs.map { org.web3j.abi.datatypes.Address(it.senderAddress) }
                val recipients = proofs.map { org.web3j.abi.datatypes.Address(it.recipientAddress) }
                val timestamps = proofs.map { Uint256(it.timestamp) }
                val signatures = proofs.map { org.web3j.abi.datatypes.DynamicBytes(it.senderSignature) }
                
                val function = Function(
                    "submitBatchRelayProofs",
                    listOf(
                        org.web3j.abi.datatypes.DynamicArray(org.web3j.abi.datatypes.generated.Bytes32::class.java, messageHashes),
                        org.web3j.abi.datatypes.DynamicArray(org.web3j.abi.datatypes.Address::class.java, senders),
                        org.web3j.abi.datatypes.DynamicArray(org.web3j.abi.datatypes.Address::class.java, recipients),
                        org.web3j.abi.datatypes.DynamicArray(Uint256::class.java, timestamps),
                        org.web3j.abi.datatypes.DynamicArray(org.web3j.abi.datatypes.DynamicBytes::class.java, signatures)
                    ),
                    emptyList()
                )
                val txData = FunctionEncoder.encode(function)

                val tx = Web3Transaction(
                    Address(wallet.address),
                    Address(MumbleChatContracts.REGISTRY_PROXY),
                    BigInteger.ZERO,
                    BigInteger.ZERO,
                    BigInteger.valueOf(500000), // Higher gas for batch
                    -1,
                    txData
                )

                createTransactionInteract.requestSignature(tx, wallet, MumbleChatContracts.CHAIN_ID, this@RelayNodeViewModel)
            } catch (e: Exception) {
                Timber.e(e, "Failed to submit batch relay proofs")
                _error.value = "Submit batch proof failed: ${e.message}"
                _isLoading.value = false
            }
        }
    }
    
    // Data class for relay proof
    data class RelayProof(
        val messageHash: ByteArray,
        val senderAddress: String,
        val recipientAddress: String,
        val timestamp: Long,
        val senderSignature: ByteArray
    )

    // TransactionSendHandlerInterface implementation
    override fun transactionFinalised(transactionReturn: TransactionReturn?) {
        viewModelScope.launch {
            _isLoading.value = false
            val txHash = transactionReturn?.hash ?: "unknown"
            Timber.d("Relay transaction finalized: $txHash")
            
            when (pendingOperation) {
                "activate" -> _success.value = "Successfully activated as relay node!"
                "deactivate" -> _success.value = "Relay node deactivated. Stake returned."
                "heartbeat" -> {
                    _success.value = "Heartbeat sent! Uptime updated."
                    loadRelayStatus() // Refresh tier info
                }
                "claim_fee" -> {
                    _success.value = "Fee rewards claimed!"
                    loadRelayStatus() // Refresh balance
                }
                "claim_daily" -> {
                    _success.value = "Daily pool reward claimed!"
                    _claimableReward.value = 0.0 // Reset claimable
                    loadRelayStatus() // Refresh balance
                    loadDailyPoolStats() // Refresh stats
                }
                "update_storage" -> {
                    _success.value = "Storage capacity updated!"
                    loadRelayStatus() // Refresh tier info
                }
                "submit_proof" -> {
                    _success.value = "Relay proof submitted! Reward earned."
                    loadRelayStatus() // Refresh stats
                }
                "submit_batch_proof" -> {
                    _success.value = "Batch relay proofs submitted! Rewards earned."
                    loadRelayStatus() // Refresh stats
                }
                else -> _success.value = "Transaction successful!"
            }
            pendingOperation = null
        }
    }

    override fun transactionError(txError: TransactionReturn?) {
        viewModelScope.launch {
            _isLoading.value = false
            _error.value = txError?.throwable?.message ?: "Transaction failed"
            pendingOperation = null
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun clearSuccess() {
        _success.value = null
    }
}
