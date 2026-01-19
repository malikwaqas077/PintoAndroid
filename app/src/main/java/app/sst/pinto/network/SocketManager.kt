package app.sst.pinto.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SocketManager private constructor() {
    private val TAG = "SocketManager"

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // No timeout for WebSockets
        .build()

    private var webSocket: WebSocket? = null
    private var serverUrl: String = "ws://192.168.2.112:5001"
    
    // Coroutine scope for emitting messages
    private val messageScope = CoroutineScope(Dispatchers.IO)

    // Connection state
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // Message receiver - use SharedFlow with buffer to queue all messages
    // StateFlow only holds latest value, which can cause messages to be lost when they arrive quickly
    private val _messageReceived = MutableSharedFlow<String>(
        extraBufferCapacity = 64, // Buffer up to 64 messages
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.SUSPEND // Suspend if buffer is full
    )
    val messageReceived: SharedFlow<String> = _messageReceived.asSharedFlow()

    fun connect(url: String) {
        serverUrl = url
        // Don't connect if already connected or connecting
        if (_connectionState.value == ConnectionState.CONNECTED) {
            Log.d(TAG, "Already connected")
            return
        }
        
        if (_connectionState.value == ConnectionState.CONNECTING) {
            Log.d(TAG, "Connection already in progress, skipping duplicate connect request")
            return
        }

        _connectionState.value = ConnectionState.CONNECTING

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, socketListener)
    }

    fun disconnect() {
        webSocket?.close(1000, "Closing connection")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }
    // Add these methods to the SocketManager class

    /**
     * Force reconnection to the server.
     * Useful after screensaver or timeout periods.
     */
    fun ensureConnected() {
        Log.d(TAG, "Ensuring socket connection is active")
        when (_connectionState.value) {
            ConnectionState.CONNECTED -> {
                Log.d(TAG, "Already connected to $serverUrl")
                // Send a small ping message to verify connection is still alive
                val pingSuccess = webSocket?.send("{\"ping\":true}")
                Log.d(TAG, "Sent ping message, result: $pingSuccess")
            }
            ConnectionState.CONNECTING -> {
                Log.d(TAG, "Connection already in progress, waiting for it to complete")
            }
            ConnectionState.DISCONNECTED -> {
                Log.d(TAG, "Not connected, attempting reconnection to $serverUrl")
                connect(serverUrl)
            }
        }
    }

    /**
     * Checks connection state and returns if connected
     */
    fun isConnected(): Boolean {
        return _connectionState.value == ConnectionState.CONNECTED
    }
    fun sendMessage(message: String): Boolean {
        return if (_connectionState.value == ConnectionState.CONNECTED) {
            webSocket?.send(message) ?: false
        } else {
            Log.e(TAG, "Cannot send message, not connected")
            false
        }
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connection opened")
            _connectionState.value = ConnectionState.CONNECTED
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Message received: $text")
            // Emit message in a coroutine scope to ensure thread safety
            // tryEmit is non-blocking and thread-safe, but using emit in a coroutine is safer
            messageScope.launch {
                _messageReceived.emit(text)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket connection closed: $reason")
            _connectionState.value = ConnectionState.DISCONNECTED
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}")
            _connectionState.value = ConnectionState.DISCONNECTED

            // Implement reconnection logic here
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (_connectionState.value != ConnectionState.CONNECTING) {
            // Use a coroutine for delayed reconnection
            CoroutineScope(Dispatchers.IO).launch {
                delay(5000) // 5 second delay
                if (_connectionState.value == ConnectionState.DISCONNECTED) {
                    connect(serverUrl)
                }
            }
        }
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    companion object {
        @Volatile
        private var instance: SocketManager? = null

        fun getInstance(): SocketManager {
            return instance ?: synchronized(this) {
                instance ?: SocketManager().also { instance = it }
            }
        }
    }
}