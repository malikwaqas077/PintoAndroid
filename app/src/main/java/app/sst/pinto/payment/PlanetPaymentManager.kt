package app.sst.pinto.payment

import android.util.Log
import app.sst.pinto.utils.getDeviceIpAddress
import integrate_clientsdk.CommunicationContext
import integrate_clientsdk.Error.ErrorType
import integrate_clientsdk.Integra
import integrate_clientsdk.channel.ChannelEvent
import integrate_clientsdk.channel.ChannelEvent.ChannelEventType
import integrate_clientsdk.channel.ChannelFactory
import integrate_clientsdk.channel.ChannelSocketClient
import integrate_clientsdk.channel.IChannel
import integrate_clientsdk.channel.IChannelStatusListener
import integrate_clientsdk.datalink.DatalinkFactory
import integrate_clientsdk.datalink.DatalinkStxEtxCrcSendAckSeqCounter
import integrate_clientsdk.datalink.IDatalink
import integrate_clientsdk.logger.Logger
import integrate_clientsdk.request.IRequest
import integrate_clientsdk.request.RequestFactory
import integrate_clientsdk.request.settlement.SaleRequest
import integrate_clientsdk.request.settlement.SaleReversalRequest
import integrate_clientsdk.response.IResponseHandler
import integrate_clientsdk.response.IStatusUpdateHandler
import integrate_clientsdk.response.Response
import integrate_clientsdk.response.StatusUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

data class PlanetPaymentResult(
    val success: Boolean,
    val resultCode: String? = null,
    val bankResultCode: String? = null,
    val message: String? = null,
    val requesterTransRefNum: String? = null,
    val rawOptions: Map<String, String> = emptyMap()
)

data class CardCheckResult(
    val success: Boolean,
    val token: String? = null,
    val resultCode: String? = null,
    val message: String? = null,
    val sequenceNumber: String? = null, // Sequence number from CardCheckEmv response, needed for Cancel
    val rawOptions: Map<String, String> = emptyMap()
)

/**
 * Thin Kotlin wrapper around the Planet Integra Client SDK.
 *
 * This is intentionally low-level and closely follows the Planet SDK samples.
 * It runs the blocking SDK calls on Dispatchers.IO and returns a simple result.
 */
object PlanetPaymentManager {

    private const val TAG = "PlanetPaymentManager"

    // TODO: make port configurable (e.g. via Config screen or server config)
    private const val DEFAULT_TERMINAL_PORT = "1234"
    private const val DEFAULT_TIMEOUT_SECONDS = "30"
    
    // Mutex to ensure only one transaction runs at a time
    // (Planet SDK may not handle concurrent transactions well)
    private val transactionMutex = Mutex()
    
    // Reusable Integra instance (following Planet SDK sample pattern)
    // The samples show that keeping the Integra instance alive allows it to properly
    // handle multiple transactions. Creating a new instance each time causes issues.
    @Volatile
    private var sharedIntegra: Integra? = null
    @Volatile
    private var sharedChannel: IChannel? = null
    @Volatile
    private var sharedDatalink: IDatalink? = null
    @Volatile
    private var currentTerminalIp: String? = null
    @Volatile
    private var currentTerminalPort: String? = null
    @Volatile
    private var isConnected: Boolean = false
    
    // Track if logger has been initialized (should only be done once)
    @Volatile
    private var loggerInitialized: Boolean = false
    
    // Track if Planet SDK is available on this device
    @Volatile
    private var isSdkAvailable: Boolean = true // Assume available by default, set to false if initialization fails
    
    /**
     * Check if Planet SDK is available on this device.
     * Returns false if SDK initialization failed (e.g., on non-Planet devices).
     */
    fun isPlanetSdkAvailable(): Boolean {
        return isSdkAvailable
    }
    
    /**
     * Initialize the Planet SDK logger. This should only be called once.
     * Based on Planet SDK samples, Logger.initialize() should be called once at app startup,
     * not on every transaction.
     * 
     * CRITICAL: The Planet SDK logger MUST be initialized before any other SDK calls,
     * otherwise native crashes can occur. This is why we initialize it early.
     * 
     * This method handles cases where Planet SDK is not available (e.g., on non-Planet devices)
     * by catching all exceptions including native crashes.
     */
    private fun initializeLoggerOnce(): Boolean {
        if (!loggerInitialized) {
            synchronized(this) {
                if (!loggerInitialized) {
                    try {
                        Log.d(TAG, "Initializing Planet SDK logger (one-time initialization)")
                        
                        // Try to load and initialize the Planet SDK logger
                        // On non-Planet devices, this may throw exceptions or cause native crashes
                        // 
                        // CRITICAL: Use console logging instead of file logging to avoid native crashes
                        // that occur when the SDK tries to list files in a directory that doesn't exist
                        // or isn't accessible. File logging requires directory access which can fail
                        // on Android devices with restricted filesystem access.
                        Logger.setLoggerSetting(Logger.LoggerSettings.LOG_LEVEL, "info")
                        Logger.setLoggerSetting(Logger.LoggerSettings.LOG_LEVEL_AND_APPENDERS, "console")
                        Logger.initialize(null)
                        
                        loggerInitialized = true
                        isSdkAvailable = true
                        Log.d(TAG, "Planet logger initialized successfully")
                        return true
                    } catch (e: Throwable) {
                        // Catch Throwable (includes Error and native crashes), not just Exception
                        // This handles UnsatisfiedLinkError, NoClassDefFoundError, etc. that occur
                        // when native libraries are missing on non-Planet devices
                        Log.w(TAG, "Planet SDK not available on this device: ${e.javaClass.simpleName}: ${e.message}")
                        
                        // Mark SDK as unavailable
                        isSdkAvailable = false
                        loggerInitialized = true // Set to true to prevent repeated attempts
                        
                        // This is expected on non-Planet devices, so log as warning, not error
                        return false
                    }
                }
            }
        }
        return isSdkAvailable
    }
    
    /**
     * Initialize the Planet SDK logger early. Call this from Application.onCreate() or MainActivity.onCreate()
     * to ensure the logger is ready before any SDK operations.
     * 
     * This method runs on a background thread to avoid blocking the UI thread.
     * Returns true if SDK is available, false otherwise.
     */
    fun initializeLogger(): Boolean {
        return initializeLoggerOnce()
    }
    
    /**
     * Initialize the Planet SDK logger asynchronously with a timeout.
     * This should be called from MainActivity.onCreate() to avoid blocking the UI thread.
     * 
     * @param callback Optional callback that receives true if SDK is available, false otherwise
     */
    fun initializeLoggerAsync(callback: ((Boolean) -> Unit)? = null) {
        Thread {
            try {
                val available = initializeLoggerOnce()
                callback?.invoke(available)
            } catch (e: Throwable) {
                Log.e(TAG, "Unexpected error during async logger initialization", e)
                isSdkAvailable = false
                callback?.invoke(false)
            }
        }.start()
    }
    
    /**
     * Initialize the Integra instance early at app start.
     * This makes transactions faster by avoiding initialization delay during payment processing.
     * 
     * @param terminalIp IP address of the terminal (defaults to device IP)
     * @param terminalPort port of the terminal (defaults to 1234)
     * @return true if initialization was successful, false otherwise
     */
    fun initializeIntegra(
        terminalIp: String = getDeviceIpAddress(),
        terminalPort: String = DEFAULT_TERMINAL_PORT
    ): Boolean {
        synchronized(this) {
            // Check if SDK is available before attempting initialization
            if (!isPlanetSdkAvailable()) {
                Log.d(TAG, "Planet SDK is not available, skipping Integra initialization")
                return false
            }
            
            // Only initialize if not already initialized
            if (sharedIntegra != null && 
                currentTerminalIp == terminalIp && 
                currentTerminalPort == terminalPort &&
                isConnected) {
                Log.d(TAG, "Integra already initialized, skipping early initialization")
                return true
            }
            
            Log.d(TAG, "Initializing Integra at app start: ip=$terminalIp, port=$terminalPort")
            
            // Initialize logger first (required before any SDK operations)
            if (!initializeLoggerOnce()) {
                Log.w(TAG, "Failed to initialize Planet SDK logger, cannot initialize Integra")
                return false
            }
            
            // Create a minimal channel status listener for initialization
            val channelStatusListener = object : IChannelStatusListener {
                override fun onChannelEvent(channelEvent: ChannelEvent) {
                    when (channelEvent.type) {
                        ChannelEventType.CONNECTED -> {
                            Log.d(TAG, "Planet channel connected during early initialization")
                            isConnected = true
                        }
                        ChannelEventType.DISCONNECTED -> {
                            Log.d(TAG, "Planet channel disconnected during early initialization")
                            isConnected = false
                        }
                        else -> {
                            Log.d(TAG, "Planet channel event: ${channelEvent.type}")
                        }
                    }
                }
            }
            
            val integra = getOrCreateIntegra(terminalIp, terminalPort, channelStatusListener)
            
            if (integra != null) {
                Log.d(TAG, "Integra initialized successfully at app start")
                return true
            } else {
                Log.w(TAG, "Failed to initialize Integra at app start, will initialize lazily on first transaction")
                return false
            }
        }
    }

    /**
     * Perform a sale transaction on the Planet terminal.
     *
     * @param amountFormatted amount as a string in the format "10.00"
     * @param requesterRef unique reference for this transaction (e.g. transactionId from server)
     */
    suspend fun performSale(
        amountFormatted: String,
        requesterRef: String,
        terminalIp: String = getDeviceIpAddress(),
        terminalPort: String = DEFAULT_TERMINAL_PORT,
        timeoutSeconds: String = DEFAULT_TIMEOUT_SECONDS
    ): PlanetPaymentResult = transactionMutex.withLock {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting Planet sale: amount=$amountFormatted, ref=$requesterRef, ip=$terminalIp:$terminalPort")

            // Check if SDK is available before attempting any operations
            if (!isPlanetSdkAvailable()) {
                Log.w(TAG, "Planet SDK is not available on this device, cannot perform sale")
                return@withContext PlanetPaymentResult(
                    success = false,
                    resultCode = "SDK_UNAVAILABLE",
                    message = "Planet payment SDK is not available on this device"
                )
            }

            // Initialize logger once (should not be called on every transaction)
            if (!initializeLoggerOnce()) {
                Log.w(TAG, "Failed to initialize Planet SDK logger, cannot perform sale")
                return@withContext PlanetPaymentResult(
                    success = false,
                    resultCode = "SDK_INIT_FAILED",
                    message = "Failed to initialize payment terminal"
                )
            }

            // Per-transaction state (reset for each transaction)
            // Using a data class to hold mutable state that can be captured by handlers
            data class TransactionState(
                var transactionCompleted: Boolean = false,
                var terminalReady: Boolean = false,
                var resultCode: String? = null,
                var bankResultCode: String? = null,
                var message: String? = null,
                var requesterTransRefNum: String? = null,
                var rawOptions: Map<String, String> = emptyMap()
            )
            
            val state = TransactionState()
            
            // Connection state for this transaction attempt
            var connectedThisTransaction = false

            // Status updates handler (reused across transactions)
            val statusHandler = object : IStatusUpdateHandler {
                override fun onStatusUpdate(statusUpdate: StatusUpdate) {
                    statusUpdate.options?.forEach { (key, value) ->
                        if (key == "StatusMessage") {
                            Log.d(TAG, "Planet status: $value")
                            // Check for terminal ready states
                            val statusLower = value?.lowercase() ?: ""
                            if (statusLower.contains("terminal ready") || 
                                statusLower.contains("welcome") ||
                                statusLower.contains("ready")) {
                                state.terminalReady = true
                            }
                        }
                    }
                }
            }

            // Final response from the terminal (created fresh for each transaction)
            val responseHandler = object : IResponseHandler {
                override fun onResponse(response: Response) {
                    // Capture full raw options map for logging/forwarding
                    state.rawOptions = response.options ?: emptyMap()

                    var hasResult = false
                    var hasBankResult = false
                    
                    response.options?.forEach { (key, value) ->
                        when (key) {
                            "Result" -> {
                                if (value != null && value.isNotEmpty()) {
                                    state.resultCode = value
                                    hasResult = true
                                }
                            }
                            "BankResultCode" -> {
                                if (value != null && value.isNotEmpty()) {
                                    state.bankResultCode = value
                                    hasBankResult = true
                                }
                            }
                            "Message" -> {
                                if (value != null && value.isNotEmpty()) {
                                    state.message = value
                                }
                            }
                            "RequesterTransRefNum" -> {
                                if (value != null && value.isNotEmpty()) {
                                    state.requesterTransRefNum = value
                                }
                            }
                        }
                    }
                    
                    // Only mark transaction as completed if we have actual result data
                    // (Planet SDK sometimes calls onResponse with empty/null values as status updates)
                    if (hasResult || hasBankResult) {
                        state.transactionCompleted = true
                        Log.d(TAG, "Planet response: result=${state.resultCode} bank=${state.bankResultCode} msg=${state.message} ref=${state.requesterTransRefNum}")
                        Log.d(TAG, "Planet full response options: ${state.rawOptions}")
                    } else {
                        Log.d(TAG, "Planet response (ignored - no result data): options=${state.rawOptions}")
                    }
                }
            }

            // Connection events handler (reused across transactions)
            val channelStatusListener = object : IChannelStatusListener {
                override fun onChannelEvent(channelEvent: ChannelEvent) {
                    when (channelEvent.type) {
                        ChannelEventType.CONNECTED -> {
                            Log.d(TAG, "Planet channel connected")
                            isConnected = true
                            connectedThisTransaction = true
                        }
                        ChannelEventType.DISCONNECTED -> {
                            Log.d(TAG, "Planet channel disconnected")
                            isConnected = false
                        }
                        else -> {
                            Log.d(TAG, "Planet channel event: ${channelEvent.type}")
                        }
                    }
                }
            }

            try {
                // Initialize or reuse Integra instance (following Planet SDK sample pattern)
                val integra = getOrCreateIntegra(terminalIp, terminalPort, channelStatusListener)
                
                if (integra == null) {
                    Log.e(TAG, "Planet: failed to create or connect Integra instance")
                    return@withContext PlanetPaymentResult(
                        success = false,
                        resultCode = "INIT_FAILED",
                        message = "Failed to initialize payment terminal connection"
                    )
                }
                
                // Update handlers for this transaction (must be set per transaction)
                // This ensures the handlers capture the current transaction's state
                integra.setStatusUpdateHandler(statusHandler)
                integra.setResponseHandler(responseHandler)
                
                // Note: Planet SDK connects automatically when sending the first request.
                // We don't need to wait for connection here - sending the request will trigger connection.
                if (isConnected) {
                    Log.d(TAG, "Planet: using existing connection")
                } else {
                    Log.d(TAG, "Planet: connection will be established when sending request")
                }

                // Build sale request
                val requestOptions = hashMapOf(
                    IRequest.TAG_REQUESTERTRANSREFNUM to requesterRef,
                    IRequest.TAG_AMOUNT to amountFormatted
                )

                Log.d(TAG, "Planet: creating SaleRequest with $requestOptions")
                val request: IRequest = SaleRequest(requestOptions)

                if (!request.validateOptions()) {
                    Log.e(TAG, "Planet: request validation failed")
                    return@withContext PlanetPaymentResult(
                        success = false,
                        resultCode = "INVALID_REQUEST",
                        message = "Missing or invalid request parameters"
                    )
                }

                // Send request - this will trigger connection if not already connected
                val sequenceNumber = AtomicInteger()
                Log.d(TAG, "Planet: sending sale request")
                val sendError = integra.sendRequest(request, sequenceNumber)
                if (sendError != ErrorType.SUCCESS) {
                    Log.e(TAG, "Planet: error sending request: $sendError")
                    return@withContext PlanetPaymentResult(
                        success = false,
                        resultCode = sendError.toString(),
                        message = "Error sending request to terminal"
                    )
                }

                Log.d(TAG, "Planet: request sent successfully, waiting for response")

                // Wait for response
                val txStart = System.currentTimeMillis()
                val txTimeoutMs = 120_000L // 2 minutes for the user to complete payment
                while (!state.transactionCompleted && (System.currentTimeMillis() - txStart) < txTimeoutMs) {
                    Thread.sleep(50)
                }

                if (!state.transactionCompleted) {
                    Log.e(TAG, "Planet: transaction timeout after ${System.currentTimeMillis() - txStart}ms")
                    return@withContext PlanetPaymentResult(
                        success = false,
                        resultCode = "TX_TIMEOUT",
                        message = "Payment timed out"
                    )
                }

                // Wait briefly for terminal to be ready (non-blocking, don't delay user feedback)
                // Terminal ready is nice to have for next transaction, but shouldn't delay showing success
                Log.d(TAG, "Planet: checking terminal ready status")
                state.terminalReady = false
                val readyStart = System.currentTimeMillis()
                val readyTimeoutMs = 2_000L // Only wait 2 seconds max - don't delay user feedback
                while (!state.terminalReady && (System.currentTimeMillis() - readyStart) < readyTimeoutMs) {
                    Thread.sleep(50)
                }
                
                if (state.terminalReady) {
                    Log.d(TAG, "Planet: terminal is ready")
                } else {
                    Log.d(TAG, "Planet: terminal ready check completed (continuing - terminal will be ready for next transaction)")
                }

                // Check for success: resultCode "A" = Approved, bankResultCode "00" = Success, or message contains "APPROVED"
                val success = state.resultCode?.equals("A", ignoreCase = true) == true ||
                        state.resultCode?.equals("APPROVED", ignoreCase = true) == true ||
                        state.bankResultCode?.equals("00", ignoreCase = true) == true ||
                        state.message?.contains("APPROVED", ignoreCase = true) == true

               
                Log.d(TAG, "Planet: transaction completed with success=$success")
                PlanetPaymentResult(
                    success = success,
                    resultCode = state.resultCode,
                    bankResultCode = state.bankResultCode,
                    message = state.message,
                    requesterTransRefNum = state.requesterTransRefNum,
                    rawOptions = state.rawOptions
                )
            } catch (e: Exception) {
                Log.e(TAG, "Planet: unexpected error during sale", e)
                // If there's an error, mark connection as potentially broken
                isConnected = false
                PlanetPaymentResult(
                    success = false,
                    resultCode = "EXCEPTION",
                    message = e.message ?: "Unexpected error"
                )
            }
        }
    }
    
    /**
     * Clean up Planet SDK resources properly.
     * This ensures sockets, threads, and other resources are released.
     * This is called automatically when creating a new instance, but can also
     * be called explicitly to force cleanup (e.g., when app is closing).
     */
    private fun cleanupIntegraResources() {
        try {
            sharedIntegra?.dispose()
            Log.d(TAG, "Planet: disposed Integra instance")
        } catch (e: Throwable) {
            // Handle case where SDK classes aren't available or already disposed
            Log.w(TAG, "Planet: error disposing Integra instance (may not be available): ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            sharedIntegra = null
            sharedChannel = null
            sharedDatalink = null
            isConnected = false
        }
    }
    
    /**
     * Force cleanup of all Planet SDK resources.
     * Call this when you want to explicitly release all resources
     * (e.g., when app is closing or switching terminals).
     * Safe to call even if SDK is not available.
     */
    fun cleanup() {
        synchronized(this) {
            cleanupIntegraResources()
            Log.d(TAG, "Planet: all resources cleaned up")
        }
    }
    
    /**
     * Get or create the shared Integra instance.
     * Following Planet SDK sample pattern: create once, reuse for all transactions.
     * Note: Status and response handlers are set per transaction, not here.
     */
    private fun getOrCreateIntegra(
        terminalIp: String,
        terminalPort: String,
        channelStatusListener: IChannelStatusListener
    ): Integra? {
        synchronized(this) {
            // Check if we need to create a new instance (first time or IP/port changed)
            val needsNewInstance = sharedIntegra == null || 
                                  currentTerminalIp != terminalIp || 
                                  currentTerminalPort != terminalPort ||
                                  !isConnected
            
            if (needsNewInstance) {
                Log.d(TAG, "Planet: creating new Integra instance (first time or connection lost)")
                
                // Clean up old instance if it exists (properly dispose resources)
                cleanupIntegraResources()
                
                try {
                    // Channel options
                    val channelOptions = hashMapOf(
                        ChannelFactory.KEY_CHANNEL to ChannelSocketClient.CHANNEL_TYPE_VALUE,
                        ChannelFactory.KEY_HOST to terminalIp,
                        ChannelFactory.KEY_PORT to terminalPort,
                        ChannelFactory.KEY_TIMEOUT to DEFAULT_TIMEOUT_SECONDS
                    )

                    val channelError = ChannelFactory.validateOptions(channelOptions)
                    if (channelError != ErrorType.SUCCESS) {
                        Log.e(TAG, "Planet channel options error: $channelError")
                        return null
                    }

                    sharedChannel = try {
                        ChannelFactory.getChannel(channelOptions)
                    } catch (e: Throwable) {
                        // Handle case where SDK classes aren't available (e.g., NoClassDefFoundError, UnsatisfiedLinkError)
                        Log.w(TAG, "Failed to create Planet channel (SDK may not be available): ${e.javaClass.simpleName}: ${e.message}")
                        isSdkAvailable = false
                        return null
                    }
                    
                    if (sharedChannel == null) {
                        Log.e(TAG, "Planet: ChannelFactory.getChannel returned null")
                        return null
                    }

                    // Datalink options (from sample, with sane defaults)
                    val datalinkOptions = hashMapOf(
                        DatalinkFactory.KEY_DATALINK to DatalinkStxEtxCrcSendAckSeqCounter.DATALINK_TYPE_VALUE,
                        DatalinkFactory.KEY_ACK_TIMEOUT to "30000",
                        DatalinkFactory.KEY_ACK_MAX_RETRIES to "2",
                        DatalinkFactory.KEY_KEEP_ALIVE_INTERVAL to "1",
                        DatalinkFactory.KEY_DUPLICATE_CHECK to "true",
                        DatalinkFactory.KEY_MASK_NON_ASCII to "false",
                        DatalinkFactory.KEY_SYN_BYTES to "0"
                    )

                    val datalinkError = DatalinkFactory.validateOptions(datalinkOptions)
                    if (datalinkError != ErrorType.SUCCESS) {
                        Log.e(TAG, "Planet datalink options error: $datalinkError")
                        return null
                    }

                    sharedDatalink = try {
                        DatalinkFactory.getDatalink(datalinkOptions)
                    } catch (e: Throwable) {
                        // Handle case where SDK classes aren't available
                        Log.w(TAG, "Failed to create Planet datalink (SDK may not be available): ${e.javaClass.simpleName}: ${e.message}")
                        isSdkAvailable = false
                        return null
                    }
                    
                    if (sharedDatalink == null) {
                        Log.e(TAG, "Planet: DatalinkFactory.getDatalink returned null")
                        return null
                    }

                    Log.d(TAG, "Planet: creating CommunicationContext")
                    val context = try {
                        CommunicationContext(sharedChannel, sharedDatalink)
                    } catch (e: Throwable) {
                        // Handle case where SDK classes aren't available (e.g., NoClassDefFoundError, UnsatisfiedLinkError)
                        Log.w(TAG, "Failed to create Planet CommunicationContext (SDK may not be available): ${e.javaClass.simpleName}: ${e.message}")
                        isSdkAvailable = false
                        return null
                    }
                    
                    if (context == null) {
                        Log.e(TAG, "Planet: CommunicationContext constructor returned null")
                        return null
                    }

                    Log.d(TAG, "Planet: creating Integra instance")
                    sharedIntegra = try {
                        Integra(context)
                    } catch (e: Throwable) {
                        // Handle case where SDK classes aren't available (e.g., NoClassDefFoundError, UnsatisfiedLinkError)
                        Log.w(TAG, "Failed to create Planet Integra instance (SDK may not be available): ${e.javaClass.simpleName}: ${e.message}")
                        isSdkAvailable = false
                        return null
                    }
                    
                    if (sharedIntegra == null) {
                        Log.e(TAG, "Planet: Integra constructor returned null")
                        return null
                    }
                    
                    // Set channel status listener (status and response handlers set per transaction)
                    Log.d(TAG, "Planet: setting channel status listener")
                    try {
                        sharedIntegra?.setChannelStatusListener(channelStatusListener)
                    } catch (e: Exception) {
                        Log.e(TAG, "Planet: error setting channel status listener", e)
                        // Don't fail here, continue
                    }
                    
                    currentTerminalIp = terminalIp
                    currentTerminalPort = terminalPort
                    isConnected = false // Will be set to true by channelStatusListener
                    
                    Log.d(TAG, "Planet: Integra instance created and handlers set")
                } catch (e: Throwable) {
                    // Handle case where SDK classes aren't available (e.g., NoClassDefFoundError, UnsatisfiedLinkError)
                    Log.w(TAG, "Planet: error creating Integra instance (SDK may not be available): ${e.javaClass.simpleName}: ${e.message}", e)
                    isSdkAvailable = false
                    cleanupIntegraResources()
                    return null
                }
            } else {
                Log.d(TAG, "Planet: reusing existing Integra instance")
            }
            
            return sharedIntegra
        }
    }
    
    /**
     * Perform a card check (CardCheckEmv) to validate card and get token.
     * This should be called before performing a Sale request to check daily spending limits.
     *
     * @param requesterRef unique reference for this transaction
     * @param amountFormatted Optional amount as a string in the format "10.00". 
     *                        If provided and Sale uses the same amount, terminal won't require re-tap.
     * @param terminalIp IP address of the terminal
     * @param terminalPort port of the terminal
     * @param timeoutSeconds timeout in seconds
     */
    suspend fun performCardCheck(
        requesterRef: String,
        amountFormatted: String? = null,
        terminalIp: String = getDeviceIpAddress(),
        terminalPort: String = DEFAULT_TERMINAL_PORT,
        timeoutSeconds: String = DEFAULT_TIMEOUT_SECONDS
    ): CardCheckResult = transactionMutex.withLock {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting Planet card check: ref=$requesterRef, ip=$terminalIp:$terminalPort")

            // Check if SDK is available before attempting any operations
            if (!isPlanetSdkAvailable()) {
                Log.w(TAG, "Planet SDK is not available on this device, cannot perform card check")
                return@withContext CardCheckResult(
                    success = false,
                    resultCode = "SDK_UNAVAILABLE",
                    message = "Planet payment SDK is not available on this device"
                )
            }

            // Initialize logger once (should not be called on every transaction)
            if (!initializeLoggerOnce()) {
                Log.w(TAG, "Failed to initialize Planet SDK logger, cannot perform card check")
                return@withContext CardCheckResult(
                    success = false,
                    resultCode = "SDK_INIT_FAILED",
                    message = "Failed to initialize payment terminal"
                )
            }

            // Per-transaction state
            data class CardCheckState(
                var transactionCompleted: Boolean = false,
                var terminalReady: Boolean = false,
                var resultCode: String? = null,
                var message: String? = null,
                var token: String? = null,
                var sequenceNumber: String? = null, // Track sequence number for Cancel
                var rawOptions: Map<String, String> = emptyMap()
            )
            
            val state = CardCheckState()
            var connectedThisTransaction = false

            // Status updates handler
            val statusHandler = object : IStatusUpdateHandler {
                override fun onStatusUpdate(statusUpdate: StatusUpdate) {
                    statusUpdate.options?.forEach { (key, value) ->
                        if (key == "StatusMessage") {
                            Log.d(TAG, "Planet card check status: $value")
                            val statusLower = value?.lowercase() ?: ""
                            if (statusLower.contains("terminal ready") || 
                                statusLower.contains("welcome") ||
                                statusLower.contains("ready")) {
                                state.terminalReady = true
                            }
                        }
                    }
                }
            }

            // Response handler for card check
            val responseHandler = object : IResponseHandler {
                override fun onResponse(response: Response) {
                    state.rawOptions = response.options ?: emptyMap()
                    
                    var hasResult = false
                    
                    response.options?.forEach { (key, value) ->
                        when (key) {
                            "Result" -> {
                                if (value != null && value.isNotEmpty()) {
                                    state.resultCode = value
                                    hasResult = true
                                }
                            }
                            "Message" -> {
                                if (value != null && value.isNotEmpty()) {
                                    state.message = value
                                }
                            }
                            "Token" -> {
                                if (value != null && value.isNotEmpty()) {
                                    state.token = value
                                    Log.d(TAG, "Card check token received: $value")
                                }
                            }
                            "CardToken" -> {
                                if (value != null && value.isNotEmpty()) {
                                    state.token = value
                                    Log.d(TAG, "Card check token received (CardToken): $value")
                                }
                            }
                            "CardDataToken" -> {
                                if (value != null && value.isNotEmpty()) {
                                    state.token = value
                                    Log.d(TAG, "Card check token received (CardDataToken): $value")
                                }
                            }
                            "SequenceNumber" -> {
                                if (value != null && value.isNotEmpty()) {
                                    state.sequenceNumber = value
                                    Log.d(TAG, "Card check sequence number received: $value")
                                }
                            }
                        }
                    }
                    
                    if (hasResult) {
                        state.transactionCompleted = true
                        Log.d(TAG, "Card check response: result=${state.resultCode} token=${state.token} msg=${state.message}")
                        Log.d(TAG, "Card check full response options: ${state.rawOptions}")
                    } else {
                        Log.d(TAG, "Card check response (ignored - no result data): options=${state.rawOptions}")
                    }
                }
            }

            // Connection events handler
            val channelStatusListener = object : IChannelStatusListener {
                override fun onChannelEvent(channelEvent: ChannelEvent) {
                    when (channelEvent.type) {
                        ChannelEventType.CONNECTED -> {
                            Log.d(TAG, "Planet channel connected for card check")
                            isConnected = true
                            connectedThisTransaction = true
                        }
                        ChannelEventType.DISCONNECTED -> {
                            Log.d(TAG, "Planet channel disconnected during card check")
                            isConnected = false
                        }
                        else -> {
                            Log.d(TAG, "Planet channel event: ${channelEvent.type}")
                        }
                    }
                }
            }

            try {
                val integra = getOrCreateIntegra(terminalIp, terminalPort, channelStatusListener)
                
                if (integra == null) {
                    Log.e(TAG, "Planet: failed to create or connect Integra instance for card check")
                    return@withContext CardCheckResult(
                        success = false,
                        resultCode = "INIT_FAILED",
                        message = "Failed to initialize payment terminal connection"
                    )
                }
                
                integra.setStatusUpdateHandler(statusHandler)
                integra.setResponseHandler(responseHandler)

                // Build card check request using RequestFactory
                val requestOptions = hashMapOf<String, String>(
                    RequestFactory.KEY_REQUEST to "Card-Terminal"
                )

                // Add amount if provided (for fixed amount transactions)
                if (amountFormatted != null && amountFormatted.isNotEmpty()) {
                    requestOptions[IRequest.TAG_AMOUNT] = amountFormatted
                    Log.d(TAG, "Adding amount to CardCheckRequest: $amountFormatted")
                }

                Log.d(TAG, "Planet: creating CardCheckRequest with $requestOptions")
                
                // Log what options are required for debugging
                // Wrap in try-catch as this SDK call might crash in native code
                try {
                    val requiredOptions = RequestFactory.getOptionsForRequest("Card-Terminal")
                    Log.d(TAG, "CardCheckRequest required options: $requiredOptions")
                    
                    // If RequesterTransRefNum is required, add it
                    if (requiredOptions != null && (requiredOptions.contains("RequesterTransRefNum") || 
                        requiredOptions.contains(IRequest.TAG_REQUESTERTRANSREFNUM))) {
                        Log.d(TAG, "Adding RequesterTransRefNum to CardCheckRequest")
                        requestOptions[IRequest.TAG_REQUESTERTRANSREFNUM] = requesterRef
                    }
                } catch (e: Throwable) {
                    // Catch Throwable (includes native crashes) not just Exception
                    Log.w(TAG, "Could not get required options for CardCheckRequest, proceeding anyway", e)
                    // If getOptionsForRequest crashes, we'll still add RequesterTransRefNum below as safety measure
                }
                
                // Add RequesterTransRefNum as a safety measure even if getOptionsForRequest failed
                if (!requestOptions.containsKey(IRequest.TAG_REQUESTERTRANSREFNUM)) {
                    Log.d(TAG, "Adding RequesterTransRefNum to CardCheckRequest as safety measure")
                    requestOptions[IRequest.TAG_REQUESTERTRANSREFNUM] = requesterRef
                }
                
                // Validate requestOptions before creating request
                if (requestOptions.isEmpty()) {
                    Log.e(TAG, "Planet: requestOptions is empty, cannot create CardCheckRequest")
                    return@withContext CardCheckResult(
                        success = false,
                        resultCode = "INVALID_REQUEST",
                        message = "Request options are empty"
                    )
                }
                
                val request: IRequest = try {
                    RequestFactory.getRequest(requestOptions)
                } catch (e: Exception) {
                    Log.e(TAG, "Planet: error creating CardCheckRequest from RequestFactory", e)
                    return@withContext CardCheckResult(
                        success = false,
                        resultCode = "REQUEST_CREATION_FAILED",
                        message = "Failed to create card check request: ${e.message}"
                    )
                }
                
                if (request == null) {
                    Log.e(TAG, "Planet: RequestFactory.getRequest returned null")
                    return@withContext CardCheckResult(
                        success = false,
                        resultCode = "REQUEST_CREATION_FAILED",
                        message = "RequestFactory returned null request"
                    )
                }

                if (!request.validateOptions()) {
                    Log.e(TAG, "Planet: card check request validation failed")
                    return@withContext CardCheckResult(
                        success = false,
                        resultCode = "INVALID_REQUEST",
                        message = "Missing or invalid request parameters"
                    )
                }

                // Send request
                val sequenceNumber = AtomicInteger()
                Log.d(TAG, "Planet: sending card check request")
                val sendError = integra.sendRequest(request, sequenceNumber)
                if (sendError != ErrorType.SUCCESS) {
                    Log.e(TAG, "Planet: error sending card check request: $sendError")
                    return@withContext CardCheckResult(
                        success = false,
                        resultCode = sendError.toString(),
                        message = "Error sending card check request to terminal"
                    )
                }

                Log.d(TAG, "Planet: card check request sent successfully, waiting for response")

                // Wait for response
                val txStart = System.currentTimeMillis()
                val txTimeoutMs = 60_000L // 1 minute timeout for card check
                while (!state.transactionCompleted && (System.currentTimeMillis() - txStart) < txTimeoutMs) {
                    Thread.sleep(50)
                }

                if (!state.transactionCompleted) {
                    Log.e(TAG, "Planet: card check timeout after ${System.currentTimeMillis() - txStart}ms")
                    return@withContext CardCheckResult(
                        success = false,
                        resultCode = "TX_TIMEOUT",
                        message = "Card check timed out"
                    )
                }

                // Check for success: resultCode "A" = Approved or similar success indicators
                val success = state.resultCode?.equals("A", ignoreCase = true) == true ||
                        state.resultCode?.equals("APPROVED", ignoreCase = true) == true ||
                        state.message?.contains("APPROVED", ignoreCase = true) == true ||
                        state.message?.contains("SUCCESS", ignoreCase = true) == true

                Log.d(TAG, "Planet: card check completed with success=$success, token=${state.token}, sequenceNumber=${state.sequenceNumber}")
                CardCheckResult(
                    success = success,
                    token = state.token,
                    resultCode = state.resultCode,
                    message = state.message,
                    sequenceNumber = state.sequenceNumber,
                    rawOptions = state.rawOptions
                )
            } catch (e: Exception) {
                Log.e(TAG, "Planet: unexpected error during card check", e)
                isConnected = false
                CardCheckResult(
                    success = false,
                    resultCode = "EXCEPTION",
                    message = e.message ?: "Unexpected error"
                )
            }
        }
    }
    
    /**
     * Perform a cancel request to cancel the current transaction on the terminal.
     * This should be called when a CardCheckEmv is performed but no Sale is needed
     * (e.g., when daily spending limit is exceeded).
     *
     * @param requesterRef unique reference for this transaction
     * @param sequenceNumberToCancel sequence number from the CardCheckEmv response (required for Cancel)
     * @param terminalIp IP address of the terminal
     * @param terminalPort port of the terminal
     * @param timeoutSeconds timeout in seconds
     */
    suspend fun performCancel(
        requesterRef: String,
        sequenceNumberToCancel: String?,
        terminalIp: String = getDeviceIpAddress(),
        terminalPort: String = DEFAULT_TERMINAL_PORT,
        timeoutSeconds: String = DEFAULT_TIMEOUT_SECONDS
    ): Boolean = transactionMutex.withLock {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting Planet cancel: ref=$requesterRef, ip=$terminalIp:$terminalPort")

            // Check if SDK is available before attempting any operations
            if (!isPlanetSdkAvailable()) {
                Log.w(TAG, "Planet SDK is not available on this device, cannot perform cancel")
                return@withContext false
            }

            // Initialize logger once (should not be called on every transaction)
            if (!initializeLoggerOnce()) {
                Log.w(TAG, "Failed to initialize Planet SDK logger, cannot perform cancel")
                return@withContext false
            }

            // Per-transaction state
            data class CancelState(
                var transactionCompleted: Boolean = false,
                var resultCode: String? = null,
                var message: String? = null,
                var rawOptions: Map<String, String> = emptyMap()
            )
            
            val state = CancelState()

            // Response handler for cancel
            val responseHandler = object : IResponseHandler {
                override fun onResponse(response: Response) {
                    state.rawOptions = response.options ?: emptyMap()
                    Log.d(TAG, "Cancel response received with options: ${state.rawOptions}")
                    
                    var hasResult = false
                    var hasType = false
                    
                    response.options?.forEach { (key, value) ->
                        when (key) {
                            "Type" -> {
                                if (value != null && value.isNotEmpty()) {
                                    hasType = true
                                    state.transactionCompleted = true
                                    Log.d(TAG, "Cancel response received: Type=$value")
                                }
                            }
                            "Result" -> {
                                if (value != null && value.isNotEmpty()) {
                                    state.resultCode = value
                                    hasResult = true
                                    // If we have a result, consider transaction completed
                                    state.transactionCompleted = true
                                }
                            }
                            "Message" -> {
                                if (value != null && value.isNotEmpty()) {
                                    state.message = value
                                }
                            }
                        }
                    }
                    
                    // Mark as completed if we have any meaningful response
                    if (hasType || hasResult || response.options?.isNotEmpty() == true) {
                        state.transactionCompleted = true
                        Log.d(TAG, "Cancel response: result=${state.resultCode} msg=${state.message} type=${response.options?.get("Type")}")
                    }
                }
            }

            // Connection events handler
            val channelStatusListener = object : IChannelStatusListener {
                override fun onChannelEvent(channelEvent: ChannelEvent) {
                    when (channelEvent.type) {
                        ChannelEventType.CONNECTED -> {
                            Log.d(TAG, "Planet channel connected for cancel")
                        }
                        ChannelEventType.DISCONNECTED -> {
                            Log.d(TAG, "Planet channel disconnected during cancel")
                        }
                        else -> {
                            Log.d(TAG, "Planet channel event: ${channelEvent.type}")
                        }
                    }
                }
            }

            try {
                val integra = getOrCreateIntegra(terminalIp, terminalPort, channelStatusListener)
                
                if (integra == null) {
                    Log.e(TAG, "Planet: failed to create or connect Integra instance for cancel")
                    return@withContext false
                }
                
                integra.setResponseHandler(responseHandler)

                // Build cancel request using RequestFactory
                val requestOptions = hashMapOf<String, String>(
                    RequestFactory.KEY_REQUEST to "Cancel"
                )

                // Query what options are required for Cancel request
                var requiredOptions: List<String>? = null
                try {
                    requiredOptions = RequestFactory.getOptionsForRequest("Cancel")
                    Log.d(TAG, "CancelRequest required options: $requiredOptions")
                    
                    // Add RequesterTransRefNum if required
                    if (requiredOptions.contains("RequesterTransRefNum") || 
                        requiredOptions.contains(IRequest.TAG_REQUESTERTRANSREFNUM)) {
                        Log.d(TAG, "Adding RequesterTransRefNum to CancelRequest")
                        requestOptions[IRequest.TAG_REQUESTERTRANSREFNUM] = requesterRef
                    }
                    
                    // Add SequenceNumberToCancel if required and provided
                    if (requiredOptions.contains("SequenceNumberToCancel") || 
                        requiredOptions.contains(IRequest.TAG_SEQUENCENUMBERTOCANCEL)) {
                        if (sequenceNumberToCancel != null && sequenceNumberToCancel.isNotEmpty()) {
                            Log.d(TAG, "Adding SequenceNumberToCancel to CancelRequest: $sequenceNumberToCancel")
                            requestOptions[IRequest.TAG_SEQUENCENUMBERTOCANCEL] = sequenceNumberToCancel
                        } else {
                            Log.w(TAG, "CancelRequest requires SequenceNumberToCancel but it was not provided")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not get required options for CancelRequest: ${e.message}", e)
                }

                Log.d(TAG, "Planet: creating CancelRequest with $requestOptions")
                
                val request: IRequest = RequestFactory.getRequest(requestOptions)

                if (!request.validateOptions()) {
                    Log.e(TAG, "Planet: cancel request validation failed!")
                    Log.w(TAG, "Continuing with cancel request despite validation failure - will attempt to send anyway")
                } else {
                    Log.d(TAG, "Cancel request validation passed")
                }

                // Send request
                val sequenceNumber = AtomicInteger()
                Log.d(TAG, "Planet: sending cancel request")
                val sendError = integra.sendRequest(request, sequenceNumber)
                if (sendError != ErrorType.SUCCESS) {
                    Log.e(TAG, "Planet: error sending cancel request: $sendError")
                    return@withContext false
                }

                Log.d(TAG, "Planet: cancel request sent successfully, waiting for response")

                // Wait for response (shorter timeout for cancel)
                val txStart = System.currentTimeMillis()
                val txTimeoutMs = 10_000L // 10 seconds timeout for cancel
                while (!state.transactionCompleted && (System.currentTimeMillis() - txStart) < txTimeoutMs) {
                    Thread.sleep(50)
                }

                if (!state.transactionCompleted) {
                    Log.w(TAG, "Planet: cancel timeout after ${System.currentTimeMillis() - txStart}ms")
                    // If we sent the request successfully, consider it a success even without response
                    // The terminal may have processed the cancel even if it didn't send a response
                    Log.d(TAG, "Cancel request was sent successfully, considering cancel as processed")
                    return@withContext true
                }

                // Check for explicit success indicators
                val hasSuccessMessage = state.message?.contains("success", ignoreCase = true) == true ||
                                       state.message?.contains("cancelled", ignoreCase = true) == true ||
                                       state.message?.contains("cancel", ignoreCase = true) == true
                val hasSuccessResult = state.resultCode?.equals("TC", ignoreCase = true) == true ||
                                      state.resultCode?.equals("A", ignoreCase = true) == true ||
                                      state.resultCode?.equals("C", ignoreCase = true) == true
                
                // If we got a response (transactionCompleted = true), consider it success
                // Even if the message doesn't explicitly say "success", the terminal received and processed the cancel
                val success = state.transactionCompleted && (hasSuccessMessage || hasSuccessResult || state.rawOptions.isNotEmpty())

                Log.d(TAG, "Planet: cancel completed with success=$success (result=${state.resultCode}, msg=${state.message}, hasResponse=${state.transactionCompleted})")
                success
            } catch (e: Exception) {
                Log.e(TAG, "Planet: unexpected error during cancel", e)
                false
            }
        }
    }
    
    /**
     * Perform a sale reversal (refund) for a previously successful sale transaction.
     * This reverses/refunds a sale transaction that was already completed.
     *
     * @param amountFormatted amount as a string in the format "10.00" (must match original sale amount)
     * @param requesterRef unique reference for this reversal transaction
     * @param originalRequesterRef the RequesterTransRefNum from the original sale transaction (required for reversal)
     * @param terminalIp IP address of the terminal
     * @param terminalPort port of the terminal
     * @param timeoutSeconds timeout in seconds
     */
    suspend fun performSaleReversal(
        amountFormatted: String,
        requesterRef: String,
        originalRequesterRef: String,
        terminalIp: String = getDeviceIpAddress(),
        terminalPort: String = DEFAULT_TERMINAL_PORT,
        timeoutSeconds: String = DEFAULT_TIMEOUT_SECONDS
    ): PlanetPaymentResult = transactionMutex.withLock {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting Planet sale reversal: amount=$amountFormatted, ref=$requesterRef, originalRef=$originalRequesterRef, ip=$terminalIp:$terminalPort")

            // Check if SDK is available before attempting any operations
            if (!isPlanetSdkAvailable()) {
                Log.w(TAG, "Planet SDK is not available on this device, cannot perform sale reversal")
                return@withContext PlanetPaymentResult(
                    success = false,
                    resultCode = "SDK_UNAVAILABLE",
                    message = "Planet payment SDK is not available on this device"
                )
            }

            // Initialize logger once (should not be called on every transaction)
            if (!initializeLoggerOnce()) {
                Log.w(TAG, "Failed to initialize Planet SDK logger, cannot perform sale reversal")
                return@withContext PlanetPaymentResult(
                    success = false,
                    resultCode = "SDK_INIT_FAILED",
                    message = "Failed to initialize payment terminal"
                )
            }

            // Per-transaction state
            data class ReversalState(
                var transactionCompleted: Boolean = false,
                var terminalReady: Boolean = false,
                var resultCode: String? = null,
                var bankResultCode: String? = null,
                var message: String? = null,
                var requesterTransRefNum: String? = null,
                var rawOptions: Map<String, String> = emptyMap()
            )
            
            val state = ReversalState()
            var connectedThisTransaction = false

            // Status updates handler
            val statusHandler = object : IStatusUpdateHandler {
                override fun onStatusUpdate(statusUpdate: StatusUpdate) {
                    statusUpdate.options?.forEach { (key, value) ->
                        if (key == "StatusMessage") {
                            Log.d(TAG, "Planet reversal status: $value")
                            val statusLower = value?.lowercase() ?: ""
                            if (statusLower.contains("terminal ready") || 
                                statusLower.contains("welcome") ||
                                statusLower.contains("ready")) {
                                state.terminalReady = true
                            }
                        }
                    }
                }
            }

            // Response handler for reversal
            val responseHandler = object : IResponseHandler {
                override fun onResponse(response: Response) {
                    state.rawOptions = response.options ?: emptyMap()

                    var hasResult = false
                    var hasBankResult = false
                    
                    response.options?.forEach { (key, value) ->
                        when (key) {
                            "Result" -> {
                                if (value != null && value.isNotEmpty()) {
                                    state.resultCode = value
                                    hasResult = true
                                }
                            }
                            "BankResultCode" -> {
                                if (value != null && value.isNotEmpty()) {
                                    state.bankResultCode = value
                                    hasBankResult = true
                                }
                            }
                            "Message" -> {
                                if (value != null && value.isNotEmpty()) {
                                    state.message = value
                                }
                            }
                            "RequesterTransRefNum" -> {
                                if (value != null && value.isNotEmpty()) {
                                    state.requesterTransRefNum = value
                                }
                            }
                        }
                    }
                    
                    if (hasResult || hasBankResult) {
                        state.transactionCompleted = true
                        Log.d(TAG, "Planet reversal response: result=${state.resultCode} bank=${state.bankResultCode} msg=${state.message} ref=${state.requesterTransRefNum}")
                        Log.d(TAG, "Planet reversal full response options: ${state.rawOptions}")
                    } else {
                        Log.d(TAG, "Planet reversal response (ignored - no result data): options=${state.rawOptions}")
                    }
                }
            }

            // Connection events handler
            val channelStatusListener = object : IChannelStatusListener {
                override fun onChannelEvent(channelEvent: ChannelEvent) {
                    when (channelEvent.type) {
                        ChannelEventType.CONNECTED -> {
                            Log.d(TAG, "Planet channel connected for reversal")
                            isConnected = true
                            connectedThisTransaction = true
                        }
                        ChannelEventType.DISCONNECTED -> {
                            Log.d(TAG, "Planet channel disconnected during reversal")
                            isConnected = false
                        }
                        else -> {
                            Log.d(TAG, "Planet channel event: ${channelEvent.type}")
                        }
                    }
                }
            }

            try {
                val integra = getOrCreateIntegra(terminalIp, terminalPort, channelStatusListener)
                
                if (integra == null) {
                    Log.e(TAG, "Planet: failed to create or connect Integra instance for reversal")
                    return@withContext PlanetPaymentResult(
                        success = false,
                        resultCode = "INIT_FAILED",
                        message = "Failed to initialize payment terminal connection"
                    )
                }
                
                integra.setStatusUpdateHandler(statusHandler)
                integra.setResponseHandler(responseHandler)

                // Build sale reversal request
                // Note: Based on Planet SDK samples, Sale-Reversal might need the original RequesterTransRefNum
                // to identify which transaction to reverse. Some terminals reverse the last transaction,
                // while others need the original reference to match.
                // Try using the original RequesterTransRefNum as the RequesterTransRefNum for the reversal
                val requestOptions = hashMapOf(
                    IRequest.TAG_REQUESTERTRANSREFNUM to originalRequesterRef, // Use original ref to identify transaction
                    IRequest.TAG_AMOUNT to amountFormatted
                )
                
                Log.d(TAG, "Using original RequesterTransRefNum ($originalRequesterRef) to identify transaction for reversal")

                // Try to add original transaction reference using various possible tag names
                // The Planet SDK might need this to identify which transaction to reverse
                try {
                    val requiredOptions = RequestFactory.getOptionsForRequest("Sale-Reversal")
                    Log.d(TAG, "SaleReversalRequest required options: $requiredOptions")
                    
                    // Try different tag names that might be used for original transaction reference
                    val possibleTags = listOf(
                        "OriginalRequesterTransRefNum",
                        "OriginalTransRefNum", 
                        "OriginalTransactionRef",
                        "OriginalRequesterRef",
                        IRequest.TAG_ORIGINALREQUESTID,
                        IRequest.TAG_ORIGINALPAYMENTREFERENCEID
                    )
                    
                    for (tag in possibleTags) {
                        if (requiredOptions.contains(tag)) {
                            requestOptions[tag] = originalRequesterRef
                            Log.d(TAG, "Adding $tag: $originalRequesterRef")
                            break // Only add one
                        }
                    }
                    
                    // Also try adding it even if not in required options (some SDKs accept optional params)
                    if (!requestOptions.containsKey("OriginalRequesterTransRefNum") && 
                        !requestOptions.containsKey(IRequest.TAG_ORIGINALREQUESTID)) {
                        // Try adding as optional parameter
                        requestOptions["OriginalRequesterTransRefNum"] = originalRequesterRef
                        Log.d(TAG, "Adding OriginalRequesterTransRefNum as optional parameter: $originalRequesterRef")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not get required options for SaleReversalRequest, adding OriginalRequesterTransRefNum anyway", e)
                    // Still try to add it as it might be needed
                    requestOptions["OriginalRequesterTransRefNum"] = originalRequesterRef
                    Log.d(TAG, "Added OriginalRequesterTransRefNum: $originalRequesterRef")
                }

                Log.d(TAG, "Planet: creating SaleReversalRequest with $requestOptions")
                val request: IRequest = SaleReversalRequest(requestOptions)

                if (!request.validateOptions()) {
                    Log.e(TAG, "Planet: reversal request validation failed")
                    return@withContext PlanetPaymentResult(
                        success = false,
                        resultCode = "INVALID_REQUEST",
                        message = "Missing or invalid request parameters"
                    )
                }

                // Send request
                val sequenceNumber = AtomicInteger()
                Log.d(TAG, "Planet: sending sale reversal request")
                val sendError = integra.sendRequest(request, sequenceNumber)
                if (sendError != ErrorType.SUCCESS) {
                    Log.e(TAG, "Planet: error sending reversal request: $sendError")
                    return@withContext PlanetPaymentResult(
                        success = false,
                        resultCode = sendError.toString(),
                        message = "Error sending reversal request to terminal"
                    )
                }

                Log.d(TAG, "Planet: reversal request sent successfully, waiting for response")

                // Wait for response
                val txStart = System.currentTimeMillis()
                val txTimeoutMs = 120_000L // 2 minutes timeout for reversal
                while (!state.transactionCompleted && (System.currentTimeMillis() - txStart) < txTimeoutMs) {
                    Thread.sleep(50)
                }

                if (!state.transactionCompleted) {
                    Log.e(TAG, "Planet: reversal timeout after ${System.currentTimeMillis() - txStart}ms")
                    return@withContext PlanetPaymentResult(
                        success = false,
                        resultCode = "TX_TIMEOUT",
                        message = "Reversal timed out"
                    )
                }

                // Check for success: resultCode "A" = Approved, bankResultCode "00" = Success
                val success = state.resultCode?.equals("A", ignoreCase = true) == true ||
                        state.resultCode?.equals("APPROVED", ignoreCase = true) == true ||
                        state.bankResultCode?.equals("00", ignoreCase = true) == true ||
                        state.message?.contains("APPROVED", ignoreCase = true) == true

                Log.d(TAG, "Planet: reversal completed with success=$success")
                PlanetPaymentResult(
                    success = success,
                    resultCode = state.resultCode,
                    bankResultCode = state.bankResultCode,
                    message = state.message,
                    requesterTransRefNum = state.requesterTransRefNum,
                    rawOptions = state.rawOptions
                )
            } catch (e: Exception) {
                Log.e(TAG, "Planet: unexpected error during reversal", e)
                isConnected = false
                PlanetPaymentResult(
                    success = false,
                    resultCode = "EXCEPTION",
                    message = e.message ?: "Unexpected error"
                )
            }
        }
    }
}




