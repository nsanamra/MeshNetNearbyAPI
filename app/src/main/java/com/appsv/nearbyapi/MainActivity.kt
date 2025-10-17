package com.appsv.nearbyapi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
    // NEW: UI elements for advertising and discovery
    private lateinit var advertiseButton: Button
    private lateinit var discoverButton: Button
    private lateinit var discoveredDevicesRecyclerView: RecyclerView
    private lateinit var discoveredDevicesLabel: TextView

    private lateinit var messageAdapter: MessageAdapter
    private lateinit var discoveredDeviceAdapter: DiscoveredDeviceAdapter

    private var messages = mutableListOf<Message>()
    private var seenSet = mutableSetOf<String>()
    private val connectedEndpoints = mutableMapOf<String, String>()
    // NEW: List to hold discovered endpoints
    private val discoveredEndpoints = mutableMapOf<String, DiscoveredEndpointInfo>()

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
        // NEW: Initialize new UI elements
        advertiseButton = findViewById(R.id.advertiseButton)
        discoverButton = findViewById(R.id.discoverButton)
        discoveredDevicesRecyclerView = findViewById(R.id.discoveredDevicesRecyclerView)
        discoveredDevicesLabel = findViewById(R.id.discoveredDevicesLabel)

        userIdTextView.text = "Your ID: $myUsername"

        // Setup for messages RecyclerView
        messageAdapter = MessageAdapter(messages, myUsername)
        messagesRecyclerView.layoutManager = LinearLayoutManager(this)
        messagesRecyclerView.adapter = messageAdapter

        // NEW: Setup for discovered devices RecyclerView
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


        // NEW: Advertise button logic
        advertiseButton.setOnClickListener {
            if (isAdvertising) {
                stopAdvertising()
            } else {
                if (hasPermissions()) startAdvertising() else requestPermissions()
            }
        }

        // NEW: Discover button logic
        discoverButton.setOnClickListener {
            if (isDiscovering) {
                stopDiscovery()
            } else {
                if (hasPermissions()) startDiscovery() else requestPermissions()
            }
        }

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(KEY_MESSAGES, ArrayList(messages))
        outState.putSerializable(KEY_SEEN_SET, HashSet(seenSet))
    }

    // MODIFIED: No auto-start, user initiates via buttons
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

    // NEW: Function to stop advertising
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
            // NEW: Show discovery UI
            discoveredDevicesLabel.visibility = View.VISIBLE
            discoveredDevicesRecyclerView.visibility = View.VISIBLE
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Failed to start discovery", Toast.LENGTH_SHORT).show()
            Log.e("MainActivity", "Discovery failed", e)
            isDiscovering = false
        }
    }

    // NEW: Function to stop discovery
    private fun stopDiscovery() {
        connectionsClient.stopDiscovery()
        isDiscovering = false
        discoverButton.text = "Discover"
        // NEW: Hide discovery UI and clear list
        discoveredEndpoints.clear()
        updateDiscoveredDevicesList()
        discoveredDevicesLabel.visibility = View.GONE
        discoveredDevicesRecyclerView.visibility = View.GONE
        Log.d("MainActivity", "Discovery stopped")
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.d("MainActivity", "onConnectionInitiated: accepting connection")
            // NEW: Show a dialog to accept the connection
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
                    // NEW: A connection is established, stop discovery to save battery
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
            // NEW: Add to list and update UI
            if (!connectedEndpoints.containsKey(endpointId)) {
                discoveredEndpoints[endpointId] = discoveredEndpointInfo
                updateDiscoveredDevicesList()
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d("MainActivity", "onEndpointLost: endpoint lost: $endpointId")
            // NEW: Remove from list and update UI
            discoveredEndpoints.remove(endpointId)
            updateDiscoveredDevicesList()
        }
    }

    // NEW: Helper function to update the discovered devices RecyclerView
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
