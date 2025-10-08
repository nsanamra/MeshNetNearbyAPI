package com.appsv.nearbyapi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.offlinechatapp.R
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.nio.charset.StandardCharsets
import java.util.*

class MainActivity : AppCompatActivity() {

    private val STRATEGY = Strategy.P2P_STAR
    private val SERVICE_ID = "com.appsv.nearbyapi.SERVICE_ID"

    // Use Android ID as a unique identifier. It's more reliable than MAC address.
    private val myUsername: String by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: UUID.randomUUID().toString()
    }

    private lateinit var connectionsClient: ConnectionsClient
    private lateinit var userIdTextView: TextView
    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var messageEditText: EditText
    private lateinit var sendBroadcastButton: Button
    private lateinit var sendPrivateButton: Button
    private lateinit var connectedDevicesTextView: TextView

    private lateinit var messageAdapter: MessageAdapter
    private val messages = mutableListOf<Message>()
    private val seenSet = mutableSetOf<String>()
    private val connectedEndpoints = mutableMapOf<String, String>() // endpointId -> username

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001
        private val REQUIRED_PERMISSIONS: Array<String> by lazy {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.NEARBY_WIFI_DEVICES // This is the new, required permission
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

        connectionsClient = Nearby.getConnectionsClient(this)
        userIdTextView = findViewById(R.id.userIdTextView)
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView)
        messageEditText = findViewById(R.id.messageEditText)
        sendBroadcastButton = findViewById(R.id.sendBroadcastButton)
        sendPrivateButton = findViewById(R.id.sendPrivateButton)

        userIdTextView.text = "Your ID: $myUsername"

        messageAdapter = MessageAdapter(messages, myUsername)
        messagesRecyclerView.layoutManager = LinearLayoutManager(this)
        messagesRecyclerView.adapter = messageAdapter

        sendBroadcastButton.setOnClickListener {
            sendMessage(messageEditText.text.toString(), "BROADCAST")
            messageEditText.text.clear()
        }

        sendPrivateButton.setOnClickListener {
            // In a real app, you would have a UI to select a recipient
            val recipientId = "RECIPIENT_ID_HERE" // Placeholder
            sendMessage(messageEditText.text.toString(), recipientId)
            messageEditText.text.clear()
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasPermissions()) {
            startNearbyServices()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onStop() {
        connectionsClient.stopAllEndpoints()
        super.onStop()
    }

    private fun hasPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startNearbyServices()
            } else {
                Toast.makeText(this, "Permissions are required to use this app.", Toast.LENGTH_LONG).show()
                // Consider showing a dialog to explain why permissions are needed and directing them to settings
            }
        }
    }

    private fun startNearbyServices() {
        startAdvertising()
        startDiscovery()
    }


    private fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            myUsername, SERVICE_ID, connectionLifecycleCallback, advertisingOptions
        ).addOnSuccessListener {
            Toast.makeText(this, "Advertising started", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "Advertising started successfully")
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Failed to start advertising", Toast.LENGTH_SHORT).show()
            Log.e("MainActivity", "Advertising failed", e)
        }
    }

    private fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            SERVICE_ID, endpointDiscoveryCallback, discoveryOptions
        ).addOnSuccessListener {
            Toast.makeText(this, "Discovery started", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "Discovery started successfully")
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Failed to start discovery", Toast.LENGTH_SHORT).show()
            Log.e("MainActivity", "Discovery failed", e)
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            // Automatically accept connections
            connectionsClient.acceptConnection(endpointId, payloadCallback)
            connectedEndpoints[endpointId] = connectionInfo.endpointName
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Toast.makeText(this@MainActivity, "Connected to ${connectedEndpoints[endpointId]}", Toast.LENGTH_SHORT).show()
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Toast.makeText(this@MainActivity, "Connection rejected by ${connectedEndpoints[endpointId]}", Toast.LENGTH_SHORT).show()
                }
                ConnectionsStatusCodes.STATUS_ERROR -> {
                    Toast.makeText(this@MainActivity, "Connection error", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    // Other status codes
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Toast.makeText(this@MainActivity, "Disconnected from ${connectedEndpoints[endpointId]}", Toast.LENGTH_SHORT).show()
            connectedEndpoints.remove(endpointId)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, discoveredEndpointInfo: DiscoveredEndpointInfo) {
            connectionsClient.requestConnection(myUsername, endpointId, connectionLifecycleCallback)
        }

        override fun onEndpointLost(endpointId: String) {
            // A previously discovered endpoint has gone away.
        }
    }

    private fun sendMessage(messageText: String, recipientId: String) {
        if (connectedEndpoints.isEmpty()){
            Toast.makeText(this, "No one is nearby to send a message to.", Toast.LENGTH_SHORT).show()
            return
        }

        val msgId = "$myUsername-${System.currentTimeMillis()}"
        val message = Message(msgId, myUsername, recipientId, messageText)

        // Add to seenSet to prevent loops
        seenSet.add(message.msgId)

        // Add to our own message list
        runOnUiThread {
            messages.add(message)
            messageAdapter.notifyItemInserted(messages.size - 1)
            messagesRecyclerView.scrollToPosition(messages.size - 1)
        }

        // Forward (gossip) to all connected neighbors
        forwardMessage(message)
    }

    private fun forwardMessage(message: Message) {
        val messageString = "${message.msgId}|${message.senderId}|${message.recipientId}|${message.messageText}"
        val payload = Payload.fromBytes(messageString.toByteArray(StandardCharsets.UTF_8))
        connectionsClient.sendPayload(connectedEndpoints.keys.toList(), payload)
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val receivedString = payload.asBytes()?.toString(StandardCharsets.UTF_8)
                val parts = receivedString?.split("|")
                if (parts != null && parts.size == 4) {
                    val msgId = parts[0]
                    val senderId = parts[1]
                    val recipientId = parts[2]
                    val messageText = parts[3]

                    // Check if we have already seen this message
                    if (seenSet.contains(msgId)) {
                        return // Already processed, do not forward
                    }
                    seenSet.add(msgId)

                    val receivedMessage = Message(msgId, senderId, recipientId, messageText)

                    // Display if it's a broadcast or for me
                    if (recipientId == "BROADCAST" || recipientId == myUsername) {
                        runOnUiThread {
                            messages.add(receivedMessage)
                            messageAdapter.notifyItemInserted(messages.size - 1)
                            messagesRecyclerView.scrollToPosition(messages.size - 1)
                        }
                    }
                    // Gossip to neighbors
                    forwardMessage(receivedMessage)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Bytes payload transfer updates are sent here.
        }
    }
}

