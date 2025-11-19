package com.appsv.nearbyapi

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.offlinechatapp.R
import java.io.OutputStream
import java.lang.Exception

class MessageAdapter(private val messages: List<Message>, private val currentUsername: String) : RecyclerView.Adapter<MessageAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageBubble: LinearLayout = view.findViewById(R.id.messageBubble)
        val messageTextView: TextView = view.findViewById(R.id.messageTextView)
        val senderTextView: TextView = view.findViewById(R.id.senderTextView)
        val messageImageView: ImageView = view.findViewById(R.id.messageImageView)
        // NEW: Download Button
        val downloadImageButton: Button = view.findViewById(R.id.downloadImageButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = messages[position]

        // Handle messageType
        if (message.messageType == "TEXT") {
            holder.messageTextView.visibility = View.VISIBLE
            holder.messageImageView.visibility = View.GONE
            holder.downloadImageButton.visibility = View.GONE
            holder.messageTextView.text = message.messageText
        } else { // "IMAGE"
            try {
                val imageBytes = Base64.decode(message.messageText, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                holder.messageImageView.setImageBitmap(bitmap)
                holder.messageImageView.visibility = View.VISIBLE
                holder.messageTextView.visibility = View.GONE

                // Show download button for images
                holder.downloadImageButton.visibility = View.VISIBLE
                holder.downloadImageButton.setOnClickListener {
                    saveImageToGallery(holder.itemView.context, bitmap)
                }

            } catch (e: Exception) {
                Log.e("MessageAdapter", "Failed to decode Base64 image", e)
                holder.messageImageView.visibility = View.GONE
                holder.downloadImageButton.visibility = View.GONE
                holder.messageTextView.visibility = View.VISIBLE
                holder.messageTextView.text = "[Image load failed]"
            }
        }

        // Formatting (Sent vs Received)
        val params = holder.messageBubble.layoutParams as LinearLayout.LayoutParams

        if (message.senderId == currentUsername) {
            holder.senderTextView.visibility = View.GONE
            params.gravity = Gravity.END
            holder.messageBubble.setBackgroundResource(R.drawable.message_bubble_sent)
            holder.messageTextView.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.white))
        } else {
            holder.senderTextView.visibility = View.VISIBLE
            holder.senderTextView.text = message.senderId
            params.gravity = Gravity.START
            holder.messageBubble.setBackgroundResource(R.drawable.message_bubble_received)
            holder.messageTextView.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.black))
        }
        holder.messageBubble.layoutParams = params
    }

    override fun getItemCount() = messages.size

    // NEW: Function to save image
    private fun saveImageToGallery(context: Context, bitmap: Bitmap) {
        val filename = "IMG_${System.currentTimeMillis()}.jpg"
        var fos: OutputStream? = null
        var imageUri: android.net.Uri? = null

        try {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }

            imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            fos = imageUri?.let { resolver.openOutputStream(it) }

            if (fos != null) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos!!)
                Toast.makeText(context, "Image Saved to Gallery!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("MessageAdapter", "Error saving image", e)
            Toast.makeText(context, "Error saving image", Toast.LENGTH_SHORT).show()
        } finally {
            fos?.close()
        }
    }
}