package app.sst.pinto.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import app.sst.pinto.utils.AppLog
import app.sst.pinto.config.ConfigManager
import app.sst.pinto.utils.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Persistent WebSocket to Ask portal /deviceHub (EvCloudPay strategy).
 * Separate from the payment SocketManager connection.
 */
class PortalWebSocketClient(
    private val context: Context,
    private val deviceSerial: String
) {
    private val TAG = "PortalWebSocketClient"
    private val configManager = ConfigManager.getInstance(context)
    private val fileLogger = FileLogger.getInstance(context)

    private val INITIAL_RETRY_DELAY_MS = 2_000L
    private val MAX_RETRY_DELAY_MS = 60_000L
    private val KEEP_ALIVE_INTERVAL_MS = 90_000L

    @Volatile private var isConnected = false
    @Volatile private var isConnecting = false
    @Volatile private var shouldConnect = false
    private var retryAttempts = 0
    private var currentRetryDelay = INITIAL_RETRY_DELAY_MS
    private var connectionGeneration = 0L

    private val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectionJob: Job? = null
    private var keepAliveJob: Job? = null

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    @Volatile private var isNetworkAvailable = true

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private var webSocket: WebSocket? = null
    private var messageCallback: ((String) -> Unit)? = null

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    init {
        startNetworkMonitoring()
    }

    fun setMessageCallback(callback: (String) -> Unit) {
        messageCallback = callback
    }

    fun connect() {
        if (isConnected || isConnecting) return
        val url = configManager.getPortalWebSocketUrl()
        if (url.isBlank()) {
            fileLogger.w(TAG, "Portal URL not configured — skipping deviceHub connect")
            return
        }
        synchronized(this) {
            if (isConnected || isConnecting) return
            shouldConnect = true
            attemptConnection()
        }
    }

    fun disconnect() {
        synchronized(this) {
            shouldConnect = false
            connectionGeneration++
            isConnected = false
            isConnecting = false
            retryAttempts = 0
            currentRetryDelay = INITIAL_RETRY_DELAY_MS
        }
        reconnectionJob?.cancel()
        reconnectionJob = null
        keepAliveJob?.cancel()
        keepAliveJob = null
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _connectionState.value = false
        fileLogger.i(TAG, "Disconnected from portal deviceHub")
    }

    fun sendMessage(message: String): Boolean {
        if (!isConnected) {
            fileLogger.w(TAG, "Cannot send — not connected to portal")
            return false
        }
        val success = webSocket?.send(message) ?: false
        if (!success) {
            fileLogger.e(TAG, "Failed to send portal message (len=${message.length})")
            handleConnectionFailure()
        }
        return success
    }

    private fun attemptConnection() {
        if (!shouldConnect) return
        if (!isNetworkAvailable) {
            scheduleReconnection()
            return
        }

        val url = configManager.getPortalWebSocketUrl()
        if (url.isBlank()) {
            fileLogger.w(TAG, "Portal URL empty — not connecting")
            return
        }

        val myGeneration: Long
        synchronized(this) {
            if (isConnecting || isConnected) return
            connectionGeneration++
            isConnecting = true
            myGeneration = connectionGeneration
        }

        connectionScope.launch {
            try {
                fileLogger.i(
                    TAG,
                    "Connecting to portal deviceHub attempt=${retryAttempts + 1} url=$url serial=$deviceSerial"
                )
                val request = Request.Builder()
                    .url(url)
                    .addHeader("X-Device-Serial", deviceSerial)
                    .build()
                val ws = client.newWebSocket(request, createListener(myGeneration))
                synchronized(this@PortalWebSocketClient) {
                    if (connectionGeneration == myGeneration) {
                        webSocket = ws
                    } else {
                        ws.close(1000, "Stale")
                    }
                }
            } catch (e: Exception) {
                fileLogger.e(TAG, "Error creating portal WebSocket", e)
                handleConnectionFailure(myGeneration)
            }
        }
    }

    private fun createListener(listenerGeneration: Long): WebSocketListener {
        return object : WebSocketListener() {
            private fun isStale(): Boolean = synchronized(this@PortalWebSocketClient) {
                connectionGeneration != listenerGeneration
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (isStale()) {
                    webSocket.close(1000, "Stale")
                    return
                }
                synchronized(this@PortalWebSocketClient) {
                    isConnected = true
                    isConnecting = false
                    retryAttempts = 0
                    currentRetryDelay = INITIAL_RETRY_DELAY_MS
                }
                _connectionState.value = true
                fileLogger.i(TAG, "Portal deviceHub connected serial=$deviceSerial")
                startKeepAlive()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (isStale()) return
                messageCallback?.invoke(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (isStale()) return
                fileLogger.w(TAG, "Portal deviceHub closed code=$code reason=$reason")
                handleConnectionFailure(listenerGeneration)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (isStale()) return
                fileLogger.e(TAG, "Portal deviceHub failure: ${t.message}", t)
                handleConnectionFailure(listenerGeneration)
            }
        }
    }

    private fun handleConnectionFailure(generation: Long? = null) {
        synchronized(this) {
            if (generation != null && connectionGeneration != generation) return
            isConnected = false
            isConnecting = false
            webSocket = null
        }
        _connectionState.value = false
        keepAliveJob?.cancel()
        keepAliveJob = null
        if (shouldConnect) scheduleReconnection()
    }

    private fun scheduleReconnection() {
        if (!shouldConnect) return
        reconnectionJob?.cancel()
        reconnectionJob = connectionScope.launch {
            delay(currentRetryDelay)
            if (!shouldConnect || !isActive) return@launch
            retryAttempts++
            currentRetryDelay = min(
                (currentRetryDelay * 1.5).toLong(),
                MAX_RETRY_DELAY_MS
            )
            attemptConnection()
        }
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = connectionScope.launch {
            while (isActive && isConnected) {
                delay(KEEP_ALIVE_INTERVAL_MS)
                if (!isConnected) break
                val ping = JSONObject()
                    .put("action", "ping")
                    .put("timestamp", System.currentTimeMillis())
                    .toString()
                webSocket?.send(ping)
            }
        }
    }

    private fun startNetworkMonitoring() {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        isNetworkAvailable = true
                        if (shouldConnect && !isConnected && !isConnecting) {
                            currentRetryDelay = INITIAL_RETRY_DELAY_MS
                            attemptConnection()
                        }
                    }

                    override fun onLost(network: Network) {
                        isNetworkAvailable = false
                    }
                }
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "Network monitoring unavailable", e)
        }
    }
}
