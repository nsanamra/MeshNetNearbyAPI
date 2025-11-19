package com.appsv.nearbyapi

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.offlinechatapp.R
import java.lang.Exception

class MessageAdapter(private val messages: List<Message>, private val currentUsername: String) : RecyclerView.Adapter<MessageAdapter.ViewHolder>() {

    // The ViewHolder needs references to ALL the views you want to modify for each item
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageBubble: LinearLayout = view.findViewById(R.id.messageBubble)
        val messageTextView: TextView = view.findViewById(R.id.messageTextView)
        val senderTextView: TextView = view.findViewById(R.id.senderTextView)
        val messageImageView: ImageView = view.findViewById(R.id.messageImageView) // NEW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = messages[position]

        // NEW: Handle messageType (TEXT vs IMAGE)
        if (message.messageType == "TEXT") {
            holder.messageTextView.visibility = View.VISIBLE
            holder.messageImageView.visibility = View.GONE
            holder.messageTextView.text = message.messageText
        } else { // "IMAGE"
            try {
                // Decode Base64 string to Bitmap
                val imageBytes = Base64.decode(message.messageText, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                holder.messageImageView.setImageBitmap(bitmap)
                holder.messageImageView.visibility = View.VISIBLE
                holder.messageTextView.visibility = View.GONE
            } catch (e: Exception) {
                Log.e("MessageAdapter", "Failed to decode Base64 image", e)
                // Show error message in the text view
                holder.messageImageView.visibility = View.GONE
                holder.messageTextView.visibility = View.VISIBLE
                holder.messageTextView.text = "[Image load failed]"
            }
        }

        // --- This part is unchanged ---
        val params = holder.messageBubble.layoutParams as LinearLayout.LayoutParams

        if (message.senderId == currentUsername) {
            // This is a message sent by the current user
            holder.senderTextView.visibility = View.GONE // Hide sender for your own messages
            params.gravity = Gravity.END
            holder.messageBubble.setBackgroundResource(R.drawable.message_bubble_sent)
            // Set text color based on type
            holder.messageTextView.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.white))

        } else {
            // This is a received message
            holder.senderTextView.visibility = View.VISIBLE
            holder.senderTextView.text = message.senderId
            params.gravity = Gravity.START
            holder.messageBubble.setBackgroundResource(R.drawable.message_bubble_received)
            // Set text color based on type
            holder.messageTextView.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.black))
        }
        holder.messageBubble.layoutParams = params
    }

    override fun getItemCount() = messages.size
}