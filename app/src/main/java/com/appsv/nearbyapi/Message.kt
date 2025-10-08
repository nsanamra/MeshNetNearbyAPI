package com.appsv.nearbyapi

import org.w3c.dom.Text

data class Message(
    val msgId: String,
    val senderId: String, // This must be 'senderId' to match the adapter
    val recipientId: String,
    val messageText: String
)

