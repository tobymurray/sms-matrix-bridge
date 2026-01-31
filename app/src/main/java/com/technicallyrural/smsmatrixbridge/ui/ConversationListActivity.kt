package com.technicallyrural.smsmatrixbridge.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.technicallyrural.smsmatrixbridge.BridgeApplication
import com.technicallyrural.smsmatrixbridge.MainActivity
import com.technicallyrural.smsmatrixbridge.R
import com.technicallyrural.smsmatrixbridge.data.ConversationEntity
import com.technicallyrural.smsmatrixbridge.status.StatusActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Main conversation list activity - the minimal SMS client inbox.
 *
 * Displays all conversations with:
 * - Contact name or phone number
 * - Last message preview
 * - Timestamp
 * - Unread count badge
 * - Delivery error indicator
 */
class ConversationListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var adapter: ConversationAdapter

    private val repository by lazy { BridgeApplication.instance.messageRepository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversation_list)

        setupViews()
        observeConversations()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_conversation_list, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_status -> {
                startActivity(Intent(this, StatusActivity::class.java))
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, MainActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupViews() {
        recyclerView = findViewById(R.id.conversationRecyclerView)
        emptyView = findViewById(R.id.emptyView)

        adapter = ConversationAdapter { conversation ->
            openConversation(conversation)
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fabNewConversation).setOnClickListener {
            startNewConversation()
        }
    }

    private fun observeConversations() {
        lifecycleScope.launch {
            repository.getActiveConversations().collectLatest { conversations ->
                adapter.submitList(conversations)
                updateEmptyState(conversations.isEmpty())
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
        }
    }

    private fun openConversation(conversation: ConversationEntity) {
        val intent = Intent(this, ConversationActivity::class.java).apply {
            putExtra(ConversationActivity.EXTRA_CONVERSATION_ID, conversation.id)
            putExtra(ConversationActivity.EXTRA_PHONE_NUMBER, conversation.phoneNumber)
        }
        startActivity(intent)
    }

    private fun startNewConversation() {
        val intent = Intent(this, NewConversationActivity::class.java)
        startActivity(intent)
    }

    /**
     * Adapter for the conversation list RecyclerView.
     */
    private class ConversationAdapter(
        private val onClick: (ConversationEntity) -> Unit
    ) : ListAdapter<ConversationEntity, ConversationAdapter.ViewHolder>(ConversationDiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_conversation, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position), onClick)
        }

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val nameText: TextView = itemView.findViewById(R.id.conversationName)
            private val previewText: TextView = itemView.findViewById(R.id.conversationPreview)
            private val timestampText: TextView = itemView.findViewById(R.id.conversationTimestamp)
            private val unreadBadge: TextView = itemView.findViewById(R.id.unreadBadge)
            private val errorIcon: ImageView = itemView.findViewById(R.id.errorIcon)
            private val matrixIcon: ImageView = itemView.findViewById(R.id.matrixIcon)

            fun bind(conversation: ConversationEntity, onClick: (ConversationEntity) -> Unit) {
                // Name or phone number
                nameText.text = conversation.displayName ?: conversation.phoneNumber

                // Last message preview
                previewText.text = conversation.lastMessagePreview ?: "No messages"

                // Timestamp
                timestampText.text = formatTimestamp(conversation.lastMessageTimestamp)

                // Unread badge
                if (conversation.unreadCount > 0) {
                    unreadBadge.visibility = View.VISIBLE
                    unreadBadge.text = if (conversation.unreadCount > 99) "99+" else conversation.unreadCount.toString()
                } else {
                    unreadBadge.visibility = View.GONE
                }

                // Error indicator
                errorIcon.visibility = if (conversation.hasDeliveryError) View.VISIBLE else View.GONE

                // Matrix sync indicator
                matrixIcon.visibility = if (conversation.matrixRoomId != null) View.VISIBLE else View.GONE

                // Bold text for unread
                val typeface = if (conversation.unreadCount > 0) {
                    android.graphics.Typeface.DEFAULT_BOLD
                } else {
                    android.graphics.Typeface.DEFAULT
                }
                nameText.typeface = typeface
                previewText.typeface = typeface

                itemView.setOnClickListener { onClick(conversation) }
            }

            private fun formatTimestamp(timestamp: Long): String {
                if (timestamp == 0L) return ""

                val now = Calendar.getInstance()
                val msgTime = Calendar.getInstance().apply { timeInMillis = timestamp }

                return when {
                    // Today - show time
                    now.get(Calendar.DATE) == msgTime.get(Calendar.DATE) &&
                    now.get(Calendar.YEAR) == msgTime.get(Calendar.YEAR) -> {
                        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
                    }
                    // Yesterday
                    now.get(Calendar.DATE) - msgTime.get(Calendar.DATE) == 1 &&
                    now.get(Calendar.YEAR) == msgTime.get(Calendar.YEAR) -> {
                        "Yesterday"
                    }
                    // This week - show day name
                    now.get(Calendar.WEEK_OF_YEAR) == msgTime.get(Calendar.WEEK_OF_YEAR) &&
                    now.get(Calendar.YEAR) == msgTime.get(Calendar.YEAR) -> {
                        SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
                    }
                    // This year - show date
                    now.get(Calendar.YEAR) == msgTime.get(Calendar.YEAR) -> {
                        SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
                    }
                    // Different year
                    else -> {
                        SimpleDateFormat("M/d/yy", Locale.getDefault()).format(Date(timestamp))
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
}
