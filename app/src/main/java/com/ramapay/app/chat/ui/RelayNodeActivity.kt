package com.ramapay.app.chat.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ramapay.app.R
import com.ramapay.app.chat.blockchain.RelayNodeStatus
import com.ramapay.app.chat.core.WalletBridge
import com.ramapay.app.chat.service.NonceClearService
import com.ramapay.app.chat.viewmodel.RelayNodeViewModel
import com.ramapay.app.databinding.ActivityRelayNodeBinding
import com.ramapay.app.entity.SignAuthenticationCallback
import com.ramapay.app.service.KeyService
import com.ramapay.app.widget.AWalletAlertDialog
import com.ramapay.hardware.SignatureFromKey
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

/**
 * Activity for managing relay node status.
 * 
 * V2 Features:
 * - Activate/Deactivate as relay node
 * - Tier-based rewards (Bronze/Silver/Gold/Platinum)
 * - Claim fee pool rewards
 * - Update storage capacity
 * - View security/reputation info
 */
@AndroidEntryPoint
class RelayNodeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRelayNodeBinding
    private val viewModel: RelayNodeViewModel by viewModels()
    
    @Inject lateinit var nonceClearService: NonceClearService
    @Inject lateinit var walletBridge: WalletBridge
    @Inject lateinit var keyService: KeyService
    
    private val disposables = CompositeDisposable()
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    private val decimalFormat = DecimalFormat("#,##0.000000") // 6 decimal places for small MCT values

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRelayNodeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupButtons()
        observeViewModel()
        
        viewModel.loadRelayStatus()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = "Relay Node"
        }
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupButtons() {
        binding.btnActivate.setOnClickListener {
            showTierSelectionDialog()
        }
        
        binding.btnDeactivate.setOnClickListener {
            showDeactivateConfirmation()
        }
        
        binding.btnUpdateStorage.setOnClickListener {
            showUpdateStorageDialog()
        }
        
        binding.btnClaimFeeReward.setOnClickListener {
            showClaimFeeRewardConfirmation()
        }
        
        // V3.1: Daily pool claim button
        binding.btnClaimDailyReward.setOnClickListener {
            showClaimDailyPoolConfirmation()
        }
        
        // Clear stuck transactions button
        binding.btnClearStuckTx.setOnClickListener {
            checkAndClearStuckTransactions()
        }
        
        // V3.2: Setup earnings dashboard tabs
        setupEarningsTabs()
        
        // Refresh button
        binding.btnRefreshStats.setOnClickListener {
            viewModel.loadRelayStatus()
            viewModel.loadDailyPoolStats()
            checkForStuckTransactions() // Also check for stuck txs on refresh
        }
        
        // Initial check for stuck transactions
        checkForStuckTransactions()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                binding.progressLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
                binding.btnActivate.isEnabled = !isLoading
                binding.btnDeactivate.isEnabled = !isLoading
            }
        }
        
        lifecycleScope.launch {
            viewModel.relayStatus.collect { status ->
                updateUI(status)
            }
        }
        
        lifecycleScope.launch {
            viewModel.mctBalance.collect { balance ->
                binding.textMctBalance.text = "${decimalFormat.format(balance)} MCT"
                
                // Update stake requirement icon
                val hasEnough = balance >= 100.0
                binding.iconReqStake.setImageResource(
                    if (hasEnough) R.drawable.ic_check_circle else R.drawable.ic_cancel
                )
                binding.iconReqStake.setColorFilter(
                    getColor(if (hasEnough) R.color.green else R.color.error)
                )
            }
        }
        
        lifecycleScope.launch {
            viewModel.isRegistered.collect { isRegistered ->
                binding.iconReqRegistered.setColorFilter(
                    getColor(if (isRegistered) R.color.green else R.color.error)
                )
            }
        }
        
        // V2: Observe tier info
        lifecycleScope.launch {
            viewModel.tierInfo.collect { tierInfo ->
                tierInfo?.let { updateTierUI(it) }
            }
        }
        
        // V2: Observe fee pool share
        lifecycleScope.launch {
            viewModel.feePoolShare.collect { share ->
                binding.textFeeRewardAmount.text = "${decimalFormat.format(share)} MCT"
                binding.btnClaimFeeReward.isEnabled = share > 0
            }
        }
        
        lifecycleScope.launch {
            viewModel.error.collect { error ->
                error?.let {
                    Toast.makeText(this@RelayNodeActivity, it, Toast.LENGTH_LONG).show()
                    viewModel.clearError()
                }
            }
        }
        
        lifecycleScope.launch {
            viewModel.success.collect { success ->
                success?.let {
                    Toast.makeText(this@RelayNodeActivity, it, Toast.LENGTH_SHORT).show()
                    viewModel.clearSuccess()
                }
            }
        }
        
        // Observe transaction status message - show as overlay
        lifecycleScope.launch {
            viewModel.txStatusMessage.collect { message ->
                if (message != null) {
                    binding.textTxStatus.text = message
                    binding.overlayTxStatus.visibility = View.VISIBLE
                } else {
                    binding.overlayTxStatus.visibility = View.GONE
                }
            }
        }
        
        // V3.1: Observe daily pool stats
        lifecycleScope.launch {
            viewModel.dailyPoolStats.collect { stats ->
                stats?.let { updateDailyPoolUI(it) }
            }
        }
        
        // V3.1: Observe claimable reward
        lifecycleScope.launch {
            viewModel.claimableReward.collect { claimable ->
                updateClaimableRewardUI(claimable)
            }
        }
        
        // V4: Observe authentication requests for wallet signing
        // This handles biometric/PIN authentication for HD and keystore wallets
        lifecycleScope.launch {
            viewModel.authenticationRequest.collect { request ->
                request?.let { authRequest ->
                    Timber.d("Authentication requested for: ${authRequest.operationName}")
                    handleAuthenticationRequest(authRequest)
                }
            }
        }
    }
    
    /**
     * Handle authentication request from ViewModel.
     * Triggers biometric/PIN authentication for KEYSTORE and HDKEY wallets.
     */
    private fun handleAuthenticationRequest(request: RelayNodeViewModel.AuthenticationRequest) {
        val signCallback = object : SignAuthenticationCallback {
            override fun gotAuthorisation(gotAuth: Boolean) {
                Timber.d("Authentication result: $gotAuth")
                if (gotAuth) {
                    viewModel.onAuthenticationSuccess()
                } else {
                    viewModel.onAuthenticationFailed()
                }
            }

            override fun cancelAuthentication() {
                Timber.d("Authentication cancelled")
                viewModel.onAuthenticationFailed()
            }

            override fun gotSignature(signature: SignatureFromKey) {
                // For hardware wallets - the signature is returned directly
                Timber.d("Got hardware wallet signature")
                viewModel.onAuthenticationSuccess()
            }
        }

        // Request authentication from the ViewModel (which uses KeyService)
        viewModel.getAuthorisation(this, signCallback)
    }

    private fun updateUI(status: RelayNodeStatus?) {
        if (status == null || !status.isActive) {
            showNotActiveUI()
        } else {
            showActiveUI(status)
        }
    }

    private fun showNotActiveUI() {
        binding.textNodeStatus.text = "Not Active"
        binding.textStatusDescription.text = "Become a relay node to earn MCT tokens"
        binding.iconStatus.setColorFilter(getColor(R.color.dove))
        
        binding.btnActivate.visibility = View.VISIBLE
        binding.btnDeactivate.visibility = View.GONE
        
        binding.cardRequirements.visibility = View.VISIBLE
        binding.cardStats.visibility = View.GONE
        binding.cardEarningsDashboard.visibility = View.GONE  // Hide earnings dashboard
        binding.cardTier.visibility = View.GONE
        binding.cardFeeRewards.visibility = View.GONE
        binding.cardDailyPool.visibility = View.GONE  // V3.1: Hide daily pool when not active
    }

    private fun showActiveUI(status: RelayNodeStatus) {
        binding.textNodeStatus.text = "Active"
        binding.textStatusDescription.text = "Your node is relaying messages"
        binding.iconStatus.setColorFilter(getColor(R.color.green))
        
        binding.btnActivate.visibility = View.GONE
        binding.btnDeactivate.visibility = View.VISIBLE
        
        binding.cardRequirements.visibility = View.GONE
        binding.cardStats.visibility = View.VISIBLE
        binding.cardEarningsDashboard.visibility = View.VISIBLE  // Show earnings dashboard
        binding.cardTier.visibility = View.VISIBLE
        binding.cardFeeRewards.visibility = View.VISIBLE
        binding.cardDailyPool.visibility = View.VISIBLE  // V3.1: Show daily pool card
        
        // Update stats
        binding.textMessagesRelayed.text = status.messagesRelayed.toString()
        binding.textRewardsEarned.text = decimalFormat.format(status.rewardsEarned)
        binding.textStakedAmount.text = "${decimalFormat.format(status.stakedAmount)} MCT"
        
        // V4.1: Handle registeredAt properly - show "Recently" if 0 (legacy fallback)
        if (status.registeredAt > 0) {
            binding.textActiveSince.text = dateFormat.format(Date(status.registeredAt * 1000))
        } else {
            binding.textActiveSince.text = "Recently"
        }
        
        // V2: Update tier display
        binding.textCurrentTier.text = status.tierName
        binding.textCurrentTier.setTextColor(getTierColor(status.tier))
        binding.textRewardMultiplier.text = "${status.rewardMultiplier}x"
        binding.textDailyUptime.text = formatUptime(status.dailyUptimeSeconds)
        
        // Display storage in GB for cleaner UI
        val storageGB = status.storageMB / 1024.0
        if (storageGB >= 1.0) {
            binding.textStorageProvided.text = "${String.format("%.1f", storageGB)} GB"
        } else {
            binding.textStorageProvided.text = "${status.storageMB} MB"
        }
        
        // Fee pool multiplier
        binding.textFeePoolMultiplier.text = "${status.rewardMultiplier}x"
        
        // V3.1: Load daily pool stats
        viewModel.loadDailyPoolStats()
        
        // V3.2: Update earnings dashboard
        updateEarningsDashboard(status)
    }
    
    private fun updateEarningsDashboard(status: RelayNodeStatus) {
        // Today's earnings (estimate based on messages relayed today)
        val todayEarnings = status.rewardsEarned // Will be refined with daily tracking
        binding.textPeriodEarnings.text = decimalFormat.format(todayEarnings)
        binding.textPeriodLabel.text = "Today's Earnings"
        
        // Relays today
        binding.textPeriodRelays.text = status.messagesRelayed.toString()
        
        // Avg per message
        val avgPerMsg = if (status.messagesRelayed > 0) {
            status.rewardsEarned / status.messagesRelayed
        } else 0.0
        binding.textAvgPerMsg.text = String.format("%.4f", avgPerMsg)
        
        // Uptime
        binding.textPeriodUptime.text = formatUptime(status.dailyUptimeSeconds)
        
        // Network share (placeholder - would need total network relays)
        binding.textNetworkShare.text = "~0.1%"
        
        // Quick stats (estimates)
        binding.textBestDayEarnings.text = "${decimalFormat.format(status.rewardsEarned * 1.2)} MCT"
        
        // V4.1: Handle daysActive calculation properly
        val daysActive = if (status.registeredAt > 0) {
            ((System.currentTimeMillis() / 1000) - status.registeredAt) / 86400
        } else {
            1L  // Default to 1 day if registeredAt is unknown
        }
        binding.textTotalDaysActive.text = "${daysActive.toInt()} days"
        
        val avgDaily = if (daysActive > 0) status.rewardsEarned / daysActive else 0.0
        binding.textAvgDailyEarnings.text = "${decimalFormat.format(avgDaily)} MCT"
        
        // Tier bonus (estimate)
        val tierBonus = status.rewardsEarned * (status.rewardMultiplier - 1.0)
        binding.textTierBonusEarned.text = "+${decimalFormat.format(tierBonus)} MCT"
    }
    
    private fun setupEarningsTabs() {
        binding.tabsPeriod.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> binding.textPeriodLabel.text = "Today's Earnings"
                    1 -> binding.textPeriodLabel.text = "This Week's Earnings"
                    2 -> binding.textPeriodLabel.text = "This Month's Earnings"
                    3 -> binding.textPeriodLabel.text = "All-Time Earnings"
                }
                // In production, would load period-specific data from ViewModel
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
    }

    private fun updateTierUI(tierInfo: RelayNodeViewModel.TierInfo) {
        binding.textCurrentTier.text = tierInfo.tier
        binding.textCurrentTier.setTextColor(getTierColorByName(tierInfo.tier))
        binding.textRewardMultiplier.text = "${tierInfo.multiplier}x"
        binding.textDailyUptime.text = String.format("%.1f hours", tierInfo.dailyUptimeHours)
        
        // Display storage in GB for cleaner UI
        val storageGB = tierInfo.storageMB / 1024.0
        if (storageGB >= 1.0) {
            binding.textStorageProvided.text = "${String.format("%.1f", storageGB)} GB"
        } else {
            binding.textStorageProvided.text = "${tierInfo.storageMB} MB"
        }
        
        // Update next tier hint with GB-based thresholds
        if (tierInfo.nextTier != null) {
            val hints = mutableListOf<String>()
            if (tierInfo.uptimeNeeded > 0) hints.add("${tierInfo.uptimeNeeded.toInt()}h more uptime")
            if (tierInfo.storageNeeded > 0) {
                // Convert MB needed to GB for display
                val storageGB = tierInfo.storageNeeded / 1024.0
                if (storageGB >= 1.0) {
                    hints.add("${String.format("%.1f", storageGB)} GB more storage")
                } else {
                    hints.add("${tierInfo.storageNeeded} MB more storage")
                }
            }
            
            val nextMultiplier = when (tierInfo.nextTier) {
                "Silver" -> "1.5x"
                "Gold" -> "2.0x"
                "Platinum" -> "3.0x"
                else -> ""
            }
            val poolShare = when (tierInfo.nextTier) {
                "Silver" -> "20%"
                "Gold" -> "30%"
                "Platinum" -> "40%"
                else -> ""
            }
            binding.textNextTierHint.text = "${hints.joinToString(" + ")} for ${tierInfo.nextTier} ($nextMultiplier, $poolShare pool)"
            binding.textNextTierHint.visibility = View.VISIBLE
            
            // Update progress
            val progress = calculateTierProgress(tierInfo)
            binding.progressNextTier.progress = progress
        } else {
            binding.textNextTierHint.text = "Maximum tier achieved! 🎉 (3.0x, 40% pool share)"
            binding.progressNextTier.progress = 100
        }
    }

    private fun calculateTierProgress(tierInfo: RelayNodeViewModel.TierInfo): Int {
        // Calculate progress to next tier (0-100) - Updated for GB scale
        val uptimeProgress = when (tierInfo.tier) {
            "Bronze" -> (tierInfo.dailyUptimeHours / 8.0 * 50).toInt()   // Progress to Silver (8h)
            "Silver" -> (tierInfo.dailyUptimeHours / 12.0 * 50).toInt()  // Progress to Gold (12h)
            "Gold" -> (tierInfo.dailyUptimeHours / 16.0 * 50).toInt()    // Progress to Platinum (16h)
            else -> 50
        }
        val storageProgress = when (tierInfo.tier) {
            "Bronze" -> (tierInfo.storageMB / 2048.0 * 50).toInt()  // Progress to Silver (2GB)
            "Silver" -> (tierInfo.storageMB / 4096.0 * 50).toInt()  // Progress to Gold (4GB)
            "Gold" -> (tierInfo.storageMB / 8192.0 * 50).toInt()    // Progress to Platinum (8GB)
            else -> 50
        }
        return minOf(100, uptimeProgress + storageProgress)
    }

    private fun getTierColor(tier: Int): Int {
        return when (tier) {
            0 -> getColor(R.color.bronze)
            1 -> getColor(R.color.tier_silver)
            2 -> getColor(R.color.gold)
            3 -> getColor(R.color.platinum)
            else -> getColor(R.color.bronze)
        }
    }

    private fun getTierColorByName(tier: String): Int {
        return when (tier) {
            "Bronze" -> getColor(R.color.bronze)
            "Silver" -> getColor(R.color.tier_silver)
            "Gold" -> getColor(R.color.gold)
            "Platinum" -> getColor(R.color.platinum)
            else -> getColor(R.color.bronze)
        }
    }

    private fun formatUptime(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return "${hours}h ${minutes}m"
    }

    /**
     * Show tier selection bottom sheet dialog
     */
    private fun showTierSelectionDialog() {
        val dialog = TierSelectionDialog.newInstance { selectedTier ->
            showFinalActivateConfirmation(selectedTier.storageMB)
        }
        dialog.show(supportFragmentManager, "TierSelectionDialog")
    }

    private fun showActivateConfirmation() {
        // Legacy function - now replaced by showTierSelectionDialog()
        showTierSelectionDialog()
    }
    
    private fun showLegacyActivateConfirmation() {
        // V2: Ask for storage capacity
        val storageOptions = arrayOf("1 GB (Bronze)", "2 GB (Silver)", "4 GB (Gold)", "8+ GB (Platinum)")
        val storageMBValues = arrayOf(1024, 2048, 4096, 8192)
        var selectedStorage = 1024

        AlertDialog.Builder(this)
            .setTitle("Select Storage Capacity")
            .setSingleChoiceItems(storageOptions, 0) { _, which ->
                selectedStorage = storageMBValues[which]
            }
            .setPositiveButton("Continue") { dialog, _ ->
                dialog.dismiss()
                showFinalActivateConfirmation(selectedStorage)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showFinalActivateConfirmation(storageMB: Int) {
        val tierName = when {
            storageMB >= 8192 -> "Platinum"
            storageMB >= 4096 -> "Gold"
            storageMB >= 2048 -> "Silver"
            else -> "Bronze"
        }
        
        val storageGB = storageMB / 1024.0
        val multiplier = when (tierName) {
            "Platinum" -> "3.0x"
            "Gold" -> "2.0x"
            "Silver" -> "1.5x"
            else -> "1.0x"
        }
        val poolShare = when (tierName) {
            "Platinum" -> "40%"
            "Gold" -> "30%"
            "Silver" -> "20%"
            else -> "10%"
        }
        
        val dialog = AWalletAlertDialog(this)
        dialog.setTitle("Activate as $tierName Node")
        dialog.setMessage(
            "You will stake 100 MCT tokens to become a relay node.\n\n" +
            "📦 Storage: ${String.format("%.0f", storageGB)} GB\n" +
            "🏆 Tier: $tierName ($multiplier rewards)\n" +
            "📊 Daily Pool Share: $poolShare\n\n" +
            "Benefits:\n" +
            "• Earn 0.001 MCT per message relayed\n" +
            "• Get $multiplier bonus on fee pool rewards\n" +
            "• Higher tier = bigger share of daily pool\n" +
            "• Stake is fully refundable on deactivation\n\n" +
            "Continue?"
        )
        dialog.setButtonText(R.string.action_confirm)
        dialog.setSecondaryButtonText(R.string.cancel)
        
        dialog.setButtonListener {
            dialog.dismiss()
            viewModel.activateAsRelayWithStorage(storageMB)
        }
        
        dialog.setSecondaryButtonListener {
            dialog.dismiss()
        }
        
        dialog.show()
    }

    private fun showDeactivateConfirmation() {
        val dialog = AWalletAlertDialog(this)
        dialog.setTitle("Deactivate Relay Node")
        dialog.setMessage(
            "Your 100 MCT stake will be returned to your wallet.\n\n" +
            "⚠️ Any unclaimed fee rewards will be lost.\n\n" +
            "You can reactivate anytime by staking again."
        )
        dialog.setButtonText(R.string.action_confirm)
        dialog.setSecondaryButtonText(R.string.cancel)
        
        dialog.setButtonListener {
            dialog.dismiss()
            viewModel.deactivateRelay()
        }
        
        dialog.setSecondaryButtonListener {
            dialog.dismiss()
        }
        
        dialog.show()
    }

    private fun showUpdateStorageDialog() {
        val storageOptions = arrayOf(
            "1 GB (Bronze tier - 1.0x)",
            "2 GB (Silver tier - 1.5x)", 
            "4 GB (Gold tier - 2.0x)", 
            "8+ GB (Platinum tier - 3.0x)"
        )
        val storageMBValues = arrayOf(1024, 2048, 4096, 8192)
        var selectedStorage = 1024

        AlertDialog.Builder(this)
            .setTitle("Update Storage Capacity")
            .setMessage("Select the storage capacity you want to provide.\nHigher storage = Higher tier = Bigger pool share!")
            .setSingleChoiceItems(storageOptions, 0) { _, which ->
                selectedStorage = storageMBValues[which]
            }
            .setPositiveButton("Update") { dialog, _ ->
                dialog.dismiss()
                viewModel.updateStorage(selectedStorage)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showClaimFeeRewardConfirmation() {
        val dialog = AWalletAlertDialog(this)
        dialog.setTitle("Claim Fee Pool Rewards")
        dialog.setMessage(
            "Your tier-based bonus from the transaction fee pool.\n\n" +
            "🎁 Your Multiplier: ${binding.textFeePoolMultiplier.text}\n\n" +
            "This will transfer your earned fee pool rewards to your wallet."
        )
        dialog.setButtonText(R.string.action_confirm)
        dialog.setSecondaryButtonText(R.string.cancel)
        
        dialog.setButtonListener {
            dialog.dismiss()
            viewModel.claimFeeReward()
        }
        
        dialog.setSecondaryButtonListener {
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    // V3.1: Daily Pool UI Methods
    
    private fun updateDailyPoolUI(stats: RelayNodeViewModel.DailyPoolStats) {
        binding.textTodayRelays.text = stats.myRelaysToday.toString()
        binding.textTotalNetworkRelays.text = if (stats.networkRelaysToday > 0) {
            stats.networkRelaysToday.toString()
        } else {
            "Loading..."
        }
        binding.textEstimatedReward.text = if (stats.estimatedReward > 0) {
            "~${decimalFormat.format(stats.estimatedReward)} MCT"
        } else {
            "~${decimalFormat.format(stats.myRelaysToday * 0.001)} MCT"
        }
    }
    
    private fun updateClaimableRewardUI(claimable: Double) {
        if (claimable > 0) {
            binding.layoutClaimableReward.visibility = View.VISIBLE
            binding.textClaimableReward.text = "${decimalFormat.format(claimable)} MCT"
            binding.btnClaimDailyReward.isEnabled = true
            binding.btnClaimDailyReward.text = "Claim ${decimalFormat.format(claimable)} MCT"
        } else {
            binding.layoutClaimableReward.visibility = View.GONE
            binding.btnClaimDailyReward.isEnabled = false
            binding.btnClaimDailyReward.text = "No Rewards to Claim"
        }
    }
    
    private fun showClaimDailyPoolConfirmation() {
        val claimable = viewModel.claimableReward.value
        
        val dialog = AWalletAlertDialog(this)
        dialog.setTitle("Claim Daily Pool Reward")
        dialog.setMessage(
            "🏆 Your Fair Share from Yesterday's Pool\n\n" +
            "💰 Amount: ${decimalFormat.format(claimable)} MCT\n\n" +
            "This is your proportional share based on:\n" +
            "• Messages you relayed\n" +
            "• Your tier multiplier\n" +
            "• Total network activity\n\n" +
            "Claim now?"
        )
        dialog.setButtonText(R.string.action_confirm)
        dialog.setSecondaryButtonText(R.string.cancel)
        
        dialog.setButtonListener {
            dialog.dismiss()
            viewModel.claimDailyPoolReward()
        }
        
        dialog.setSecondaryButtonListener {
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    // ============ Clear Stuck Transactions ============
    
    private fun checkForStuckTransactions() {
        val walletAddress = walletBridge.getCurrentWalletAddress() ?: return
        
        disposables.add(
            nonceClearService.checkNonceStatus(walletAddress)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ status ->
                    binding.btnClearStuckTx.visibility = if (status.hasStuckTransactions) {
                        binding.btnClearStuckTx.text = "🔧 Clear ${status.stuckCount} Stuck Transaction${if (status.stuckCount > 1) "s" else ""}"
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                }, { _ ->
                    binding.btnClearStuckTx.visibility = View.GONE
                })
        )
    }
    
    private fun checkAndClearStuckTransactions() {
        val walletAddress = walletBridge.getCurrentWalletAddress()
        if (walletAddress == null) {
            Toast.makeText(this, "No wallet connected", Toast.LENGTH_SHORT).show()
            return
        }
        
        val progressDialog = AWalletAlertDialog(this).apply {
            setTitle("Checking Transactions")
            setMessage("Looking for stuck transactions...")
            setProgressMode()
            setCancelable(false)
            show()
        }
        
        disposables.add(
            nonceClearService.checkNonceStatus(walletAddress)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ status ->
                    progressDialog.dismiss()
                    
                    if (status.hasStuckTransactions) {
                        showClearNonceDialog(status.stuckCount)
                    } else {
                        Toast.makeText(this, "✅ No stuck transactions found", Toast.LENGTH_SHORT).show()
                        binding.btnClearStuckTx.visibility = View.GONE
                    }
                }, { error ->
                    progressDialog.dismiss()
                    Toast.makeText(this, "Error: ${error.message}", Toast.LENGTH_LONG).show()
                })
        )
    }
    
    private fun showClearNonceDialog(stuckCount: Int) {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Stuck Transactions Found")
            .setMessage(
                "Found $stuckCount stuck transaction${if (stuckCount > 1) "s" else ""}.\n\n" +
                "These are pending transactions that haven't been confirmed by the network.\n\n" +
                "Clearing them will:\n" +
                "• Send $stuckCount small replacement transaction${if (stuckCount > 1) "s" else ""}\n" +
                "• Use a higher gas price to replace stuck ones\n" +
                "• Cost a small amount of RAMA for gas\n\n" +
                "Do you want to proceed?"
            )
            .setPositiveButton("Clear Now") { _, _ ->
                performNonceClear()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun performNonceClear() {
        val wallet = walletBridge.getCurrentWallet()
        if (wallet == null) {
            Toast.makeText(this, "No wallet connected", Toast.LENGTH_SHORT).show()
            return
        }
        
        val progressDialog = AWalletAlertDialog(this).apply {
            setTitle("Clearing Stuck Transactions")
            setMessage("Sending replacement transactions...")
            setProgressMode()
            setCancelable(false)
            show()
        }
        
        disposables.add(
            nonceClearService.clearStuckTransactions(wallet)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ result ->
                    progressDialog.dismiss()
                    
                    if (result.success) {
                        Toast.makeText(
                            this,
                            "✅ Cleared ${result.clearedCount} stuck transaction${if (result.clearedCount > 1) "s" else ""}",
                            Toast.LENGTH_LONG
                        ).show()
                        binding.btnClearStuckTx.visibility = View.GONE
                        // Refresh status after clearing
                        viewModel.loadRelayStatus()
                    } else {
                        Toast.makeText(
                            this,
                            "Failed to clear: ${result.error}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }, { error ->
                    progressDialog.dismiss()
                    Toast.makeText(this, "Error: ${error.message}", Toast.LENGTH_LONG).show()
                })
        )
    }
    
    override fun onDestroy() {
        super.onDestroy()
        disposables.clear()
    }
}
