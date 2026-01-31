package com.technicallyrural.smsmatrixbridge.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.technicallyrural.smsmatrixbridge.BridgeApplication
import com.technicallyrural.smsmatrixbridge.R
import com.technicallyrural.smsmatrixbridge.data.ConversationEntity
import com.technicallyrural.smsmatrixbridge.data.MessageDirection
import com.technicallyrural.smsmatrixbridge.data.MessageEntity
import com.technicallyrural.smsmatrixbridge.data.MessageStatus
import com.technicallyrural.smsmatrixbridge.sms.SmsSendManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity for viewing and sending messages in a conversation.
 *
 * Features:
 * - Message bubbles (inbound/outbound)
 * - Status indicators (sent/delivered/failed)
 * - Send box for composing messages
 * - Retry failed messages
 */
class ConversationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_PHONE_NUMBER = "phone_number"
    }

    private var conversationId: Long = -1
    private var phoneNumber: String = ""
    private var conversation: ConversationEntity? = null

    private lateinit var recyclerView: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var toolbar: MaterialToolbar
    private lateinit var adapter: MessageAdapter

    private val repository by lazy { BridgeApplication.instance.messageRepository }
    private val sendManager by lazy { SmsSendManager.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversation)

        conversationId = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1)
        phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: ""

        // Handle deep links (smsto:, sms:)
        if (conversationId == -1L && intent.data != null) {
            val uri = intent.data
            phoneNumber = uri?.schemeSpecificPart?.replace("-", "")?.replace(" ", "") ?: ""
        }

        setupViews()
        loadConversation()
        observeMessages()
    }

    override fun onResume() {
        super.onResume()
        // Mark conversation as read
        lifecycleScope.launch {
            if (conversationId > 0) {
                repository.markConversationRead(conversationId)
            }
        }
    }

    private fun setupViews() {
        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.title = phoneNumber

        recyclerView = findViewById(R.id.messageRecyclerView)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)

        adapter = MessageAdapter(
            onRetryClick = { message -> retryMessage(message) }
        )

        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter

        sendButton.setOnClickListener {
            sendMessage()
        }
    }

    private fun loadConversation() {
        lifecycleScope.launch {
            if (conversationId > 0) {
                conversation = repository.getConversationById(conversationId)
            } else if (phoneNumber.isNotBlank()) {
                // Get or create conversation for this phone number
                conversation = repository.getOrCreateConversation(phoneNumber)
                conversationId = conversation?.id ?: -1
            }

            conversation?.let { conv ->
                toolbar.title = conv.displayName ?: conv.phoneNumber
                toolbar.subtitle = if (conv.matrixRoomId != null) "↔ Matrix synced" else null
            }
        }
    }

    private fun observeMessages() {
        lifecycleScope.launch {
            // Wait for conversation to be loaded
            while (conversationId <= 0) {
                kotlinx.coroutines.delay(100)
                if (conversation != null) {
                    conversationId = conversation!!.id
                }
            }

            repository.getMessagesForConversation(conversationId).collectLatest { messages ->
                adapter.submitList(messages)
                // Scroll to bottom
                if (messages.isNotEmpty()) {
                    recyclerView.scrollToPosition(messages.size - 1)
                }
            }
        }
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isBlank()) return

        messageInput.text.clear()

        lifecycleScope.launch {
            sendManager.sendFromLocalUi(
                phoneNumber = phoneNumber,
                body = text
            )
        }
    }

    private fun retryMessage(message: MessageEntity) {
        lifecycleScope.launch {
            sendManager.retryMessage(message.id)
        }
    }

    /**
     * Adapter for message list RecyclerView.
     */
    private class MessageAdapter(
        private val onRetryClick: (MessageEntity) -> Unit
    ) : ListAdapter<MessageEntity, MessageAdapter.ViewHolder>(MessageDiffCallback()) {

        companion object {
            private const val TYPE_INBOUND = 0
            private const val TYPE_OUTBOUND = 1
        }

        override fun getItemViewType(position: Int): Int {
            return if (getItem(position).direction == MessageDirection.INBOUND) {
                TYPE_INBOUND
            } else {
                TYPE_OUTBOUND
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val layoutId = if (viewType == TYPE_INBOUND) {
                R.layout.item_message_inbound
            } else {
                R.layout.item_message_outbound
            }
            val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position), onRetryClick)
        }

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val bodyText: TextView = itemView.findViewById(R.id.messageBody)
            private val timestampText: TextView = itemView.findViewById(R.id.messageTimestamp)
            private val statusIcon: ImageView? = itemView.findViewById(R.id.statusIcon)
            private val retryButton: View? = itemView.findViewById(R.id.retryButton)

            fun bind(message: MessageEntity, onRetryClick: (MessageEntity) -> Unit) {
                bodyText.text = message.body

                val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                timestampText.text = timeFormat.format(Date(message.timestamp))

                // Status indicator (outbound only)
                statusIcon?.let { icon ->
                    when (message.status) {
                        MessageStatus.PENDING -> {
                            icon.setImageResource(android.R.drawable.ic_menu_recent_history)
                            icon.setColorFilter(ContextCompat.getColor(itemView.context, android.R.color.darker_gray))
                            icon.visibility = View.VISIBLE
                        }
                        MessageStatus.SENDING -> {
                            icon.setImageResource(android.R.drawable.ic_menu_upload)
                            icon.setColorFilter(ContextCompat.getColor(itemView.context, android.R.color.darker_gray))
                            icon.visibility = View.VISIBLE
                        }
                        MessageStatus.SENT -> {
                            icon.setImageResource(android.R.drawable.ic_menu_send)
                            icon.setColorFilter(ContextCompat.getColor(itemView.context, android.R.color.darker_gray))
                            icon.visibility = View.VISIBLE
                        }
                        MessageStatus.DELIVERED -> {
                            icon.setImageResource(android.R.drawable.ic_menu_send)
                            icon.setColorFilter(ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark))
                            icon.visibility = View.VISIBLE
                        }
                        MessageStatus.FAILED -> {
                            icon.setImageResource(android.R.drawable.ic_dialog_alert)
                            icon.setColorFilter(ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark))
                            icon.visibility = View.VISIBLE
                        }
                        MessageStatus.RECEIVED -> {
                            icon.visibility = View.GONE
                        }
                    }
                }

                // Retry button (failed messages only)
                retryButton?.let { btn ->
                    if (message.status == MessageStatus.FAILED) {
                        btn.visibility = View.VISIBLE
                        btn.setOnClickListener { onRetryClick(message) }
                    } else {
                        btn.visibility = View.GONE
                    }
                }
            }
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
}
