package com.appsv.nearbyapi

import java.io.Serializable

// Add ": Serializable" to allow this object to be saved
data class Message(
    val msgId: String,
    val senderId: String,
    val recipientId: String,
    val messageType: String, // "TEXT" or "IMAGE"
    val messageText: String  // Will hold text OR a Base64-encoded image
) : Serializable