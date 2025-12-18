# Pinto Android App - Release Notes

## Version 1.1.2 (Build 3)

**Release Date:** December 2025

---

## Overview

This release adds a quick-access diagnostic shortcut for viewing device information directly from the main screen.

---

## New Features

### 1. Quick Device Info Shortcut

- **Serial Number & IP Display**: Users can now quickly view the device serial number and current IP address from the main screen.
- **Access Gesture**: Press and hold the **top-left corner** of the screen.
- **PIN Code**: When prompted, enter **38472** to display the device serial number and IP address.
- **Use Cases**: Simplifies on-site troubleshooting, device identification, and network diagnostics without navigating to the full Settings screen.

---

## Version 1.1.0 (Build 2)

**Release Date:** December 2025

---

## Overview

This release introduces enhanced logging capabilities, improved video playback functionality, comprehensive settings management, and expanded server-client communication features. The update focuses on improving device management, diagnostics, and user experience.

---

## New Features

### 1. Enhanced Logging System

**Extended Log Retention with Automatic Management**

- **30-Day Log Retention**: All application logs are now retained for 30 days before automatic cleanup
- **4 MB File Size Limit**: Each log file is capped at 4 MB to ensure optimal storage management
- **Automatic File Rotation**: New log files are created automatically when the size limit is reached or when a new day begins
- **Daily Log Files**: Logs are organized by date with format `log_YYYY-MM-DD_N.txt` where N is the file counter for that day
- **Thread-Safe Logging**: All logging operations are thread-safe, ensuring reliable log capture in multi-threaded environments
- **Automatic Cleanup**: Old log files (older than 30 days) are automatically removed to prevent storage issues
- **Log Location**: Logs are stored in `Android/data/[app_package]/files/logs/`

**Log Levels Supported:**
- DEBUG - Detailed diagnostic information
- INFO - General informational messages
- WARN - Warning messages for potential issues
- ERROR - Error messages with full stack traces

---

### 2. Video Playback Enhancements

**Expanded Video Display on Error Screens**

- **Error Screen Video Support**: The idle/screensaver video now plays on error screens (Connection Error and Device Error screens), providing a more consistent user experience
- **Previous Behavior**: Video was only displayed on the amount selection screen
- **Planet LIVE Device Support**: Videos play without audio on Planet LIVE devices to comply with device-specific requirements
- **Seamless Integration**: Video playback seamlessly transitions between screensaver, amount selection, and error states

**Video Sources:**
- Bundled resource video (default fallback)
- Cloud-downloaded videos (when configured via Settings)

---

### 3. Settings Screen

**Comprehensive Device Management Interface**

A new Settings screen provides centralized access to device configuration and management tools. Access the Settings screen by pressing and holding the top-left corner for 3 seconds, then entering the PIN (01482).

#### 3.1 Server Configuration

**Purpose**: Configure the WebSocket server connection details

**How It Works:**
1. Navigate to Settings → "Server configuration"
2. Enter the server IP address or domain name
3. Enter the server port number (default: 5001)
4. Click "Save" or "Connect" (for first-time setup)

**Important Note**: After saving server configuration (when not first-time setup), the app will automatically close. This is **intentional behavior** - Planet Kiosk Mode will automatically restart the app, establishing a fresh WebSocket connection with the new server configuration. This ensures a clean connection state and prevents connection issues.

**Validation:**
- Server address accepts IPv4 addresses, domain names, or hostnames
- Port must be between 1-65535
- Real-time validation provides immediate feedback

#### 3.2 Download Idle Video

**Purpose**: Download and cache custom screensaver videos from the cloud

**How It Works:**
1. Navigate to Settings → "Download video"
2. Enter the site name (e.g., "idlevideo") or full video URL
3. Click "Download"
4. The app downloads the video from the configured cloud library
5. Video is cached locally and automatically used as the screensaver video
6. Progress indicator shows download status
7. Success/error messages provide feedback

**Video Download Details:**
- Videos are stored in the app's internal storage
- Cached videos persist across app restarts
- If download fails, the app falls back to the default bundled video
- Supports both site-name shortcuts and full URL downloads
- Timeout: 180 seconds for complete download

#### 3.3 Close App

**Purpose**: Manually close the application

**How It Works:**
1. Navigate to Settings → "Close App" (red button at bottom)
2. Click to immediately close the application
3. Planet Kiosk Mode will automatically restart the app

**Use Cases:**
- Force app restart for troubleshooting
- Apply configuration changes
- Clear application state

---

### 4. Device Information Display

**Real-Time Device Information**

The Settings screen now displays:
- **Device IP Address**: Shows the device's current local network IP address (IPv4)
- **Serial Number**: Displays the device's unique serial number

**Information Updates:**
- Device information is automatically loaded when the Settings screen opens
- Information is retrieved in real-time, ensuring accuracy
- Displays "Loading..." while information is being retrieved
- Shows "unknown" if information cannot be retrieved

---

### 5. Server-Client Communication Enhancements

#### 5.1 Device Information Request

**Server Can Request Device Information**

The server can now request device IP address and serial number from the client.

**How It Works:**
1. Server sends an `INFO_SCREEN` message with `requestType: "DEVICE_INFO"`
2. Client automatically responds with the device IP address and serial number
3. Both responses use the same transaction ID from the request for correlation
4. Note for Planet: the provided mock server does not yet return IP or serial values; settings will show the retrieved device values locally, but the mock server will not echo them back.

#### 5.2 Remote App Restart Command

**Server Can Request App Restart**

The server can now send a command to restart the application remotely.

**How It Works:**
1. Server sends a `RESTART_APP` message
2. Client receives the message and immediately closes the application
3. Planet Kiosk Mode automatically restarts the app
4. Fresh WebSocket connection is established upon restart

**Use Cases:**
- Apply configuration changes remotely
- Troubleshoot connection issues
- Force app refresh without physical access
- Clear application state remotely

---

## Technical Details

### Version Information
- **Version Name**: 1.1.0
- **Version Code**: 2
- **Minimum Android SDK**: 25 (Android 7.1)
- **Target Android SDK**: 34 (Android 14)

### Logging Implementation
- **Log Format**: `[timestamp] [level] [thread] [tag] message`
- **Timestamp Format**: `yyyy-MM-dd HH:mm:ss.SSS`
- **File Naming**: `log_YYYY-MM-DD_N.txt`
- **Storage**: External files directory (accessible via file manager)
- **Cleanup Schedule**: Automatic cleanup runs when new logs are created

### Video Playback
- **Player**: ExoPlayer (Media3)
- **Supported Formats**: MP4 (H.264/H.265)
- **Playback Mode**: Loop (continuous playback)
- **Audio**: Muted for Planet LIVE devices
- **Fallback**: Bundled resource video if cached video unavailable

### Network Communication
- **Protocol**: WebSocket (ws:// or wss://)
- **Reconnection**: Automatic reconnection with 5-second delay on failure
- **Connection State**: Monitored and logged for diagnostics
- **Message Format**: JSON

---

## Known Behaviors

### App Closure After Configuration
When server configuration is saved (except during first-time setup), the app will close automatically. This is **expected behavior**:
- Ensures fresh WebSocket connection
- Prevents stale connection states
- Planet Kiosk Mode automatically restarts the app
- New configuration is applied immediately upon restart

### Video Audio on Planet LIVE Devices
Videos play without audio on Planet LIVE devices. This is intentional to comply with device-specific requirements and prevent audio conflicts.

### Planet Mock Server Note
The mock server provided for testing does not implement returning device IP address or serial number. These values are displayed locally in the Settings screen but will not be echoed by the mock server.

---

## Bug Fixes & Improvements

- Improved error handling for video playback
- Enhanced connection state management
- Better logging coverage for troubleshooting
- Optimized video download with progress feedback
- Improved user feedback for all settings operations

---

## Upgrade Instructions

1. Install the new APK version (1.1.0, Build 2)
2. On first launch, configure server settings if prompted
3. Existing configurations are preserved
4. Logs from previous versions are maintained (if within 30-day retention)

---

## Support

For technical support or questions regarding this release, please contact your system administrator or support team.

---

## Changelog Summary

- Added 30-day log retention with 4 MB file size limits
- Video playback now available on error screens
- Silent video playback for Planet LIVE devices
- New Settings screen with server configuration
- Video download functionality from cloud storage
- Device IP and serial number display in Settings
- Server can request device information remotely
- Server can trigger app restart remotely
- Improved error handling and user feedback

---

*End of Release Notes*
