package com.ramapay.app.chat.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ramapay.app.chat.data.entity.MessageEntity
import com.ramapay.app.chat.data.entity.MessageStatus
import com.ramapay.app.databinding.ItemMessageIncomingBinding
import com.ramapay.app.databinding.ItemMessageOutgoingBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter for message list in conversation.
 */
class MessageListAdapter(
    private val currentWalletAddress: String,
    private val onRetryClick: (MessageEntity) -> Unit,
    private val onMessageLongClick: (MessageEntity) -> Boolean,
    private val showSenderInfo: Boolean = false  // For group chats
) : ListAdapter<MessageEntity, RecyclerView.ViewHolder>(MessageDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_OUTGOING = 0
        private const val VIEW_TYPE_INCOMING = 1
    }

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position)
        return if (message.senderAddress.equals(currentWalletAddress, ignoreCase = true)) {
            VIEW_TYPE_OUTGOING
        } else {
            VIEW_TYPE_INCOMING
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_OUTGOING -> {
                val binding = ItemMessageOutgoingBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                OutgoingViewHolder(binding)
            }
            else -> {
                val binding = ItemMessageIncomingBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                IncomingViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is OutgoingViewHolder -> holder.bind(message)
            is IncomingViewHolder -> holder.bind(message)
        }
    }

    inner class OutgoingViewHolder(
        private val binding: ItemMessageOutgoingBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onMessageLongClick(getItem(position))
                } else {
                    false
                }
            }

            binding.buttonRetry.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onRetryClick(getItem(position))
                }
            }
        }

        fun bind(message: MessageEntity) {
            binding.textMessage.text = message.content
            binding.textTime.text = formatTime(message.timestamp)

            // Status indicator
            when (message.status) {
                MessageStatus.PENDING, MessageStatus.SENDING -> {
                    binding.iconStatus.isVisible = true
                    binding.progressSending.isVisible = true
                    binding.buttonRetry.isVisible = false
                }
                MessageStatus.SENT_DIRECT, MessageStatus.SENT_TO_RELAY -> {
                    binding.iconStatus.isVisible = true
                    binding.progressSending.isVisible = false
                    binding.buttonRetry.isVisible = false
                }
                MessageStatus.DELIVERED -> {
                    binding.iconStatus.isVisible = true
                    binding.progressSending.isVisible = false
                    binding.buttonRetry.isVisible = false
                }
                MessageStatus.READ -> {
                    binding.iconStatus.isVisible = true
                    binding.progressSending.isVisible = false
                    binding.buttonRetry.isVisible = false
                }
                MessageStatus.FAILED -> {
                    binding.iconStatus.isVisible = false
                    binding.progressSending.isVisible = false
                    binding.buttonRetry.isVisible = true
                }
            }

            // Encryption indicator
            binding.iconEncrypted.isVisible = message.encryptedContent != null
        }
    }

    inner class IncomingViewHolder(
        private val binding: ItemMessageIncomingBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onMessageLongClick(getItem(position))
                } else {
                    false
                }
            }
        }

        fun bind(message: MessageEntity) {
            binding.textMessage.text = message.content
            binding.textTime.text = formatTime(message.timestamp)

            // Encryption indicator
            binding.iconEncrypted.isVisible = message.encryptedContent != null
        }
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<MessageEntity>() {
        override fun areItemsTheSame(oldItem: MessageEntity, newItem: MessageEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: MessageEntity, newItem: MessageEntity): Boolean {
            return oldItem == newItem
        }
    }
}
