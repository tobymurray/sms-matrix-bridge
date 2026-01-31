package com.technicallyrural.smsmatrixbridge.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.technicallyrural.smsmatrixbridge.BridgeApplication
import com.technicallyrural.smsmatrixbridge.R

/**
 * Activity for starting a new conversation with a phone number.
 */
class NewConversationActivity : AppCompatActivity() {

    private lateinit var phoneInput: EditText
    private lateinit var startButton: Button

    private val repository by lazy { BridgeApplication.instance.messageRepository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_conversation)

        setupViews()
    }

    private fun setupViews() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        phoneInput = findViewById(R.id.phoneInput)
        startButton = findViewById(R.id.startButton)

        startButton.setOnClickListener {
            startConversation()
        }
    }

    private fun startConversation() {
        val phone = phoneInput.text.toString().trim()

        if (phone.isBlank()) {
            Toast.makeText(this, "Please enter a phone number", Toast.LENGTH_SHORT).show()
            return
        }

        // Normalize phone number
        val normalized = repository.normalizePhoneNumber(phone)

        // Open conversation activity
        val intent = Intent(this, ConversationActivity::class.java).apply {
            putExtra(ConversationActivity.EXTRA_PHONE_NUMBER, normalized)
        }
        startActivity(intent)
        finish()
    }
}
