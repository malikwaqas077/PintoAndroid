# Pinto Android App - Release Notes

## Version 2.1 (Build 4)

**Release Date:** February 2026

---

### Changes

- **Screensaver video download**: Fixed an issue where downloading by video name (for example, `screensaver`) sometimes failed. Name input and full URL input both work correctly now.
- **Screensaver video audio**: Replaced the default screensaver video with a new version that has no audio track. Downloaded custom videos are still used when available.

---

## Version 2.0 (Build 2)

**Release Date:** December 2025

---

## Overview

This release introduces significant architectural improvements with enhanced local control, payment provider configuration, mock payment support, and comprehensive device configuration management. The app now operates more independently from the backend, reducing server dependencies and improving reliability.

---

## New Features

### 1. Payment Provider Configuration

**Dynamic Payment Provider Selection from Backend**

- **Payment Provider Configuration**: The app now receives payment provider configuration from the backend controller via `DEVICE_INFO` message.
- **Supported Providers**:
  - **"integra"** (default): Uses real Planet Integra Client SDK for actual payment terminal hardware
  - **"mock"**: Uses MockPaymentManager for simulated payments (useful for testing and development)
- **Configuration Flow**: Server sends `DEVICE_INFO` message with `paymentProvider` field, which is stored locally in device configuration.
- **Runtime Switching**: Payment provider can be changed remotely via backend configuration without app restart.
- **Settings Display**: Current payment provider is displayed in Settings screen with clear indication (Real vs Simulated).

---

### 2. Local Screen Control

**Reduced Backend Dependencies for Screen Management**

The app now controls most screens locally without requiring backend messages for every screen transition. This improves responsiveness and reduces server load.

#### 2.1 Keypad Entry Screen (KEYPAD)

- **Local Control**: When user selects "Other" option from amount selection, the KEYPAD screen is shown locally without server message.
- **Local Validation**: Uses device configuration (min/max transaction limits) for validation.
- **Currency Display**: Currency symbol is retrieved from local device configuration.
- **User Experience**: Immediate screen transition without waiting for server response.

#### 2.2 Timeout Screen (TIMEOUT)

- **Local Control**: When `yaspaEnabled = false` in device configuration, the TIMEOUT screen is shown locally after amount selection.
- **Automatic Flow**: After showing timeout screen for 3 seconds, payment processing begins automatically.
- **No Server Dependency**: Server does not need to send TIMEOUT screen message when YASPA is disabled.

#### 2.3 Processing Screen (PROCESSING)

- **Local Control**: For DEBIT_CARD and PAY_BY_BANK payment methods, the PROCESSING screen is shown automatically by the app.
- **Immediate Display**: Screen appears immediately when payment method is selected, before any server communication.
- **Payment Flow**: Processing screen is shown while performing local card check and payment operations.

#### 2.4 Success/Failed Screens (SUCCESS/FAILED)

- **Local Control**: Transaction success or failure screens are shown locally based on payment result.
- **Automatic Display**: After payment processing completes, appropriate screen is shown without server message.
- **Error Handling**: Failed transactions show error messages from payment provider response.

#### 2.5 Limit Error Screen (LIMIT_ERROR)

- **Local Control**: When transaction amount exceeds min/max limits, LIMIT_ERROR screen is shown locally.
- **Immediate Validation**: Validation occurs before sending amount to server, providing instant feedback.
- **User Recovery**: User can reset and return to amount selection after viewing limit error.

#### 2.6 Receipt Question Screen (RECEIPT_QUESTION)

- **Local Control**: Receipt question screen display is controlled by local `requireCardReceipt` configuration.
- **Conditional Display**: If `requireCardReceipt = false`, the screen is skipped and receipt is automatically declined.
- **Configuration-Based**: Behavior is determined by device configuration received from backend.

#### 2.7 Mock Payment Card Screen (MOCK_PAYMENT_CARD)

- **Local Control**: When using mock payment provider, a special card presentation screen is shown locally.
- **Provider-Specific**: Only appears when `paymentProvider = "mock"`.
- **Visual Feedback**: Displays amount and payment instructions specific to mock payment flow.

---

### 3. Local Transaction Limit Checking

**Client-Side Limit Validation**

- **Local Validation**: Transaction amounts are validated against min/max limits locally before sending to server.
- **Device Configuration**: Uses `minTransactionLimit` and `maxTransactionLimit` from local device configuration.
- **Immediate Feedback**: If amount is outside limits, LIMIT_ERROR screen is shown immediately without server communication.
- **Currency-Aware**: Error messages display currency symbol from device configuration.
- **Special Cases**: Mock payment provider includes special limit checking (e.g., amount 101 triggers daily limit exceeded).

**Validation Flow:**
1. User selects or enters amount
2. App checks amount against local min/max limits
3. If valid, amount is sent to server
4. If invalid, LIMIT_ERROR screen is shown locally

---

### 4. DEVICE_INFO for IP Address and Serial Number

**Device Information Reporting**

- **Automatic Response**: When server sends `INFO_SCREEN` message with `requestType: "DEVICE_INFO"`, the app automatically responds with device information.
- **IP Address Reporting**: App sends device IP address via `DEVICE_INFO` message with `screen: "DEVICE_IP"`.
- **Serial Number Reporting**: App sends device serial number via `DEVICE_INFO` message with `screen: "DEVICE_SERIAL"`.
- **Transaction Correlation**: Both responses use the same transaction ID from the request for proper correlation.
- **Settings Display**: IP address and serial number are also displayed in Settings screen for local viewing.
- **Real-Time Retrieval**: Device information is retrieved in real-time when requested, ensuring accuracy.

**Message Format:**
- IP Address: `{"messageType": "DEVICE_INFO", "screen": "DEVICE_IP", "data": {"deviceIpAddress": "192.168.1.100"}}`
- Serial Number: `{"messageType": "DEVICE_INFO", "screen": "DEVICE_SERIAL", "data": {"deviceSerialNumber": "ABC123"}}`

---

### 5. Mock Payment Support

**Simulated Payment Processing for Testing**

- **MockPaymentManager**: New payment manager implementation for simulated payment responses.
- **Provider Selection**: Activated when `paymentProvider = "mock"` in device configuration.
- **Full Payment Flow**: Supports complete payment flow including:
  - Card check (CardCheckEmv simulation)
  - Sale transaction
  - Transaction cancellation
  - Sale reversal (refund)
- **Realistic Simulation**: Mock responses match the structure of real Integra SDK responses for compatibility.
- **Special Behaviors**:
  - Amount 101.00 triggers daily limit exceeded error
  - All other amounts return successful payment
  - Simulated network delays (1-4 seconds) for realistic testing
- **Development Benefits**: Enables testing without requiring actual payment terminal hardware.

**Mock Payment Features:**
- Generates mock card tokens and sequence numbers
- Simulates approval/rejection responses
- Provides realistic timing delays
- Supports all payment operations (check, sale, cancel, reversal)

---

### 6. Local Device Configuration Management

**Comprehensive Device Settings Storage**

The app now stores and manages device configuration locally, received from backend via `DEVICE_INFO` message. All configuration values are persisted in local database.

#### 6.1 Transaction Limits

- **Min Transaction Limit**: Minimum allowed transaction amount (stored as `minTransactionLimit`)
- **Max Transaction Limit**: Maximum allowed transaction amount (stored as `maxTransactionLimit`)
- **Local Storage**: Limits are stored in device configuration database
- **Default Values**: Defaults to £10 min and £300 max if not configured
- **Usage**: Used for local validation before sending amounts to server

#### 6.2 Transaction Fees

- **Fee Type**: Supports two fee calculation methods:
  - **"FIXED"**: Fixed amount added to transaction (e.g., £0.50)
  - **"PERCENTAGE"**: Percentage of transaction amount (e.g., 2.5%)
- **Fee Value**: Fee amount or percentage value (stored as `transactionFeeValue`)
- **Automatic Calculation**: Final amount including fee is calculated automatically before payment processing
- **Display**: Original amount is shown to user, but final amount (with fee) is used for payment

#### 6.3 Currency Configuration

- **Currency Code**: Three-letter currency code (e.g., "GBP", "USD", "EUR")
- **Currency Symbol**: Automatically converted to symbol for display (£, $, €)
- **Local Storage**: Currency is stored in device configuration
- **Default**: Defaults to "GBP" if not configured

#### 6.4 YASPA (Payment Method Selection) Control

- **YASPA Enabled**: When `yaspaEnabled = true`, user sees payment method selection screen
- **YASPA Disabled**: When `yaspaEnabled = false`, app shows timeout screen and proceeds directly to payment
- **Flow Control**: Determines whether payment method selection is shown or skipped
- **Local Behavior**: App controls screen flow based on this configuration

#### 6.5 Payment Provider

- **Provider Selection**: Determines which payment manager to use ("integra" or "mock")
- **Runtime Configuration**: Can be changed remotely via backend
- **Local Storage**: Stored in device configuration database
- **Default**: Defaults to "integra" if not configured

#### 6.6 Card Receipt Requirement

- **Require Card Receipt**: When `requireCardReceipt = true`, receipt question screen is shown
- **Skip Receipt**: When `requireCardReceipt = false`, receipt question is skipped and automatically declined
- **Local Control**: App controls receipt screen display based on this configuration
- **Default**: Defaults to `true` for backward compatibility

#### 6.7 Configuration Persistence

- **Database Storage**: All configuration is stored in Room database (`DeviceInfo` entity)
- **Automatic Updates**: Configuration is updated when `DEVICE_INFO` message is received from server
- **Settings Display**: All configuration values are visible in Settings screen
- **Fallback Values**: App uses sensible defaults if configuration is missing

---

## Technical Details

### Configuration Storage

- **Database**: Room database with `DeviceInfo` entity
- **Table**: Single-row table (id = 1) for device configuration
- **Update Strategy**: REPLACE strategy for insert/update operations
- **Fields**: currency, minTransactionLimit, maxTransactionLimit, transactionFeeType, transactionFeeValue, yaspaEnabled, paymentProvider, requireCardReceipt

### Payment Provider Architecture

- **PlanetPaymentManager**: Handles real payment terminal operations via Planet Integra Client SDK
- **MockPaymentManager**: Simulates payment operations for testing
- **Provider Selection**: Determined at runtime based on `paymentProvider` configuration
- **Unified Interface**: Both managers implement same interface for seamless switching

### Local Screen Control

- **State Management**: Screen states managed locally in `PaymentViewModel`
- **State Flow**: Uses Kotlin StateFlow for reactive screen updates
- **Backend Override**: Server can still send screen change messages to override local behavior
- **Priority**: Local screens take precedence unless server explicitly sends screen change

### Device Information Retrieval

- **IP Address**: Retrieved via `NetworkInterface` API
- **Serial Number**: Retrieved via `Build.getSerial()` or `Build.SERIAL` (depending on Android version)
- **Error Handling**: Returns "unknown" if information cannot be retrieved
- **Thread Safety**: All operations are thread-safe and use coroutines

---

## Known Behaviors

### Local Screen Control

When the app is handling payment locally (YASPA disabled or local payment methods), some server screen change messages may be ignored. This is expected behavior to maintain local control of the payment flow.

### Mock Payment Provider

When using mock payment provider, all payment operations are simulated. No actual payment terminal hardware is required, making it ideal for testing and development.

### Device Configuration

If device configuration is not received from server, the app uses default values. Configuration can be sent at any time via `DEVICE_INFO` message and will be applied immediately.

### Transaction Fee Calculation

Transaction fees are calculated automatically and added to the original amount. The user sees the original amount, but the final amount (including fee) is used for payment processing.

---

## Bug Fixes & Improvements

- Improved local screen control reduces server dependencies
- Enhanced payment provider flexibility with runtime configuration
- Better error handling for device information retrieval
- Optimized transaction limit validation with immediate feedback
- Improved configuration management with persistent storage
- Enhanced Settings screen with comprehensive device information display

---

## Upgrade Instructions

1. Install the new APK version (2.0, Build 2)
2. On first launch, device configuration will be received from server via `DEVICE_INFO` message
3. Existing server connections will automatically receive new configuration
4. Settings screen now displays all device configuration values
5. Mock payment provider can be enabled via backend configuration for testing

---

## Support

For technical support or questions regarding this release, please contact your system administrator or support team.

---

## Changelog Summary

- Added payment provider configuration from backend (integra/mock)
- Implemented local control for KEYPAD, TIMEOUT, PROCESSING, SUCCESS, FAILED, LIMIT_ERROR, RECEIPT_QUESTION, and MOCK_PAYMENT_CARD screens
- Added local transaction limit checking before server communication
- Implemented DEVICE_INFO message support for IP address and serial number reporting
- Added MockPaymentManager for simulated payment processing
- Implemented comprehensive local device configuration management (limits, fees, currency, YASPA, payment provider, receipt requirement)
- Enhanced Settings screen with full device configuration display
- Improved payment flow with reduced server dependencies
- Better error handling and user feedback

---

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
