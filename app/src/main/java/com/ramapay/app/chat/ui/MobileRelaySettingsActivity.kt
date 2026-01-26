package com.ramapay.app.chat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.ramapay.app.R
import com.ramapay.app.chat.core.ChatService
import com.ramapay.app.chat.network.HubConnection
import com.ramapay.app.chat.network.MobileRelayServer
import com.ramapay.app.databinding.ActivityMobileRelayBinding
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * MobileRelaySettingsActivity - Turn your phone into a MumbleChat relay node
 * ═══════════════════════════════════════════════════════════════════════════
 * 
 * This activity allows users to:
 * 1. Start/Stop the mobile relay server
 * 2. View connection statistics
 * 3. Share their relay endpoint with others
 * 4. Connect to custom relay endpoints
 * 5. View hub connection status
 * 
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │                     Mobile Relay Settings                               │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │                                                                         │
 * │   ┌─────────────────────────────────────────────────┐                   │
 * │   │  Hub Connection: ● Connected                    │                   │
 * │   │  Status: hub.mumblechat.com                     │                   │
 * │   └─────────────────────────────────────────────────┘                   │
 * │                                                                         │
 * │   ┌─────────────────────────────────────────────────┐                   │
 * │   │  Mobile Relay Server                            │                   │
 * │   │  Status: Running on port 8765                   │                   │
 * │   │  [ Start Server ] [ Stop Server ]               │                   │
 * │   └─────────────────────────────────────────────────┘                   │
 * │                                                                         │
 * │   ┌─────────────────────────────────────────────────┐                   │
 * │   │  Statistics                                     │                   │
 * │   │  Connected Clients: 3                           │                   │
 * │   │  Messages Relayed: 1,234                        │                   │
 * │   │  Uptime: 2h 15m                                 │                   │
 * │   └─────────────────────────────────────────────────┘                   │
 * │                                                                         │
 * │   ┌─────────────────────────────────────────────────┐                   │
 * │   │  Share Your Relay                               │                   │
 * │   │  ws://192.168.1.100:8765                        │                   │
 * │   │  [ Copy ] [ Share QR ]                          │                   │
 * │   └─────────────────────────────────────────────────┘                   │
 * │                                                                         │
 * │   ┌─────────────────────────────────────────────────┐                   │
 * │   │  Custom Endpoint                                │                   │
 * │   │  [ Connect to custom relay ]                    │                   │
 * │   └─────────────────────────────────────────────────┘                   │
 * │                                                                         │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
@AndroidEntryPoint
class MobileRelaySettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMobileRelayBinding
    private val viewModel: MobileRelayViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMobileRelayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupButtons()
        observeViewModel()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = "Mobile Relay"
        }
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupButtons() {
        // Start/Stop server buttons
        binding.btnStartServer.setOnClickListener {
            viewModel.startRelayServer()
        }

        binding.btnStopServer.setOnClickListener {
            viewModel.stopRelayServer()
        }

        // Copy endpoint
        binding.btnCopyEndpoint.setOnClickListener {
            viewModel.relayEndpoint.value?.let { endpoint ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("MumbleChat Relay", endpoint)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Endpoint copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        // Share QR
        binding.btnShareQr.setOnClickListener {
            // TODO: Generate and show QR code for the endpoint
            Toast.makeText(this, "QR sharing coming soon", Toast.LENGTH_SHORT).show()
        }

        // Custom endpoint
        binding.btnCustomEndpoint.setOnClickListener {
            showCustomEndpointDialog()
        }

        // Refresh stats
        binding.btnRefresh.setOnClickListener {
            viewModel.refreshStats()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.hubConnectionState.collect { state ->
                updateHubConnectionUI(state)
            }
        }

        lifecycleScope.launch {
            viewModel.relayServerState.collect { state ->
                updateRelayServerUI(state)
            }
        }

        lifecycleScope.launch {
            viewModel.relayStats.collect { stats ->
                updateStatsUI(stats)
                
                // Update hub endpoint and fee
                if (stats != null && stats.hubEndpoint != null) {
                    binding.textEndpointUrl.text = stats.hubEndpoint
                    binding.textHubFee.text = "Hub fee: ${stats.hubFeePercent}%"
                    binding.btnCopyEndpoint.isEnabled = true
                    binding.btnShareQr.isEnabled = true
                } else if (stats?.isRunning == true) {
                    binding.textEndpointUrl.text = "Connecting to hub..."
                    binding.btnCopyEndpoint.isEnabled = false
                    binding.btnShareQr.isEnabled = false
                } else {
                    binding.textEndpointUrl.text = "Start server to get endpoint..."
                    binding.btnCopyEndpoint.isEnabled = false
                    binding.btnShareQr.isEnabled = false
                }
            }
        }
        
        // Observe hub stats for network info
        lifecycleScope.launch {
            viewModel.hubStats.collect { stats ->
                updateNetworkInfoUI(stats)
            }
        }
        
        // Observe estimated rewards
        lifecycleScope.launch {
            viewModel.estimatedRewards.collect { rewards ->
                updateRewardsUI(rewards)
            }
        }

        // relayEndpoint is now handled via relayStats.hubEndpoint above
        // This is kept for backward compatibility but stats.hubEndpoint takes priority
        lifecycleScope.launch {
            viewModel.relayEndpoint.collect { endpoint ->
                // Only update if we don't have hub endpoint yet
                if (viewModel.relayStats.value?.hubEndpoint == null && endpoint != null) {
                    binding.textEndpointUrl.text = endpoint
                    binding.btnCopyEndpoint.isEnabled = true
                    binding.btnShareQr.isEnabled = true
                }
            }
        }

        lifecycleScope.launch {
            viewModel.customEndpoint.collect { endpoint ->
                if (endpoint != null) {
                    binding.textCustomEndpoint.text = endpoint
                    binding.textCustomEndpoint.visibility = View.VISIBLE
                } else {
                    binding.textCustomEndpoint.visibility = View.GONE
                }
            }
        }

        lifecycleScope.launch {
            viewModel.isLoading.collect { loading ->
                binding.progressLoading.visibility = if (loading) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            viewModel.error.collect { error ->
                error?.let {
                    Toast.makeText(this@MobileRelaySettingsActivity, it, Toast.LENGTH_LONG).show()
                    viewModel.clearError()
                }
            }
        }
    }
    
    private fun updateNetworkInfoUI(stats: HubConnection.HubStats) {
        binding.textNetworkInfo.text = "📊 Pool: 100 MCT/day • ${stats.totalNodes} nodes • ${stats.totalWeightedRelays} relays"
    }
    
    private fun updateRewardsUI(rewards: HubConnection.EstimatedRewards) {
        binding.textRelayReward.text = String.format("%.4f", rewards.relayPoolReward)
        binding.textTierReward.text = String.format("%.4f", rewards.tierReward)
        binding.textTotalReward.text = String.format("%.4f", rewards.totalEstimated)
    }

    private fun updateHubConnectionUI(state: HubConnection.HubConnectionState) {
        val (statusText, statusColor, statusIcon) = when (state) {
            HubConnection.HubConnectionState.DISCONNECTED -> Triple(
                "Disconnected",
                R.color.dove,
                R.drawable.ic_circle_outline
            )
            HubConnection.HubConnectionState.CONNECTING -> Triple(
                "Connecting...",
                R.color.warning,
                R.drawable.ic_sync
            )
            HubConnection.HubConnectionState.CONNECTED -> Triple(
                "Connected",
                R.color.green,
                R.drawable.ic_circle
            )
            HubConnection.HubConnectionState.AUTHENTICATED -> Triple(
                "Authenticated",
                R.color.green,
                R.drawable.ic_check_circle
            )
            HubConnection.HubConnectionState.RECONNECTING -> Triple(
                "Reconnecting...",
                R.color.warning,
                R.drawable.ic_sync
            )
            HubConnection.HubConnectionState.ERROR -> Triple(
                "Error",
                R.color.error,
                R.drawable.ic_cancel
            )
        }

        binding.textHubStatus.text = statusText
        binding.textHubStatus.setTextColor(getColor(statusColor))
        binding.iconHubStatus.setImageResource(statusIcon)
        binding.iconHubStatus.setColorFilter(getColor(statusColor))
        
        binding.textHubEndpoint.text = when (state) {
            HubConnection.HubConnectionState.AUTHENTICATED,
            HubConnection.HubConnectionState.CONNECTED -> "hub.mumblechat.com"
            else -> "---"
        }
    }

    private fun updateRelayServerUI(state: MobileRelayServer.RelayServerState) {
        when (state) {
            MobileRelayServer.RelayServerState.STOPPED -> {
                binding.textRelayStatus.text = "Stopped"
                binding.textRelayStatus.setTextColor(getColor(R.color.dove))
                binding.iconRelayStatus.setColorFilter(getColor(R.color.dove))
                binding.btnStartServer.visibility = View.VISIBLE
                binding.btnStopServer.visibility = View.GONE
            }
            MobileRelayServer.RelayServerState.STARTING -> {
                binding.textRelayStatus.text = "Starting..."
                binding.textRelayStatus.setTextColor(getColor(R.color.warning))
                binding.iconRelayStatus.setColorFilter(getColor(R.color.warning))
                binding.btnStartServer.visibility = View.GONE
                binding.btnStopServer.visibility = View.GONE
            }
            MobileRelayServer.RelayServerState.RUNNING -> {
                binding.textRelayStatus.text = "Running"
                binding.textRelayStatus.setTextColor(getColor(R.color.green))
                binding.iconRelayStatus.setColorFilter(getColor(R.color.green))
                binding.btnStartServer.visibility = View.GONE
                binding.btnStopServer.visibility = View.VISIBLE
            }
            MobileRelayServer.RelayServerState.ERROR -> {
                binding.textRelayStatus.text = "Error"
                binding.textRelayStatus.setTextColor(getColor(R.color.error))
                binding.iconRelayStatus.setColorFilter(getColor(R.color.error))
                binding.btnStartServer.visibility = View.VISIBLE
                binding.btnStopServer.visibility = View.GONE
            }
        }
    }

    private fun updateStatsUI(stats: MobileRelayServer.RelayStats?) {
        if (stats == null) {
            binding.textConnectedClients.text = "0"
            binding.textMessagesRelayed.text = "0"
            binding.textUptime.text = "0s"
            binding.textOfflineStored.text = "0"
            return
        }

        binding.textConnectedClients.text = stats.connectedClients.toString()
        binding.textMessagesRelayed.text = stats.messagesRelayed.toString()
        binding.textUptime.text = formatUptime(stats.uptimeSeconds)
        binding.textOfflineStored.text = stats.offlineMessagesStored.toString()
    }

    private fun formatUptime(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    private fun showCustomEndpointDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_endpoint, null)
        val editEndpoint = dialogView.findViewById<android.widget.EditText>(R.id.editEndpoint)

        // Pre-fill with current custom endpoint if any
        viewModel.customEndpoint.value?.let {
            editEndpoint.setText(it)
        }

        AlertDialog.Builder(this)
            .setTitle("Custom Relay Endpoint")
            .setView(dialogView)
            .setPositiveButton("Connect") { _, _ ->
                val endpoint = editEndpoint.text.toString().trim()
                if (endpoint.isNotEmpty()) {
                    viewModel.setCustomEndpoint(endpoint)
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Use Default") { _, _ ->
                viewModel.setCustomEndpoint(null)
            }
            .show()
    }
}

/**
 * ViewModel for MobileRelaySettingsActivity
 */
@HiltViewModel
class MobileRelayViewModel @Inject constructor(
    private val chatService: ChatService,
    private val hubConnection: HubConnection
) : ViewModel() {

    val hubConnectionState: StateFlow<HubConnection.HubConnectionState> = chatService.hubConnectionState
    val relayServerState: StateFlow<MobileRelayServer.RelayServerState> = chatService.mobileRelayState
    
    // Hub stats for rewards display
    val hubStats: StateFlow<HubConnection.HubStats> = hubConnection.hubStats
    val estimatedRewards: StateFlow<HubConnection.EstimatedRewards> = hubConnection.estimatedRewards

    private val _relayStats = MutableStateFlow<MobileRelayServer.RelayStats?>(null)
    val relayStats: StateFlow<MobileRelayServer.RelayStats?> = _relayStats

    private val _relayEndpoint = MutableStateFlow<String?>(null)
    val relayEndpoint: StateFlow<String?> = _relayEndpoint

    private val _customEndpoint = MutableStateFlow<String?>(null)
    val customEndpoint: StateFlow<String?> = _customEndpoint

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        refreshStats()
    }

    fun startRelayServer() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val endpoint = chatService.startMobileRelayServer()
                _relayEndpoint.value = endpoint
                if (endpoint == null) {
                    _error.value = "Failed to start relay server"
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to start relay server")
                _error.value = "Error: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun stopRelayServer() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                chatService.stopMobileRelayServer()
                _relayEndpoint.value = null
            } catch (e: Exception) {
                Timber.e(e, "Failed to stop relay server")
                _error.value = "Error: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setCustomEndpoint(endpoint: String?) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                chatService.setCustomRelayEndpoint(endpoint)
                _customEndpoint.value = endpoint
            } catch (e: Exception) {
                Timber.e(e, "Failed to set custom endpoint")
                _error.value = "Error: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refreshStats() {
        viewModelScope.launch {
            try {
                _relayStats.value = chatService.getMobileRelayStats()
                _relayEndpoint.value = chatService.getMobileRelayEndpoint()
            } catch (e: Exception) {
                Timber.e(e, "Failed to refresh stats")
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
