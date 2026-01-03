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
import com.ramapay.app.chat.viewmodel.RelayNodeViewModel
import com.ramapay.app.databinding.ActivityRelayNodeBinding
import com.ramapay.app.widget.AWalletAlertDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

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
    
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    private val decimalFormat = DecimalFormat("#,##0.00")

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
            showActivateConfirmation()
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
        binding.cardTier.visibility = View.GONE
        binding.cardFeeRewards.visibility = View.GONE
    }

    private fun showActiveUI(status: RelayNodeStatus) {
        binding.textNodeStatus.text = "Active"
        binding.textStatusDescription.text = "Your node is relaying messages"
        binding.iconStatus.setColorFilter(getColor(R.color.green))
        
        binding.btnActivate.visibility = View.GONE
        binding.btnDeactivate.visibility = View.VISIBLE
        
        binding.cardRequirements.visibility = View.GONE
        binding.cardStats.visibility = View.VISIBLE
        binding.cardTier.visibility = View.VISIBLE
        binding.cardFeeRewards.visibility = View.VISIBLE
        
        // Update stats
        binding.textMessagesRelayed.text = status.messagesRelayed.toString()
        binding.textRewardsEarned.text = decimalFormat.format(status.rewardsEarned)
        binding.textStakedAmount.text = "${decimalFormat.format(status.stakedAmount)} MCT"
        binding.textActiveSince.text = dateFormat.format(Date(status.registeredAt * 1000))
        
        // V2: Update tier display
        binding.textCurrentTier.text = status.tierName
        binding.textCurrentTier.setTextColor(getTierColor(status.tier))
        binding.textRewardMultiplier.text = "${status.rewardMultiplier}x"
        binding.textDailyUptime.text = formatUptime(status.dailyUptimeSeconds)
        binding.textStorageProvided.text = "${status.storageMB} MB"
        
        // Fee pool multiplier
        binding.textFeePoolMultiplier.text = "${status.rewardMultiplier}x"
    }

    private fun updateTierUI(tierInfo: RelayNodeViewModel.TierInfo) {
        binding.textCurrentTier.text = tierInfo.tier
        binding.textCurrentTier.setTextColor(getTierColorByName(tierInfo.tier))
        binding.textRewardMultiplier.text = "${tierInfo.multiplier}x"
        binding.textDailyUptime.text = String.format("%.1f hours", tierInfo.dailyUptimeHours)
        binding.textStorageProvided.text = "${tierInfo.storageMB} MB"
        
        // Update next tier hint
        if (tierInfo.nextTier != null) {
            val hints = mutableListOf<String>()
            if (tierInfo.uptimeNeeded > 0) hints.add("${tierInfo.uptimeNeeded.toInt()}h more uptime")
            if (tierInfo.storageNeeded > 0) hints.add("${tierInfo.storageNeeded} MB more storage")
            
            val nextMultiplier = when (tierInfo.nextTier) {
                "Silver" -> "1.5x"
                "Gold" -> "2.0x"
                "Platinum" -> "3.0x"
                else -> ""
            }
            binding.textNextTierHint.text = "${hints.joinToString(" + ")} for ${tierInfo.nextTier} ($nextMultiplier)"
            binding.textNextTierHint.visibility = View.VISIBLE
            
            // Update progress
            val progress = calculateTierProgress(tierInfo)
            binding.progressNextTier.progress = progress
        } else {
            binding.textNextTierHint.text = "Maximum tier achieved! 🎉"
            binding.progressNextTier.progress = 100
        }
    }

    private fun calculateTierProgress(tierInfo: RelayNodeViewModel.TierInfo): Int {
        // Calculate progress to next tier (0-100)
        val uptimeProgress = when (tierInfo.tier) {
            "Bronze" -> (tierInfo.dailyUptimeHours / 4.0 * 50).toInt()
            "Silver" -> (tierInfo.dailyUptimeHours / 8.0 * 50).toInt()
            "Gold" -> (tierInfo.dailyUptimeHours / 16.0 * 50).toInt()
            else -> 50
        }
        val storageProgress = when (tierInfo.tier) {
            "Bronze" -> (tierInfo.storageMB / 50.0 * 50).toInt()
            "Silver" -> (tierInfo.storageMB / 200.0 * 50).toInt()
            "Gold" -> (tierInfo.storageMB / 500.0 * 50).toInt()
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

    private fun showActivateConfirmation() {
        // V2: Ask for storage capacity
        val storageOptions = arrayOf("50 MB (Bronze)", "200 MB (Silver)", "500 MB (Gold+)")
        val storageMBValues = arrayOf(50, 200, 500)
        var selectedStorage = 50

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
            storageMB >= 500 -> "Gold or higher"
            storageMB >= 200 -> "Silver or higher"
            else -> "Bronze"
        }
        
        val dialog = AWalletAlertDialog(this)
        dialog.setTitle("Activate Relay Node")
        dialog.setMessage(
            "You will stake 100 MCT tokens to become a relay node.\n\n" +
            "Storage: ${storageMB} MB → Eligible for $tierName tier\n\n" +
            "• Minting rewards: 0.001 MCT per 1,000 messages\n" +
            "• Fee pool bonus: Up to 3x based on tier\n" +
            "• Keep app running to increase uptime\n" +
            "• Withdraw stake anytime\n\n" +
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
        val storageOptions = arrayOf("50 MB (Bronze tier)", "200 MB (Silver tier)", "500 MB (Gold tier)")
        val storageMBValues = arrayOf(50, 200, 500)
        var selectedStorage = 50

        AlertDialog.Builder(this)
            .setTitle("Update Storage Capacity")
            .setMessage("Select the storage capacity you want to provide.\nHigher storage = Higher tier potential!")
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
}
