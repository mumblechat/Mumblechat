package com.ramapay.app.chat.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import com.ramapay.app.R
import com.ramapay.app.databinding.DialogTierSelectionBinding

/**
 * Bottom sheet dialog for selecting relay node tier.
 * 
 * Tier System:
 * - Bronze: 1 GB storage, 4+ hours/day uptime, 1.0x multiplier, 10% pool share
 * - Silver: 2 GB storage, 8+ hours/day uptime, 1.5x multiplier, 20% pool share
 * - Gold:   4 GB storage, 12+ hours/day uptime, 2.0x multiplier, 30% pool share
 * - Platinum: 8+ GB storage, 16+ hours/day uptime, 3.0x multiplier, 40% pool share
 */
class TierSelectionDialog : BottomSheetDialogFragment() {

    private var _binding: DialogTierSelectionBinding? = null
    private val binding get() = _binding!!

    private var selectedTier: NodeTier = NodeTier.BRONZE
    private var onTierSelected: ((NodeTier) -> Unit)? = null

    enum class NodeTier(
        val storageMB: Int,
        val storageDisplay: String,
        val uptimeHours: Int,
        val multiplier: Double,
        val poolPercentage: Int,
        val expectedEarnings: String
    ) {
        BRONZE(1024, "1 GB", 4, 1.0, 10, "~5-10 MCT/day"),
        SILVER(2048, "2 GB", 8, 1.5, 20, "~10-20 MCT/day"),
        GOLD(4096, "4 GB", 12, 2.0, 30, "~15-30 MCT/day"),
        PLATINUM(8192, "8+ GB", 16, 3.0, 40, "~25-50 MCT/day")
    }

    companion object {
        fun newInstance(onTierSelected: (NodeTier) -> Unit): TierSelectionDialog {
            return TierSelectionDialog().apply {
                this.onTierSelected = onTierSelected
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogTierSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupTierCards()
        setupConfirmButton()
        
        // Default to Gold (popular choice)
        selectTier(NodeTier.GOLD)
    }

    private fun setupTierCards() {
        binding.cardTierBronze.setOnClickListener { selectTier(NodeTier.BRONZE) }
        binding.cardTierSilver.setOnClickListener { selectTier(NodeTier.SILVER) }
        binding.cardTierGold.setOnClickListener { selectTier(NodeTier.GOLD) }
        binding.cardTierPlatinum.setOnClickListener { selectTier(NodeTier.PLATINUM) }

        binding.radioBronze.setOnClickListener { selectTier(NodeTier.BRONZE) }
        binding.radioSilver.setOnClickListener { selectTier(NodeTier.SILVER) }
        binding.radioGold.setOnClickListener { selectTier(NodeTier.GOLD) }
        binding.radioPlatinum.setOnClickListener { selectTier(NodeTier.PLATINUM) }
    }

    private fun selectTier(tier: NodeTier) {
        selectedTier = tier

        // Update radio buttons
        binding.radioBronze.isChecked = tier == NodeTier.BRONZE
        binding.radioSilver.isChecked = tier == NodeTier.SILVER
        binding.radioGold.isChecked = tier == NodeTier.GOLD
        binding.radioPlatinum.isChecked = tier == NodeTier.PLATINUM

        // Update card checked states
        binding.cardTierBronze.isChecked = tier == NodeTier.BRONZE
        binding.cardTierSilver.isChecked = tier == NodeTier.SILVER
        binding.cardTierGold.isChecked = tier == NodeTier.GOLD
        binding.cardTierPlatinum.isChecked = tier == NodeTier.PLATINUM

        // Update expected earnings
        binding.textExpectedEarnings.text = tier.expectedEarnings

        // Update button text
        binding.btnConfirmTier.text = "Activate as ${tier.name.lowercase().replaceFirstChar { it.uppercase() }} Node"
    }

    private fun setupConfirmButton() {
        binding.btnConfirmTier.setOnClickListener {
            onTierSelected?.invoke(selectedTier)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
