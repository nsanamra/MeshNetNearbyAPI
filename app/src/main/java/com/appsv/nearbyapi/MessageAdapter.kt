package com.appsv.nearbyapi

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.offlinechatapp.R

class MessageAdapter(private val messages: List<Message>, private val currentUsername: String) : RecyclerView.Adapter<MessageAdapter.ViewHolder>() {

    // The ViewHolder needs references to ALL the views you want to modify for each item
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageBubble: LinearLayout = view.findViewById(R.id.messageBubble)
        val messageTextView: TextView = view.findViewById(R.id.messageTextView)
        val senderTextView: TextView = view.findViewById(R.id.senderTextView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = messages[position]
        holder.messageTextView.text = message.messageText

        val params = holder.messageBubble.layoutParams as LinearLayout.LayoutParams

        if (message.senderId == currentUsername) {
            // This is a message sent by the current user
            holder.senderTextView.visibility = View.GONE // Hide sender for your own messages
            params.gravity = Gravity.END
            holder.messageBubble.setBackgroundResource(R.drawable.message_bubble_sent)
            holder.messageTextView.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.white))

        } else {
            // This is a received message
            holder.senderTextView.visibility = View.VISIBLE
            holder.senderTextView.text = message.senderId
            params.gravity = Gravity.START
            holder.messageBubble.setBackgroundResource(R.drawable.message_bubble_received)
            holder.messageTextView.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.black))

        }
        holder.messageBubble.layoutParams = params
    }

    override fun getItemCount() = messages.size
}

