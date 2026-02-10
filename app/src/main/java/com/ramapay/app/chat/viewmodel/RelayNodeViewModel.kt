package com.ramapay.app.chat.viewmodel

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ramapay.app.chat.MumbleChatContracts
import com.ramapay.app.chat.blockchain.RelayNodeStatus
import com.ramapay.app.chat.blockchain.TransactionConfirmation
import com.ramapay.app.chat.core.WalletBridge
import com.ramapay.app.chat.nat.StunClient
import com.ramapay.app.chat.registry.RegistrationManager
import com.ramapay.app.chat.relay.RelayService
import com.ramapay.app.entity.SignAuthenticationCallback
import com.ramapay.app.entity.TransactionReturn
import com.ramapay.app.entity.Wallet
import com.ramapay.app.interact.CreateTransactionInteract
import com.ramapay.app.service.KeyService
import com.ramapay.app.service.TokensService
import com.ramapay.app.service.TransactionSendHandlerInterface
import com.ramapay.app.web3.entity.Address
import com.ramapay.app.web3.entity.Web3Transaction
import com.ramapay.hardware.SignatureFromKey
import com.ramapay.hardware.SignatureReturnType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.abi.datatypes.Bool
import org.web3j.utils.Numeric
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * ViewModel for managing relay node status and operations.
 * Supports V2 features: Tier system, fee rewards, protection protocol
 */
@HiltViewModel
class RelayNodeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletBridge: WalletBridge,
    private val registrationManager: RegistrationManager,
    private val tokensService: TokensService,
    private val keyService: KeyService,
    private val createTransactionInteract: CreateTransactionInteract,
    private val stunClient: StunClient
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
    
    // Transaction history for transparency
    private val _transactionHistory = MutableStateFlow<List<TransactionRecord>>(emptyList())
    val transactionHistory: StateFlow<List<TransactionRecord>> = _transactionHistory
    
    // Current transaction status message
    private val _txStatusMessage = MutableStateFlow<String?>(null)
    val txStatusMessage: StateFlow<String?> = _txStatusMessage
    
    // Authentication request for wallet signing
    // The Activity observes this and triggers biometric/PIN authentication
    private val _authenticationRequest = MutableStateFlow<AuthenticationRequest?>(null)
    val authenticationRequest: StateFlow<AuthenticationRequest?> = _authenticationRequest
    
    // Pending transaction waiting for authentication
    private var pendingAuthTransaction: Web3Transaction? = null
    private var pendingAuthWallet: Wallet? = null

    private var pendingOperation: String? = null
    private var pendingTxHash: String? = null
    
    // For two-step approve + register flow
    private var pendingStorageMB: Int = 50
    private var pendingEndpoint: String = ""

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
    
    // Transaction record for history
    data class TransactionRecord(
        val txHash: String,
        val operation: String,
        val status: TxStatus,
        val timestamp: Long,
        val confirmations: Int = 0,
        val blockNumber: Long = 0
    )
    
    enum class TxStatus {
        PENDING,
        CONFIRMING,
        CONFIRMED,
        FAILED
    }
    
    // Authentication request for wallet signing
    // Contains the operation name for display purposes
    data class AuthenticationRequest(
        val operationName: String,
        val requestId: Long = System.currentTimeMillis()
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
                    var relayInfo = getRelayNodeInfo(wallet.address)
                    
                    // V4.4.1: Fetch real messages relayed count from hub API
                    if (relayInfo != null) {
                        val hubMessages = fetchHubMessagesRelayed(wallet.address)
                        if (hubMessages > relayInfo.messagesRelayed) {
                            relayInfo = relayInfo.copy(messagesRelayed = hubMessages)
                        }
                    }
                    
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
            // Directly fetch MCT balance from blockchain - no need to import token
            val balanceWei = registrationManager.blockchainService.getMCTBalance(walletAddress)
            val balanceDecimal = BigDecimal(balanceWei).divide(BigDecimal.TEN.pow(18))
            Timber.d("MCT Balance for $walletAddress: $balanceDecimal MCT (raw: $balanceWei)")
            balanceDecimal.toDouble()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get MCT balance from blockchain")
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

    /**
     * V4.4.1: Fetch actual messages relayed count from hub API.
     * The hub tracks real message counts per node, matched by wallet address.
     * Blockchain on-chain count is often 0 since messages aren't written on-chain.
     */
    private fun fetchHubMessagesRelayed(walletAddress: String): Long {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            
            val request = Request.Builder()
                .url("https://hub.mumblechat.com/api/stats")
                .build()
            
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return 0
            
            val body = response.body?.string() ?: return 0
            val json = JSONObject(body)
            val nodes = json.getJSONArray("nodes")
            
            var totalMessages = 0L
            for (i in 0 until nodes.length()) {
                val node = nodes.getJSONObject(i)
                val nodeWallet = node.optString("walletAddress", "")
                if (nodeWallet.equals(walletAddress, ignoreCase = true)) {
                    totalMessages += node.optLong("messagesRelayed", 0)
                }
            }
            
            Timber.d("Hub stats: $totalMessages messages for wallet $walletAddress")
            totalMessages
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch hub stats")
            0
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
    
    /**
     * Get authentication for wallet transaction signing.
     * This triggers biometric/PIN authentication for KEYSTORE and HDKEY wallets.
     * Call this from the Activity with the Activity context.
     */
    fun getAuthorisation(activity: Activity, callback: SignAuthenticationCallback) {
        val wallet = pendingAuthWallet
        if (wallet == null) {
            Timber.e("No pending wallet for authentication")
            callback.gotAuthorisation(false)
            return
        }
        
        Timber.d("Getting authentication for wallet type: ${wallet.type}, hdIndex: ${wallet.hdKeyIndex}")
        keyService.getAuthenticationForSignature(wallet, activity, callback)
    }
    
    /**
     * Called when authentication is successful, proceeds with the pending transaction.
     */
    fun onAuthenticationSuccess() {
        val tx = pendingAuthTransaction
        val wallet = pendingAuthWallet
        
        if (tx == null || wallet == null) {
            Timber.e("No pending transaction after authentication")
            _error.value = "Transaction was lost during authentication"
            _isLoading.value = false
            return
        }
        
        Timber.d("Authentication successful, sending transaction for operation: $pendingOperation")
        Timber.d("Wallet: ${wallet.address}, Type: ${wallet.type}, HDIndex: ${wallet.hdKeyIndex}")
        
        // Clear the authentication request
        _authenticationRequest.value = null
        
        // Now actually send the transaction
        createTransactionInteract.requestSignature(tx, wallet, MumbleChatContracts.CHAIN_ID, this)
    }
    
    /**
     * Called when authentication fails or is cancelled.
     */
    fun onAuthenticationFailed() {
        Timber.d("Authentication failed or cancelled")
        _authenticationRequest.value = null
        pendingAuthTransaction = null
        pendingAuthWallet = null
        pendingOperation = null
        _isLoading.value = false
        _error.value = "Authentication cancelled"
    }
    
    /**
     * Helper to request authentication before sending a transaction.
     * This sets up the pending state and emits an authentication request.
     */
    private fun requestAuthAndSendTransaction(
        tx: Web3Transaction,
        wallet: Wallet,
        operationName: String
    ) {
        pendingAuthTransaction = tx
        pendingAuthWallet = wallet
        
        Timber.d("Requesting authentication for $operationName")
        Timber.d("Wallet info - Address: ${wallet.address}, Type: ${wallet.type}, HDIndex: ${wallet.hdKeyIndex}")
        
        // Emit authentication request for the Activity to handle
        _authenticationRequest.value = AuthenticationRequest(operationName)
    }

    fun activateAsRelay() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            pendingOperation = "approve"  // First step: approve MCT

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

                // Store endpoint for later use after approval (discovered via STUN)
                pendingEndpoint = generateP2PEndpoint()
                pendingStorageMB = 50 // Default storage
                
                // Fetch current gas price from network
                val gasPrice = withContext(Dispatchers.IO) {
                    registrationManager.blockchainService.getGasPrice()
                }

                // Step 1: Approve MCT spending (100 MCT = 100 * 10^18 wei)
                val stakeAmount = BigInteger.valueOf(100).multiply(BigInteger.TEN.pow(18))
                
                val approveFunction = Function(
                    "approve",
                    listOf(
                        org.web3j.abi.datatypes.Address(MumbleChatContracts.REGISTRY_PROXY),
                        Uint256(stakeAmount)
                    ),
                    listOf(org.web3j.abi.TypeReference.create(Bool::class.java))
                )
                val approveTxData = FunctionEncoder.encode(approveFunction)

                // Create approve transaction to MCT token
                val approveTx = Web3Transaction(
                    Address(wallet.address),
                    Address(MumbleChatContracts.MCT_TOKEN_PROXY),
                    BigInteger.ZERO,
                    gasPrice,  // Use fetched gas price
                    BigInteger.valueOf(100000), // Gas limit for approve
                    -1,
                    approveTxData
                )

                Timber.d("Sending MCT approve transaction to ${MumbleChatContracts.MCT_TOKEN_PROXY}, gasPrice: $gasPrice")
                
                // Request authentication first, then send transaction
                requestAuthAndSendTransaction(approveTx, wallet, "Approve MCT for Staking")

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
                
                // Fetch current gas price
                val gasPrice = withContext(Dispatchers.IO) {
                    registrationManager.blockchainService.getGasPrice()
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
                    gasPrice,
                    BigInteger.valueOf(200000),
                    -1,
                    txData
                )

                // Request authentication first, then send transaction
                requestAuthAndSendTransaction(tx, wallet, "Deactivate Relay Node")

            } catch (e: Exception) {
                Timber.e(e, "Failed to deactivate relay")
                _error.value = "Failed to deactivate: ${e.message}"
                _isLoading.value = false
            }
        }
    }

    private suspend fun generateP2PEndpoint(): String {
        // Discover real public IP via STUN for cross-network relay discovery
        return withContext(Dispatchers.IO) {
            try {
                val stunResult = stunClient.discoverPublicAddress()
                if (stunResult != null) {
                    // Register with real public IP:port so other devices can find us
                    // Port 19372 is our relay service port
                    "${stunResult.publicIp}:19372".also {
                        Timber.d("Generated real P2P endpoint: $it")
                    }
                } else {
                    // STUN failed - use hub-managed fallback endpoint
                    // Hub routes traffic so real IP not required for registration
                    Timber.w("STUN discovery failed, using hub-managed endpoint")
                    "hub.mumblechat.com/node/mobile:19372"
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to discover public address via STUN, using fallback")
                "hub.mumblechat.com/node/mobile:19372"
            }
        }
    }
    
    /**
     * Manually encode registerAsRelay(string,uint256) to work around web3j encoding bug
     * Function selector: 0xdf5d864d
     */
    private fun encodeRegisterAsRelay(endpoint: String, storageMB: Long): String {
        val functionSelector = "df5d864d"
        
        // Encode parameters according to ABI:
        // - offset to string data (always 0x40 = 64 for two params)
        // - storageMB as uint256
        // - string length
        // - string data (padded to 32 bytes)
        
        val offsetToString = "0000000000000000000000000000000000000000000000000000000000000040"
        val storageEncoded = String.format("%064x", storageMB)
        
        val endpointBytes = endpoint.toByteArray(Charsets.UTF_8)
        val stringLength = String.format("%064x", endpointBytes.size)
        
        // Pad string data to multiple of 32 bytes
        val paddedLength = ((endpointBytes.size + 31) / 32) * 32
        val paddedBytes = ByteArray(paddedLength)
        System.arraycopy(endpointBytes, 0, paddedBytes, 0, endpointBytes.size)
        val stringData = Numeric.toHexStringNoPrefix(paddedBytes)
        
        return "0x$functionSelector$offsetToString$storageEncoded$stringLength$stringData"
    }

    // V2: Send heartbeat to update uptime
    fun sendHeartbeat() {
        viewModelScope.launch {
            _isLoading.value = true
            pendingOperation = "heartbeat"

            try {
                val wallet = walletBridge.getCurrentWallet() ?: return@launch
                
                // Fetch current gas price
                val gasPrice = withContext(Dispatchers.IO) {
                    registrationManager.blockchainService.getGasPrice()
                }

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
                    gasPrice,
                    BigInteger.valueOf(100000),
                    -1,
                    txData
                )

                // Request authentication first, then send transaction
                requestAuthAndSendTransaction(tx, wallet, "Send Heartbeat")
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
                
                // Fetch current gas price
                val gasPrice = withContext(Dispatchers.IO) {
                    registrationManager.blockchainService.getGasPrice()
                }

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
                    gasPrice,
                    BigInteger.valueOf(150000),
                    -1,
                    txData
                )

                // Request authentication first, then send transaction
                requestAuthAndSendTransaction(tx, wallet, "Claim Fee Reward")
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
                
                // Fetch current gas price
                val gasPrice = withContext(Dispatchers.IO) {
                    registrationManager.blockchainService.getGasPrice()
                }
                
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
                    gasPrice,
                    BigInteger.valueOf(200000),
                    -1,
                    txData
                )

                // Request authentication first, then send transaction
                requestAuthAndSendTransaction(tx, wallet, "Claim Daily Reward")
            } catch (e: Exception) {
                Timber.e(e, "Failed to claim daily pool reward")
                _error.value = "Claim failed: ${e.message}"
                _isLoading.value = false
            }
        }
    }
    
    // V3.1: Load daily pool stats from blockchain
    fun loadDailyPoolStats() {
        viewModelScope.launch {
            try {
                val wallet = walletBridge.getCurrentWallet() ?: return@launch
                
                withContext(Dispatchers.IO) {
                    // Fetch today's pool info from blockchain
                    val poolInfo = registrationManager.blockchainService.getTodayPoolInfo()
                    
                    // Fetch my stats for today
                    val myStats = registrationManager.blockchainService.getMyTodayStats(wallet.address)
                    
                    // Fetch yesterday's claimable reward
                    val yesterdayDayId = (System.currentTimeMillis() / 1000 / 86400) - 1
                    val claimable = registrationManager.blockchainService.getClaimableReward(wallet.address, yesterdayDayId)
                    _claimableReward.value = claimable
                    
                    // Fetch fee pool balance
                    val feePoolBalance = registrationManager.blockchainService.getFeePoolBalance()
                    
                    if (poolInfo != null) {
                        _dailyPoolStats.value = DailyPoolStats(
                            dayId = poolInfo.dayId,
                            myRelaysToday = myStats?.relayCount ?: 0,
                            networkRelaysToday = poolInfo.totalRelays,
                            numContributors = poolInfo.numContributors,
                            poolAmount = poolInfo.poolAmount,
                            estimatedReward = myStats?.estimatedReward ?: 0.0,
                            yesterdayClaimable = claimable
                        )
                        
                        // Update fee pool share based on actual data
                        val tierInfo = _tierInfo.value
                        if (tierInfo != null && poolInfo.totalWeightedRelays > 0) {
                            val myWeightedRelays = (myStats?.relayCount ?: 0) * tierInfo.multiplier * 100
                            val sharePercent = (myWeightedRelays / poolInfo.totalWeightedRelays) * feePoolBalance
                            _feePoolShare.value = sharePercent
                        }
                    } else {
                        // Fallback to local estimate
                        val todayDayId = System.currentTimeMillis() / 1000 / 86400
                        _dailyPoolStats.value = DailyPoolStats(
                            dayId = todayDayId,
                            myRelaysToday = _relayStatus.value?.messagesRelayed ?: 0,
                            networkRelaysToday = 0,
                            numContributors = 0,
                            poolAmount = 100.0,
                            estimatedReward = 0.0,
                            yesterdayClaimable = claimable
                        )
                    }
                }
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
                
                // Fetch current gas price
                val gasPrice = withContext(Dispatchers.IO) {
                    registrationManager.blockchainService.getGasPrice()
                }

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
                    gasPrice,
                    BigInteger.valueOf(100000),
                    -1,
                    txData
                )

                // Request authentication first, then send transaction
                requestAuthAndSendTransaction(tx, wallet, "Update Storage Capacity")
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
            pendingOperation = "approve"  // First step: approve MCT

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

                // Store for later use after approval (discovered via STUN)
                pendingEndpoint = generateP2PEndpoint()
                pendingStorageMB = storageMB
                
                // Fetch current gas price
                val gasPrice = withContext(Dispatchers.IO) {
                    registrationManager.blockchainService.getGasPrice()
                }

                // Step 1: Approve MCT spending (100 MCT = 100 * 10^18 wei)
                val stakeAmount = BigInteger.valueOf(100).multiply(BigInteger.TEN.pow(18))
                
                val approveFunction = Function(
                    "approve",
                    listOf(
                        org.web3j.abi.datatypes.Address(MumbleChatContracts.REGISTRY_PROXY),
                        Uint256(stakeAmount)
                    ),
                    listOf(org.web3j.abi.TypeReference.create(Bool::class.java))
                )
                val approveTxData = FunctionEncoder.encode(approveFunction)

                val approveTx = Web3Transaction(
                    Address(wallet.address),
                    Address(MumbleChatContracts.MCT_TOKEN_PROXY),
                    BigInteger.ZERO,
                    gasPrice,
                    BigInteger.valueOf(100000),
                    -1,
                    approveTxData
                )

                Timber.d("Sending MCT approve transaction for $storageMB MB storage, gasPrice: $gasPrice")
                
                // Request authentication first, then send transaction
                requestAuthAndSendTransaction(approveTx, wallet, "Approve MCT for Staking")
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
                
                // Fetch current gas price
                val gasPrice = withContext(Dispatchers.IO) {
                    registrationManager.blockchainService.getGasPrice()
                }

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
                    gasPrice,
                    BigInteger.valueOf(200000),
                    -1,
                    txData
                )

                // Request authentication first, then send transaction
                requestAuthAndSendTransaction(tx, wallet, "Submit Relay Proof")
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
                
                // Fetch current gas price
                val gasPrice = withContext(Dispatchers.IO) {
                    registrationManager.blockchainService.getGasPrice()
                }

                val tx = Web3Transaction(
                    Address(wallet.address),
                    Address(MumbleChatContracts.REGISTRY_PROXY),
                    BigInteger.ZERO,
                    gasPrice,
                    BigInteger.valueOf(500000), // Higher gas for batch
                    -1,
                    txData
                )

                // Request authentication first, then send transaction
                requestAuthAndSendTransaction(tx, wallet, "Submit Batch Relay Proofs")
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
    
    // Helper to add transaction to history
    private fun addTxToHistory(txHash: String, operation: String, status: TxStatus) {
        val record = TransactionRecord(
            txHash = txHash,
            operation = operation,
            status = status,
            timestamp = System.currentTimeMillis()
        )
        _transactionHistory.value = listOf(record) + _transactionHistory.value.take(9) // Keep last 10
    }
    
    // Helper to update transaction status in history
    private fun updateTxInHistory(txHash: String, status: TxStatus, confirmations: Int = 0, blockNumber: Long = 0) {
        _transactionHistory.value = _transactionHistory.value.map { record ->
            if (record.txHash == txHash) {
                record.copy(status = status, confirmations = confirmations, blockNumber = blockNumber)
            } else record
        }
    }
    
    // Get operation display name
    private fun getOperationName(operation: String?): String = when (operation) {
        "approve" -> "MCT Approval"
        "activate" -> "Relay Node Registration"
        "deactivate" -> "Relay Node Deactivation"
        "heartbeat" -> "Heartbeat"
        "claim_fee" -> "Fee Reward Claim"
        "claim_daily" -> "Daily Pool Claim"
        "update_storage" -> "Storage Update"
        "submit_proof" -> "Relay Proof"
        "submit_batch_proof" -> "Batch Relay Proofs"
        else -> "Transaction"
    }

    // TransactionSendHandlerInterface implementation
    override fun transactionFinalised(transactionReturn: TransactionReturn?) {
        viewModelScope.launch {
            val txHash = transactionReturn?.hash ?: "unknown"
            val operation = pendingOperation
            Timber.d("Relay transaction submitted: $txHash, operation: $operation")
            
            // Add to history as pending
            addTxToHistory(txHash, getOperationName(operation), TxStatus.PENDING)
            pendingTxHash = txHash
            
            when (operation) {
                "approve" -> {
                    // Wait for approval confirmation before proceeding
                    _txStatusMessage.value = "⏳ Waiting for approval confirmation (2 blocks)..."
                    
                    val confirmation = waitForTxConfirmation(txHash, 2)
                    
                    if (confirmation != null && confirmation.status) {
                        updateTxInHistory(txHash, TxStatus.CONFIRMED, confirmation.confirmations, confirmation.blockNumber)
                        _txStatusMessage.value = "✅ Approval confirmed! Registering as relay node..."
                        
                        // Small delay to ensure blockchain state is updated
                        delay(1000)
                        
                        // Proceed to registration
                        pendingOperation = "activate"
                        sendRegisterAsRelayTx()
                    } else {
                        updateTxInHistory(txHash, TxStatus.FAILED)
                        _isLoading.value = false
                        _txStatusMessage.value = null
                        _error.value = "Approval transaction failed or timed out"
                        pendingOperation = null
                    }
                }
                "activate" -> {
                    _txStatusMessage.value = "⏳ Waiting for registration confirmation (2 blocks)..."
                    
                    val confirmation = waitForTxConfirmation(txHash, 2)
                    
                    if (confirmation != null && confirmation.status) {
                        updateTxInHistory(txHash, TxStatus.CONFIRMED, confirmation.confirmations, confirmation.blockNumber)
                        _isLoading.value = false
                        _txStatusMessage.value = null
                        _success.value = "🎉 Successfully activated as relay node!\nTx: ${txHash.take(10)}...${txHash.takeLast(6)}\nBlock: ${confirmation.blockNumber}"
                        pendingOperation = null
                        loadRelayStatus() // Refresh status
                        
                        // Start the RelayService foreground service
                        try {
                            RelayService.start(context, pendingStorageMB.toLong())
                            Timber.d("RelayService started with storage: ${pendingStorageMB}MB")
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to start RelayService")
                        }
                    } else {
                        updateTxInHistory(txHash, TxStatus.FAILED)
                        _isLoading.value = false
                        _txStatusMessage.value = null
                        _error.value = "Registration failed. Please try again."
                        pendingOperation = null
                    }
                }
                "deactivate" -> {
                    _txStatusMessage.value = "⏳ Waiting for deactivation confirmation..."
                    
                    val confirmation = waitForTxConfirmation(txHash, 2)
                    
                    if (confirmation != null && confirmation.status) {
                        updateTxInHistory(txHash, TxStatus.CONFIRMED, confirmation.confirmations, confirmation.blockNumber)
                        _success.value = "✅ Relay node deactivated. Stake returned.\nTx: ${txHash.take(10)}..."
                        // Reload status to reflect deactivation
                        loadRelayStatus()
                        
                        // Stop the RelayService foreground service
                        try {
                            RelayService.stop(context)
                            Timber.d("RelayService stopped")
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to stop RelayService")
                        }
                    } else {
                        updateTxInHistory(txHash, TxStatus.FAILED)
                        _error.value = "Deactivation failed"
                    }
                    _isLoading.value = false
                    _txStatusMessage.value = null
                    pendingOperation = null
                }
                "heartbeat" -> {
                    handleSimpleConfirmation(txHash, "Heartbeat sent! Uptime updated.")
                    loadRelayStatus()
                }
                "claim_fee" -> {
                    handleSimpleConfirmation(txHash, "Fee rewards claimed!")
                    loadRelayStatus()
                }
                "claim_daily" -> {
                    handleSimpleConfirmation(txHash, "Daily pool reward claimed!")
                    _claimableReward.value = 0.0
                    loadRelayStatus()
                    loadDailyPoolStats()
                }
                "update_storage" -> {
                    handleSimpleConfirmation(txHash, "Storage capacity updated!")
                    loadRelayStatus()
                }
                "submit_proof", "submit_batch_proof" -> {
                    handleSimpleConfirmation(txHash, "Relay proofs submitted!")
                    loadRelayStatus()
                }
                else -> {
                    handleSimpleConfirmation(txHash, "Transaction successful!")
                }
            }
        }
    }
    
    // Helper for simple confirmations
    private suspend fun handleSimpleConfirmation(txHash: String, successMessage: String) {
        _txStatusMessage.value = "⏳ Waiting for confirmation..."
        
        val confirmation = waitForTxConfirmation(txHash, 2)
        
        if (confirmation != null && confirmation.status) {
            updateTxInHistory(txHash, TxStatus.CONFIRMED, confirmation.confirmations, confirmation.blockNumber)
            _success.value = "$successMessage\nTx: ${txHash.take(10)}..."
        } else {
            updateTxInHistory(txHash, TxStatus.FAILED)
            _error.value = "Transaction failed"
        }
        _isLoading.value = false
        _txStatusMessage.value = null
        pendingOperation = null
    }
    
    // Wait for transaction confirmation
    private suspend fun waitForTxConfirmation(txHash: String, requiredConfirmations: Int): TransactionConfirmation? {
        return withContext(Dispatchers.IO) {
            registrationManager.blockchainService.waitForConfirmations(
                txHash = txHash,
                requiredConfirmations = requiredConfirmations,
                maxWaitTimeMs = 90000, // 90 seconds max
                pollIntervalMs = 2000   // Check every 2 seconds
            )
        }
    }
    
    /**
     * Step 2: Send registerAsRelay transaction after approval
     */
    private fun sendRegisterAsRelayTx() {
        viewModelScope.launch {
            try {
                val wallet = walletBridge.getCurrentWallet()
                if (wallet == null) {
                    _error.value = "No wallet available"
                    _isLoading.value = false
                    pendingOperation = null
                    return@launch
                }

                Timber.d("Sending registerAsRelay with endpoint=$pendingEndpoint, storage=$pendingStorageMB")
                
                // Fetch current gas price
                val gasPrice = withContext(Dispatchers.IO) {
                    registrationManager.blockchainService.getGasPrice()
                }

                // Use manual ABI encoding to avoid web3j Utf8String bug
                val txData = encodeRegisterAsRelay(pendingEndpoint, pendingStorageMB.toLong())
                Timber.d("Manual encoded txData: $txData")

                val tx = Web3Transaction(
                    Address(wallet.address),
                    Address(MumbleChatContracts.REGISTRY_PROXY),
                    BigInteger.ZERO,
                    gasPrice,
                    BigInteger.valueOf(500000), // Higher gas for stake transfer
                    -1,
                    txData
                )

                // Request authentication first, then send transaction
                requestAuthAndSendTransaction(tx, wallet, "Register as Relay Node")
            } catch (e: Exception) {
                Timber.e(e, "Failed to send registerAsRelay")
                _error.value = "Failed to register: ${e.message}"
                _isLoading.value = false
                pendingOperation = null
            }
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
