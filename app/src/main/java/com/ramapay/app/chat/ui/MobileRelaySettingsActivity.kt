package com.ramapay.app.chat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
            // Check battery optimization before starting
            if (!isIgnoringBatteryOptimizations()) {
                showBatteryOptimizationDialog()
            } else {
                viewModel.startRelayServer()
            }
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
        
        // Manual heartbeat button
        binding.btnSendHeartbeat.setOnClickListener {
            showHeartbeatConfirmDialog()
        }
        
        // Connection mode selector
        binding.radioGroupConnectionMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.radioHub -> ConnectionMode.HUB_BASED
                R.id.radioP2p -> ConnectionMode.DIRECT_P2P
                R.id.radioHybrid -> ConnectionMode.HYBRID
                else -> ConnectionMode.HUB_BASED
            }
            viewModel.setConnectionMode(mode)
        }
    }
    
    private fun showHeartbeatConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚡ Send Manual Heartbeat")
            .setMessage(
                "This will send a heartbeat transaction to the blockchain.\n\n" +
                "• Confirms your node is online\n" +
                "• Costs a small amount of RAMA gas\n" +
                "• Not required if auto-heartbeat is working\n\n" +
                "Continue?"
            )
            .setPositiveButton("Send Now") { _, _ ->
                viewModel.sendManualHeartbeat()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        
        // Observe heartbeat states
        lifecycleScope.launch {
            viewModel.lastHeartbeatTime.collect { timestamp ->
                updateLastHeartbeatUI(timestamp)
            }
        }
        
        lifecycleScope.launch {
            viewModel.isHeartbeatSending.collect { sending ->
                binding.btnSendHeartbeat.isEnabled = !sending
                binding.progressHeartbeat.visibility = if (sending) View.VISIBLE else View.GONE
                binding.btnSendHeartbeat.text = if (sending) "Sending..." else "⚡ Send Heartbeat"
            }
        }
        
        lifecycleScope.launch {
            viewModel.heartbeatResult.collect { result ->
                result?.let {
                    Toast.makeText(this@MobileRelaySettingsActivity, it, Toast.LENGTH_SHORT).show()
                    viewModel.clearHeartbeatResult()
                }
            }
        }
        
        // Observe connection mode
        lifecycleScope.launch {
            viewModel.connectionMode.collect { mode ->
                updateConnectionModeUI(mode)
            }
        }
        
        // Observe P2P peers
        lifecycleScope.launch {
            viewModel.p2pPeersConnected.collect { peers ->
                binding.textP2pPeers.text = "$peers peers"
            }
        }
    }
    
    private fun updateLastHeartbeatUI(timestamp: Long) {
        if (timestamp <= 0) {
            binding.textLastHeartbeat.text = "Never"
            binding.textNextHeartbeat.text = "Start relay to begin"
        } else {
            val elapsed = System.currentTimeMillis() - timestamp
            val elapsedMinutes = elapsed / (60 * 1000)
            val elapsedHours = elapsedMinutes / 60
            
            binding.textLastHeartbeat.text = when {
                elapsedMinutes < 1 -> "Just now"
                elapsedMinutes < 60 -> "${elapsedMinutes}m ago"
                else -> "${elapsedHours}h ${elapsedMinutes % 60}m ago"
            }
            
            // Next heartbeat in ~5.5 hours
            val nextHeartbeat = (5.5 * 60 * 60 * 1000).toLong() - elapsed
            if (nextHeartbeat > 0) {
                val nextMinutes = nextHeartbeat / (60 * 1000)
                val nextHours = nextMinutes / 60
                binding.textNextHeartbeat.text = "Next in ${nextHours}h ${nextMinutes % 60}m"
            } else {
                binding.textNextHeartbeat.text = "Due now"
            }
        }
    }
    
    private fun updateConnectionModeUI(mode: ConnectionMode) {
        when (mode) {
            ConnectionMode.HUB_BASED -> {
                binding.radioHub.isChecked = true
                binding.textConnectionModeDesc.text = "🔒 IP hidden, routed through hub"
            }
            ConnectionMode.DIRECT_P2P -> {
                binding.radioP2p.isChecked = true
                binding.textConnectionModeDesc.text = "⚡ Faster, direct peer connections"
            }
            ConnectionMode.HYBRID -> {
                binding.radioHybrid.isChecked = true
                binding.textConnectionModeDesc.text = "🔄 Best of both - hub discovery + P2P"
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
    
    // ============ Battery Optimization ============
    
    /**
     * Check if app is exempt from battery optimization.
     * This is required for reliable background relay operation.
     */
    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(packageName)
        }
        return true  // Not needed on older Android versions
    }
    
    /**
     * Show dialog explaining why battery optimization exemption is needed.
     */
    private fun showBatteryOptimizationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Background Relay Permission")
            .setMessage(
                "To keep your relay node running when the phone is locked, " +
                "MumbleChat needs to be exempt from battery optimization.\n\n" +
                "This ensures:\n" +
                "• Continuous message relaying\n" +
                "• Accurate uptime tracking for rewards\n" +
                "• Reliable hub connection\n\n" +
                "You can change this in Settings anytime."
            )
            .setPositiveButton("Allow") { _, _ ->
                requestBatteryOptimizationExemption()
            }
            .setNegativeButton("Start Anyway") { _, _ ->
                // Start even without exemption (may be killed by system)
                viewModel.startRelayServer()
                Toast.makeText(
                    this, 
                    "⚠️ Relay may stop when phone is locked", 
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }
    
    /**
     * Request battery optimization exemption from the system.
     */
    @Suppress("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                
                // Start relay after user returns (they may or may not grant)
                // The relay will work, but may be killed by system if not exempt
                viewModel.startRelayServer()
            } catch (e: Exception) {
                Timber.e(e, "Failed to request battery optimization exemption")
                // Fallback: open battery settings
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) {
                    Toast.makeText(this, "Please disable battery optimization in Settings", Toast.LENGTH_LONG).show()
                }
                viewModel.startRelayServer()
            }
        } else {
            viewModel.startRelayServer()
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
 * Connection mode for relay operations
 */
enum class ConnectionMode {
    HUB_BASED,     // Connect through hub (default, IP hidden)
    DIRECT_P2P,    // Direct P2P connections (faster, IP visible)
    HYBRID         // Use both (hub for discovery, P2P for direct when possible)
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
    
    // Heartbeat tracking
    private val _lastHeartbeatTime = MutableStateFlow<Long>(0L)
    val lastHeartbeatTime: StateFlow<Long> = _lastHeartbeatTime
    
    private val _isHeartbeatSending = MutableStateFlow(false)
    val isHeartbeatSending: StateFlow<Boolean> = _isHeartbeatSending
    
    private val _heartbeatResult = MutableStateFlow<String?>(null)
    val heartbeatResult: StateFlow<String?> = _heartbeatResult
    
    // Connection mode
    private val _connectionMode = MutableStateFlow(ConnectionMode.HUB_BASED)
    val connectionMode: StateFlow<ConnectionMode> = _connectionMode
    
    // P2P stats
    private val _p2pPeersConnected = MutableStateFlow(0)
    val p2pPeersConnected: StateFlow<Int> = _p2pPeersConnected

    init {
        refreshStats()
        loadLastHeartbeatTime()
    }
    
    private fun loadLastHeartbeatTime() {
        viewModelScope.launch {
            try {
                _lastHeartbeatTime.value = chatService.getLastHeartbeatTime()
            } catch (e: Exception) {
                Timber.e(e, "Failed to load last heartbeat time")
            }
        }
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
                _p2pPeersConnected.value = chatService.getConnectedP2PPeers()
            } catch (e: Exception) {
                Timber.e(e, "Failed to refresh stats")
            }
        }
    }
    
    /**
     * Send a manual heartbeat to the blockchain.
     * This is useful when the user wants to ensure their node is active
     * without waiting for the automatic 5.5 hour interval.
     */
    fun sendManualHeartbeat() {
        viewModelScope.launch {
            _isHeartbeatSending.value = true
            _heartbeatResult.value = null
            try {
                val result = chatService.sendManualHeartbeat()
                if (result.success) {
                    _lastHeartbeatTime.value = System.currentTimeMillis()
                    _heartbeatResult.value = "✅ Heartbeat sent! TX: ${result.txHash?.take(10)}..."
                } else {
                    _heartbeatResult.value = "❌ ${result.error ?: "Failed to send heartbeat"}"
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to send manual heartbeat")
                _heartbeatResult.value = "❌ Error: ${e.message}"
            } finally {
                _isHeartbeatSending.value = false
            }
        }
    }
    
    /**
     * Set the connection mode for relay operations.
     */
    fun setConnectionMode(mode: ConnectionMode) {
        viewModelScope.launch {
            try {
                chatService.setConnectionMode(mode)
                _connectionMode.value = mode
            } catch (e: Exception) {
                Timber.e(e, "Failed to set connection mode")
                _error.value = "Error: ${e.message}"
            }
        }
    }
    
    fun clearHeartbeatResult() {
        _heartbeatResult.value = null
    }

    fun clearError() {
        _error.value = null
    }
}
