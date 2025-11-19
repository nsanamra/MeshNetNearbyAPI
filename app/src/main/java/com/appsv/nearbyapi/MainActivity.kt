package com.appsv.nearbyapi

// Add all these imports at the top
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.offlinechatapp.R
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.*

class MainActivity : AppCompatActivity() {

    // ... (Constants STRATEGY, SERVICE_ID, myUsername are unchanged) ...
    private val STRATEGY = Strategy.P2P_STAR
    private val SERVICE_ID = "com.appsv.nearbyapi.SERVICE_ID"

    private val myUsername: String by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: UUID.randomUUID().toString()
    }


    private lateinit var connectionsClient: ConnectionsClient
    private lateinit var userIdTextView: TextView
    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var messageEditText: EditText
    private lateinit var recipientIdEditText: EditText
    private lateinit var sendBroadcastButton: Button
    private lateinit var sendPrivateButton: Button
    private lateinit var advertiseButton: Button
    private lateinit var discoverButton: Button
    private lateinit var discoveredDevicesRecyclerView: RecyclerView
    private lateinit var discoveredDevicesLabel: TextView

    // NEW: UI for image attachment
    private lateinit var attachImageButton: ImageButton
    private lateinit var attachmentPreviewTextView: TextView
    private var pendingImageUri: Uri? = null

    private lateinit var messageAdapter: MessageAdapter
    private lateinit var discoveredDeviceAdapter: DiscoveredDeviceAdapter

    private var messages = mutableListOf<Message>()
    private var seenSet = mutableSetOf<String>()
    private val connectedEndpoints = mutableMapOf<String, String>()
    private val discoveredEndpoints = mutableMapOf<String, DiscoveredEndpointInfo>()

    // NEW: ActivityResultLauncher for picking an image
    private val imagePickerLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                pendingImageUri = it
                val fileName = getFileName(it)
                attachmentPreviewTextView.text = "Attached: $fileName (click to clear)"
                attachmentPreviewTextView.visibility = View.VISIBLE
                messageEditText.isEnabled = false // Disable text when image is attached
                messageEditText.hint = "Image attached"
            }
        }

    // ... (Companion object with permissions is unchanged) ...
    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001
        private const val KEY_MESSAGES = "messages_key"
        private const val KEY_SEEN_SET = "seen_set_key"

        @Volatile private var isAdvertising = false
        @Volatile private var isDiscovering = false

        private val REQUIRED_PERMISSIONS: Array<String> by lazy {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState != null) {
            messages = (savedInstanceState.getSerializable(KEY_MESSAGES) as? ArrayList<Message>)?.toMutableList() ?: mutableListOf()
            seenSet = (savedInstanceState.getSerializable(KEY_SEEN_SET) as? HashSet<String>)?.toMutableSet() ?: mutableSetOf()
        }

        connectionsClient = Nearby.getConnectionsClient(this)
        userIdTextView = findViewById(R.id.userIdTextView)
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView)
        messageEditText = findViewById(R.id.messageEditText)
        recipientIdEditText = findViewById(R.id.recipientIdEditText)
        sendBroadcastButton = findViewById(R.id.sendBroadcastButton)
        sendPrivateButton = findViewById(R.id.sendPrivateButton)
        advertiseButton = findViewById(R.id.advertiseButton)
        discoverButton = findViewById(R.id.discoverButton)
        discoveredDevicesRecyclerView = findViewById(R.id.discoveredDevicesRecyclerView)
        discoveredDevicesLabel = findViewById(R.id.discoveredDevicesLabel)

        // NEW: Initialize attachment UI
        attachImageButton = findViewById(R.id.attachImageButton)
        attachmentPreviewTextView = findViewById(R.id.attachmentPreviewTextView)

        userIdTextView.text = "Your ID: $myUsername"

        // Setup for messages RecyclerView
        messageAdapter = MessageAdapter(messages, myUsername)
        messagesRecyclerView.layoutManager = LinearLayoutManager(this)
        messagesRecyclerView.adapter = messageAdapter

        // ... (Discovered devices RecyclerView setup is unchanged) ...
        discoveredDeviceAdapter = DiscoveredDeviceAdapter(discoveredEndpoints.toList()) { endpointId ->
            if (!connectedEndpoints.containsKey(endpointId)) {
                connectionsClient.requestConnection(myUsername, endpointId, connectionLifecycleCallback)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Connection requested...", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Log.e("MainActivity", "requestConnection failed", e)
                        Toast.makeText(this, "Failed to request connection", Toast.LENGTH_SHORT).show()
                    }
            } else {
                Toast.makeText(this, "Already connected or connecting.", Toast.LENGTH_SHORT).show()
            }
        }
        discoveredDevicesRecyclerView.layoutManager = LinearLayoutManager(this)
        discoveredDevicesRecyclerView.adapter = discoveredDeviceAdapter


        // ... (Advertise/Discover button listeners are unchanged) ...
        advertiseButton.setOnClickListener {
            if (isAdvertising) {
                stopAdvertising()
            } else {
                if (hasPermissions()) startAdvertising() else requestPermissions()
            }
        }

        discoverButton.setOnClickListener {
            if (isDiscovering) {
                stopDiscovery()
            } else {
                if (hasPermissions()) startDiscovery() else requestPermissions()
            }
        }

        // NEW: Attach button listener
        attachImageButton.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        // NEW: Clear attachment listener
        attachmentPreviewTextView.setOnClickListener {
            clearAttachment()
        }

        // MODIFIED: Send button listeners
        sendBroadcastButton.setOnClickListener {
            sendMessage("BROADCAST") // Pass recipient
        }

        sendPrivateButton.setOnClickListener {
            val recipientId = recipientIdEditText.text.toString().trim()

            if (recipientId.isEmpty()) {
                Toast.makeText(this, "Please enter a Recipient ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendMessage(recipientId) // Pass recipient
        }
    }

    // ... (onSaveInstanceState, onStart, onStop, permissions logic is unchanged) ...
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(KEY_MESSAGES, ArrayList(messages))
        outState.putSerializable(KEY_SEEN_SET, HashSet(seenSet))
    }

    override fun onStart() {
        super.onStart()
        if (!hasPermissions()) {
            requestPermissions()
        }
    }

    override fun onStop() {
        stopAdvertising()
        stopDiscovery()
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        super.onStop()
    }

    private fun hasPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (!(grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })) {
                Toast.makeText(this, "Permissions are required to use this app.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ... (start/stopAdvertising, start/stopDiscovery, connectionLifecycleCallback, endpointDiscoveryCallback, updateDiscoveredDevicesList are unchanged) ...
    private fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            myUsername, SERVICE_ID, connectionLifecycleCallback, advertisingOptions
        ).addOnSuccessListener {
            Toast.makeText(this, "Advertising started", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "Advertising started successfully")
            isAdvertising = true
            advertiseButton.text = "Stop Advertising"
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Failed to start advertising", Toast.LENGTH_SHORT).show()
            Log.e("MainActivity", "Advertising failed", e)
            isAdvertising = false
        }
    }

    private fun stopAdvertising() {
        connectionsClient.stopAdvertising()
        isAdvertising = false
        advertiseButton.text = "Advertise"
        Log.d("MainActivity", "Advertising stopped")
    }

    private fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            SERVICE_ID, endpointDiscoveryCallback, discoveryOptions
        ).addOnSuccessListener {
            Toast.makeText(this, "Discovery started", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "Discovery started successfully")
            isDiscovering = true
            discoverButton.text = "Stop Discovery"
            discoveredDevicesLabel.visibility = View.VISIBLE
            discoveredDevicesRecyclerView.visibility = View.VISIBLE
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Failed to start discovery", Toast.LENGTH_SHORT).show()
            Log.e("MainActivity", "Discovery failed", e)
            isDiscovering = false
        }
    }

    private fun stopDiscovery() {
        connectionsClient.stopDiscovery()
        isDiscovering = false
        discoverButton.text = "Discover"
        discoveredEndpoints.clear()
        updateDiscoveredDevicesList()
        discoveredDevicesLabel.visibility = View.GONE
        discoveredDevicesRecyclerView.visibility = View.GONE
        Log.d("MainActivity", "Discovery stopped")
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.d("MainActivity", "onConnectionInitiated: accepting connection")
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Accept Connection")
                .setMessage("Accept connection from ${connectionInfo.endpointName}?")
                .setPositiveButton("Accept") { _, _ ->
                    connectionsClient.acceptConnection(endpointId, payloadCallback)
                    connectedEndpoints[endpointId] = connectionInfo.endpointName
                }
                .setNegativeButton("Reject") { _, _ ->
                    connectionsClient.rejectConnection(endpointId)
                }
                .show()
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.d("MainActivity", "onConnectionResult: Connection successful!")
                    Toast.makeText(this@MainActivity, "Connected to ${connectedEndpoints[endpointId]}", Toast.LENGTH_SHORT).show()
                    stopDiscovery()
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.d("MainActivity", "onConnectionResult: Connection rejected")
                    Toast.makeText(this@MainActivity, "Connection rejected by peer", Toast.LENGTH_SHORT).show()
                    connectedEndpoints.remove(endpointId)
                }
                ConnectionsStatusCodes.STATUS_ERROR -> {
                    Log.e("MainActivity", "onConnectionResult: Connection error")
                    Toast.makeText(this@MainActivity, "Connection error", Toast.LENGTH_SHORT).show()
                    connectedEndpoints.remove(endpointId)
                }
                else -> {
                    Log.d("MainActivity", "onConnectionResult: Unknown status code ${result.status.statusCode}")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            val username = connectedEndpoints[endpointId] ?: "Unknown"
            Log.d("MainActivity", "onDisconnected: $username")
            Toast.makeText(this@MainActivity, "Disconnected from $username", Toast.LENGTH_SHORT).show()
            connectedEndpoints.remove(endpointId)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, discoveredEndpointInfo: DiscoveredEndpointInfo) {
            Log.d("MainActivity", "onEndpointFound: endpoint found: ${discoveredEndpointInfo.endpointName}")
            if (!connectedEndpoints.containsKey(endpointId)) {
                discoveredEndpoints[endpointId] = discoveredEndpointInfo
                updateDiscoveredDevicesList()
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d("MainActivity", "onEndpointLost: endpoint lost: $endpointId")
            discoveredEndpoints.remove(endpointId)
            updateDiscoveredDevicesList()
        }
    }

    private fun updateDiscoveredDevicesList() {
        discoveredDeviceAdapter = DiscoveredDeviceAdapter(discoveredEndpoints.toList()) { endpointId ->
            if (!connectedEndpoints.containsKey(endpointId)) {
                connectionsClient.requestConnection(myUsername, endpointId, connectionLifecycleCallback)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Connection requested...", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Log.e("MainActivity", "requestConnection failed", e)
                        Toast.makeText(this, "Failed to request connection", Toast.LENGTH_SHORT).show()
                    }
            } else {
                Toast.makeText(this, "Already connected or connecting.", Toast.LENGTH_SHORT).show()
            }
        }
        discoveredDevicesRecyclerView.adapter = discoveredDeviceAdapter
    }


    // --- NEW HELPER FUNCTIONS ---

    @SuppressLint("Range")
    private fun getFileName(uri: Uri): String {
        var name = "unknown_image.jpg"
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                name = it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME))
            }
        }
        return name
    }

    private fun clearAttachment() {
        pendingImageUri = null
        attachmentPreviewTextView.visibility = View.GONE
        attachmentPreviewTextView.text = ""
        messageEditText.isEnabled = true
        messageEditText.hint = "Type a message..."
    }

    private fun uriToBase64(uri: Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            // Scale down bitmap to avoid OOM errors and large payloads
            val scaledBitmap = scaleBitmap(bitmap, 800) // 800px max dimension

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
            val byteArray = outputStream.toByteArray()
            Base64.encodeToString(byteArray, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error converting URI to Base64", e)
            null
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        if (originalWidth <= maxDimension && originalHeight <= maxDimension) {
            return bitmap
        }

        val newWidth: Int
        val newHeight: Int

        if (originalWidth > originalHeight) {
            newWidth = maxDimension
            newHeight = (originalHeight * (maxDimension.toFloat() / originalWidth)).toInt()
        } else {
            newHeight = maxDimension
            newWidth = (originalWidth * (maxDimension.toFloat() / originalHeight)).toInt()
        }

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    // --- MODIFIED SEND/RECEIVE LOGIC ---

    // NEW: Combined send function
    private fun sendMessage(recipientId: String) {
        if (connectedEndpoints.isEmpty()) {
            Toast.makeText(this, "No one is connected. Message not sent.", Toast.LENGTH_SHORT).show()
            return
        }

        val msgId = "$myUsername-${System.currentTimeMillis()}"
        val message: Message

        if (pendingImageUri != null) {
            // This is an image message
            try {
                val imageString = uriToBase64(pendingImageUri!!)
                if (imageString == null) {
                    Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
                    return
                }
                // Use the Base64 string as the messageText
                message = Message(msgId, myUsername, recipientId, "IMAGE", imageString)
                clearAttachment()

            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to convert image to Base64", e)
                Toast.makeText(this, "Failed to send image", Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            // This is a text message
            val messageText = messageEditText.text.toString().trim()
            if (messageText.isEmpty()) {
                Toast.makeText(this, "Please enter a message", Toast.LENGTH_SHORT).show()
                return
            }
            message = Message(msgId, myUsername, recipientId, "TEXT", messageText)
            messageEditText.text.clear()
        }

        // --- Common logic for both types ---
        seenSet.add(message.msgId)
        runOnUiThread {
            messages.add(message)
            messageAdapter.notifyItemInserted(messages.size - 1)
            messagesRecyclerView.scrollToPosition(messages.size - 1)
        }
        forwardMessage(message)

        // Clear recipient ID only for private messages
        if (recipientId != "BROADCAST") {
            recipientIdEditText.text.clear()
        }
    }

    // MODIFIED: forwardMessage now serializes the 5-part Message object
    private fun forwardMessage(message: Message) {
        // "TYPE|msgId|sender|recipient|messageText (or Base64)"
        val messageString = "${message.messageType}|${message.msgId}|${message.senderId}|${message.recipientId}|${message.messageText}"
        val payload = Payload.fromBytes(messageString.toByteArray(StandardCharsets.UTF_8))
        connectionsClient.sendPayload(connectedEndpoints.keys.toList(), payload)
            .addOnFailureListener { e ->
                Log.e("MainActivity", "sendPayload failed", e)
            }
    }

    // MODIFIED: payloadCallback now parses the 5-part Message object
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val receivedString = payload.asBytes()?.toString(StandardCharsets.UTF_8)
                val parts = receivedString?.split("|")

                // MODIFIED: Check for 5 parts
                if (parts != null && parts.size == 5) {
                    val messageType = parts[0]
                    val msgId = parts[1]
                    val senderId = parts[2]
                    val recipientId = parts[3]
                    val messageText = parts[4] // This is text OR Base64

                    if (seenSet.contains(msgId)) {
                        return
                    }
                    seenSet.add(msgId)

                    // Re-create the message object
                    val receivedMessage = Message(msgId, senderId, recipientId, messageType, messageText)

                    if (recipientId == "BROADCAST" || recipientId == myUsername) {
                        runOnUiThread {
                            messages.add(receivedMessage)
                            messageAdapter.notifyItemInserted(messages.size - 1)
                            messagesRecyclerView.scrollToPosition(messages.size - 1)
                        }
                    }
                    // Forward the original message object
                    forwardMessage(receivedMessage)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // This is not used for BYTES payloads
        }
    }
}