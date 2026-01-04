# 📱 Nearby Mesh Chat

<div align="center">

![Android](https://img.shields.io/badge/Platform-Android-green.svg)
![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg)
![License](https://img.shields.io/badge/License-MIT-blue.svg)
![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)

**A decentralized, offline-first mesh networking chat application built with Google Nearby Connections API**

*Chat without internet. Connect without boundaries.*

[Features](#-features) • [Architecture](#-architecture) • [Installation](#-installation) • [Usage](#-usage) • [Technical Details](#-technical-details)

</div>

---

## Overview

Nearby Mesh Chat is a cutting-edge Android application that enables peer-to-peer communication without requiring internet connectivity or cellular service. Using Google's Nearby Connections API, the app creates a dynamic mesh network where messages can hop through multiple devices to reach their destination, similar to how emergency communication networks operate.

Perfect for:
- 🏕️ Remote outdoor adventures
- 🏢 Building-wide communication without WiFi
- 🎪 Large events and festivals
- 🚨 Emergency situations with no network coverage
- 🎮 LAN gaming communities

---

## Features

### **End-to-End Encryption**
- **Hybrid Encryption** using Google Tink (DHKEM with AES-256-GCM)
- Automatic key exchange between peers
- Private messages are encrypted before transmission
- Only intended recipients can decrypt messages

### **Mesh Network Topology**
- **Gossip Protocol** for message flooding
- Messages automatically route through intermediate devices
- P2P_STAR strategy for optimal connectivity
- Automatic peer discovery and connection management
- Send messages to users not directly connected to you

### **Flexible Messaging**
- **Broadcast Messages**: Send to all connected users
- **Private Messages**: Encrypted one-to-one communication
- **Manual Routing**: Send messages to specific user IDs for mesh routing
- **Image Sharing**: Attach and send compressed images
- Real-time message delivery and display

### **Active Member Tracking**
- **Live Presence System** with heartbeat mechanism (15s intervals)
- Automatic timeout detection (45s)
- Dedicated Members Activity showing all active users
- Real-time last-seen timestamps
- Persistent member list across the mesh

### **Rich Media Support**
- Image attachment and transmission
- Automatic image compression and scaling
- Base64 encoding for reliable transport
- **Download to Gallery** feature for received images
- Preview before sending

### **Automatic Discovery**
- One-tap advertising mode
- Device discovery with visual list
- Connection request dialogs
- Automatic reconnection handling

### **State Persistence**
- Messages survive orientation changes
- User ID persistence using SharedPreferences
- Unique device identification (Android ID + UUID fallback)

---

## Architecture

### **Design Pattern**
- **MVVM-inspired** architecture with repository pattern
- Singleton `MeshRepository` for shared state management
- LiveData for reactive UI updates
- Coroutines for async operations

### **Core Components**

```
┌─────────────────────────────────────────────────────┐
│                  MainActivity                        │
│  - Connection Management                            │
│  - Message Sending/Receiving                        │
│  - UI Coordination                                  │
└───────────────┬─────────────────────────────────────┘
                │
    ┌───────────┴───────────┬──────────────┐
    │                       │              │
┌───▼──────────┐   ┌───────▼────────┐  ┌─▼────────────┐
│ MeshRepository│   │ Encryption     │  │ Nearby API   │
│  - Members    │   │  - Tink Crypto │  │  - Discovery │
│  - LiveData   │   │  - Key Exchange│  │  - Advertise │
└───────────────┘   └────────────────┘  └──────────────┘
                              │
                    ┌─────────┴─────────┐
                    │                   │
              ┌─────▼──────┐     ┌─────▼──────┐
              │ Broadcast  │     │  Private   │
              │  Messages  │     │  Messages  │
              └────────────┘     └────────────┘
```

### **Message Flow**

```
User A → Encrypt → Nearby API → User B → Forward → User C
                                    ↓
                              Decrypt & Display
```

### **Heartbeat System**

```
Every 15s:
  ├─ Send PRESENCE message with timestamp
  ├─ Update MeshRepository
  └─ Prune expired members (45s timeout)
```

---

## Installation

### **Prerequisites**
- Android Studio Arctic Fox or later
- Android SDK 24+ (Android 7.0 Nougat)
- Physical Android devices (emulators don't support Nearby Connections)
- Bluetooth and Location permissions

### **Setup Steps**

1. **Clone the repository**
   ```bash
   git clone https://github.com/nsanamra/MeshNetNearbyAPI.git
   cd MeshNetNearbyAPI
   ```

2. **Open in Android Studio**
   ```
   File → Open → Select project directory
   ```

3. **Sync Gradle dependencies**
   ```
   The IDE will automatically sync build.gradle.kts
   ```

4. **Build the project**
   ```
   Build → Make Project (Ctrl+F9)
   ```

5. **Install on devices**
   ```
   Run → Run 'app' (Shift+F10)
   ```

### **Key Dependencies**
```kotlin
// Nearby Connections API
implementation("com.google.android.gms:play-services-nearby:19.0.0")

// Encryption (Google Tink)
implementation("com.google.crypto.tink:tink-android:1.10.0")

// AndroidX Libraries
implementation("androidx.core:core-ktx:1.9.0")
implementation("androidx.appcompat:appcompat:1.6.1")
implementation("com.google.android.material:material:1.10.0")
```

---

## Usage

### **First Launch**

1. **Grant Permissions**
   - Location (required for Bluetooth scanning)
   - Bluetooth (Android 12+)
   - Nearby WiFi Devices (Android 13+)
   - Storage (for image attachments)

2. **Start Advertising**
   - Tap "Advertise" button
   - Your device becomes discoverable to others

3. **Discover Peers**
   - Tap "Discover" button
   - Connect to nearby devices from the list

### **Sending Messages**

#### **Broadcast Message**
```
1. Type your message
2. Click "Broadcast"
3. Message reaches all connected users
```

#### **Private Message**
```
1. Select recipient from dropdown
   OR
   Enter recipient's User ID manually (for mesh routing)
2. Type your message
3. Click "Private"
4. Message is encrypted and sent
```

#### **Image Message**
```
1. Click 📷 attachment icon
2. Select image from gallery
3. Click "Broadcast" or "Private"
4. Image is compressed and sent
```

### **Viewing Active Members**

```
1. Click "Show Members" button
2. See real-time list of all users in mesh
3. View last-seen timestamps
4. Identify yourself ("You (Me)")
```

---

## Technical Details

### **Encryption Specification**

**Algorithm**: DHKEM_X25519_HKDF_SHA256 with AES_256_GCM

**Key Exchange**:
- Each device generates a keypair on startup
- Public keys exchanged via "KEY" message type
- Stored in `peerPublicKeys` map

**Message Encryption**:
```kotlin
// Sender
val encryptedBytes = recipientKey.encrypt(
    content.toByteArray(StandardCharsets.UTF_8), 
    null
)
val ciphertext = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)

// Receiver
val ciphertext = Base64.decode(content, Base64.NO_WRAP)
val decryptedBytes = hybridDecrypt.decrypt(ciphertext, null)
val plaintext = String(decryptedBytes, StandardCharsets.UTF_8)
```

### **Message Format**

Messages are transmitted as pipe-delimited strings:
```
TYPE|MSG_ID|SENDER_ID|RECIPIENT_ID|CONTENT
```

**Message Types**:
- `TEXT`: Plain text message
- `IMAGE`: Base64-encoded image
- `KEY`: Public key exchange
- `PRESENCE`: Heartbeat with timestamp

**Example**:
```
TEXT|user123-1234567890|user123|BROADCAST|Hello World!
PRESENCE|user456-P-1234567890|user456|BROADCAST|1234567890_user456
```

### **Mesh Routing Logic**

```kotlin
if (recipient == "BROADCAST") {
    displayMessage()  // Show to everyone
    forwardMessage()  // Flood to all peers
} 
else if (recipient == myUsername) {
    decrypt()         // Decrypt for me
    displayMessage()  // Show message
    // Don't forward
} 
else {
    // Message for someone else
    forwardMessage()  // Route through mesh
}
```

### **Gossip Protocol**

**Seen Set**: Prevents message loops
```kotlin
if (seenSet.contains(msgId)) return  // Already processed
seenSet.add(msgId)                   // Mark as seen
forwardMessage(message)              // Propagate to peers
```

**Heartbeat Mechanism**:
```kotlin
// Send presence every 15s
launch {
    while (isActive) {
        sendPresenceHeartbeat()
        pruneExpiredMembers(45000L)
        delay(15000L)
    }
}
```

### **Network Strategy**

**P2P_STAR Topology**:
- One device acts as "hub"
- Others connect as "spokes"
- Efficient for small groups (2-10 devices)
- Messages flood through star topology

**Connection Lifecycle**:
```
onConnectionInitiated() → User accepts/rejects
    ↓
onConnectionResult() → Exchange keys + presence
    ↓
Connected → Forward messages
    ↓
onDisconnected() → Clean up peer data
```

---

## Screenshots

### Main Chat Interface
- Real-time message list with sent/received bubbles
- Input field with broadcast/private options
- Recipient selector (dropdown + manual entry)
- Image attachment button
- Connection controls (Advertise/Discover)

### Members Activity
- Scrollable list of active users
- User ID and last-seen timestamp
- "You (Me)" indicator for local user
- Empty state when no members

### Discovered Devices
- Dynamic list during discovery
- Device names with connect buttons
- Auto-dismiss on connection

---

## Permissions

```xml
<!-- Bluetooth (Legacy) -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />

<!-- Android 12+ Bluetooth -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- Location (Required for Bluetooth scanning) -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

<!-- WiFi -->
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />

<!-- Android 13+ Nearby -->
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />

<!-- Storage (Images) -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
```

---

## Known Limitations

- **Range**: Limited by Bluetooth/WiFi Direct range (~100m line-of-sight)
- **Concurrent Connections**: P2P_STAR typically supports 8-10 connections
- **Battery**: Continuous advertising/discovery drains battery
- **Image Size**: Large images may take time to transmit
- **No Persistence**: Messages don't survive app restarts (except in savedInstanceState)

---

## Future Enhancements

- [ ] Message persistence with Room database
- [ ] File sharing (documents, videos)
- [ ] Voice messages
- [ ] Group chat rooms
- [ ] Message read receipts
- [ ] Typing indicators
- [ ] Dark mode theme
- [ ] Custom usernames/avatars
- [ ] QR code connection
- [ ] Mesh topology visualization

---

## Contributing

Contributions are welcome! Please follow these steps:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

## 👨‍💻 Author

**Your Name**

- GitHub: [@nsanamra](https://github.com/nsanamra/)
- Email: developernamra@gmail.com.com

---

<div align="center">

**Made with ❤️ and Kotlin**

⭐ Star this repo if you find it useful!

</div>
