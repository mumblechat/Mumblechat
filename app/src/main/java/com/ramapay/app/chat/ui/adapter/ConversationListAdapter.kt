package com.ramapay.app.chat.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ramapay.app.R
import com.ramapay.app.chat.data.entity.ConversationEntity
import com.ramapay.app.databinding.ItemConversationBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Adapter for conversation list in MumbleChat.
 */
class ConversationListAdapter(
    private val onItemClick: (ConversationEntity) -> Unit,
    private val onItemLongClick: (ConversationEntity) -> Boolean
) : ListAdapter<ConversationEntity, ConversationListAdapter.ViewHolder>(ConversationDiffCallback()) {

    // Set of wallet addresses that are online
    private val onlinePeers = mutableSetOf<String>()
    
    /**
     * Update the set of online peers and refresh affected items.
     */
    fun updateOnlinePeers(peers: Set<String>) {
        val oldPeers = onlinePeers.toSet()
        onlinePeers.clear()
        onlinePeers.addAll(peers)
        
        // Find which items need updating
        val changedAddresses = (oldPeers - peers) + (peers - oldPeers)
        currentList.forEachIndexed { index, conversation ->
            if (changedAddresses.contains(conversation.peerAddress.lowercase())) {
                notifyItemChanged(index, "online_status")
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConversationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains("online_status")) {
            // Only update online indicator
            holder.updateOnlineStatus(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class ViewHolder(
        private val binding: ItemConversationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }
            binding.root.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemLongClick(getItem(position))
                } else {
                    false
                }
            }
        }

        fun bind(conversation: ConversationEntity) {
            // Format address for display (or custom name if set)
            binding.textName.text = conversation.customName ?: formatAddress(conversation.peerAddress)

            // Last message preview
            binding.textLastMessage.text = conversation.lastMessagePreview ?: ""
            binding.textLastMessage.isVisible = !conversation.lastMessagePreview.isNullOrEmpty()

            // Timestamp
            conversation.lastMessageTime?.let { timestamp ->
                binding.textTime.text = formatTimestamp(timestamp)
                binding.textTime.isVisible = true
            } ?: run {
                binding.textTime.isVisible = false
            }

            // Group member badge (hide for now - will implement with group chat entity)
            binding.badgeMembers.isVisible = false

            // Unread badge
            if (conversation.unreadCount > 0) {
                binding.badgeUnread.isVisible = true
                binding.badgeUnread.text = if (conversation.unreadCount > 99) {
                    "99+"
                } else {
                    conversation.unreadCount.toString()
                }
            } else {
                binding.badgeUnread.isVisible = false
            }

            // Pin indicator
            binding.iconPinned.isVisible = conversation.isPinned

            // Mute indicator
            binding.iconMuted.isVisible = conversation.isMuted

            // Avatar - show first 2 characters of address
            val avatarText = conversation.peerAddress.removePrefix("0x").take(2).uppercase()
            binding.textAvatar.text = avatarText

            // Avatar background color based on address
            val colorIndex = conversation.peerAddress.hashCode() and 0x7FFFFFFF
            val colors = binding.root.context.resources.getIntArray(R.array.avatar_colors)
            val bgColor = colors[colorIndex % colors.size]
            binding.avatarBackground.setBackgroundColor(bgColor)
            
            // Online status indicator
            val isOnline = onlinePeers.contains(conversation.peerAddress.lowercase())
            binding.onlineIndicator.isVisible = isOnline
        }
        
        /**
         * Update only the online status indicator (for efficient partial updates).
         */
        fun updateOnlineStatus(conversation: ConversationEntity) {
            val isOnline = onlinePeers.contains(conversation.peerAddress.lowercase())
            binding.onlineIndicator.isVisible = isOnline
        }

        private fun formatAddress(address: String): String {
            return if (address.length > 10) {
                "${address.take(6)}...${address.takeLast(4)}"
            } else {
                address
            }
        }

        private fun formatTimestamp(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp

            return when {
                diff < TimeUnit.MINUTES.toMillis(1) -> "now"
                diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m"
                diff < TimeUnit.DAYS.toMillis(1) -> {
                    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
                }
                diff < TimeUnit.DAYS.toMillis(7) -> {
                    SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
                }
                else -> {
                    SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
                }
            }
        }
    }

    class ConversationDiffCallback : DiffUtil.ItemCallback<ConversationEntity>() {
        override fun areItemsTheSame(oldItem: ConversationEntity, newItem: ConversationEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ConversationEntity, newItem: ConversationEntity): Boolean {
            return oldItem == newItem
        }
    }
}
