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
// CRITICAL FIX: Removed the incorrect import for 'R'
// import com.example.offlinechatapp.R
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.nio.charset.StandardCharsets
import java.util.*

class MainActivity : AppCompatActivity() {

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

    private lateinit var messageAdapter: MessageAdapter
    private val messages = mutableListOf<Message>()
    private val seenSet = mutableSetOf<String>()
    private val connectedEndpoints = mutableMapOf<String, String>()

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001
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

        connectionsClient = Nearby.getConnectionsClient(this)
        userIdTextView = findViewById(R.id.userIdTextView)
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView)
        messageEditText = findViewById(R.id.messageEditText)
        recipientIdEditText = findViewById(R.id.recipientIdEditText)
        sendBroadcastButton = findViewById(R.id.sendBroadcastButton)
        sendPrivateButton = findViewById(R.id.sendPrivateButton)

        userIdTextView.text = "Your ID: $myUsername"

        messageAdapter = MessageAdapter(messages, myUsername)
        messagesRecyclerView.layoutManager = LinearLayoutManager(this)
        messagesRecyclerView.adapter = messageAdapter

        sendBroadcastButton.setOnClickListener {
            val messageText = messageEditText.text.toString().trim()
            if (messageText.isNotEmpty()) {
                sendMessage(messageText, "BROADCAST")
                messageEditText.text.clear()
            } else {
                Toast.makeText(this, "Please enter a message", Toast.LENGTH_SHORT).show()
            }
        }

        sendPrivateButton.setOnClickListener {
            val recipientId = recipientIdEditText.text.toString().trim()
            val messageText = messageEditText.text.toString().trim()

            if (recipientId.isEmpty()) {
                Toast.makeText(this, "Please enter a Recipient ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (messageText.isEmpty()) {
                Toast.makeText(this, "Please enter a message", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            sendMessage(messageText, recipientId)
            messageEditText.text.clear()
            recipientIdEditText.text.clear()
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
            Log.d("MainActivity", "onConnectionInitiated: accepting connection")
            // Automatically accept connections
            connectionsClient.acceptConnection(endpointId, payloadCallback)
            // Store the endpoint name for later use
            connectedEndpoints[endpointId] = connectionInfo.endpointName
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.d("MainActivity", "onConnectionResult: Connection successful!")
                    Toast.makeText(this@MainActivity, "Connected to ${connectedEndpoints[endpointId]}", Toast.LENGTH_SHORT).show()
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.d("MainActivity", "onConnectionResult: Connection rejected")
                    Toast.makeText(this@MainActivity, "Connection rejected by ${connectedEndpoints[endpointId]}", Toast.LENGTH_SHORT).show()
                    // CLEANUP: Remove from map if connection is rejected
                    connectedEndpoints.remove(endpointId)
                }
                ConnectionsStatusCodes.STATUS_ERROR -> {
                    Log.e("MainActivity", "onConnectionResult: Connection error")
                    Toast.makeText(this@MainActivity, "Connection error", Toast.LENGTH_SHORT).show()
                    // CLEANUP: Remove from map on error
                    connectedEndpoints.remove(endpointId)
                }
                else -> {
                    Log.d("MainActivity", "onConnectionResult: Unknown status code ${result.status.statusCode}")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d("MainActivity", "onDisconnected: ${connectedEndpoints[endpointId]}")
            Toast.makeText(this@MainActivity, "Disconnected from ${connectedEndpoints[endpointId]}", Toast.LENGTH_SHORT).show()
            connectedEndpoints.remove(endpointId)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, discoveredEndpointInfo: DiscoveredEndpointInfo) {
            Log.d("MainActivity", "onEndpointFound: endpoint found, requesting connection")
            connectionsClient.requestConnection(myUsername, endpointId, connectionLifecycleCallback)
                .addOnFailureListener { e ->
                    Log.e("MainActivity", "requestConnection failed", e)
                }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d("MainActivity", "onEndpointLost: endpoint lost")
        }
    }

    private fun sendMessage(messageText: String, recipientId: String) {
        if (connectedEndpoints.isEmpty()){
            Toast.makeText(this, "No one is connected. Message not sent.", Toast.LENGTH_SHORT).show()
            return
        }
        val msgId = "$myUsername-${System.currentTimeMillis()}"
        val message = Message(msgId, myUsername, recipientId, messageText)
        seenSet.add(message.msgId)
        runOnUiThread {
            messages.add(message)
            messageAdapter.notifyItemInserted(messages.size - 1)
            messagesRecyclerView.scrollToPosition(messages.size - 1)
        }
        forwardMessage(message)
    }

    private fun forwardMessage(message: Message) {
        val messageString = "${message.msgId}|${message.senderId}|${message.recipientId}|${message.messageText}"
        val payload = Payload.fromBytes(messageString.toByteArray(StandardCharsets.UTF_8))
        connectionsClient.sendPayload(connectedEndpoints.keys.toList(), payload)
            .addOnFailureListener { e ->
                Log.e("MainActivity", "sendPayload failed", e)
            }
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

                    if (seenSet.contains(msgId)) {
                        return
                    }
                    seenSet.add(msgId)
                    val receivedMessage = Message(msgId, senderId, recipientId, messageText)
                    if (recipientId == "BROADCAST" || recipientId == myUsername) {
                        runOnUiThread {
                            messages.add(receivedMessage)
                            messageAdapter.notifyItemInserted(messages.size - 1)
                            messagesRecyclerView.scrollToPosition(messages.size - 1)
                        }
                    }
                    forwardMessage(receivedMessage)
                }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
        }
    }
}

