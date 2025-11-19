package com.appsv.nearbyapi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Spinner
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
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.config.TinkConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val STRATEGY = Strategy.P2P_STAR
    private val SERVICE_ID = "com.appsv.nearbyapi.SERVICE_ID"

    // CHANGED: Persist User ID to prevent key mismatch on restart
    private val myUsername: String by lazy {
        val prefs = getSharedPreferences("ChatAppPrefs", Context.MODE_PRIVATE)
        var id = prefs.getString("USER_ID", null)
        if (id == null) {
            id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            if (id == null || id == "9774d56d682e549c") { // Common emulator ID
                id = UUID.randomUUID().toString()
            }
            prefs.edit().putString("USER_ID", id).apply()
        }
        id!!
    }

    private lateinit var privateKeysetHandle: KeysetHandle
    private lateinit var publicKeysetHandle: KeysetHandle
    private lateinit var hybridDecrypt: HybridDecrypt
    private val peerPublicKeys = mutableMapOf<String, HybridEncrypt>()
    private var myPublicKeyStr: String = ""

    private lateinit var connectionsClient: ConnectionsClient
    private lateinit var userIdTextView: TextView
    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var messageEditText: EditText

    private lateinit var recipientSpinner: Spinner
    private lateinit var recipientAdapter: ArrayAdapter<String>
    private val recipientList = ArrayList<String>()

    private lateinit var sendBroadcastButton: Button
    private lateinit var sendPrivateButton: Button

    private lateinit var advertiseButton: Button
    private lateinit var discoverButton: Button
    private lateinit var discoveredDevicesRecyclerView: RecyclerView
    private lateinit var discoveredDevicesLabel: TextView
    private lateinit var attachImageButton: ImageButton
    private lateinit var attachmentPreviewTextView: TextView

    private lateinit var sendingProgressBar: ProgressBar

    private var pendingImageUri: Uri? = null

    private lateinit var messageAdapter: MessageAdapter
    private lateinit var discoveredDeviceAdapter: DiscoveredDeviceAdapter

    private var messages = mutableListOf<Message>()
    private var seenSet = mutableSetOf<String>()
    private val connectedEndpoints = mutableMapOf<String, String>()
    private val discoveredEndpoints = mutableMapOf<String, DiscoveredEndpointInfo>()

    private val imagePickerLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                pendingImageUri = it
                val fileName = getFileName(it)
                attachmentPreviewTextView.text = "Attached: $fileName (click to clear)"
                attachmentPreviewTextView.visibility = View.VISIBLE
                messageEditText.isEnabled = false
                messageEditText.hint = "Image attached"
            }
        }

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

        initEncryption()

        if (savedInstanceState != null) {
            messages = (savedInstanceState.getSerializable(KEY_MESSAGES) as? ArrayList<Message>)?.toMutableList() ?: mutableListOf()
            seenSet = (savedInstanceState.getSerializable(KEY_SEEN_SET) as? HashSet<String>)?.toMutableSet() ?: mutableSetOf()
        }

        connectionsClient = Nearby.getConnectionsClient(this)
        userIdTextView = findViewById(R.id.userIdTextView)
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView)
        messageEditText = findViewById(R.id.messageEditText)

        recipientSpinner = findViewById(R.id.recipientSpinner)
        recipientAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, recipientList)
        recipientSpinner.adapter = recipientAdapter

        sendBroadcastButton = findViewById(R.id.sendBroadcastButton)
        sendPrivateButton = findViewById(R.id.sendPrivateButton)

        advertiseButton = findViewById(R.id.advertiseButton)
        discoverButton = findViewById(R.id.discoverButton)
        discoveredDevicesRecyclerView = findViewById(R.id.discoveredDevicesRecyclerView)
        discoveredDevicesLabel = findViewById(R.id.discoveredDevicesLabel)
        attachImageButton = findViewById(R.id.attachImageButton)
        attachmentPreviewTextView = findViewById(R.id.attachmentPreviewTextView)

        sendingProgressBar = findViewById(R.id.sendingProgressBar)

        userIdTextView.text = "Your ID: $myUsername"

        messageAdapter = MessageAdapter(messages, myUsername)
        messagesRecyclerView.layoutManager = LinearLayoutManager(this)
        messagesRecyclerView.adapter = messageAdapter

        discoveredDeviceAdapter = DiscoveredDeviceAdapter(discoveredEndpoints.toList()) { endpointId ->
            if (!connectedEndpoints.containsKey(endpointId)) {
                connectionsClient.requestConnection(myUsername, endpointId, connectionLifecycleCallback)
                    .addOnSuccessListener { Toast.makeText(this, "Connection requested...", Toast.LENGTH_SHORT).show() }
                    .addOnFailureListener { e -> Toast.makeText(this, "Failed to request connection", Toast.LENGTH_SHORT).show() }
            } else {
                Toast.makeText(this, "Already connected.", Toast.LENGTH_SHORT).show()
            }
        }
        discoveredDevicesRecyclerView.layoutManager = LinearLayoutManager(this)
        discoveredDevicesRecyclerView.adapter = discoveredDeviceAdapter

        advertiseButton.setOnClickListener {
            if (isAdvertising) stopAdvertising() else {
                if (hasPermissions()) startAdvertising() else requestPermissions()
            }
        }

        discoverButton.setOnClickListener {
            if (isDiscovering) stopDiscovery() else {
                if (hasPermissions()) startDiscovery() else requestPermissions()
            }
        }

        attachImageButton.setOnClickListener { imagePickerLauncher.launch("image/*") }

        attachmentPreviewTextView.setOnClickListener { clearAttachment() }

        sendBroadcastButton.setOnClickListener {
            sendMessage("BROADCAST")
        }

        sendPrivateButton.setOnClickListener {
            if (recipientList.isEmpty()) {
                Toast.makeText(this, "No private recipients found yet.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val selectedRecipient = recipientSpinner.selectedItem?.toString()
            if (selectedRecipient != null) {
                sendMessage(selectedRecipient)
            } else {
                Toast.makeText(this, "Please select a recipient", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initEncryption() {
        try {
            TinkConfig.register()
            privateKeysetHandle = KeysetHandle.generateNew(
                KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM")
            )
            publicKeysetHandle = privateKeysetHandle.publicKeysetHandle
            hybridDecrypt = privateKeysetHandle.getPrimitive(HybridDecrypt::class.java)

            val outputStream = ByteArrayOutputStream()
            CleartextKeysetHandle.write(
                publicKeysetHandle,
                BinaryKeysetWriter.withOutputStream(outputStream)
            )
            myPublicKeyStr = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

            Log.d("Encryption", "Keys generated successfully.")

        } catch (e: Exception) {
            Log.e("Encryption", "Error initializing encryption", e)
            Toast.makeText(this, "Encryption Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun broadcastPublicKey() {
        if (connectedEndpoints.isEmpty()) return
        val msgId = "$myUsername-${System.currentTimeMillis()}-KEY"
        val message = Message(msgId, myUsername, "BROADCAST", "KEY", myPublicKeyStr)
        seenSet.add(msgId)
        forwardMessage(message)
    }

    // --- LIFECYCLE ---

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(KEY_MESSAGES, ArrayList(messages))
        outState.putSerializable(KEY_SEEN_SET, HashSet(seenSet))
    }

    override fun onStart() {
        super.onStart()
        if (!hasPermissions()) requestPermissions()
    }

    override fun onStop() {
        super.onStop()
        stopAdvertising()
        stopDiscovery()
    }

    override fun onDestroy() {
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        super.onDestroy()
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
        if (requestCode == REQUEST_CODE_PERMISSIONS && !(grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })) {
            Toast.makeText(this, "Permissions required.", Toast.LENGTH_LONG).show()
        }
    }

    private fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(myUsername, SERVICE_ID, connectionLifecycleCallback, advertisingOptions)
            .addOnSuccessListener {
                isAdvertising = true
                advertiseButton.text = "Stop Advertising"
                Toast.makeText(this, "Advertising...", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { isAdvertising = false }
    }

    private fun stopAdvertising() {
        connectionsClient.stopAdvertising()
        isAdvertising = false
        advertiseButton.text = "Advertise"
    }

    private fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
            .addOnSuccessListener {
                isDiscovering = true
                discoverButton.text = "Stop Discovery"
                discoveredDevicesLabel.visibility = View.VISIBLE
                discoveredDevicesRecyclerView.visibility = View.VISIBLE
            }
            .addOnFailureListener { isDiscovering = false }
    }

    private fun stopDiscovery() {
        connectionsClient.stopDiscovery()
        isDiscovering = false
        discoverButton.text = "Discover"
        discoveredEndpoints.clear()
        updateDiscoveredDevicesList()
        discoveredDevicesLabel.visibility = View.GONE
        discoveredDevicesRecyclerView.visibility = View.GONE
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Accept Connection")
                .setMessage("Connect to ${connectionInfo.endpointName}?")
                .setPositiveButton("Accept") { _, _ ->
                    connectionsClient.acceptConnection(endpointId, payloadCallback)
                    connectedEndpoints[endpointId] = connectionInfo.endpointName
                }
                .setNegativeButton("Reject") { _, _ -> connectionsClient.rejectConnection(endpointId) }
                .show()
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                Toast.makeText(this@MainActivity, "Connected!", Toast.LENGTH_SHORT).show()
                stopDiscovery()
                broadcastPublicKey()
            } else {
                connectedEndpoints.remove(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            val username = connectedEndpoints[endpointId]
            connectedEndpoints.remove(endpointId)

            if (username != null) {
                peerPublicKeys.remove(username)
                runOnUiThread {
                    if (recipientList.contains(username)) {
                        recipientList.remove(username)
                        recipientAdapter.notifyDataSetChanged()
                        Toast.makeText(this@MainActivity, "$username disconnected", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Device disconnected", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!connectedEndpoints.containsKey(endpointId)) {
                discoveredEndpoints[endpointId] = info
                updateDiscoveredDevicesList()
            }
        }
        override fun onEndpointLost(endpointId: String) {
            discoveredEndpoints.remove(endpointId)
            updateDiscoveredDevicesList()
        }
    }

    private fun updateDiscoveredDevicesList() {
        discoveredDeviceAdapter = DiscoveredDeviceAdapter(discoveredEndpoints.toList()) { endpointId ->
            if (!connectedEndpoints.containsKey(endpointId)) {
                connectionsClient.requestConnection(myUsername, endpointId, connectionLifecycleCallback)
            }
        }
        discoveredDevicesRecyclerView.adapter = discoveredDeviceAdapter
    }

    // --- SENDING LOGIC ---

    private fun sendMessage(recipientId: String) {
        if (connectedEndpoints.isEmpty()) {
            Toast.makeText(this, "No connection.", Toast.LENGTH_SHORT).show()
            return
        }

        val inputText = messageEditText.text.toString().trim()
        val imageUri = pendingImageUri

        if (imageUri == null && inputText.isEmpty()) return

        sendingProgressBar.visibility = View.VISIBLE
        sendBroadcastButton.isEnabled = false
        sendPrivateButton.isEnabled = false

        clearAttachment()
        messageEditText.text.clear()

        thread {
            try {
                val msgId = "$myUsername-${System.currentTimeMillis()}"
                var content: String
                val rawContent: String
                var type: String

                if (imageUri != null) {
                    val base64Img = uriToBase64(imageUri) ?: return@thread
                    rawContent = base64Img
                    content = base64Img
                    type = "IMAGE"
                } else {
                    rawContent = inputText
                    content = inputText
                    type = "TEXT"
                }

                if (recipientId != "BROADCAST") {
                    val recipientKey = peerPublicKeys[recipientId]
                    if (recipientKey != null) {
                        try {
                            val encryptedBytes = recipientKey.encrypt(content.toByteArray(StandardCharsets.UTF_8), null)
                            content = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
                        } catch (e: Exception) {
                            runOnUiThread {
                                Toast.makeText(this, "Encryption failed", Toast.LENGTH_SHORT).show()
                                resetUI()
                            }
                            return@thread
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this, "Key not found for $recipientId", Toast.LENGTH_SHORT).show()
                            resetUI()
                        }
                        return@thread
                    }
                }

                val msgForNetwork = Message(msgId, myUsername, recipientId, type, content)
                val msgForLocal = Message(msgId, myUsername, recipientId, type, rawContent)

                seenSet.add(msgId)

                runOnUiThread {
                    messages.add(msgForLocal)
                    messageAdapter.notifyItemInserted(messages.size - 1)
                    messagesRecyclerView.scrollToPosition(messages.size - 1)
                    resetUI()
                }
                forwardMessage(msgForNetwork)

            } catch (e: Exception) {
                Log.e("MainActivity", "Error in sendMessage thread", e)
                runOnUiThread { resetUI() }
            }
        }
    }

    private fun resetUI() {
        sendingProgressBar.visibility = View.GONE
        sendBroadcastButton.isEnabled = true
        sendPrivateButton.isEnabled = true
    }

    private fun forwardMessage(message: Message) {
        val messageString = "${message.messageType}|${message.msgId}|${message.senderId}|${message.recipientId}|${message.messageText}"
        val payload = Payload.fromBytes(messageString.toByteArray(StandardCharsets.UTF_8))
        connectionsClient.sendPayload(connectedEndpoints.keys.toList(), payload)
            .addOnFailureListener { e -> Log.e("MainActivity", "Send failed", e) }
    }

    // --- RECEIVING LOGIC ---

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val receivedString = payload.asBytes()?.toString(StandardCharsets.UTF_8) ?: return
                // CHANGED: Limit split to 5 to avoid issues if content contains pipe chars
                val parts = receivedString.split("|", limit = 5)
                if (parts.size == 5) {
                    val type = parts[0].trim()
                    val msgId = parts[1].trim()
                    val sender = parts[2].trim()
                    val recipient = parts[3].trim()
                    var content = parts[4] // Content might contain padding or whitespace which is valid in Base64

                    if (seenSet.contains(msgId)) return
                    seenSet.add(msgId)

                    if (type == "KEY") {
                        handleIncomingKey(sender, content)
                        forwardMessage(Message(msgId, sender, recipient, type, content))
                        return
                    }

                    // Decryption attempt
                    if (recipient == myUsername) {
                        try {
                            val ciphertext = Base64.decode(content, Base64.NO_WRAP)
                            val decryptedBytes = hybridDecrypt.decrypt(ciphertext, null)
                            content = String(decryptedBytes, StandardCharsets.UTF_8)
                        } catch (e: Exception) {
                            Log.e("Encryption", "Decryption failed from $sender", e)
                            content = "[Decryption Failed]"
                        }
                    }

                    if (recipient == "BROADCAST" || recipient == myUsername) {
                        val msgObj = Message(msgId, sender, recipient, type, content)
                        runOnUiThread {
                            messages.add(msgObj)
                            messageAdapter.notifyItemInserted(messages.size - 1)
                            messagesRecyclerView.scrollToPosition(messages.size - 1)
                        }
                    }

                    val originalMsg = Message(msgId, sender, recipient, type, parts[4])
                    forwardMessage(originalMsg)
                }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun handleIncomingKey(senderId: String, keyBase64: String) {
        if (peerPublicKeys.containsKey(senderId)) return

        try {
            val keyBytes = Base64.decode(keyBase64, Base64.NO_WRAP)
            val handle = CleartextKeysetHandle.read(
                BinaryKeysetReader.withInputStream(ByteArrayInputStream(keyBytes))
            )
            val encryptPrimitive = handle.getPrimitive(HybridEncrypt::class.java)
            peerPublicKeys[senderId] = encryptPrimitive

            runOnUiThread {
                if (!recipientList.contains(senderId)) {
                    recipientList.add(senderId)
                    recipientAdapter.notifyDataSetChanged()
                    Toast.makeText(this, "User $senderId available for private chat", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("Encryption", "Failed to parse key from $senderId", e)
        }
    }

    @SuppressLint("Range")
    private fun getFileName(uri: Uri): String {
        var name = "unknown.jpg"
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) name = it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME))
        }
        return name
    }

    private fun clearAttachment() {
        pendingImageUri = null
        attachmentPreviewTextView.visibility = View.GONE
        messageEditText.isEnabled = true
        messageEditText.hint = "Type a message..."
    }

    private fun uriToBase64(uri: Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            val scaled = scaleBitmap(bitmap, 800)
            val outputStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
            Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
        } catch (e: Exception) { null }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        if (originalWidth <= maxDimension && originalHeight <= maxDimension) return bitmap
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
}