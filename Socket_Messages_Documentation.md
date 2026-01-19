# Payment System Socket Communication Protocol 

## Overview

This document outlines the WebSocket communication protocol between the Android client application and the backend server for the payment system. It specifies message formats, expected responses, and the flow of communication during different stages of the payment process.

**Important Architecture Note:** The Android client now handles many screens and flows locally without requiring server messages. For DEBIT_CARD and PAY_BY_BANK payments, the client:
- Shows KEYPAD, PAYMENT_METHOD, TIMEOUT, PROCESSING, SUCCESS, and FAILED screens locally
- Performs payment processing locally via Planet SDK
- Only communicates with server for daily limit validation and post-payment flow control

See the "Client vs Server Controlled Screens" section below for details on which screens require server messages vs which are handled entirely by the client.

## Connection Details

- Connection Type: WebSocket (RFC 6455)
- Client Implementation: OkHttp WebSocket Client (version 4.11.0)
- Message Format: JSON
- Transport Security: Secure WebSocket (WSS) recommended for production


## Connection Establishment

1. The Android client connects to the backend server using WebSocket
2. Upon successful connection, the server should immediately send the initial screen state (typically the amount selection screen)
3. The client maintains a single persistent connection for the duration of the payment process

## Message Format

All messages follow this standard JSON structure:

```json
{
    "messageType": "[SCREEN_CHANGE | USER_ACTION | ERROR | STATUS_UPDATE | DEVICE_INFO | RESTART_APP | CARD_CHECK_RESULT | PAYMENT_RESULT | LIMIT_CHECK_RESULT | REFUND_REQUEST | REVERSAL_REQUEST | REVERSAL_RESULT]",
    "screen": "[SCREEN_IDENTIFIER]",
    "data": {
        // Optional data specific to the screen or action
        // See MessageData fields section for all available fields
    },
    "transactionId": "unique-transaction-identifier",
    "timestamp": 1619456789000
}
```

### MessageData Fields

The `data` object can contain the following fields (all optional, depending on message type):

**Amount Selection:**
- `amounts`: List<Int> - Available preset amounts
- `currency`: String - Currency symbol (e.g., "£", "$", "€")
- `showOtherOption`: Boolean - Whether to show "Other" option
- `selectedAmount`: Int - Selected amount (final amount including fee, rounded)
- `selectionMethod`: String - "PRESET_BUTTON", "CUSTOM", "OTHER", "KEYPAD", "YES", "NO"

**Payment Method:**
- `methods`: List<String> - Available payment methods (e.g., ["DEBIT_CARD", "PAY_BY_BANK"])
- `allowCancel`: Boolean - Whether cancel button is shown

**Error Information:**
- `errorCode`: String - Error code (e.g., "CARD_DECLINED", "TIMEOUT", "PRINTER_ERROR")
- `errorMessage`: String - Human-readable error message

**Limit Information:**
- `limit`: Int - Maximum limit amount
- `remaining`: Int - Remaining limit amount

**Payment Processing:**
- `cardToken`: String - Card token from CardCheckEmv for daily limit validation
- `paymentDetails`: Map<String, String> - Raw payment details from Planet SDK
- `paymentUrl`: String - URL for QR code payment

**Device Information:**
- `deviceIpAddress`: String - Device's local IPv4 address
- `deviceSerialNumber`: String - Device's serial number
- `requestType`: String - Request type (e.g., "DEVICE_INFO")

**Device Configuration (from server):**
- `minTransactionLimit`: Double - Minimum transaction amount
- `maxTransactionLimit`: Double - Maximum transaction amount
- `transactionFeeType`: String - "FIXED" or "PERCENTAGE"
- `transactionFeeValue`: Double - Fee value (fixed amount or percentage)
- `yaspaEnabled`: Boolean - Payment flow control flag
- `paymentProvider`: String - Payment provider to use: "integra" (real Planet/Integra terminal) or "mock" (simulated payments)
- `requireCardReceipt`: Boolean - If `true`, shows receipt question screen after successful payment. If `false`, automatically responds NO without showing the screen. Defaults to `true`.

---

# Message Type Values 

- **SCREEN_CHANGE**: Server instructs client to display a specific screen
- **USER_ACTION**: Client notifies server of user interaction
- **ERROR**: Server notifies client of an error condition
- **STATUS_UPDATE**: Server sends non-screen-changing status information
- **DEVICE_INFO**: Client sends device information (IP address, serial number) OR Server sends device configuration (transaction limits, fees, etc.)
- **RESTART_APP**: Server instructs client to restart the application
- **CARD_CHECK_RESULT**: Client sends card check result (token) to server for daily limit validation
- **PAYMENT_RESULT**: Client sends payment result from local Planet SDK to server
- **LIMIT_CHECK_RESULT**: Server responds to card check with limit validation result (APPROVED/REJECTED)
- **REFUND_REQUEST** / **REVERSAL_REQUEST**: Server requests client to refund/reverse a previously successful sale transaction
- **REVERSAL_RESULT**: Client sends refund/reversal result back to server


## Screen Identifier Values

- **AMOUNT_SELECT**: Select payment amount
- **KEYPAD**: Custom amount entry via keypad
- **PAYMENT_METHOD**: Select payment method
- **PROCESSING**: Processing payment
- **SUCCESS**: Transaction successful
- **FAILED**: Transaction failed
- **LIMIT_ERROR**: Amount limit exceeded
- **PRINT_TICKET**: Printing ticket
- **COLLECT_TICKET**: Collect printed ticket
- **THANK_YOU**: Final thank you screen
- **DEVICE_ERROR**: Terminal error condition
- **DEVICE_IP**: Device IP address information
- **DEVICE_SERIAL**: Device serial number information
- **INFO_SCREEN**: Server requests device information from client
- **TIMEOUT**: Timeout screen (shown before direct payment when yaspaEnabled=false)
- **RECEIPT_QUESTION**: Ask user if they want a receipt
- **REFUND_PROCESSING**: Processing refund after printer error
- **QR_CODE**: Display QR code for payment
- **PRINTER_ERROR**: Printer error condition
- **RESET**: Client requests to reset/return to amount selection
- **CANCEL**: Client cancels current transaction
- **RECEIPT_RESPONSE**: Client responds to receipt question (YES/NO)
- **MOCK_PAYMENT_CARD**: Mock payment card screen (shown for mock payment provider only)


## Client vs Server Controlled Screens

**Important:** The Android client now handles many screens locally without requiring server messages. This section clarifies which screens are client-controlled vs server-controlled.

### Client-Controlled Screens (No Server Dependency)

These screens are shown automatically by the client without requiring server messages:

1. **KEYPAD** - Shown locally when user selects "Other" option (amount = -1)
   - Uses device configuration for limits (min/max amounts)
   - Server can still send KEYPAD screen, but it's not required

2. **LIMIT_ERROR** - Shown locally when amount validation fails
   - Client validates against min/max limits before sending to server
   - No server message needed for limit errors

3. **PAYMENT_METHOD** - Shown locally after amount selection (when yaspaEnabled=true)
   - Client determines available methods and shows selection screen
   - Server can still send PAYMENT_METHOD screen, but client will show it locally if not received

4. **TIMEOUT** - Shown locally when yaspaEnabled=false
   - Client shows timeout screen, then proceeds directly to payment
   - No server message needed

5. **PROCESSING** - Shown automatically when payment processing starts
   - Client shows this screen when CardCheckEmv or Sale transaction begins
   - No server message needed

6. **SUCCESS** - Shown locally immediately after successful payment
   - Client shows success screen right after Planet SDK returns success
   - Server can send additional screens after (receipt question, ticket printing, etc.)

7. **FAILED** - Shown locally immediately after failed payment
   - Client shows failed screen right after Planet SDK returns failure
   - After 4 seconds, client automatically requests initial screen from server
   - Server doesn't need to send FAILED screen

8. **REFUND_PROCESSING** - Shown automatically when REVERSAL_REQUEST is received
   - Client shows refund processing screen when server sends refund/reversal request
   - Client handles complete refund flow independently (processing → success/failure → requestInitialScreen)
   - Server does NOT need to send REFUND_PROCESSING screen

### Server-Controlled Screens

These screens require server messages to be displayed:

1. **AMOUNT_SELECT** - Initial amount selection (server sends on connection)
2. **RECEIPT_QUESTION** - Ask user if they want a receipt
3. **PRINT_TICKET** - Printing ticket screen
4. **COLLECT_TICKET** - Collect printed ticket screen (only if printing succeeds)
5. **THANK_YOU** - Final thank you screen (only if no refund occurs)
6. **QR_CODE** - QR code payment screen
7. **DEVICE_ERROR** - Terminal error (server can send)
8. **PRINTER_ERROR** - Printer error (server can send)

### Hybrid Flow

Some screens can be shown by either client or server:
- **PAYMENT_METHOD**: Client shows locally, but server can override with SCREEN_CHANGE
- **KEYPAD**: Client shows locally, but server can send SCREEN_CHANGE to show it

---

## Transaction Flow and Messages

## 1. Initial Connection

Upon successful connection, server sends:

```json
{
    "messageType": "SCREEN_CHANGE",
    "screen": "AMOUNT_SELECT",
    "data": {
        "amounts": [20, 40, 60, 80, 100],
        "currency": "£",
        "showOtherOption": true
    },
    "transactionId": "initial",
    "timestamp": 1619456789000
}
```


## 2. Amount Selection

**Important:** The client performs local validation and fee calculation before sending amount to server.

When user selects a predefined amount:
1. Client validates amount against min/max limits (from device configuration)
2. Client calculates final amount including transaction fee
3. Client sends final amount (rounded to nearest integer) to server:

```json
{
    "messageType": "USER_ACTION",
    "screen": "AMOUNT_SELECT",
    "data": {
        "selectedAmount": 61,
        "selectionMethod": "PRESET_BUTTON"
    },
    "transactionId": "T123456",
    "timestamp": 1619456790000
}
```

**Note:** `selectedAmount` contains the final amount including transaction fee. If original amount was 60 and fee is 0.50, the value sent is 61 (rounded).

**Client Behavior:** If user selects "Other" option (amount = -1), client shows KEYPAD screen **locally without any server message**. The client uses device configuration for min/max limits.

**Optional:** Server can also send keypad screen, but it's not required:

```json
{
    "messageType": "SCREEN_CHANGE",
    "screen": "KEYPAD",
    "data": {
        "currency": "£",
        "minAmount": 10,
        "maxAmount": 300
    },
    "transactionId": "T123456",
    "timestamp": 1619456791000
}
```

**Note:** 
- Client uses limits from device configuration if available
- If server sends KEYPAD screen, client uses server-provided limits as fallback
- Client shows KEYPAD locally when "Other" is selected, regardless of server messages


# 3. Custom Amount Entry 

When user enters a custom amount via keypad:
1. Client validates amount against min/max limits locally
2. If validation fails, client shows LIMIT_ERROR screen locally (no server message)
3. If validation passes, client calculates final amount including transaction fee
4. Client sends final amount (rounded) to server:

```json
{
    "messageType": "USER_ACTION",
    "screen": "AMOUNT_SELECT",
    "data": {
        "selectedAmount": 76,
        "selectionMethod": "CUSTOM"
    },
    "transactionId": "T123456",
    "timestamp": 1619456792000
}
```

**Note:** `selectionMethod` is "CUSTOM" for keypad entries, "PRESET_BUTTON" for predefined amounts.


# 4. Payment Method Selection 

**Note:** Payment method selection behavior depends on `yaspaEnabled` configuration:
- If `yaspaEnabled = false`: Client shows TIMEOUT screen locally, then proceeds directly to payment (no server message needed)
- If `yaspaEnabled = true`: Server can send payment method selection screen, or client shows it locally

Server can respond with payment method selection screen (if yaspaEnabled=true):

```json
{
    "messageType": "SCREEN_CHANGE",
    "screen": "PAYMENT_METHOD",
    "data": {
        "methods": ["DEBIT_CARD", "PAY_BY_BANK"],
        "allowCancel": true,
        "currency": "£"
    },
    "transactionId": "T123456",
    "timestamp": 1619456793000
}
```

When user selects payment method, client sends:

```json
{
    "messageType": "USER_ACTION",
    "screen": "PAYMENT_METHOD",
    "data": {
        "selectedMethod": "DEBIT_CARD"
    },
    "transactionId": "T123456",
    "timestamp": 1619456794000
}
```

**Important:** For DEBIT_CARD and PAY_BY_BANK methods, the client performs payment locally via Planet SDK. The flow is:
1. Client performs CardCheckEmv to get card token
2. Client sends card token to server for daily limit check
3. Server responds with LIMIT_CHECK_RESULT
4. If approved, client performs Sale transaction locally
5. Client sends PAYMENT_RESULT to server


## 5. Processing Payment

**Important:** For DEBIT_CARD and PAY_BY_BANK, payment processing happens **entirely locally on the client**. The client shows the PROCESSING screen automatically - **no server message is needed or expected**.

**Client Flow:**
1. Client shows PROCESSING screen automatically when payment starts
2. Client performs CardCheckEmv locally (via Planet SDK)
3. Client sends card token to server for limit validation
4. Client waits for server's LIMIT_CHECK_RESULT
5. If approved, client performs Sale transaction locally
6. Client shows SUCCESS or FAILED screen locally based on result
7. Client sends PAYMENT_RESULT to server (informational only)

**Server Role:**
- Server receives CARD_CHECK_RESULT and responds with LIMIT_CHECK_RESULT
- Server receives PAYMENT_RESULT (informational - client already showed result screen)
- Server controls post-payment flow (receipt question, ticket printing, thank you)

**Note:** Server can send PROCESSING screen for non-local payment methods (e.g., QR_CODE), but for DEBIT_CARD/PAY_BY_BANK, the client handles it locally:

```json
{
    "messageType": "SCREEN_CHANGE",
    "screen": "PROCESSING",
    "data": {},
    "transactionId": "T123456",
    "timestamp": 1619456795000
}
```

### 5.1 Card Check and Limit Validation Flow

When user selects DEBIT_CARD or PAY_BY_BANK:

1. **Client performs CardCheckEmv locally** (via Planet SDK)
2. **Client sends card check result to server:**

```json
{
    "messageType": "CARD_CHECK_RESULT",
    "screen": "PROCESSING",
    "data": {
        "cardToken": "abc123xyz789",
        "selectedAmount": 60
    },
    "transactionId": "T123456",
    "timestamp": 1619456795000
}
```

3. **Server responds with limit check result:**

```json
{
    "messageType": "LIMIT_CHECK_RESULT",
    "screen": "APPROVED",
    "data": {},
    "transactionId": "T123456",
    "timestamp": 1619456796000
}
```

Or if limit exceeded:

```json
{
    "messageType": "LIMIT_CHECK_RESULT",
    "screen": "LIMIT_ERROR",
    "data": {
        "errorMessage": "Daily spending limit exceeded"
    },
    "transactionId": "T123456",
    "timestamp": 1619456796000
}
```

4. **If approved, client performs Sale transaction locally** (via Planet SDK)
5. **Client sends payment result to server:**

```json
{
    "messageType": "PAYMENT_RESULT",
    "screen": "SUCCESS",
    "data": {
        "errorCode": null,
        "errorMessage": null,
        "paymentDetails": {
            "Result": "A",
            "BankResultCode": "00",
            "Message": "APPROVED",
            "RequesterTransRefNum": "T123456"
        }
    },
    "transactionId": "T123456",
    "timestamp": 1619456797000
}
```

Or if payment failed:

```json
{
    "messageType": "PAYMENT_RESULT",
    "screen": "FAILED",
    "data": {
        "errorCode": "CARD_DECLINED",
        "errorMessage": "Card declined by issuer",
        "paymentDetails": {
            "Result": "D",
            "BankResultCode": "05",
            "Message": "DECLINED"
        }
    },
    "transactionId": "T123456",
    "timestamp": 1619456797000
}
```


# 6. Transaction Result 

For successful transactions, server sends:

```json
{
    "messageType": "SCREEN_CHANGE",
    "screen": "SUCCESS",
    "data": {
        "showReceipt": true
    },
    "transactionId": "T123456",
    "timestamp": 1619456796000
}
```

For failed transactions, server sends:

```json
{
    "messageType": "SCREEN_CHANGE",
    "screen": "FAILED",
    "data": {
        "errorMessage": "Card declined by issuer",
        "errorCode": "CARD_DECLINED"
    },
    "transactionId": "T123456",
    "timestamp": 1619456796000
}
```


## 7. Limit Error

**Important:** The client performs local limit validation before sending amount to server. If validation fails, client shows LIMIT_ERROR screen locally - **no server message is needed**.

**Client Behavior:**
- Client validates amount against min/max limits from device configuration
- If amount < minTransactionLimit or amount > maxTransactionLimit, client shows LIMIT_ERROR locally
- After 5 seconds, client automatically returns to amount selection
- Server does NOT need to send LIMIT_ERROR for amount validation

**Server Role:**
- Server can send LIMIT_ERROR for daily spending limit validation (after receiving card token)
- Server sends LIMIT_CHECK_RESULT with screen="LIMIT_ERROR" when daily limit exceeded

**Server Message (for daily spending limits, not amount validation):**

If daily spending limit exceeds, server sends:

```json
{
    "messageType": "LIMIT_CHECK_RESULT",
    "screen": "LIMIT_ERROR",
    "data": {
        "errorMessage": "Daily spending limit exceeded"
    },
    "transactionId": "T123456",
    "timestamp": 1619456796000
}
```

**Note:** This is different from amount validation - this is for daily spending limits checked after card token is received.


# 8. Ticket Printing 

After user responds to receipt question, server sends:

```json
{
    "messageType": "SCREEN_CHANGE",
    "screen": "PRINT_TICKET",
    "data": {},
    "transactionId": "T123456",
    "timestamp": 1619456797000
}
```

**Important:** After PRINT_TICKET is sent, the flow depends on whether printing succeeds or fails:

- **If printing succeeds:** Server sends COLLECT_TICKET → THANK_YOU → AMOUNT_SELECT
- **If printing fails AND auto-refund is enabled:** Server sends REVERSAL_REQUEST (skip COLLECT_TICKET and THANK_YOU)
- **If printing fails AND auto-refund is disabled:** Server sends COLLECT_TICKET → THANK_YOU → AMOUNT_SELECT (normal flow continues)

**Refund Flow (when printing fails):**
- When auto-refund is enabled and printing fails, server sends REVERSAL_REQUEST instead of COLLECT_TICKET
- Android app handles all refund screens independently (REFUND_PROCESSING → TransactionSuccess/TransactionFailed → requestInitialScreen)
- Server does NOT send THANK_YOU when refund is triggered
- See section 14 for details on refund/reversal flow


## 9. Collect Ticket

After printing completes successfully, server sends:

```json
{
    "messageType": "SCREEN_CHANGE",
    "screen": "COLLECT_TICKET",
    "data": {},
    "transactionId": "T123456",
    "timestamp": 1619456798000
}
```

**Note:** This screen is only sent if printing succeeds. If printing fails and auto-refund is enabled, REVERSAL_REQUEST is sent instead (see section 14).


## 10. Receipt Question

After successful payment, server asks if user wants a receipt:

```json
{
    "messageType": "SCREEN_CHANGE",
    "screen": "RECEIPT_QUESTION",
    "data": {},
    "transactionId": "T123456",
    "timestamp": 1619456798000
}
```

**Important:** The client checks the `requireCardReceipt` configuration setting when receiving RECEIPT_QUESTION:
- If `requireCardReceipt = true` (default): Client shows the receipt question screen and waits for user response
- If `requireCardReceipt = false`: Client automatically responds NO without showing the screen

Client responds (when `requireCardReceipt = true`):

```json
{
    "messageType": "USER_ACTION",
    "screen": "RECEIPT_RESPONSE",
    "data": {
        "selectionMethod": "YES"
    },
    "transactionId": "T123456",
    "timestamp": 1619456798500
}
```

Or:

```json
{
    "messageType": "USER_ACTION",
    "screen": "RECEIPT_RESPONSE",
    "data": {
        "selectionMethod": "NO"
    },
    "transactionId": "T123456",
    "timestamp": 1619456798500
}
```

**Note:** When `requireCardReceipt = false`, the client automatically sends a RECEIPT_RESPONSE with `selectionMethod: "NO"` without displaying the screen to the user.

**Post-Receipt Flow:**
After RECEIPT_RESPONSE is received, server sends PRINT_TICKET. The subsequent flow depends on printing success:
- **If printing succeeds:** PRINT_TICKET → COLLECT_TICKET → THANK_YOU → AMOUNT_SELECT
- **If printing fails AND auto-refund enabled:** PRINT_TICKET → REVERSAL_REQUEST → (Android handles refund screens) → AMOUNT_SELECT
- **If printing fails AND auto-refund disabled:** PRINT_TICKET → COLLECT_TICKET → THANK_YOU → AMOUNT_SELECT (normal flow continues)

## 10.1 Refund Processing

**Important:** Refund processing is now handled entirely by the Android app when printing fails. The server does NOT send REFUND_PROCESSING screen.

**Flow when printing fails:**
1. Server sends PRINT_TICKET screen
2. If printing fails AND auto-refund is enabled, server sends REVERSAL_REQUEST (see section 14)
3. Android app automatically shows REFUND_PROCESSING screen locally
4. Android app processes reversal via Planet SDK
5. Android app shows TransactionSuccess or TransactionFailed screen
6. Android app calls requestInitialScreen() to return to amount selection

**Note:** The REFUND_PROCESSING screen is client-controlled and shown automatically when REVERSAL_REQUEST is received. Server does not need to send this screen.

## 10.2 QR Code Payment

For QR code payment methods, server sends:

```json
{
    "messageType": "SCREEN_CHANGE",
    "screen": "QR_CODE",
    "data": {
        "paymentUrl": "https://payment.example.com/qr/abc123"
    },
    "transactionId": "T123456",
    "timestamp": 1619456799000
}
```

## 10.3 Timeout Screen

Server can send timeout screen (also shown locally when yaspaEnabled=false):

```json
{
    "messageType": "SCREEN_CHANGE",
    "screen": "TIMEOUT",
    "data": {},
    "transactionId": "T123456",
    "timestamp": 1619456799000
}
```

## 10.4 Printer Error

If printer has an error, server sends:

```json
{
    "messageType": "SCREEN_CHANGE",
    "screen": "PRINTER_ERROR",
    "data": {
        "errorMessage": "Printer out of paper"
    },
    "transactionId": "T123456",
    "timestamp": 1619456800000
}
```

## 11. Thank You Screen

Final screen in the flow (only sent if no refund occurs), server sends:

```json
{
    "messageType": "SCREEN_CHANGE",
    "screen": "THANK_YOU",
    "data": {},
    "transactionId": "T123456",
    "timestamp": 1619456799000
}
```

**Important:** 
- THANK_YOU is only sent if printing succeeds (normal flow: PRINT_TICKET → COLLECT_TICKET → THANK_YOU)
- If printing fails and auto-refund is enabled, server sends REVERSAL_REQUEST instead and does NOT send THANK_YOU
- Android app handles refund screens independently and calls requestInitialScreen() after refund completes


# 11. Device Error 

If terminal has an error, server sends:

```json
{
    "messageType": "SCREEN_CHANGE",
    "screen": "DEVICE_ERROR",
    "data": {
        "errorMessage": "Printer out of paper",
        "errorCode": "PRINTER_ERROR"
    },
    "transactionId": "T123456",
    "timestamp": 1619456800000
}
```


## 13. Device Information Messages

Device information can be sent from the client to the server either proactively or in response to a server request. These messages are used for device identification, network diagnostics, and inventory management.

Additionally, the server can send device configuration information to the client via DEVICE_INFO message type. This allows the server to configure transaction limits, fees, currency, and payment flow behavior.

### 13.0 Server Request for Device Information

The server can request device information by sending an INFO_SCREEN message:

```json
{
    "messageType": "SCREEN_CHANGE",
    "screen": "INFO_SCREEN",
    "data": {
        "requestType": "DEVICE_INFO"
    },
    "transactionId": "info-1765208631832",
    "timestamp": 1765208631832
}
```

**Message Details:**
- **messageType**: `"SCREEN_CHANGE"`
- **screen**: `"INFO_SCREEN"`
- **data.requestType**: `"DEVICE_INFO"` - indicates the server is requesting device information
- **transactionId**: A unique identifier for this information request (typically prefixed with "info-" to distinguish from payment transactions)
- **timestamp**: Current system timestamp

**Client Response:**
Upon receiving an INFO_SCREEN message with `requestType: "DEVICE_INFO"`, the client automatically responds by sending:
1. Device IP address message (see section 12.1)
2. Device serial number message (see section 12.2)

Both response messages use the same `transactionId` from the INFO_SCREEN request for correlation purposes.

**Important Notes:**
- INFO_SCREEN messages are **independent of payment transactions**
- The client does **not** change the current screen state when receiving INFO_SCREEN
- The client does **not** update the current payment transaction ID when receiving INFO_SCREEN
- The transactionId in INFO_SCREEN is used only for request/response correlation, not for payment processing
- The client can send device information at any time, not just in response to INFO_SCREEN requests

### 13.1 Device IP Address

Client sends device IP address:

```json
{
    "messageType": "DEVICE_INFO",
    "screen": "DEVICE_IP",
    "data": {
        "deviceIpAddress": "192.168.1.100"
    },
    "transactionId": "T123456",
    "timestamp": 1619456801000
}
```

**Message Details:**
- **messageType**: `"DEVICE_INFO"`
- **screen**: `"DEVICE_IP"`
- **data.deviceIpAddress**: The device's local IPv4 address (e.g., "192.168.1.100")
- **transactionId**: Current transaction ID or a new UUID if no active transaction
- **timestamp**: Current system timestamp

**Notes:**
- The IP address is the device's local network IP address (not the server IP)
- If the IP address cannot be determined, the value will be `"unknown"`
- The client automatically detects the first available non-loopback IPv4 address
- This message can be sent independently of any payment transaction

### 13.2 Device Serial Number

Client sends device serial number:

```json
{
    "messageType": "DEVICE_INFO",
    "screen": "DEVICE_SERIAL",
    "data": {
        "deviceSerialNumber": "ABC123456789"
    },
    "transactionId": "T123456",
    "timestamp": 1619456802000
}
```

**Message Details:**
- **messageType**: `"DEVICE_INFO"`
- **screen**: `"DEVICE_SERIAL"`
- **data.deviceSerialNumber**: The device's unique serial number
- **transactionId**: Current transaction ID or a new UUID if no active transaction
- **timestamp**: Current system timestamp

**Notes:**
- The serial number is retrieved using Android's `Build.getSerial()` (API 26+) or `Build.SERIAL` (older versions)
- Requires `READ_PHONE_STATE` permission on Android 10+ (API 29+)
- If the serial number cannot be determined, the value will be `"unknown"`
- This message can be sent independently of any payment transaction
- Useful for device identification and inventory management

**Implementation Notes:**
- Both device information messages are sent from the client to the server
- The server should acknowledge receipt but does not need to send a response
- These messages can be triggered programmatically, on-demand, or automatically in response to INFO_SCREEN requests
- When sent in response to INFO_SCREEN, the messages use the INFO_SCREEN's transactionId for correlation
- When sent independently, the messages use the current payment transaction ID (if available) or generate a new UUID
- The client implementation includes error handling and logging for both operations

### 13.3 Server Device Configuration

The server can send device configuration to the client using DEVICE_INFO message type. This configures transaction limits, fees, currency, and payment flow behavior:

```json
{
    "messageType": "DEVICE_INFO",
    "screen": "",
    "data": {
        "minTransactionLimit": 10.0,
        "maxTransactionLimit": 300.0,
        "currency": "GBP",
        "transactionFeeType": "FIXED",
        "transactionFeeValue": 0.50,
        "yaspaEnabled": true,
        "paymentProvider": "integra",
        "requireCardReceipt": true
    },
    "transactionId": "config-1765208631832",
    "timestamp": 1765208631832
}
```

**Message Details:**
- **messageType**: `"DEVICE_INFO"`
- **screen**: Empty string (not used for configuration)
- **data.minTransactionLimit**: Minimum transaction amount (Double)
- **data.maxTransactionLimit**: Maximum transaction amount (Double)
- **data.currency**: Currency code (e.g., "GBP", "USD", "EUR")
- **data.transactionFeeType**: `"FIXED"` or `"PERCENTAGE"`
- **data.transactionFeeValue**: Fee value (Double) - fixed amount or percentage
- **data.yaspaEnabled**: Boolean - if `false`, shows timeout screen then direct payment; if `true`, shows payment method selection
- **data.paymentProvider**: String - Payment provider to use: `"integra"` (real Planet/Integra terminal) or `"mock"` (simulated payments). Defaults to `"integra"` if not provided.
- **data.requireCardReceipt**: Boolean - If `true`, shows receipt question screen after successful payment. If `false`, automatically responds NO without showing the screen. Defaults to `true` if not provided.
- **transactionId**: Unique identifier for this configuration update
- **timestamp**: Unix timestamp in milliseconds

**Client Behavior:**
- Client saves this configuration to local database
- Configuration is used for:
  - Validating transaction amounts locally
  - Calculating transaction fees
  - Determining payment flow (yaspaEnabled flag)
  - Displaying currency symbol
  - Selecting payment provider (integra vs mock)
  - Controlling receipt question behavior (requireCardReceipt flag)
- Configuration persists across app restarts
- If configuration is missing, client uses default values (min: 10, max: 300, currency: GBP, yaspaEnabled: true, paymentProvider: "integra", requireCardReceipt: true)

**Payment Provider Behavior:**
- **`paymentProvider = "integra"`**: Client uses real Planet Integra Client SDK for payment processing. All payment operations (CardCheckEmv, Sale, Cancel) are performed on the actual payment terminal hardware.
- **`paymentProvider = "mock"`**: Client uses MockPaymentManager to simulate payment responses. This is useful for testing and development without requiring actual payment terminal hardware. Mock responses match the exact structure of Integra responses for compatibility.
  - Mock payments simulate successful transactions for all amounts except 101.00
  - Amount 101.00 triggers a daily limit exceeded error in mock mode
  - Mock responses include realistic delays and response structures matching the real Integra SDK
  - For mock payments, client shows a special MOCK_PAYMENT_CARD screen after the PROCESSING screen (displays "Insert, swipe or present card" with amount)
  - Mock payment flow: PROCESSING → MOCK_PAYMENT_CARD (3 seconds) → CardCheckEmv → Sale → SUCCESS/FAILED

**Transaction Fee Calculation:**
- If `transactionFeeType = "FIXED"`: Final amount = selected amount + transactionFeeValue
- If `transactionFeeType = "PERCENTAGE"`: Final amount = selected amount + (selected amount × transactionFeeValue / 100)
- The final amount (including fee) is sent to the server in USER_ACTION messages


## Transaction Timeout Handling

The client implements automatic timeout handling:
- If user is inactive on amount selection screen, timeout triggers screensaver
- Client sends CANCEL message with timeout flag
- Screensaver can be dismissed by user interaction
- After screensaver dismissal, client requests fresh amount selection screen

If a transaction times out, server can send:

```json
{
    "messageType": "ERROR",
    "screen": "CURRENT_SCREEN",
    "data": {
        "errorMessage": "Transaction timed out",
        "errorCode": "TIMEOUT"
    },
    "transactionId": "T123456",
    "timestamp": 1619456800000
}
```

## Cancel and Reset Messages

Client can cancel current transaction:

```json
{
    "messageType": "USER_ACTION",
    "screen": "CANCEL",
    "data": {
        "errorMessage": "Session timed out due to inactivity"
    },
    "transactionId": "T123456",
    "timestamp": 1619456800000
}
```

Client can request reset to amount selection:

```json
{
    "messageType": "USER_ACTION",
    "screen": "RESET",
    "data": null,
    "transactionId": "T123456",
    "timestamp": 1619456800000
}
```


## Connection Error Handling

- The client implements automatic reconnection with exponential backoff
- If connection is lost, client attempts to reestablish connection
- Server should maintain transaction state and allow resumption when possible


# Security Considerations 

1. **Authentication:**
   - Initial connection should include authentication if required
   - Consider using token-based authentication in WebSocket handshake

2. **Data Validation:**
   - Server must validate all client-sent data, particularly amounts and transaction IDs
   - Client should validate server responses before displaying screens

3. **Transport Security:**
   - Use WSS (WebSocket Secure) in production environments
   - TLS 1.2 or higher is recommended

4. **Device Information:**
   - Device IP addresses are local network addresses and should be handled securely
   - Serial numbers are device identifiers and should be protected according to privacy regulations
   - Consider encrypting device information in transit


## Implementation Notes for Backend Developer

## WebSocket Server Setup

The backend should implement a WebSocket server that:

1. Accepts connections from the Android client
2. Maintains session state for each connected client
3. Processes messages according to the protocol defined above
4. Sends appropriate responses based on the payment processing flow
5. Handles device information messages for device management

## Example Server Implementations

Recommended frameworks:

- Node.js: ws, socket.io
- Java: Spring WebSocket, Jetty
- Python: websockets, Django Channels
- .NET: SignalR


## Testing

A test client is available in the Android app repository that simulates the client-side WebSocket connections. Use this for development and testing.

# Logging Requirements 

The backend should log:

1. Connection events (connect, disconnect)
2. Message receipt (inbound from client)
3. Message transmission (outbound to client)
4. Errors and exceptions
5. Transaction lifecycle events
6. Device information messages (for device management and diagnostics)

## Client Technical Details

The Android client uses:

- OkHttp WebSocket implementation (version 4.11.0)
- JSON serialization with Moshi (version 1.14.0)
- Kotlin coroutines for asynchronous handling
- StateFlow for UI state management
- Animation transitions between screens
- PNG animations (pulsing) for various process stages (replaced GIFs for better memory management)
- Network interface enumeration for IP address detection
- Android Build API for serial number retrieval
- Planet Integra Client SDK for local payment processing (CardCheckEmv and Sale transactions) when paymentProvider="integra"
- MockPaymentManager for simulated payment processing when paymentProvider="mock"
- Room database for storing device configuration
- Local transaction limit validation and fee calculation

## Device Information Implementation

The client provides two methods for sending device information:

1. **`sendDeviceIpAddress(transactionId?: String)`**: Retrieves and sends the device's local IPv4 address
2. **`sendDeviceSerialNumber(transactionId?: String)`**: Retrieves and sends the device's serial number

Both methods:
- Include error handling and logging
- Return "unknown" if information cannot be retrieved
- Can be called independently of payment transactions or in response to INFO_SCREEN requests
- Accept an optional `transactionId` parameter for correlation (used when responding to INFO_SCREEN)
- If no transactionId is provided, use the current payment transaction ID or generate a new UUID

**Automatic Response to INFO_SCREEN:**
When the client receives an INFO_SCREEN message with `requestType: "DEVICE_INFO"`, it automatically calls both methods with the INFO_SCREEN's transactionId to ensure proper request/response correlation.

**Device Configuration Handling:**
When the client receives a DEVICE_INFO message with configuration data (minTransactionLimit, maxTransactionLimit, etc.), it:
- Validates all required fields are present
- Saves configuration to local Room database
- Uses configuration for local validation and fee calculation
- Applies yaspaEnabled flag to determine payment flow
- Uses paymentProvider to select between real Integra SDK or mock payment manager
- Uses requireCardReceipt flag to control receipt question screen behavior
- Payment provider and receipt settings are displayed in the Settings screen

**Permissions Required:**
- `READ_PHONE_STATE`: Required for serial number access on Android 10+ (API 29+)

## Payment Processing Implementation

The client performs local payment processing using either the Planet Integra Client SDK (real payments) or MockPaymentManager (simulated payments), depending on the `paymentProvider` configuration:

1. **CardCheckEmv**: Validates card and retrieves token for daily limit checking
   - Called before Sale transaction
   - Returns token that is sent to server for limit validation
   - Handles connection, request/response, and error states
   - **Real mode (integra)**: Uses Planet Integra Client SDK to communicate with actual payment terminal
   - **Mock mode**: Simulates card check with mock token generation

2. **Sale Transaction**: Performs actual payment transaction
   - Uses final amount including transaction fee
   - Sends payment result (success/failure) to server
   - Includes raw payment details from payment provider
   - **Real mode (integra)**: Uses Planet Integra Client SDK to process payment on actual terminal
   - **Mock mode**: Simulates payment processing
     - Amount 101.00 returns daily limit exceeded error
     - All other amounts return successful payment response
     - Response structure matches Integra SDK format for compatibility

**Payment Flow (DEBIT_CARD and PAY_BY_BANK):**
1. **Client-controlled:** User selects payment method (shown locally)
2. **Client-controlled:** Client shows PROCESSING screen automatically
3. **For mock payments only:** Client shows MOCK_PAYMENT_CARD screen (3 seconds) after PROCESSING
4. **Client-controlled:** Client performs CardCheckEmv locally (via Planet SDK or MockPaymentManager)
5. **Client → Server:** Client sends CARD_CHECK_RESULT with card token (skipped for mock payments)
6. **Server → Client:** Server responds with LIMIT_CHECK_RESULT (APPROVED/REJECTED) - skipped for mock payments
7. **Client-controlled:** If approved, client performs Sale transaction locally
8. **Client-controlled:** Client shows SUCCESS or FAILED screen immediately based on result
9. **Client → Server:** Client sends PAYMENT_RESULT (informational - client already showed result)
10. **Server-controlled:** Server sends post-payment screens (RECEIPT_QUESTION, PRINT_TICKET, THANK_YOU)

**Key Points:**
- Client handles all payment processing screens locally (PROCESSING, SUCCESS, FAILED)
- Server only needs to validate daily limits and control post-payment flow
- If payment fails, client automatically returns to amount selection after 4 seconds
- Server does NOT need to send SUCCESS or FAILED screens for local payments

**Transaction Fee:**
- Calculated locally based on device configuration
- Final amount (with fee) is sent to server
- Original amount is displayed to user, final amount is charged

## 14. Refund/Reversal Request

The server can request the client to refund/reverse a previously successful sale transaction. This is primarily used when:
- **Ticket printing fails** after a successful payment (auto-refund scenario)
- A sale transaction succeeded on the terminal but the server couldn't process it
- A refund needs to be issued for a completed transaction
- A transaction needs to be reversed due to errors or customer requests

### 14.1 Server Request for Refund/Reversal

The server sends a REFUND_REQUEST or REVERSAL_REQUEST message:

```json
{
    "messageType": "REVERSAL_REQUEST",
    "screen": "",
    "data": {
        "originalTransactionId": "T123456",
        "originalRequesterTransRefNum": "T123456",
        "reversalAmount": 61
    },
    "transactionId": "REVERSAL_T123456",
    "timestamp": 1619456800000
}
```

**Message Details:**
- **messageType**: `"REFUND_REQUEST"` or `"REVERSAL_REQUEST"` - both are handled the same way
- **screen**: Empty string (not used for refund requests)
- **data.originalTransactionId**: Optional - transaction ID of the original sale (if not provided, uses last successful sale)
- **data.originalRequesterTransRefNum**: Optional - RequesterTransRefNum from original sale (if not provided, uses last successful sale)
- **data.reversalAmount**: Optional - amount to reverse in cents/pence (if not provided, uses last successful sale amount)
- **transactionId**: Unique identifier for this reversal request (typically prefixed with "REVERSAL_")
- **timestamp**: Unix timestamp in milliseconds

**When Refund is Triggered:**
- **Auto-refund scenario:** After PRINT_TICKET is sent, if printing fails AND auto-refund is enabled, server automatically sends REVERSAL_REQUEST
- **Manual refund:** Server can send REVERSAL_REQUEST at any time (e.g., via UI button in mock server)
- **Flow:** PRINT_TICKET → (printing fails) → REVERSAL_REQUEST (skip COLLECT_TICKET and THANK_YOU)

**Client Behavior:**
- Client checks if there's a last successful sale transaction stored
- If no successful sale is found, client sends REVERSAL_RESULT with error
- **Client automatically shows REFUND_PROCESSING screen** (no server message needed)
- Client performs SaleReversalRequest via Planet SDK (or mock reversal)
- Client sends REVERSAL_RESULT back to server
- **Client shows TransactionSuccess or TransactionFailed screen** (no server message needed)
- **Client automatically calls requestInitialScreen()** after showing result (3 seconds for success, 4 seconds for failure)
- If reversal succeeds, client clears the stored successful sale transaction

**Important Notes:**
- **Android app handles all refund screens independently** - server does NOT send REFUND_PROCESSING, SUCCESS, FAILED, or THANK_YOU screens during refund flow
- Server should NOT send COLLECT_TICKET or THANK_YOU when refund is triggered
- After refund completes, Android app automatically requests initial screen (AMOUNT_SELECT) from server

### 14.2 Client Response - Reversal Result

After processing the reversal, client sends:

```json
{
    "messageType": "REVERSAL_RESULT",
    "screen": "SUCCESS",
    "data": {
        "errorCode": null,
        "errorMessage": null,
        "paymentDetails": {
            "Result": "A",
            "BankResultCode": "00",
            "Message": "REVERSAL_APPROVED",
            "RequesterTransRefNum": "REVERSAL_T123456",
            "OriginalRequesterTransRefNum": "T123456"
        },
        "originalTransactionId": "T123456",
        "reversalAmount": 61
    },
    "transactionId": "REVERSAL_T123456",
    "timestamp": 1619456801000
}
```

Or if reversal failed:

```json
{
    "messageType": "REVERSAL_RESULT",
    "screen": "FAILED",
    "data": {
        "errorCode": "NO_SALE_FOUND",
        "errorMessage": "No successful sale transaction found to reverse",
        "paymentDetails": {}
    },
    "transactionId": "REVERSAL_T123456",
    "timestamp": 1619456801000
}
```

**Message Details:**
- **messageType**: `"REVERSAL_RESULT"`
- **screen**: `"SUCCESS"` or `"FAILED"`
- **data.errorCode**: Error code if reversal failed (null if successful)
- **data.errorMessage**: Error message if reversal failed (null if successful)
- **data.paymentDetails**: Raw payment details from Planet SDK (same structure as PAYMENT_RESULT)
- **data.originalTransactionId**: Transaction ID of the original sale that was reversed
- **data.reversalAmount**: Amount that was reversed
- **transactionId**: Same transactionId from the reversal request
- **timestamp**: Unix timestamp in milliseconds

**Important Notes:**
- Client automatically tracks the last successful sale transaction
- If server doesn't provide originalTransactionId or reversalAmount, client uses the last successful sale
- Reversal uses SaleReversalRequest from Planet SDK (same-day reversal)
- After successful reversal, the stored successful sale is cleared
- If no successful sale exists, client responds with error immediately
- **All refund screens are client-controlled** - server only sends REVERSAL_REQUEST, Android app handles the rest
- **Server does NOT send THANK_YOU when refund is triggered** - Android app manages the complete refund flow

## 15. App Restart Message

The server can instruct the client to restart the application by sending a RESTART_APP message. This is useful for applying configuration changes, recovering from errors, or performing maintenance operations. When the app is closed, kiosk mode will automatically reopen it.

### 14.0 Server Request to Restart App

```json
{
    "messageType": "RESTART_APP",
    "screen": "",
    "data": null,
    "transactionId": "restart-1765208631832",
    "timestamp": 1765208631832
}
```

**Message Details:**
- **messageType**: `"RESTART_APP"` - indicates the server is requesting an app restart
- **screen**: Can be empty or any value (not used for restart)
- **data**: Can be null or empty (no additional data required)
- **transactionId**: Unique identifier for the restart request
- **timestamp**: Unix timestamp in milliseconds

**Client Behavior:**
- Upon receiving a RESTART_APP message, the client immediately closes the application using `finishAffinity()`
- The app does not send a response before closing
- If kiosk mode is enabled, the app will automatically reopen after being closed
- This allows the server to remotely restart the app for configuration updates or error recovery

**Use Cases:**
- Applying new server configuration changes
- Recovering from persistent connection issues
- Performing maintenance operations
- Clearing app state and restarting fresh

## Summary of Key Features

### New Message Types
- **CARD_CHECK_RESULT**: Client sends card token to server for daily limit validation
- **PAYMENT_RESULT**: Client sends local payment result to server
- **LIMIT_CHECK_RESULT**: Server responds to card check with approval/rejection
- **REFUND_REQUEST** / **REVERSAL_REQUEST**: Server requests client to refund/reverse a previously successful sale transaction
- **REVERSAL_RESULT**: Client sends refund/reversal result back to server

### New Screens
- **TIMEOUT**: Timeout screen (shown before direct payment when yaspaEnabled=false)
- **RECEIPT_QUESTION**: Ask user if they want a receipt (can be auto-skipped if requireCardReceipt=false)
- **REFUND_PROCESSING**: Processing refund after printer error
- **QR_CODE**: Display QR code for payment
- **PRINTER_ERROR**: Printer error condition
- **MOCK_PAYMENT_CARD**: Mock payment card screen (shown for mock payment provider only)

### Local Payment Processing
- Client performs CardCheckEmv and Sale transactions locally via Planet SDK
- Card token is sent to server for daily limit validation
- **Client shows PROCESSING, SUCCESS, and FAILED screens locally** - no server messages needed
- Payment result is sent to server after local processing completes (informational only)
- Server controls post-payment flow (receipt, ticket printing, thank you)

### Client-Controlled Screens
- **KEYPAD**: Shown locally when "Other" is selected
- **LIMIT_ERROR**: Shown locally for amount validation failures
- **PAYMENT_METHOD**: Shown locally based on yaspaEnabled flag
- **TIMEOUT**: Shown locally when yaspaEnabled=false
- **PROCESSING**: Shown automatically when payment starts
- **SUCCESS**: Shown locally immediately after successful payment
- **FAILED**: Shown locally immediately after failed payment, then auto-returns to amount selection

### Device Configuration
- Server can send device configuration via DEVICE_INFO message
- Configures transaction limits, fees, currency, payment flow behavior, payment provider, and receipt question behavior
- Client validates amounts and calculates fees locally
- Configuration persists in local database
- Payment provider can be set to "integra" (real terminal) or "mock" (simulated payments)
- Receipt question can be enabled/disabled via `requireCardReceipt` flag (defaults to `true`)

### Transaction Fee Calculation
- Supports FIXED and PERCENTAGE fee types
- Calculated locally before sending amount to server
- Final amount (including fee) is sent in USER_ACTION messages

### Payment Flow Control
- **yaspaEnabled=false**: Shows timeout screen, then proceeds directly to payment
- **yaspaEnabled=true**: Shows payment method selection screen
- Server can override with SCREEN_CHANGE messages

### Enhanced Error Handling
- Local limit validation before sending to server
- Automatic timeout handling with screensaver
- Cancel and reset messages for transaction management
- Refund processing for printer errors
- Refund/reversal support for server-initiated transaction reversals

### Refund/Reversal Support
- Client automatically tracks last successful sale transaction
- Server can request refund/reversal via REFUND_REQUEST or REVERSAL_REQUEST message
- **Refund is triggered when ticket printing fails** (if auto-refund is enabled)
- **Flow:** PRINT_TICKET → (printing fails) → REVERSAL_REQUEST → (Android handles refund screens) → requestInitialScreen()
- Client performs SaleReversalRequest via Planet SDK (or mock reversal)
- **Android app handles all refund screens independently** (REFUND_PROCESSING → TransactionSuccess/TransactionFailed)
- Client sends REVERSAL_RESULT back to server with reversal status
- **Server does NOT send THANK_YOU when refund is triggered** - Android app manages complete refund flow
- Supports both real Planet terminal and mock payment provider

### Receipt Question Control
- `requireCardReceipt` configuration flag controls receipt question behavior
- When `requireCardReceipt = true`: Client shows receipt question screen and waits for user response
- When `requireCardReceipt = false`: Client automatically responds NO without showing the screen
- Default value is `true` for backward compatibility
- Setting is configurable via DEVICE_INFO message from server

---

This document covers the essential communication protocol between the Android client and backend server. Adjustments may be needed based on specific backend implementation details or additional business requirements.

